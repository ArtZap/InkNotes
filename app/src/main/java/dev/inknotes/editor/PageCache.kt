package dev.inknotes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageBackground
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageImage
import dev.inknotes.data.PageStroke
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Renders part of a PDF page: the [w]×[h] pixels from ([left], [top]), the whole page being
 * [fullWidthPx] wide. Called on a background thread; null on failure.
 */
fun interface PdfRegion {
    fun render(fullWidthPx: Float, left: Float, top: Float, w: Int, h: Int): Bitmap?
}

/**
 * Pages kept as pictures, so a frame costs a few bitmap draws however much is written:
 * a full page of handwriting is thousands of strokes, far too many to draw every frame.
 *
 * A page is rendered on a background thread at about the zoom it is shown at. After that
 * only what changed is redrawn, in place and on the spot: a new stroke, an erased bit or
 * an undo touch a small area, which is cleared and drawn again from the strokes crossing
 * it. Large changes are rendered anew in the background while the old picture stays up.
 * Selected elements are left out of the picture (the editor draws them on top).
 */
internal class PageCache(private val invalidate: () -> Unit) {

    /** How a page looks apart from its elements: anything here changing means a new picture. */
    data class Look(
        val width: Float,
        val height: Float,
        val paper: PageBackground,
        val dark: Boolean,
        val photo: Bitmap?,
        val pdf: Bitmap?,
        val pictures: List<Bitmap?>,
        val pdfDark: Boolean = false,
    )

    /**
     * The picture in horizontal strips: a change re-uploads to the GPU only the strips it
     * touches, not a whole page of pixels after every stroke.
     */
    private class Tiles(val strips: List<Bitmap>, val stripPx: Int)

    private class Entry(var look: Look) {
        var bitmap: Tiles? = null
        var scale = 0f
        var strokes: List<PageStroke> = emptyList()
        var fills: List<PageFill> = emptyList()
        var images: List<PageImage> = emptyList()
        var excluded: Set<Any> = emptySet()
        var rendering = false
        var again = false
        var lastUse = 0L
    }

    private val entries = IdentityHashMap<PageDocument, Entry>()
    // One worker, which ends when idle: nothing to shut down when the editor goes away.
    private val exec = ThreadPoolExecutor(0, 1, 20, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "page-cache").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())
    private val patcher = PageRenderer()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var frame = 0L

    /** Forget every picture (theme or paper changed for all pages). */
    fun clear() {
        entries.clear()
        details.clear()
        wanted.clear()
    }

    enum class Result {
        DRAWN,

        /** No picture yet (it is being rendered): the caller shows a placeholder. */
        NOT_READY,

        /** Zoomed in past what a picture holds: the caller draws the visible elements. */
        ZOOMED,
    }

    /** Draws [doc] from its picture on [canvas] (already in page units), leaving out [excluded]. */
    fun draw(
        canvas: Canvas,
        doc: PageDocument,
        look: Look,
        viewScale: Float,
        excluded: Set<Any>,
        visible: RectF? = null,
        pdfRegion: PdfRegion? = null,
        picture: (String) -> Bitmap?,
    ): Result {
        val target = targetScale(look, viewScale)
        val e = entries.getOrPut(doc) { Entry(look) }
        e.lastUse = frame
        if (pdfRegion != null && visible != null && look.pdf != null &&
            drawDetail(canvas, doc, look, viewScale, visible, excluded, pdfRegion, picture)
        ) {
            if (e.bitmap == null) render(doc, e, look, target, excluded, picture)
            return Result.DRAWN
        }
        val b = e.bitmap
        if (b == null) {
            render(doc, e, look, target, excluded, picture)
            return Result.NOT_READY
        }
        if (e.look != look) {
            // Paper or theme changed: the old picture stays up until the new one is ready.
            render(doc, e, look, target, excluded, picture)
        } else if (!sameContent(e, doc, excluded)) {
            if (!patch(e, doc, excluded, picture)) render(doc, e, look, target, excluded, picture)
        }
        // A sharper (or lighter) picture for the zoom now shown.
        if (target > e.scale * RESCALE || target < e.scale / RESCALE) render(doc, e, look, target, excluded, picture)
        // Zoomed in far past what a picture can hold: the caller draws what is visible.
        if (viewScale > e.scale * MAX_BLUR) return Result.ZOOMED
        val tiles = e.bitmap ?: return Result.NOT_READY
        val k = look.width / tiles.strips[0].width
        for ((n, strip) in tiles.strips.withIndex()) {
            val top = n * tiles.stripPx * k
            dst.set(0f, top, look.width, top + strip.height * k)
            canvas.drawBitmap(strip, null, dst, bitmapPaint)
        }
        // A new picture on its way (a large change): what the old one lacks is drawn over it
        // meanwhile, so nothing blinks — e.g. the rest of a table when one line of it is picked.
        if (!sameContent(e, doc, excluded)) drawMissing(canvas, e, doc, look, excluded, viewScale, picture)
        return Result.DRAWN
    }

    private fun drawMissing(canvas: Canvas, e: Entry, doc: PageDocument, look: Look, excluded: Set<Any>, viewScale: Float, picture: (String) -> Bitmap?) {
        val shown = identitySet(e.strokes)
        val shownFills = identitySet(e.fills)
        val shownImages = identitySet(e.images)
        fun missing(x: Any, inPicture: Boolean) = x !in excluded && (!inPicture || x in e.excluded)
        val strokes = doc.strokes.filter { missing(it, it in shown) }
        val fills = doc.fills.filter { missing(it, it in shownFills) }
        val images = doc.images.filter { missing(it, it in shownImages) }
        if (strokes.isEmpty() && fills.isEmpty() && images.isEmpty()) return
        val r = patcher
        r.pageWidth = look.width
        r.pageHeight = look.height
        r.paper = look.paper
        r.dark = look.dark
        r.photo = look.photo
        r.pdf = look.pdf
        r.pdfDark = look.pdfDark
        r.pdfDetail = null
        val toScreen = Matrix().apply { setScale(viewScale, viewScale) }
        for (img in images) r.drawImage(canvas, img, picture(img.file))
        r.drawFills(canvas, fills)
        val highlights = strokes.filter { it.kind == InkKind.HIGHLIGHTER }
        if (highlights.isNotEmpty()) {
            r.beginHighlighter(canvas)
            for (s in highlights) r.drawStroke(canvas, s, toScreen)
            r.endHighlighter(canvas)
        }
        r.drawInk(canvas, strokes.filter { it.kind.isInk }, toScreen)
    }

    /**
     * The shown part of a PDF page at exactly the zoom it is shown at, everything on it
     * included: the page's picture is one bitmap for the whole page, which a PDF's small
     * print does not survive. Rendered once the view stands still, then patched like the
     * picture as the page changes.
     */
    private class Detail(val doc: PageDocument, val look: Look, val rect: RectF, val left: Float, val top: Float, val scale: Float, val pdf: Bitmap, val bitmap: Bitmap) {
        var strokes: List<PageStroke> = emptyList()
        var fills: List<PageFill> = emptyList()
        var images: List<PageImage> = emptyList()
        var excluded: Set<Any> = emptySet()
        var lastUse = 0L
    }

    /** A detail asked for: rendered when the view has kept to it for [SETTLE_MS]. */
    private class Wanted(val look: Look, val rect: RectF, val scale: Float, val excluded: Set<Any>, val region: PdfRegion, val picture: (String) -> Bitmap?) {
        val since = SystemClock.uptimeMillis()
        var seen = 0L
    }

    private val details = IdentityHashMap<PageDocument, Detail>()
    private val wanted = IdentityHashMap<PageDocument, Wanted>()
    private var detailRendering = false
    private val settle = Runnable { startDetail() }

    /** Draws the page's detail when there is one for this view; else asks for it and returns false. */
    private fun drawDetail(
        canvas: Canvas,
        doc: PageDocument,
        look: Look,
        viewScale: Float,
        visible: RectF,
        excluded: Set<Any>,
        region: PdfRegion,
        picture: (String) -> Bitmap?,
    ): Boolean {
        // A small page (zoomed out) is sharp enough from its picture.
        if (look.width * viewScale < MIN_DETAIL_PAGE_PX) return false
        val shown = RectF(visible)
        if (!shown.intersect(0f, 0f, look.width, look.height)) return false
        shown.inset(EDGE_SLACK / viewScale, EDGE_SLACK / viewScale)
        val d = details[doc]
        if (d != null) {
            if (d.look == look && abs(d.scale / viewScale - 1f) < SCALE_MATCH && d.rect.contains(shown) && patchDetail(d, doc, excluded, picture)) {
                d.lastUse = frame
                canvas.drawBitmap(d.bitmap, null, d.rect, bitmapPaint)
                wanted.remove(doc)
                return true
            }
            details.remove(doc)
        }
        val w = wanted[doc]
        if (w != null && w.look == look && abs(w.scale / viewScale - 1f) < SCALE_MATCH && w.rect.contains(shown)) {
            w.seen = frame
            return false
        }
        // Too many pages shown at this size: only the ones already sharp.
        if (details.size >= MAX_DETAILS && details.values.all { it.lastUse >= frame - 1 }) return false
        val r = RectF(shown).apply {
            inset(-shown.width() * MARGIN, -shown.height() * MARGIN)
            intersect(0f, 0f, look.width, look.height)
        }
        if (r.width() * r.height() * viewScale * viewScale > MAX_DETAIL_PIXELS) r.set(shown)
        if (r.width() * r.height() * viewScale * viewScale > MAX_DETAIL_PIXELS) return false
        wanted[doc] = Wanted(look, r, viewScale, identityCopy(excluded), region, picture).also { it.seen = frame }
        main.removeCallbacks(settle)
        main.postDelayed(settle, SETTLE_MS)
        return false
    }

    /** Renders the detail asked for longest ago, once the view has stayed on it; one at a time. */
    private fun startDetail() {
        if (detailRendering) return
        val now = SystemClock.uptimeMillis()
        wanted.entries.removeAll { it.value.seen < frame - 1 }
        val next = wanted.entries.filter { now - it.value.since >= SETTLE_MS }.minByOrNull { it.value.since }
        if (next == null) {
            if (wanted.isNotEmpty()) main.postDelayed(settle, SETTLE_MS)
            return
        }
        val doc = next.key
        val w = next.value
        wanted.remove(doc)
        // Whole pixels: the bitmap sits on the screen's pixel grid.
        val left = floor(w.rect.left * w.scale)
        val top = floor(w.rect.top * w.scale)
        val bw = (ceil(w.rect.right * w.scale) - left).toInt()
        val bh = (ceil(w.rect.bottom * w.scale) - top).toInt()
        if (bw < 1 || bh < 1) return startDetail()
        val rect = RectF(left / w.scale, top / w.scale, (left + bw) / w.scale, (top + bh) / w.scale)
        val strokes = doc.strokes
        val fills = doc.fills
        val images = doc.images
        val pictures = images.associate { it.file to w.picture(it.file) }
        detailRendering = true
        runCatching {
            exec.execute {
                val made = runCatching {
                    val pdf = w.region.render(w.look.width * w.scale, left, top, bw, bh) ?: return@runCatching null
                    val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.translate(-left, -top)
                    canvas.scale(w.scale, w.scale)
                    paint(canvas, renderer, w.look, strokes, fills, images, w.excluded, { pictures[it] }, null, Matrix().apply { setScale(w.scale, w.scale); postTranslate(-left, -top) }, pdf, rect)
                    Detail(doc, w.look, rect, left, top, w.scale, pdf, bitmap)
                }.getOrNull()
                main.post {
                    detailRendering = false
                    if (made != null && entries[doc] != null) {
                        made.strokes = strokes
                        made.fills = fills
                        made.images = images
                        made.excluded = w.excluded
                        made.lastUse = frame
                        details[doc] = made
                        while (details.size > MAX_DETAILS) details.remove(details.values.minBy { it.lastUse }.doc)
                        invalidate()
                    }
                    startDetail()
                }
            }
        }.onFailure { detailRendering = false }
    }

    /** Brings [d] up to the page now, in place; false when that is too much (it is rendered anew). */
    private fun patchDetail(d: Detail, doc: PageDocument, excluded: Set<Any>, picture: (String) -> Bitmap?): Boolean {
        if (d.strokes === doc.strokes && d.fills === doc.fills && d.images === doc.images && sameSet(d.excluded, excluded)) return true
        val dirty = changedArea(d.strokes, d.fills, d.images, d.excluded, doc, excluded, d.look) ?: return false
        if (!dirty.isEmpty && RectF.intersects(dirty, d.rect)) {
            val canvas = Canvas(d.bitmap)
            canvas.translate(-d.left, -d.top)
            canvas.scale(d.scale, d.scale)
            canvas.clipRect(dirty)
            paint(canvas, patcher, d.look, doc.strokes, doc.fills, doc.images, excluded, picture, dirty, Matrix().apply { setScale(d.scale, d.scale); postTranslate(-d.left, -d.top) }, d.pdf, d.rect)
        }
        d.strokes = doc.strokes
        d.fills = doc.fills
        d.images = doc.images
        d.excluded = identityCopy(excluded)
        return true
    }

    /**
     * Where the page differs from a picture of [strokes], [fills], [images] without [excluded]:
     * empty when nowhere, null when too much changed to patch.
     */
    private fun changedArea(
        strokes: List<PageStroke>,
        fills: List<PageFill>,
        images: List<PageImage>,
        wasExcluded: Set<Any>,
        doc: PageDocument,
        excluded: Set<Any>,
        look: Look,
    ): RectF? {
        val dirty = RectF()
        var any = false
        var changed = 0
        fun add(r: RectF?) {
            if (r == null) return
            if (any) dirty.union(r) else dirty.set(r)
            any = true
            changed++
        }
        fun <T : Any> diff(old: List<T>, new: List<T>, bounds: (T) -> RectF?) {
            if (old === new) return
            val oldSet = identitySet(old)
            val newSet = identitySet(new)
            val before = changed
            for (x in old) if (x !in newSet) add(bounds(x))
            for (x in new) if (x !in oldSet) add(bounds(x))
            // The same elements in another order (z-order changed): the picture is redone.
            if (changed == before) changed = Int.MAX_VALUE / 2
        }
        diff(strokes, doc.strokes, ::strokeBounds)
        diff(fills, doc.fills) { it.bounds }
        diff(images, doc.images) { PageEdits.bounds(it) }
        if (!sameSet(wasExcluded, excluded)) {
            for (x in wasExcluded) if (x !in excluded) add(boundsOf(x))
            for (x in excluded) if (x !in wasExcluded) add(boundsOf(x))
        }
        if (changed > MAX_PATCH) return null
        if (!any) return RectF()
        dirty.inset(-PATCH_MARGIN, -PATCH_MARGIN)
        if (dirty.width() * dirty.height() > MAX_PATCH_AREA * look.width * look.height) return null
        return dirty
    }

    /** Called after each frame: pictures of pages not shown for a while are dropped. */
    fun endFrame() {
        frame++
        details.values.removeAll { it.lastUse < frame - 1 }
        if (entries.size <= MAX_ENTRIES) return
        val old = entries.entries.sortedBy { it.value.lastUse }.take(entries.size - MAX_ENTRIES)
        for ((doc, e) in old) if (e.lastUse < frame - 1) entries.remove(doc)
    }

    private fun targetScale(look: Look, viewScale: Float): Float {
        // Steps, so a slow zoom does not re-render at every frame; capped in pixels.
        val step = Math.pow(RESCALE.toDouble(), Math.ceil(Math.log(viewScale.toDouble()) / Math.log(RESCALE.toDouble()))).toFloat()
        val byPixels = sqrt(MAX_PIXELS / (look.width * look.height))
        val bySide = MAX_SIDE / max(look.width, look.height)
        return min(step, min(byPixels, bySide))
    }

    private fun sameContent(e: Entry, doc: PageDocument, excluded: Set<Any>) =
        e.strokes === doc.strokes && e.fills === doc.fills && e.images === doc.images && sameSet(e.excluded, excluded)

    private fun sameSet(a: Set<Any>, b: Set<Any>) = a.size == b.size && a.all { it in b }

    /** Redraws the area of what changed since the picture; false when that is too much. */
    private fun patch(e: Entry, doc: PageDocument, excluded: Set<Any>, picture: (String) -> Bitmap?): Boolean {
        val b = e.bitmap ?: return false
        if (e.rendering) return false
        val dirty = changedArea(e.strokes, e.fills, e.images, e.excluded, doc, excluded, e.look) ?: return false
        if (!dirty.isEmpty) {
            val first = (dirty.top * e.scale / b.stripPx).toInt().coerceAtLeast(0)
            val last = (dirty.bottom * e.scale / b.stripPx).toInt().coerceAtMost(b.strips.size - 1)
            for (n in first..last) {
                val canvas = Canvas(b.strips[n])
                canvas.translate(0f, -(n * b.stripPx).toFloat())
                canvas.scale(e.scale, e.scale)
                canvas.clipRect(dirty)
                paint(canvas, patcher, e.look, doc.strokes, doc.fills, doc.images, excluded, picture, dirty, Matrix().apply { setScale(e.scale, e.scale) }, null, null)
            }
        }
        e.strokes = doc.strokes
        e.fills = doc.fills
        e.images = doc.images
        e.excluded = identityCopy(excluded)
        return true
    }

    private fun render(doc: PageDocument, e: Entry, look: Look, scale: Float, excluded: Set<Any>, picture: (String) -> Bitmap?) {
        if (e.rendering) {
            e.again = true
            return
        }
        e.rendering = true
        e.again = false
        val strokes = doc.strokes
        val fills = doc.fills
        val images = doc.images
        val ex = identityCopy(excluded)
        val pictures = images.associate { it.file to picture(it.file) }
        runCatching {
            exec.execute {
                val bitmap = runCatching {
                    val w = max(1, (look.width * scale).toInt())
                    val h = max(1, (look.height * scale).toInt())
                    val whole = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(whole)
                    canvas.scale(scale, scale)
                    paint(canvas, renderer, look, strokes, fills, images, ex, { pictures[it] }, null, Matrix().apply { setScale(scale, scale) }, null, null)
                    // Mutable strips (a later change is drawn into them).
                    val strips = (0 until h step STRIP_PX).map { y ->
                        val sh = min(STRIP_PX, h - y)
                        Bitmap.createBitmap(w, sh, Bitmap.Config.ARGB_8888).also { strip ->
                            Canvas(strip).drawBitmap(whole, Rect(0, y, w, y + sh), Rect(0, 0, w, sh), null)
                        }
                    }
                    whole.recycle()
                    Tiles(strips, STRIP_PX)
                }.getOrNull()
                main.post {
                    e.rendering = false
                    if (entries[doc] !== e) return@post
                    if (bitmap != null) {
                        e.bitmap = bitmap
                        e.scale = scale
                        e.strokes = strokes
                        e.fills = fills
                        e.images = images
                        e.excluded = ex
                    }
                    e.look = look
                    // Changes made meanwhile are drawn over it on the next frame.
                    e.again = false
                    invalidate()
                }
            }
        }.onFailure { e.rendering = false }
    }

    private val renderer = PageRenderer() // background thread only

    /** The page (or the part in [clip]) as the editor shows it, without [excluded] elements. */
    private fun paint(
        canvas: Canvas,
        r: PageRenderer,
        look: Look,
        strokes: List<PageStroke>,
        fills: List<PageFill>,
        images: List<PageImage>,
        excluded: Set<Any>,
        picture: (String) -> Bitmap?,
        clip: RectF?,
        toScreen: Matrix,
        pdfDetail: Bitmap?,
        pdfDetailRect: RectF?,
    ) {
        r.pageWidth = look.width
        r.pageHeight = look.height
        r.paper = look.paper
        r.dark = look.dark
        r.photo = look.photo
        r.pdf = look.pdf
        r.pdfDark = look.pdfDark
        r.pdfDetail = pdfDetail
        pdfDetailRect?.let(r.pdfDetailRect::set)
        r.drawBackground(canvas, edgeWidth = 0f)
        fun visible(b: RectF?) = clip == null || (b != null && RectF.intersects(b, clip))
        for (img in images) if (img !in excluded && visible(PageEdits.bounds(img))) r.drawImage(canvas, img, picture(img.file))
        r.drawFills(canvas, fills.filter { it !in excluded && visible(it.bounds) }, Hatch.budgetFor(fills.size))
        val highlights = strokes.filter { it.kind == InkKind.HIGHLIGHTER && it !in excluded && visible(boundsFor(it, clip)) }
        if (highlights.isNotEmpty()) {
            r.beginHighlighter(canvas)
            for (s in highlights) r.drawStroke(canvas, s, toScreen)
            r.endHighlighter(canvas)
        }
        r.drawInk(canvas, strokes.filter { it.kind.isInk && it !in excluded && visible(boundsFor(it, clip)) }, toScreen)
    }

    /** Bounds are needed only to cut a patch; a full render does not look at them. */
    private fun boundsFor(s: PageStroke, clip: RectF?): RectF? = if (clip == null) null else strokeBounds(s)

    private fun boundsOf(x: Any): RectF? = when (x) {
        is PageStroke -> strokeBounds(x)
        is PageFill -> x.bounds
        is PageImage -> PageEdits.bounds(x)
        else -> null
    }

    companion object {
        // Main thread only.
        private val bounds = WeakHashMap<PageStroke, RectF>()

        /** Bounds of a stroke with its glow, remembered: they are asked for every frame when zoomed in. */
        fun strokeBounds(s: PageStroke): RectF? {
            bounds[s]?.let { return it }
            val b = Neon.withGlow(s, PageEdits.bounds(s) ?: return null)
            bounds[s] = b
            return b
        }

        private fun <T : Any> identitySet(list: List<T>): MutableSet<T> =
            Collections.newSetFromMap(IdentityHashMap<T, Boolean>(list.size * 2)).apply { addAll(list) }

        private fun identityCopy(set: Set<Any>): Set<Any> =
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { addAll(set) }

        private const val MAX_ENTRIES = 5
        private const val STRIP_PX = 256
        private const val MAX_PIXELS = 7_000_000f
        private const val MAX_SIDE = 4096f
        private const val RESCALE = 1.4f
        private const val MAX_BLUR = 1.6f
        private const val MAX_PATCH = 400
        private const val MAX_PATCH_AREA = 0.35f

        // Details of PDF pages: for pages shown at least this wide, a margin around the view
        // for small scrolls, at most a few pages and pixels each.
        private const val MIN_DETAIL_PAGE_PX = 900f
        private const val MARGIN = 0.1f
        private const val MAX_DETAILS = 3
        private const val MAX_DETAIL_PIXELS = 12_000_000f
        private const val SCALE_MATCH = 0.005f
        private const val SETTLE_MS = 180L

        /** Screen pixels at the view's edges a detail may leave uncovered (rounding). */
        private const val EDGE_SLACK = 1f
        private const val PATCH_MARGIN = 2f
    }
}
