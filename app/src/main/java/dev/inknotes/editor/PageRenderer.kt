package dev.inknotes.editor

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.inknotes.data.InkKind
import dev.inknotes.data.InkTextures
import dev.inknotes.data.Notebook
import dev.inknotes.data.PageBackground
import dev.inknotes.data.DashStyle
import dev.inknotes.data.FillPattern
import dev.inknotes.data.HatchStyle
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageImage
import dev.inknotes.data.PageStroke
import dev.inknotes.data.PaperKind
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a page (paper, PDF/photo background, fills, ink) onto a canvas that is already in
 * page coordinates. Shared by the on-screen editor and the PDF exporter.
 */
class PageRenderer {
    val ink: CanvasStrokeRenderer = CanvasStrokeRenderer.create(InkTextures.delegate)

    var dark = false
    var paper = PageBackground(PaperKind.BLANK)
    var photo: Bitmap? = null

    /** Rendered PDF page; when set it replaces the paper and the page is always light. */
    var pdf: Bitmap? = null

    /** Part of the PDF page rendered sharp for the zoom shown, drawn over [pdf] at [pdfDetailRect]. */
    var pdfDetail: Bitmap? = null
    val pdfDetailRect = RectF()
    var pageHeight = PageEditorView.PAGE_H
    var pageWidth = PageEditorView.PAGE_W

    /** Blurred neon glow; off for PDF, which can't blur, so the glow is drawn as soft bands. */
    var softGlow = true

    /** Ink is recoloured for dark paper only; a PDF page is white in both themes. */
    private val inkOnDark get() = (paper.color?.let(PaperStyle::isDark) ?: dark) && pdf == null
    private val style get() = PaperStyle.of(dark && pdf == null, if (pdf == null) paper.color else null)

    private val pagePaint = Paint()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // One cache per paper brightness: pages of both kinds (PDF and dark paper) are drawn in the same frame.
    private val onDarkCache = WeakHashMap<PageStroke, Stroke>()
    private val onLightCache = WeakHashMap<PageStroke, Stroke>()
    private val neonPaths = WeakHashMap<PageStroke, Path>()
    private class NeonParts(val color: Int, val tube: Stroke, val core: Stroke)

    // Per paper brightness, like the ink caches: a PDF page and a dark page can share a frame.
    private val neonOnDark = WeakHashMap<PageStroke, NeonParts>()
    private val neonOnLight = WeakHashMap<PageStroke, NeonParts>()
    private val blurs = HashMap<Float, BlurMaskFilter>()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Paper colour, ruling or background image, then clips to the page. [edgeWidth] 0 = no border. */
    /** Only the page's outline: the rest of the background is in the cached picture. */
    fun drawEdge(canvas: Canvas, edgeWidth: Float) {
        edgePaint.strokeWidth = edgeWidth
        edgePaint.color = style.edge
        canvas.drawRect(0f, 0f, pageWidth, pageHeight, edgePaint)
    }

    fun drawBackground(canvas: Canvas, edgeWidth: Float) {
        val page = RectF(0f, 0f, pageWidth, pageHeight)
        pagePaint.color = style.paper
        canvas.drawRect(page, pagePaint)
        if (edgeWidth > 0f) {
            edgePaint.strokeWidth = edgeWidth
            edgePaint.color = style.edge
            canvas.drawRect(page, edgePaint)
        }
        canvas.clipRect(page)
        val pdfPage = pdf
        if (pdfPage != null) {
            canvas.drawBitmap(pdfPage, null, page, bitmapPaint)
            pdfDetail?.let { canvas.drawBitmap(it, null, pdfDetailRect, bitmapPaint) }
            return
        }
        if (paper.kind == PaperKind.PHOTO) {
            photo?.let { drawCover(canvas, it, page) }
            return
        }
        // The ruling at the opacity chosen for it, as one layer (crossings don't add up).
        val lines = paper.lines.coerceIn(0f, 1f)
        if (lines <= 0f) return
        val faded = lines < 1f
        if (faded) canvas.saveLayerAlpha(page, (lines * 255).toInt())
        drawRuling(canvas)
        if (faded) canvas.restore()
    }

    private fun drawRuling(canvas: Canvas) {
        when (paper.kind) {
            PaperKind.BLANK -> Unit
            PaperKind.LINED -> ruled(canvas, PaperStyle.LINE_SPACING, 140f)
            PaperKind.FINE_LINED -> ruled(canvas, PaperStyle.FINE_LINE_SPACING, 110f)
            PaperKind.GRID -> {
                linePaint.color = style.grid
                var x = PaperStyle.GRID_STEP
                while (x < pageWidth) {
                    canvas.drawLine(x, 0f, x, pageHeight, linePaint)
                    x += PaperStyle.GRID_STEP
                }
                var y = PaperStyle.GRID_STEP
                while (y < pageHeight) {
                    canvas.drawLine(0f, y, pageWidth, y, linePaint)
                    y += PaperStyle.GRID_STEP
                }
            }
            PaperKind.DASH_GRID -> {
                linePaint.color = style.line
                linePaint.pathEffect = gridDashes
                var x = PaperStyle.GRID_STEP
                while (x < pageWidth) {
                    canvas.drawLine(x, 0f, x, pageHeight, linePaint)
                    x += PaperStyle.GRID_STEP
                }
                var y = PaperStyle.GRID_STEP
                while (y < pageHeight) {
                    canvas.drawLine(0f, y, pageWidth, y, linePaint)
                    y += PaperStyle.GRID_STEP
                }
                linePaint.pathEffect = null
            }
            PaperKind.DOTS -> {
                linePaint.color = style.dot
                var y = PaperStyle.GRID_STEP
                while (y < pageHeight) {
                    var x = PaperStyle.GRID_STEP
                    while (x < pageWidth) {
                        canvas.drawCircle(x, y, dotRadius, linePaint)
                        x += PaperStyle.GRID_STEP
                    }
                    y += PaperStyle.GRID_STEP
                }
            }
            PaperKind.MILLIMETER -> millimeter(canvas)
            PaperKind.MUSIC -> music(canvas)
            PaperKind.CORNELL -> cornell(canvas)
            PaperKind.PLANNER -> planner(canvas)
            PaperKind.PHOTO -> Unit
        }
    }

    /** Dashes of the dashed grid: four per cell, starting at the crossings so they show as crosses. */
    private val gridDashes = android.graphics.DashPathEffect(floatArrayOf(PaperStyle.GRID_STEP / 8, PaperStyle.GRID_STEP / 8), PaperStyle.GRID_STEP / 16)

    /** Radius of the dots of dotted paper; previews make them a pixel or so. */
    var dotRadius = 1.6f

    /** Lines one pixel wide whatever the scale: for small previews of the paper. */
    var hairline = false
        set(value) {
            field = value
            linePaint.strokeWidth = if (value) 0f else 1.2f
        }

    private fun width(w: Float) {
        linePaint.strokeWidth = if (hairline) 0f else w
    }

    /** Graph paper: 1 mm lines, every 5 mm stronger, every 10 mm stronger still. */
    private fun millimeter(canvas: Canvas) {
        val mm = 1f / Notebook.MM_PER_UNIT
        val faint = (style.grid and 0x00FFFFFF) or 0x66000000
        var i = 1
        while (true) {
            val x = i * mm
            val y = i * mm
            if (x >= pageWidth && y >= pageHeight) break
            when {
                i % 10 == 0 -> { linePaint.color = style.line; width(1.4f) }
                i % 5 == 0 -> { linePaint.color = style.grid; width(1.1f) }
                else -> { linePaint.color = faint; width(0.6f) }
            }
            // Previews skip the 1 mm lines: at that size they are only grey.
            if (!hairline || i % 5 == 0) {
                if (x < pageWidth) canvas.drawLine(x, 0f, x, pageHeight, linePaint)
                if (y < pageHeight) canvas.drawLine(0f, y, pageWidth, y, linePaint)
            }
            i++
        }
        width(1.2f)
    }

    /** Staves of five lines 2 mm apart, 17 mm from one staff to the next. */
    private fun music(canvas: Canvas) {
        val mm = 1f / Notebook.MM_PER_UNIT
        linePaint.color = style.line
        width(1.3f)
        val left = 15 * mm
        val right = pageWidth - 15 * mm
        var top = 25 * mm
        while (top + 8 * mm < pageHeight - 15 * mm) {
            for (k in 0..4) canvas.drawLine(left, top + 2 * k * mm, right, top + 2 * k * mm, linePaint)
            // The barline closing each staff on the left.
            canvas.drawLine(left, top, left, top + 8 * mm, linePaint)
            top += 17 * mm
        }
        width(1.2f)
    }

    /** Cornell notes: a title band, a cue column on the left, ruled notes and a summary box. */
    private fun cornell(canvas: Canvas) {
        val mm = 1f / Notebook.MM_PER_UNIT
        val head = 25 * mm
        val summary = pageHeight - 50 * mm
        val cue = 60 * mm
        linePaint.color = style.line
        var y = head + PaperStyle.LINE_SPACING
        while (y < summary - 2f) {
            canvas.drawLine(0f, y, pageWidth, y, linePaint)
            y += PaperStyle.LINE_SPACING
        }
        linePaint.color = style.margin
        width(1.8f)
        canvas.drawLine(0f, head, pageWidth, head, linePaint)
        canvas.drawLine(cue, head, cue, summary, linePaint)
        canvas.drawLine(0f, summary, pageWidth, summary, linePaint)
        width(1.2f)
        label(canvas, "Вопросы", 4 * mm, head + 7 * mm)
        label(canvas, "Конспект", cue + 4 * mm, head + 7 * mm)
        label(canvas, "Итог", 4 * mm, summary + 7 * mm)
    }

    /** A week: seven day boxes in two columns and one for notes, under a title band. */
    private fun planner(canvas: Canvas) {
        val mm = 1f / Notebook.MM_PER_UNIT
        val m = 10 * mm
        val top = 25 * mm
        val colW = (pageWidth - 3 * m) / 2
        val rowH = (pageHeight - top - m - 3 * (4 * mm)) / 4
        linePaint.color = style.line
        width(1.4f)
        canvas.drawLine(m, top - 6 * mm, pageWidth - m, top - 6 * mm, linePaint)
        val days = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье", "Заметки")
        for ((i, day) in days.withIndex()) {
            val col = i / 4
            val row = i % 4
            val x = m + col * (colW + m)
            val y = top + row * (rowH + 4 * mm)
            linePaint.color = style.grid
            var ly = y + 9 * mm + PaperStyle.FINE_LINE_SPACING
            while (ly < y + rowH - 2f) {
                canvas.drawLine(x, ly, x + colW, ly, linePaint)
                ly += PaperStyle.FINE_LINE_SPACING
            }
            linePaint.color = style.line
            canvas.drawLine(x, y + 9 * mm, x + colW, y + 9 * mm, linePaint)
            label(canvas, day, x, y + 6 * mm)
        }
        width(1.2f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun label(canvas: Canvas, text: String, x: Float, y: Float) {
        labelPaint.color = style.margin
        labelPaint.textSize = 4.2f / Notebook.MM_PER_UNIT
        canvas.drawText(text, x, y, labelPaint)
    }

    /**
     * Highlighter is laid like a real marker: multiplied into what is under it on light paper
     * (letters of a photo or PDF stay as dark as they were), screened on dark paper. Call
     * [endHighlighter] after drawing the highlighter strokes.
     */
    fun beginHighlighter(canvas: Canvas) {
        highlighterPaint.blendMode = if (inkOnDark) BlendMode.SCREEN else BlendMode.MULTIPLY
        canvas.saveLayer(null, highlighterPaint)
    }

    fun endHighlighter(canvas: Canvas) = canvas.restore()

    private val highlighterPaint = Paint()

    /** Only the pictures behind the page: the PDF page or the background photo, no ruling. */
    fun drawBackdrop(canvas: Canvas) {
        val page = RectF(0f, 0f, pageWidth, pageHeight)
        val pdfPage = pdf
        when {
            pdfPage != null -> canvas.drawBitmap(pdfPage, null, page, bitmapPaint)
            paper.kind == PaperKind.PHOTO -> photo?.let { drawCover(canvas, it, page) }
            else -> Unit
        }
    }

    /**
     * [toScreen] maps page units to device pixels; the canvas must already carry it, the
     * renderer only uses it to pick the level of detail.
     */
    fun drawStroke(canvas: Canvas, s: PageStroke, toScreen: Matrix) {
        if (s.kind == InkKind.NEON) {
            drawNeonGlow(canvas, s)
            drawNeonTube(canvas, s, toScreen, core = false)
            drawNeonTube(canvas, s, toScreen, core = true)
        } else if (s.dash != null) {
            drawDashed(canvas, s)
        } else {
            ink.draw(canvas, displayStroke(s), toScreen)
        }
    }

    private val dashPaths = WeakHashMap<PageStroke, Path>()
    private val dashEffects = HashMap<Pair<DashStyle, Float>, DashPathEffect>()
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * A dashed line with its own pattern ([PageStroke.dash]): the pen's track as it is (shapes
     * keep sharp corners), stroked with the line's width and dashes.
     */
    private fun drawDashed(canvas: Canvas, s: PageStroke) {
        val dash = s.dash ?: return
        val width = s.stroke.brush.size
        val path = dashPaths.getOrPut(s) { polyline(s.stroke) }
        if (dashEffects.size > 64) dashEffects.clear()
        dashPaint.pathEffect = dashEffects.getOrPut(dash to width) { DashPathEffect(dash.intervals(width), 0f) }
        dashPaint.color = InkColors.forPaper(s.stroke.brush.colorIntArgb, inkOnDark)
        dashPaint.strokeWidth = width
        canvas.drawPath(path, dashPaint)
    }

    private fun polyline(stroke: Stroke): Path {
        val inputs = stroke.inputs
        val path = Path()
        if (inputs.size == 0) return path
        val p = StrokeInput()
        inputs.populate(0, p)
        var px = p.x
        var py = p.y
        path.moveTo(px, py)
        for (i in 1 until inputs.size) {
            inputs.populate(i, p)
            if (kotlin.math.hypot(p.x - px, p.y - py) < 0.3f) continue
            px = p.x
            py = p.y
            path.lineTo(px, py)
        }
        if (inputs.size == 1) path.lineTo(px + 0.01f, py)
        return path
    }

    /**
     * Ink strokes of [strokes] in z-order. The glow of every neon line goes under all the
     * neon tubes, and lines drawn one after another light their cores over each other's
     * tubes, so crossing neon lines run together instead of the newer one's halo dimming
     * the older one.
     */
    fun drawInk(canvas: Canvas, strokes: List<PageStroke>, toScreen: Matrix) {
        for (s in strokes) if (s.kind == InkKind.NEON) drawNeonGlow(canvas, s)
        var i = 0
        while (i < strokes.size) {
            val s = strokes[i]
            if (s.kind != InkKind.NEON) {
                if (s.dash != null) drawDashed(canvas, s) else if (s.kind.isInk) ink.draw(canvas, displayStroke(s), toScreen)
                i++
                continue
            }
            var end = i
            while (end < strokes.size && strokes[end].kind == InkKind.NEON) end++
            for (k in i until end) drawNeonTube(canvas, strokes[k], toScreen, core = false)
            for (k in i until end) drawNeonTube(canvas, strokes[k], toScreen, core = true)
            i = end
        }
    }

    /**
     * Neon glow: a faint halo and a tight bright glow around the line, both in proportion
     * to the tube (the brush size).
     */
    private fun drawNeonGlow(canvas: Canvas, s: PageStroke) {
        val color = InkColors.forPaper(s.stroke.brush.colorIntArgb, inkOnDark)
        val tube = s.stroke.brush.size
        val halo = Neon.haloWidth(tube)
        val path = neonPaths.getOrPut(s) { centerline(s.stroke) }
        glowPaint.blendMode = if (inkOnDark && softGlow) BlendMode.SCREEN else null
        if (softGlow) {
            glow(canvas, path, color, 0x70, tube + halo * 2.2f, halo * 1.5f)
            glow(canvas, path, color, 0xC0, tube * 2.2f, tube * 0.9f)
        } else {
            glow(canvas, path, color, 0x1C, tube + halo * 3f, 0f)
            glow(canvas, path, color, 0x30, tube + halo * 1.8f, 0f)
            glow(canvas, path, color, 0x50, tube * 2.2f, 0f)
        }
    }

    /** The tube in the line's colour, or the light core inside it, like a lit gas tube. */
    private fun drawNeonTube(canvas: Canvas, s: PageStroke, toScreen: Matrix, core: Boolean) {
        val color = InkColors.forPaper(s.stroke.brush.colorIntArgb, inkOnDark)
        val tube = s.stroke.brush.size
        val neonParts = if (inkOnDark) neonOnDark else neonOnLight
        val parts = neonParts[s]?.takeIf { it.color == color } ?: NeonParts(
            color,
            s.stroke.copy(s.stroke.brush.copyWithColorIntArgb(colorIntArgb = color)),
            s.stroke.copy(s.stroke.brush.copyWithColorIntArgb(colorIntArgb = Neon.core(color), size = Neon.coreWidth(tube))),
        ).also { neonParts[s] = it }
        ink.draw(canvas, if (core) parts.core else parts.tube, toScreen)
    }

    private fun glow(canvas: Canvas, path: Path, color: Int, alpha: Int, width: Float, blur: Float) {
        glowPaint.color = (color and 0x00FFFFFF) or (alpha shl 24)
        glowPaint.strokeWidth = width
        glowPaint.maskFilter = if (blur > 0f) blurs.getOrPut(blur) { BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) } else null
        canvas.drawPath(path, glowPaint)
    }

    /** The pen's track, smoothed through the midpoints of its samples. */
    private fun centerline(stroke: Stroke): Path {
        val inputs = stroke.inputs
        val path = Path()
        if (inputs.size == 0) return path
        val p = StrokeInput()
        inputs.populate(0, p)
        var px = p.x
        var py = p.y
        path.moveTo(px, py)
        for (i in 1 until inputs.size) {
            inputs.populate(i, p)
            if (kotlin.math.hypot(p.x - px, p.y - py) < 0.5f) continue
            path.quadTo(px, py, (px + p.x) / 2, (py + p.y) / 2)
            px = p.x
            py = p.y
        }
        // A dot: a zero-length line still gets round caps.
        path.lineTo(px + 0.01f, py)
        return path
    }

    fun drawFill(canvas: Canvas, f: PageFill, alpha: Int, maxCells: Float = Hatch.MAX_CELLS) {
        canvas.save()
        canvas.clipPath(f.path)
        f.cutPath?.let(canvas::clipOutPath)
        // Anchored to the page, not the shape: fills of the same look line up (see Hatch).
        Hatch.draw(canvas, f.bounds, 0f, 0f, f.pattern, f.style, InkColors.forPaper(f.color, inkOnDark), alpha, fillPaint, maxCells)
        canvas.restore()
    }

    /**
     * Layers for drawing fills of the same look as one: off for PDF, whose canvas can't
     * mask a layer, so there each fill is drawn by itself.
     */
    var fillLayers = true

    private data class FillLook(val pattern: FillPattern, val style: HatchStyle, val color: Int)

    /**
     * [fills] in order. Those with the same pattern, settings and colour are one hatching seen
     * through all their shapes: the lines run on across them and are drawn once where they
     * overlap, so overlaps don't get darker.
     */
    fun drawFills(canvas: Canvas, fills: List<PageFill>, maxCells: Float = Hatch.budgetFor(fills.size)) {
        if (fills.size < 2 || !fillLayers) {
            for (f in fills) drawFill(canvas, f, 255, maxCells)
            return
        }
        val groups = LinkedHashMap<Any, MutableList<PageFill>>()
        // Solid ink (arrowheads, filled stamps) is opaque: nothing to gain from a layer.
        for (f in fills) groups.getOrPut(if (f.pattern == FillPattern.INK) f else FillLook(f.pattern, f.style.sane(), f.color)) { ArrayList(1) } += f
        for (group in groups.values) {
            if (group.size == 1) drawFill(canvas, group[0], 255, maxCells) else drawFillGroup(canvas, group, maxCells * group.size)
        }
    }

    private fun drawFillGroup(canvas: Canvas, group: List<PageFill>, maxCells: Float) {
        val first = group[0]
        val bounds = RectF(first.bounds)
        for (f in group) bounds.union(f.bounds)
        val color = InkColors.forPaper(first.color, inkOnDark)
        val alpha = Hatch.alphaOf(first.pattern, first.style, color, 255)
        if (alpha == 0) return
        // The pattern once over all the shapes, opaque; then kept only inside them; then faded.
        canvas.saveLayerAlpha(bounds, alpha)
        Hatch.draw(canvas, bounds, 0f, 0f, first.pattern, first.style, color, 255, fillPaint, maxCells, opaque = true)
        canvas.saveLayer(bounds, keepInsidePaint)
        for (f in group) {
            val cut = f.cutPath
            if (cut != null) {
                canvas.save()
                canvas.clipOutPath(cut)
            }
            canvas.drawPath(f.path, shapePaint)
            if (cut != null) canvas.restore()
        }
        canvas.restore()
        canvas.restore()
    }

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val keepInsidePaint = Paint().apply { blendMode = BlendMode.DST_IN }

    /** A placed picture; a light frame stands in while the bitmap is still loading. */
    fun drawImage(canvas: Canvas, img: PageImage, bitmap: Bitmap?) {
        val r = RectF(img.x, img.y, img.x + img.w, img.y + img.h)
        canvas.save()
        if (img.rotation != 0f) canvas.rotate(img.rotation, r.centerX(), r.centerY())
        val text = img.text
        if (text != null) {
            val color = InkColors.forPaper(text.color, inkOnDark)
            val layout = textLayouts[img]?.takeIf { it.first == color }?.second
                ?: TextBoxes.layout(text, img.w, color).also { textLayouts[img] = color to it }
            canvas.translate(img.x, img.y)
            layout.draw(canvas)
        } else if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, r, bitmapPaint)
        } else {
            edgePaint.strokeWidth = 1f
            edgePaint.color = style.edge
            canvas.drawRect(r, edgePaint)
        }
        canvas.restore()
    }

    /**
     * Hint marker: an opaque band with a grainy sheen that hides what is under it, or, once
     * [revealed], only a faint tint showing where it lies.
     */
    fun drawSpoiler(canvas: Canvas, s: PageStroke, toScreen: Matrix, revealed: Boolean) {
        val color = s.stroke.brush.colorIntArgb or 0xFF000000.toInt()
        if (revealed) {
            val tint = spoilerTints.getOrPut(s) { s.stroke.copy(s.stroke.brush.copyWithColorIntArgb(colorIntArgb = (color and 0x00FFFFFF) or 0x30000000)) }
            ink.draw(canvas, tint, toScreen)
            return
        }
        val band = spoilerBands.getOrPut(s) { s.stroke.copy(s.stroke.brush.copyWithColorIntArgb(colorIntArgb = color)) }
        // PDF: a plain band (no layers there).
        if (!softGlow) {
            ink.draw(canvas, band, toScreen)
            return
        }
        val b = s.stroke.shape.computeBoundingBox() ?: return
        val r = RectF(b.xMin, b.yMin, b.xMax, b.yMax)
        canvas.saveLayer(r, null)
        ink.draw(canvas, band, toScreen)
        canvas.drawRect(r, grainPaint)
        canvas.restore()
    }

    private val spoilerBands = WeakHashMap<PageStroke, Stroke>()
    private val textLayouts = WeakHashMap<PageImage, Pair<Int, android.text.StaticLayout>>()
    private val spoilerTints = WeakHashMap<PageStroke, Stroke>()

    /** Light and dark specks laid only over the band drawn just before (SRC_ATOP). */
    private val grainPaint = Paint().apply {
        val px = 32
        val tile = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(7)
        for (i in 0 until 90) {
            val light = rnd.nextBoolean()
            tile.setPixel(rnd.nextInt(px), rnd.nextInt(px), if (light) 0x88FFFFFF.toInt() else 0x44000000)
        }
        shader = android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(0.45f, 0.45f) })
        }
        blendMode = BlendMode.SRC_ATOP
    }

    /** Whole page content in z-order: pictures under fills under highlighter under ink, hint marker on top. */
    fun drawContent(
        canvas: Canvas,
        strokes: List<PageStroke>,
        fills: List<PageFill>,
        toScreen: Matrix,
        images: List<PageImage> = emptyList(),
        loadImage: (String) -> Bitmap? = { null },
        revealed: (PageStroke) -> Boolean = { false },
    ) {
        for (img in images) drawImage(canvas, img, loadImage(img.file))
        drawFills(canvas, fills)
        if (strokes.any { it.kind == InkKind.HIGHLIGHTER }) {
            beginHighlighter(canvas)
            for (s in strokes) if (s.kind == InkKind.HIGHLIGHTER) drawStroke(canvas, s, toScreen)
            endHighlighter(canvas)
        }
        drawInk(canvas, strokes, toScreen)
        for (s in strokes) if (s.kind == InkKind.SPOILER) drawSpoiler(canvas, s, toScreen, revealed = revealed(s))
    }

    /** Stroke recoloured for the current paper (see [InkColors.forPaper]), cached per stroke. */
    private fun displayStroke(s: PageStroke): Stroke {
        val color = s.stroke.brush.colorIntArgb
        val shown = InkColors.forPaper(color, inkOnDark)
        if (shown == color) return s.stroke
        val cache = if (inkOnDark) onDarkCache else onLightCache
        return cache.getOrPut(s) { s.stroke.copy(s.stroke.brush.copyWithColorIntArgb(colorIntArgb = shown)) }
    }

    private fun ruled(canvas: Canvas, spacing: Float, top: Float) {
        linePaint.color = style.line
        var y = top
        while (y < pageHeight - 40f) {
            canvas.drawLine(0f, y, pageWidth, y, linePaint)
            y += spacing
        }
        linePaint.color = style.margin
        canvas.drawLine(PaperStyle.MARGIN_X, 0f, PaperStyle.MARGIN_X, pageHeight, linePaint)
    }

    /** Scales the image to cover [page], cropping the overflow evenly. */
    private fun drawCover(canvas: Canvas, image: Bitmap, page: RectF) {
        val k = max(page.width() / image.width, page.height() / image.height)
        val w = min(image.width.toFloat(), page.width() / k)
        val h = min(image.height.toFloat(), page.height() / k)
        val left = ((image.width - w) / 2).toInt()
        val top = ((image.height - h) / 2).toInt()
        canvas.drawBitmap(image, Rect(left, top, (left + w).toInt(), (top + h).toInt()), page, bitmapPaint)
    }
}
