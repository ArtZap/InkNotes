package dev.inknotes.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import dev.inknotes.data.DashStyle
import dev.inknotes.data.FillPattern
import dev.inknotes.data.HatchStyle
import dev.inknotes.data.InkBrushes
import dev.inknotes.data.InkKind
import dev.inknotes.data.InkTextures
import dev.inknotes.data.PageBackground
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageImage
import dev.inknotes.data.PageStroke
import java.util.Collections
import java.util.IdentityHashMap
import dev.inknotes.math.Answer
import dev.inknotes.math.PageAnswer
import dev.inknotes.math.Printer
import dev.inknotes.math.Plot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Direction a finger scroll keeps to (see FingerGesture). */
private enum class ScrollAxis { UNDECIDED, VERTICAL, HORIZONTAL, FREE }

enum class Tool { PEN, FOUNTAIN, HIGHLIGHTER, ERASER, LASSO, AREA, NEON, LASER, SPOILER }

enum class EraserMode { WHOLE, PARTIAL }

/** What the eraser takes: everything, or only one kind (the marker without the text under it). */
enum class EraserTarget(val title: String) {
    ALL("Всё"),
    INK("Только ручка"),
    MARKER("Только маркер"),
    FILLS("Только штриховка"),
    ;

    fun strokes(kind: InkKind) = when (this) {
        ALL -> true
        INK -> kind.isInk
        MARKER -> kind == InkKind.HIGHLIGHTER || kind == InkKind.SPOILER
        FILLS -> false
    }

    val fills get() = this == ALL || this == FILLS
}

/** Which way a selection handle resizes: in proportion, or only wider or taller. */
private enum class Axis { BOTH, X, Y }

/** What the handles of a shape being reshaped stand for: an arc, a smooth curve, or corners. */
private enum class Line { ARC, CURVE, PIECES }

/**
 * All pages of a notebook stacked vertically and scrolled continuously. Elements of a page
 * live in that page's coordinates (an A4 page is [PAGE_W] wide, a unit is the same size on
 * every paper page); pages sit at ([lefts], [tops]) in "world" space and
 * [worldToView] maps the world to the screen.
 *
 * Input routing: the stylus uses the current tool on the page where the gesture starts;
 * fingers pan/zoom (1–2 fingers), a quick two-finger tap undoes and a three-finger tap
 * redoes. Fingers that land while the pen is down are ignored (palm). Any pointer that
 * lands inside the selection drags it. Holding the pen still at the end of a stroke
 * replaces the stroke with the ideal shape it resembles.
 *
 * A finger tap opens a hidden hint (hint marker), shows how an answer was found, fills the
 * shape under it (hatch tool) or selects the object under it; a finger held still and then
 * moved draws a lasso. Fingers on the ruler move and turn it; the pen run along its edge
 * draws a straight line. Straight lines and shapes stick to each other's ends and corners.
 */
@SuppressLint("ViewConstructor")
class PageEditorView(context: Context) : FrameLayout(context), InProgressStrokesFinishedListener {

    interface Listener {
        fun onDocumentChanged()
        fun onUndoGesture()
        fun onRedoGesture()
        fun onSelectionChanged(count: Int)
        fun onPendingAreaChanged(pending: Boolean)
        fun onVisiblePageChanged(index: Int)

        /** The eyedropper was lifted over this colour (opaque); [pickingColor] is off again. */
        fun onColorPicked(color: Int) {}

        /** Words [from]…[to] of the PDF text of page [page] picked (from < 0: the pick is gone). */
        fun onPdfTextPicked(page: Int, from: Int, to: Int) {}

        /** A finger tapped an answer after "=": show how it was found. */
        fun onAnswerTapped(answer: PageAnswer) {}

        /** A short message for the user (e.g. why a tap did nothing). */
        fun onHint(text: String) {}

        /** Edits made by [block] (on several pages) undo as one step. */
        fun editsTogether(block: () -> Unit) = block()

        /**
         * A new page right after page [after] (for what no longer fits on it when space was
         * inserted); returns its index, or null when it cannot be made.
         */
        fun addPageAfter(after: Int): Int? = null

        /** Space was inserted (or not): the one-shot mode is over. */
        fun onSpaceDone() {}

        /** The zoom window opened or closed. */
        fun onZoomPadChanged(open: Boolean) {}

        /** New writing on page [page] covering [bounds] (page units): sound being recorded is tied to it. */
        fun onInkAdded(page: Int, bounds: RectF) {}

        /** Plays the sound recorded while the writing at ([x], [y]) was made; false when there is none. */
        fun listenAt(page: Int, x: Float, y: Float): Boolean = false
    }

    /** The notebook's pages; documents are loaded lazily as pages scroll into view. */
    interface PageSource {
        val pageCount: Int
        fun pageHeight(index: Int): Float

        /** Width in page units: paper pages differ by format, a unit is the same size on all. */
        fun pageWidth(index: Int): Float = PAGE_W
        fun document(index: Int): PageDocument

        /** The page's document if it is in memory; null while it loads (the view is refreshed then). */
        fun loadedDocument(index: Int): PageDocument? = document(index)

        /** A small picture of the page, shown while it loads. */
        fun thumbnail(index: Int): Bitmap? = null

        /** Rendered PDF page, or null for ordinary pages or while it is still rendering. */
        fun pdfBitmap(index: Int): Bitmap?

        /** Sharp parts of a PDF page for the zoom shown; null when the page is not one. */
        fun pdfRegion(index: Int): PdfRegion? = null

        /** Decoded picture placed on a page, or null while it loads (the view is refreshed then). */
        fun image(file: String): Bitmap? = null

        /** Answers computed after "=" on page [index]. */
        fun answers(index: Int): List<PageAnswer> = emptyList()

        /** Rectangles of the links on page [index] (shown with a frame; a finger tap follows them). */
        fun links(index: Int): List<RectF> = emptyList()

        /** Writing on page [index] that has sound to it, shown while listening by touch is on. */
        fun soundMarks(index: Int): List<RectF> = emptyList()

        /** Follows the link at ([x], [y]) of page [index]; false when there is none. */
        fun followLink(index: Int, x: Float, y: Float): Boolean = false

        /** Words of the PDF under page [index] (page units), null for other pages or while they are read. */
        fun pdfWords(index: Int): List<dev.inknotes.data.PdfWord>? = null
    }

    var listener: Listener? = null

    var source: PageSource? = null
        set(value) {
            field = value
            relayout()
            fitToWidth(0)
        }

    private val pageRenderer = PageRenderer()

    var paper: PageBackground
        get() = pageRenderer.paper
        set(value) {
            pageRenderer.paper = value
            pageLayer.invalidate()
        }

    /** Decoded photo for [paper] when its kind is PHOTO. */
    var paperPhoto: Bitmap?
        get() = pageRenderer.photo
        set(value) {
            pageRenderer.photo = value
            pageLayer.invalidate()
        }

    /** PDF pages are shown dark (their pictures come dark from the source). */
    var pdfDark = false
        set(value) {
            if (field == value) return
            field = value
            pageRenderer.pdfDark = value
            pageLayer.invalidate()
        }

    var dark = false
        set(value) {
            if (field == value) return
            field = value
            pageRenderer.dark = value
            setBackgroundColor(PaperStyle.of(value).desk)
            pageLayer.invalidate()
        }

    var tool = Tool.PEN
        set(value) {
            if (field == value) return
            field = value
            if (value != Tool.LASSO) clearSelection()
            if (value != Tool.AREA) clearPendingArea()
            pageLayer.invalidate()
        }

    var eraserMode = EraserMode.WHOLE
    var eraserTarget = EraserTarget.ALL

    /**
     * Eyedropper: the next touch (pen or finger) picks the colour under it from what is on
     * screen (ink, paper, photo, PDF) instead of drawing; a loupe shows it while dragging.
     */
    var pickingColor = false
        set(value) {
            field = value
            if (!value) endPick()
        }
    private var pickShot: Bitmap? = null
    private var pickX = Float.NaN
    private var pickY = Float.NaN
    private var pickColor = 0

    /** Eraser radius on screen (dp), the same whatever the zoom. */
    var eraserRadiusDp = 10f
    /** What the pen tool draws: a pen line, pencil or dashes. */
    var penKind = InkKind.PEN

    /** Pattern of new dashed lines. */
    var dashStyle = DashStyle.DEFAULT

    var penColor = Color.BLACK
    var penSize = 2.8f
    var fountainColor = 0xFF1A1A1A.toInt()
    var fountainSize = 6f
    var neonColor = 0xFFFF2BD6.toInt()
    var neonSize = 7f
    var spoilerColor = 0xFF7E8A9A.toInt()
    var spoilerSize = 26f

    /** A zigzag scribbled over writing erases it. */
    var scribbleErase = true

    /** Layers not drawn; and those whose elements are not erased, selected or moved (hidden or locked). */
    var hiddenLayers: Set<Int> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            hiddenCache.clear()
            pageLayer.invalidate()
        }
    var frozenLayers: Set<Int> = emptySet()

    private fun editable(x: Any) = frozenLayers.isEmpty() || dev.inknotes.data.Layers.of(x) !in frozenLayers

    /** Elements of hidden layers per page, made again when the page changes. */
    private val hiddenCache = java.util.WeakHashMap<PageDocument, Pair<Int, Set<Any>>>()

    private fun hiddenOf(doc: PageDocument): Set<Any> {
        if (hiddenLayers.isEmpty()) return emptySet()
        hiddenCache[doc]?.takeIf { it.first == doc.revision }?.let { return it.second }
        val set: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        for (x in doc.strokes) if (x.layer in hiddenLayers) set += x
        for (x in doc.fills) if (x.layer in hiddenLayers) set += x
        for (x in doc.images) if (x.layer in hiddenLayers) set += x
        hiddenCache[doc] = doc.revision to set
        return set
    }

    /** The ruler lying over the pages (world coordinates). */
    val ruler = Ruler()
    var highlighterColor = 0x80FFEB3B.toInt()
    var highlighterSize = 22f
    var fillPattern = FillPattern.LINES
    var fillStyle = HatchStyle.DEFAULT
    var fillColor = Color.BLACK

    /** Page shown in the middle of the screen. */
    var currentPage = 0
        private set

    private val pageLayer = PageLayer(context)
    private val laser = Laser(resources.displayMetrics.density)
    private val inProgressView = InProgressStrokesView(context).apply { textureBitmapStore = InkTextures.delegate }
    private val predictor = MotionEventPredictor.newInstance(this)
    private val worldToView = Matrix()
    private val viewToWorld = Matrix()
    private var tops = FloatArray(0)
    private var heights = FloatArray(0)
    private var widths = FloatArray(0)

    /** Pages of different widths are centred under one another in a column this wide. */
    private var lefts = FloatArray(0)
    private var worldWidth = PAGE_W
    private var totalHeight = 0f

    /** Page, kind and the colour the user chose (wet ink is shown recoloured for the paper). */
    private class WetStroke(val page: Int, val kind: InkKind, val color: Int, val size: Float, val dash: DashStyle?, val even: Boolean = false)

    private val wetStrokes = HashMap<InProgressStrokeId, WetStroke>()
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private enum class Mode { NONE, DRAW, SNAPPED, ERASE, GESTURE, PATH, MOVE, LASER, RULER, RULER_DRAW, SPACE, COMPASS, PDF_TEXT }

    private var mode = Mode.NONE

    /** Page the current gesture works on. */
    private var activePage = 0

    /** Pointer that draws, erases, traces a path or drags in the current gesture. */
    private var activePointerId = -1
    private var drawStrokeId: InProgressStrokeId? = null
    private var fitScale = 1f

    // Eraser/hover cursor in page coordinates of [cursorPage]; NaN when hidden.
    private var cursorPage = 0
    private var cursorX = Float.NaN
    private var cursorY = Float.NaN
    private var lastX = 0f
    private var lastY = 0f

    /** Lasso or area outline being traced (x,y pairs) on [activePage]. */
    private val tracePoints = ArrayList<Float>()
    private var pendingArea: FloatArray? = null
    private var areaPage = 0

    /**
     * [pendingArea] without the closed shapes inside it (see [AreaFinder.carve]), once worked
     * out; it is what [applyFill] puts on the page.
     */
    private var pendingPieces: List<FloatArray>? = null

    private val cache = PageCache { pageLayer.invalidate() }

    /** The selection is being dragged, resized or turned. */
    private val dragging get() = moveDx != 0f || moveDy != 0f || moveKx != 1f || moveKy != 1f || moveAngle != 0f || vertexPoints != null

    /** Highlighter passes over text become level bands behind the letters. */
    var straightHighlighter = true

    private val selected: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    private var selectionPage = 0
    private var moveStartX = 0f
    private var moveStartY = 0f
    private var moveDx = 0f

    // Resizing the selection with its corner handle, or stretching it with a side handle
    // ([resizeAxis]): width times [moveKx], height times [moveKy] around the anchor;
    // turning it with the handle above the frame: [moveAngle] degrees around the pivot.
    private var resizing = false
    private var resizeAxis = Axis.BOTH
    private var rotating = false

    /** A move that has not yet gone past the touch slop: lifting now is a tap on the selection. */
    private var moveTravelled = false
    private var moveDownAt = 0L
    private var moveByFinger = false
    private var moveKx = 1f
    private var moveKy = 1f
    private var moveAngle = 0f
    // Not "pivotX": inside the page layer (a View) that name is the layer's own pivot.
    private var turnX = 0f
    private var turnY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var cornerX = 0f
    private var cornerY = 0f
    private var moveDy = 0f

    // Dragging one corner of the selected shape: its corners as moved so far; -1 = no corner.
    private var vertexIndex = -1
    private var vertexPoints: FloatArray? = null
    private var vertexStart = FloatArray(0)
    private var vertexClosed = false
    private var vertexLine = Line.PIECES

    // Magnet while moving or drawing: connection points of what moves and what they stick to.
    private var moveAnchors = FloatArray(0)
    private var moveTargets: Magnet.Targets? = null

    /** Where the last snap went, in page units of [snapPage]; NaN = nowhere. */
    private var snapX = Float.NaN
    private var snapY = Float.NaN
    private var snapPage = 0

    /** Hint-marker bands opened by a tap (until tapped again). */
    private val revealed: MutableSet<PageStroke> = Collections.newSetFromMap(java.util.WeakHashMap())

    // A finger held still turns into a lasso.
    private var fingerLasso = false
    private var downX = 0f
    private var downY = 0f
    private val longPressRunnable = Runnable { startFingerLasso() }

    // Moving the ruler with one or two fingers (view pixels / degrees at the last event).
    private var rulerX = 0f
    private var rulerY = 0f
    private var rulerTurn = Float.NaN
    private var rulerAngle0 = 0f
    private var rulerTurn0 = 0f

    // A line along the ruler: its edge and extent along it.
    private var rulerEdge = 0
    private var rulerFrom = 0f
    private var rulerTo = 0f
    private var rulerOffset = 0f

    // Hold-to-snap: samples of the current stroke and where/when the pen last moved.
    private val strokeSamples = ArrayList<ShapeRecognizer.P>()
    private val strokePressures = ArrayList<Float>()
    private var holdAnchorX = 0f
    private var holdAnchorY = 0f
    private val holdRunnable = Runnable { snapToShape() }

    // A plain main-thread handler: View.postDelayed would not run while the view is detached.
    private val holdHandler = Handler(Looper.getMainLooper())

    private val gesture = FingerGesture()

    // Search hit highlighted for a few seconds (page coordinates of [flashPage]).
    private var flashPage = -1
    private var flashRect: RectF? = null
    private var pendingFlash: Pair<Int, RectF>? = null
    private val flashRunnable = Runnable {
        flashRect = null
        pageLayer.invalidate()
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFB300 }
    private val spacePaint = Paint()
    private val soundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xAAFF6D00.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xAA2979FF.toInt()
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = GUIDE_COLOR
    }

    init {
        setBackgroundColor(PaperStyle.LIGHT.desk)
        addView(pageLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgressView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inProgressView.addFinishedStrokesListener(this)
        setOnGenericMotionListener { _, e -> onHover(e) }
        post(::warmUpInk)
    }

    fun refresh() = pageLayer.invalidate()

    /** Called after undo/redo replaced page contents. */
    fun onContentReplaced() {
        clearSelection()
        pageLayer.invalidate()
    }

    /** Recomputes page positions after pages were added, removed, moved or resized. */
    fun relayout() {
        val src = source
        val n = src?.pageCount ?: 0
        heights = FloatArray(n) { src!!.pageHeight(it) }
        widths = FloatArray(n) { src!!.pageWidth(it) }
        val oldWidth = worldWidth
        worldWidth = widths.maxOrNull() ?: PAGE_W
        lefts = FloatArray(n) { (worldWidth - widths[it]) / 2 }
        tops = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            tops[i] = y
            y += heights[i] + PAGE_GAP
        }
        totalHeight = max(0f, y - PAGE_GAP)
        clearSelection()
        clearPendingArea()
        // A page of another width came or went: same place and zoom, refitted.
        if (worldWidth != oldWidth && width > 0 && n > 0) refit(width) else applyViewport()
    }

    /** Where page [index] is on screen: how far its top is from the view's top (pixels). */
    fun pagePlace(index: Int): Float? {
        if (index !in tops.indices) return null
        val v = FloatArray(9).also(worldToView::getValues)
        return v[Matrix.MTRANS_Y] + tops[index] * v[Matrix.MSCALE_Y]
    }

    /** Puts page [index] back where [pagePlace] found it (after pages around it moved). */
    fun restorePagePlace(index: Int, place: Float) {
        if (index !in tops.indices) return
        stopFling()
        val v = FloatArray(9).also(worldToView::getValues)
        v[Matrix.MTRANS_Y] = place - tops[index] * v[Matrix.MSCALE_Y]
        worldToView.setValues(v)
        applyViewport()
    }

    /** Page [index] at the top of the view, or the place [y] units down it (a bit below the top edge). */
    fun scrollToPage(index: Int, y: Float = 0f) {
        if (index !in tops.indices) return
        stopFling()
        val v = FloatArray(9).also(worldToView::getValues)
        val margin = if (y > 0f) 3 * PAGE_MARGIN * density else PAGE_MARGIN * density
        v[Matrix.MTRANS_Y] = margin - (tops[index] + y) * v[Matrix.MSCALE_X]
        worldToView.setValues(v)
        applyViewport()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Only the height changed (the tool bar above grew or shrank): keep zoom and place,
        // onLayout keeps the pages where they were on screen.
        // The width changed (the pages panel opened, the tablet turned): the same place stays in
        // view, fitted to the new width if it was fitted, otherwise at the same zoom.
        when {
            oldw == w && oldw > 0 && tops.isNotEmpty() -> applyViewport()
            oldw > 0 && tops.isNotEmpty() -> refit(oldw)
            else -> fitToWidth(currentPage)
        }
        pendingFlash?.let { (page, rect) ->
            pendingFlash = null
            flash(page, rect)
        }
    }

    private var windowTop = Int.MIN_VALUE
    private var layoutWidth = 0

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val loc = IntArray(2)
        getLocationInWindow(loc)
        // Moved down or up in the window at the same width: the pages stay put on screen.
        if (windowTop != Int.MIN_VALUE && loc[1] != windowTop && width == layoutWidth && tops.isNotEmpty()) {
            worldToView.postTranslate(0f, (windowTop - loc[1]).toFloat())
            applyViewport()
        }
        windowTop = loc[1]
        layoutWidth = width
    }

    /** Scrolls [rect] (page coordinates of [page]) to the middle of the screen and marks it briefly. */
    fun flash(page: Int, rect: RectF) {
        stopFling()
        if (width == 0 || page !in tops.indices) {
            pendingFlash = page to RectF(rect)
            return
        }
        val v = FloatArray(9).also(worldToView::getValues)
        val s = v[Matrix.MSCALE_X]
        v[Matrix.MTRANS_X] = width / 2f - (lefts[page] + rect.centerX()) * s
        v[Matrix.MTRANS_Y] = height / 2f - (tops[page] + rect.centerY()) * s
        worldToView.setValues(v)
        applyViewport()
        flashPage = page
        flashRect = RectF(rect).apply { inset(-FLASH_PAD, -FLASH_PAD) }
        holdHandler.removeCallbacks(flashRunnable)
        holdHandler.postDelayed(flashRunnable, FLASH_MS)
        pageLayer.invalidate()
    }

    /**
     * Room kept free beside the pages, as a share of the view's width: on the left when
     * positive, on the right when negative (somewhere to rest the hand, or for notes beside).
     * The pages fit into what is left.
     */
    var pageShift = 0f
        set(value) {
            val v = value.coerceIn(-MAX_PAGE_SHIFT, MAX_PAGE_SHIFT)
            if (v == field) return
            field = v
            if (width > 0 && tops.isNotEmpty()) refit(width) else applyViewport()
        }

    /** Left and right edges of the part of the view the pages fit into (view pixels). */
    private fun pagesLeft() = PAGE_MARGIN * density + max(pageShift, 0f) * width
    private fun pagesRight() = width - PAGE_MARGIN * density - max(-pageShift, 0f) * width

    private fun fitScaleNow() = max(1f, pagesRight() - pagesLeft()) / worldWidth

    /** Fit page width to the screen and show [page] at the top. */
    fun fitToWidth(page: Int = currentPage) {
        if (width == 0) return
        fitScale = fitScaleNow()
        worldToView.setScale(fitScale, fitScale)
        worldToView.postTranslate(pagesLeft(), PAGE_MARGIN * density)
        scrollToPage(page.coerceIn(0, max(0, tops.size - 1)))
    }

    /**
     * After the view's width ([oldWidth] before), the room beside the pages or the pages' width
     * changed: what was at the top of the screen stays there, the zoom stays (or the pages stay
     * fitted, if they were), nothing scrolls back to the start of a page.
     */
    private fun refit(oldWidth: Int) {
        if (width == 0) return
        stopFling()
        val v = FloatArray(9).also(worldToView::getValues)
        val s = v[Matrix.MSCALE_X]
        val fitted = abs(s - fitScale) <= fitScale * 0.01f
        fitScale = fitScaleNow()
        val ns = if (fitted) fitScale else s
        val worldTop = -v[Matrix.MTRANS_Y] / s
        val worldX = (oldWidth / 2f - v[Matrix.MTRANS_X]) / s
        v[Matrix.MSCALE_X] = ns
        v[Matrix.MSCALE_Y] = ns
        v[Matrix.MTRANS_Y] = -worldTop * ns
        v[Matrix.MTRANS_X] = width / 2f - worldX * ns
        worldToView.setValues(v)
        applyViewport()
    }

    /** Maps page coordinates of [page] to view pixels (for tests and overlays). */
    fun pageToViewPoint(page: Int, x: Float, y: Float): FloatArray =
        floatArrayOf(x + lefts[page], y + tops[page]).also { worldToView.mapPoints(it) }

    private fun pageMatrix(page: Int) = Matrix(worldToView).apply { preTranslate(lefts[page], tops[page]) }

    /** The ink strokes in the lasso selection. */
    fun selectedInk(): List<PageStroke> = selected.filterIsInstance<PageStroke>().filter { it.kind.isInk }

    private fun viewToPageMatrix(page: Int) = Matrix(viewToWorld).apply { postTranslate(-lefts[page], -tops[page]) }

    private fun toPage(page: Int, x: Float, y: Float): FloatArray =
        floatArrayOf(x, y).also {
            viewToWorld.mapPoints(it)
            it[0] -= lefts[page]
            it[1] -= tops[page]
        }

    /** Page under a view point; points in the gap between pages belong to the nearer page. */
    private fun pageAt(viewX: Float, viewY: Float): Int {
        if (tops.isEmpty()) return 0
        val w = floatArrayOf(viewX, viewY).also(viewToWorld::mapPoints)
        for (i in tops.indices) if (w[1] < tops[i] + heights[i] + PAGE_GAP / 2) return i
        return tops.lastIndex
    }

    private fun doc(page: Int): PageDocument? = source?.takeIf { page < it.pageCount }?.document(page)

    // ------------------------------------------------------------ selection

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        pageLayer.invalidate()
        listener?.onSelectionChanged(0)
    }

    fun deleteSelection() {
        val doc = doc(selectionPage) ?: return
        doc.edit(doc.strokes.filterNot { it in selected }, doc.fills.filterNot { it in selected }, doc.images.filterNot { it in selected })
        clearSelection()
        changed()
    }

    /** What was copied with Ctrl+C: elements of a page (immutable, so kept as they are) and their box. */
    class Clip(val strokes: List<PageStroke>, val fills: List<PageFill>, val images: List<PageImage>, val box: RectF)

    fun copySelection(): Clip? {
        val doc = doc(selectionPage) ?: return null
        val box = rawSelectionBounds()?.let(::RectF) ?: return null
        return Clip(doc.strokes.filter { it in selected }, doc.fills.filter { it in selected }, doc.images.filter { it in selected }, box)
    }

    /** [clip] put on [page], selected: where it was copied from, a little lower if that place is taken, else in the middle of the screen. */
    fun paste(page: Int, clip: Clip, samePlace: Boolean) {
        val (dx, dy) = if (samePlace) {
            DUPLICATE_OFFSET to DUPLICATE_OFFSET
        } else {
            val c = visibleCenter(page)
            (c[0] - clip.box.centerX()) to (c[1] - clip.box.centerY())
        }
        insert(
            page,
            strokes = clip.strokes.map { PageEdits.translate(it, dx, dy) },
            images = clip.images.map { PageEdits.translate(it, dx, dy) },
            fills = clip.fills.map { PageEdits.translate(it, dx, dy) },
        )
    }

    fun duplicateSelection() {
        val doc = doc(selectionPage) ?: return
        val strokes = doc.strokes.filter { it in selected }
            .map { PageEdits.translate(it, DUPLICATE_OFFSET, DUPLICATE_OFFSET) }
        val fills = doc.fills.filter { it in selected }
            .map { PageEdits.translate(it, DUPLICATE_OFFSET, DUPLICATE_OFFSET) }
        val images = doc.images.filter { it in selected }
            .map { PageEdits.translate(it, DUPLICATE_OFFSET, DUPLICATE_OFFSET) }
        doc.edit(doc.strokes + strokes, doc.fills + fills, doc.images + images)
        reselect(strokes + fills + images)
        changed()
    }

    fun recolorSelection(color: Int) {
        val doc = doc(selectionPage) ?: return
        val newSelection = ArrayList<Any>()
        val strokes = doc.strokes.map { s ->
            if (s in selected) PageEdits.recolor(s, color).also(newSelection::add) else s
        }
        val fills = doc.fills.map { f ->
            if (f in selected) PageEdits.recolor(f, color).also(newSelection::add) else f
        }
        doc.edit(strokes, fills)
        doc.images.filterTo(newSelection) { it in selected } // pictures keep their colours
        reselect(newSelection)
        changed()
    }

    /**
     * Kinds of line among the selection that can be changed into one another (not markers),
     * once each; empty when there are none.
     */
    fun selectedLineKinds(): Set<InkKind> =
        selected.mapNotNullTo(LinkedHashSet()) { (it as? PageStroke)?.kind?.takeIf { k -> k in LINE_KINDS } }

    /**
     * Draws the selected lines as [kind] (a neon line as an ordinary one, and back), in
     * their colours and as wide as that kind of line is set now (its tool's slider).
     */
    fun restyleSelection(kind: InkKind) {
        if (kind !in LINE_KINDS) return
        val doc = doc(selectionPage) ?: return
        val newSelection = ArrayList<Any>()
        var any = false
        val strokes = doc.strokes.map { s ->
            when {
                s !in selected -> s
                s.kind !in LINE_KINDS || s.kind == kind -> s.also(newSelection::add)
                else -> {
                    any = true
                    restyle(s, kind).also(newSelection::add)
                }
            }
        }
        if (!any) return
        doc.edit(strokes = strokes)
        doc.fills.filterTo(newSelection) { it in selected }
        doc.images.filterTo(newSelection) { it in selected }
        reselect(newSelection)
        changed()
    }

    private fun restyle(s: PageStroke, kind: InkKind): PageStroke {
        val size = when (kind) {
            // An even line is as thick as the pen's line looks.
            InkKind.LINE -> InkBrushes.lineWidth(penSize)
            else -> sizeOf(kind)
        }
        val dash = if (kind == InkKind.DASHED) s.dash ?: dashStyle else null
        return PageStroke(kind, Stroke(InkBrushes.create(kind, s.stroke.brush.colorIntArgb, size), s.stroke.inputs), dash, s.layer)
    }

    /** Width a line shows (page units): a fountain pen's size is its nib, twice its usual line. */
    private fun shownWidth(s: PageStroke) = s.stroke.brush.size * if (s.kind == InkKind.FOUNTAIN) FOUNTAIN_TO_LINE else 1f

    /** Width the selected lines show (the widest of them), or null when no lines are selected. */
    fun selectionLineWidth(): Float? {
        val doc = doc(selectionPage) ?: return null
        return doc.strokes.filter { it in selected }.maxOfOrNull(::shownWidth)
    }

    /**
     * Makes every selected line [width] wide as it shows. Calls while a slider is dragged
     * make one undo step, closed by [endSelectionLineWidth].
     */
    fun setSelectionLineWidth(width: Float) {
        val doc = doc(selectionPage) ?: return
        if (doc.strokes.none { it in selected }) return
        val w = width.coerceIn(MIN_LINE_WIDTH, MAX_LINE_WIDTH)
        val newSelection = ArrayList<Any>()
        val strokes = doc.strokes.map { s ->
            if (s !in selected) return@map s
            val size = if (s.kind == InkKind.FOUNTAIN) w / FOUNTAIN_TO_LINE else w
            val resized = if (abs(s.stroke.brush.size - size) < 1e-3f) s else s.with(Stroke(InkBrushes.create(s.kind, s.stroke.brush.colorIntArgb, size), s.stroke.inputs))
            newSelection += resized
            resized
        }
        doc.beginSession()
        doc.set(strokes = strokes)
        doc.fills.filterTo(newSelection) { it in selected }
        doc.images.filterTo(newSelection) { it in selected }
        reselect(newSelection)
    }

    fun endSelectionLineWidth() {
        val doc = doc(selectionPage) ?: return
        doc.commitSession()
        changed()
    }

    /** Gives the selected dashed lines [style]. */
    fun restyleSelectedDashes(style: DashStyle) {
        val doc = doc(selectionPage) ?: return
        if (doc.strokes.none { it in selected && it.kind == InkKind.DASHED }) return
        val newSelection = ArrayList<Any>()
        val strokes = doc.strokes.map { s ->
            when {
                s !in selected -> s
                s.kind != InkKind.DASHED -> s.also(newSelection::add)
                else -> PageStroke(s.kind, s.stroke, style, s.layer).also(newSelection::add)
            }
        }
        doc.edit(strokes = strokes)
        doc.fills.filterTo(newSelection) { it in selected }
        doc.images.filterTo(newSelection) { it in selected }
        reselect(newSelection)
        changed()
    }

    /** Page of the current selection. */
    val selectionPageIndex get() = selectionPage

    /** Width and height of the selection in page units, or null with nothing selected. */
    /** Moves the selection onto [layer]; it stays selected unless that layer is hidden or locked. */
    fun moveSelectionToLayer(layer: Int) {
        val doc = doc(selectionPage) ?: return
        if (selected.isEmpty()) return
        val moved = ArrayList<Any>()
        doc.edit(
            doc.strokes.map { if (it in selected) it.onLayer(layer).also(moved::add) else it },
            doc.fills.map { if (it in selected) it.copy(layer = layer).also(moved::add) else it },
            doc.images.map { if (it in selected) it.onLayer(layer).also(moved::add) else it },
        )
        if (layer in frozenLayers) reselect(emptyList()) else reselect(moved)
        changed()
    }

    /** The one text box selected, or null. */
    /** The one selected picture (not a text box), or null. */
    fun selectedPicture(): PageImage? = selected.singleOrNull()?.let { it as? PageImage }?.takeIf { !it.isText }

    fun selectedTextBox(): PageImage? = selected.singleOrNull()?.let { it as? PageImage }?.takeIf { it.isText }

    /** Puts [img] in place of the selected text box (same page), keeping it selected. */
    fun replaceTextBox(img: PageImage) {
        val old = selectedTextBox() ?: return
        val doc = doc(selectionPage) ?: return
        doc.edit(images = doc.images.map { if (it === old) img else it })
        reselect(listOf(img))
        changed()
    }

    /** The selection gives way to [img] (handwriting turned into typed text), in one step. */
    fun replaceSelection(img: PageImage) {
        val doc = doc(selectionPage) ?: return
        if (selected.isEmpty()) return
        doc.edit(doc.strokes.filterNot { it in selected }, doc.fills.filterNot { it in selected }, doc.images.filterNot { it in selected } + img)
        reselect(listOf(img))
        changed()
    }

    /** Bounds of the selection on its page (before any drag in progress). */
    fun selectionRect(): RectF? = rawSelectionBounds()?.let(::RectF)

    fun selectionSize(): Pair<Float, Float>? = rawSelectionBounds()?.let { it.width() to it.height() }

    /**
     * Gives the selection this width and height (page units), keeping its top-left corner.
     * In proportion it is scaled; otherwise lengthened or shortened ([Elongate]).
     */
    fun resizeSelection(w: Float, h: Float, proportional: Boolean) {
        val doc = doc(selectionPage) ?: return
        val r = rawSelectionBounds() ?: return
        if (!proportional) {
            val res = Elongate.resize(
                doc.strokes.filter { it in selected }, doc.fills.filter { it in selected }, doc.images.filter { it in selected },
                r, w.coerceIn(MIN_RESIZE * r.width(), MAX_RESIZE * max(r.width(), 1f)), h.coerceIn(MIN_RESIZE * r.height(), MAX_RESIZE * max(r.height(), 1f)),
            )
            val si = res.strokes.iterator()
            val fi = res.fills.iterator()
            val ii = res.images.iterator()
            doc.edit(
                doc.strokes.map { if (it in selected) si.next() else it },
                doc.fills.map { if (it in selected) fi.next() else it },
                doc.images.map { if (it in selected) ii.next() else it },
            )
            reselect(res.strokes + res.fills + res.images)
            changed()
            return
        }
        // A straight line has no height to stretch: only its length changes.
        val fitW = r.width() > 0.01f
        val fitH = r.height() > 0.01f
        var sx = if (fitW) (w / r.width()).coerceIn(MIN_RESIZE, MAX_RESIZE) else 1f
        var sy = if (fitH) (h / r.height()).coerceIn(MIN_RESIZE, MAX_RESIZE) else 1f
        if (abs(sx - 1f) < 1e-4f && abs(sy - 1f) < 1e-4f) return
        lateinit var moved: ArrayList<Any>
        lateinit var strokes: List<PageStroke>
        lateinit var fills: List<PageFill>
        lateinit var images: List<PageImage>
        // Bounds include the width of the lines, which scales differently from the shape:
        // a couple of corrections bring the result to the size asked for.
        repeat(3) { round ->
            val t = PageEdits.Transform.stretch(sx, sy, r.left, r.top)
            val ms = ArrayList<PageStroke>()
            val mf = ArrayList<PageFill>()
            val mi = ArrayList<PageImage>()
            strokes = doc.strokes.map { s -> if (s in selected) PageEdits.transform(s, t).also(ms::add) else s }
            fills = doc.fills.map { f -> if (f in selected) PageEdits.transform(f, t).also(mf::add) else f }
            images = doc.images.map { img -> if (img in selected) PageEdits.transform(img, t).also(mi::add) else img }
            moved = ArrayList<Any>(ms + mf + mi)
            if (round == 2) return@repeat
            val got = boundsOf(ms, mf, mi) ?: return@repeat
            if (fitW && got.width() > 0.01f) sx = (sx * w / got.width()).coerceIn(MIN_RESIZE, MAX_RESIZE)
            if (fitH && got.height() > 0.01f) sy = (sy * h / got.height()).coerceIn(MIN_RESIZE, MAX_RESIZE)
        }
        doc.edit(strokes, fills, images)
        reselect(moved)
        changed()
    }

    /** Number of selected fills (the lasso toolbar offers restyling only when there are some). */
    fun selectedFillCount(): Int = doc(selectionPage)?.fills?.count { it in selected } ?: 0

    /** Gives the selected fills a new pattern and style, keeping their colours and outlines. */
    fun restyleSelection(pattern: FillPattern, style: HatchStyle) {
        val doc = doc(selectionPage) ?: return
        if (doc.fills.none { it in selected }) return
        val newSelection = ArrayList<Any>(doc.strokes.filter { it in selected } + doc.images.filter { it in selected })
        val fills = doc.fills.map { f ->
            if (f in selected) f.copy(pattern = pattern, style = style).also(newSelection::add) else f
        }
        doc.edit(fills = fills)
        reselect(newSelection)
        changed()
    }

    /**
     * Adds ready-made elements (a stamp, a table, a picture) to [page] as one undo step and
     * selects them, so they can be dragged into place or resized right away.
     */
    fun insert(page: Int, strokes: List<PageStroke> = emptyList(), images: List<PageImage> = emptyList(), fills: List<PageFill> = emptyList()) {
        val doc = doc(page) ?: return
        doc.edit(strokes = doc.strokes + strokes, fills = doc.fills + fills, images = doc.images + images)
        selectionPage = page
        reselect(strokes + fills + images)
        changed()
    }

    /** Mirrors the selection left to right ([horizontal]) or top to bottom, in place. */
    fun flipSelection(horizontal: Boolean) {
        val doc = doc(selectionPage) ?: return
        val r = rawSelectionBounds() ?: return
        val m = Matrix().apply { setScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f, r.centerX(), r.centerY()) }
        val t = PageEdits.Transform(m)
        val moved = ArrayList<Any>()
        val strokes = doc.strokes.map { s -> if (s in selected) PageEdits.transform(s, t).also(moved::add) else s }
        val fills = doc.fills.map { f ->
            if (f in selected) {
                fun map(a: FloatArray) = a.copyOf().also(m::mapPoints)
                // A mirrored hatch runs the other way.
                f.copy(outline = map(f.outline), cuts = f.cuts.map { dev.inknotes.data.EraseCut(map(it.points), it.radius) }, style = f.style.copy(angle = 180f - f.style.angle).sane()).also(moved::add)
            } else {
                f
            }
        }
        // A picture keeps its face; it changes place and turns the mirrored way.
        val images = doc.images.map { img ->
            if (img in selected) {
                val c = floatArrayOf(img.x + img.w / 2, img.y + img.h / 2).also(m::mapPoints)
                img.moved(x = c[0] - img.w / 2, y = c[1] - img.h / 2, rotation = (360f - img.rotation) % 360f).also(moved::add)
            } else {
                img
            }
        }
        doc.edit(strokes, fills, images)
        reselect(moved)
        changed()
    }

    /** A pen, finger or gesture is on the page: undo and redo wait until it is done. */
    val busy get() = mode != Mode.NONE

    /** True when the selection is the lines of a table (see [Tables]). */
    fun selectionIsTable(): Boolean = selected.isNotEmpty() && Tables.grid(selected) != null

    /** Adds a row under the selected table ([row]) or a column on its right. */
    fun addToTable(row: Boolean) {
        val doc = doc(selectionPage) ?: return
        val grid = Tables.grid(selected) ?: return
        val added = if (row) Tables.addRow(grid) else Tables.addColumn(grid)
        if (added.isEmpty()) return
        doc.edit(strokes = doc.strokes + added)
        reselect(selected.toList() + added)
        changed()
    }

    /** Takes the last row ([row]) or column away from the selected table; false when only one is left. */
    fun removeFromTable(row: Boolean): Boolean {
        val doc = doc(selectionPage) ?: return false
        val grid = Tables.grid(selected) ?: return false
        val (drop, add) = Tables.removeLast(grid, row) ?: return false
        val gone: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        gone.addAll(drop)
        doc.edit(strokes = doc.strokes.filterNot { it in gone } + add, fills = doc.fills.filterNot { it in gone })
        reselect(selected.filterNot { it in gone } + add)
        changed()
        return true
    }

    /** Shows or hides the ruler (or the [protractor]); it appears level in the middle of the screen. */
    fun toggleRuler(protractor: Boolean = false): Boolean {
        if (ruler.shown && ruler.protractor == protractor) {
            ruler.shown = false
        } else {
            ruler.protractor = protractor
            val c = floatArrayOf(width / 2f, height / 2f).also(viewToWorld::mapPoints)
            ruler.cx = c[0]
            // The half disc stands on its centre: lower, so all of it is in sight.
            ruler.cy = c[1] + if (protractor) Ruler.RADIUS * 0.45f else 0f
            ruler.angle = 0f
            ruler.shown = true
        }
        pageLayer.invalidate()
        return ruler.shown
    }

    /** The one selected stroke if it is a shape of straight pieces: its corners can be dragged. */
    private fun selectedShape(): PageStroke? {
        if (selected.size != 1) return null
        val s = selected.first() as? PageStroke ?: return null
        return s.takeIf { Magnet.points(it) != null }
    }

    /** Handles of a shape: an arc's ends and middle, or the corners of straight pieces. */
    private fun handlePoints(s: PageStroke): FloatArray? = Magnet.arc(s) ?: Magnet.curve(s) ?: Magnet.polyline(s)

    /** The line [handles] of a dragged shape stand for: the arc through them, or the corners themselves. */
    private fun shapeThrough(handles: FloatArray): FloatArray = when (vertexLine) {
        Line.ARC -> Magnet.arcThrough(handles)
        Line.CURVE -> Magnet.curveThrough(handles)
        Line.PIECES -> handles
    }

    /** Middle of the visible part of [page], in its page coordinates (for placing new things). */
    fun visibleCenter(page: Int): FloatArray {
        val p = toPage(page, width / 2f, height / 2f)
        val h = heights.getOrElse(page) { PAGE_H }
        val w = widths.getOrElse(page) { PAGE_W }
        p[0] = p[0].coerceIn(w * 0.15f, w * 0.85f)
        p[1] = p[1].coerceIn(h * 0.1f, h * 0.9f)
        return p
    }

    private fun reselect(items: Collection<Any>) {
        selected.clear()
        selected.addAll(items)
        listener?.onSelectionChanged(selected.size)
        pageLayer.invalidate()
    }

    /** Selection bounds in page coordinates of [selectionPage], as currently dragged or resized. */
    private fun selectionBounds(): RectF? {
        val r = rawSelectionBounds() ?: return null
        r.set(
            anchorX + (r.left - anchorX) * moveKx,
            anchorY + (r.top - anchorY) * moveKy,
            anchorX + (r.right - anchorX) * moveKx,
            anchorY + (r.bottom - anchorY) * moveKy,
        )
        r.offset(moveDx, moveDy)
        val pad = SELECTION_PAD_DP * density / currentScale()
        r.inset(-pad, -pad)
        return r
    }

    /** Union of the selected elements' bounds, before any drag in progress. */
    private fun rawSelectionBounds(): RectF? {
        if (selected.isEmpty()) return null
        val doc = doc(selectionPage) ?: return null
        return boundsOf(doc.strokes.filter { it in selected }, doc.fills.filter { it in selected }, doc.images.filter { it in selected })
    }

    private fun boundsOf(strokes: List<PageStroke>, fills: List<PageFill>, images: List<PageImage>): RectF? {
        val r = RectF()
        var any = false
        fun add(b: RectF) {
            if (any) r.union(b) else r.set(b)
            any = true
        }
        for (img in images) add(PageEdits.bounds(img))
        for (s in strokes) PageEdits.bounds(s)?.let(::add)
        for (f in fills) add(f.bounds)
        return if (any) r else null
    }

    // ------------------------------------------------------------- area fill

    fun applyFill(pattern: FillPattern, color: Int, style: HatchStyle = fillStyle) {
        val outline = pendingArea ?: return
        val doc = doc(areaPage) ?: return
        // Not worked out yet (the button pressed right after tracing): worked out now, it takes a moment.
        val pieces = pendingPieces ?: carve(areaPage, outline)
        doc.edit(fills = doc.fills + pieces.map { PageFill(it, pattern, color, style = style) })
        clearPendingArea()
        changed()
    }

    fun clearPendingArea() {
        if (pendingArea == null) return
        pendingArea = null
        pendingPieces = null
        pageLayer.invalidate()
        listener?.onPendingAreaChanged(false)
    }

    private fun changed() {
        pageLayer.invalidate()
        listener?.onDocumentChanged()
    }

    // ---------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        zoomPad?.let { pad ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) padTouch = e.y >= pad.top
            if (padTouch) {
                val ev = MotionEvent.obtain(e)
                ev.offsetLocation(0f, -pad.top.toFloat())
                pad.handleTouch(ev)
                ev.recycle()
                return true
            }
        }
        if (pickingColor && mode == Mode.NONE) return pick(e)
        val index = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e, index)

            MotionEvent.ACTION_POINTER_DOWN -> when {
                // The pen lands while a finger or the palm is already down: it writes (or
                // erases…) as if it came first; the fingers' gesture is dropped.
                isPen(e, index) && (mode == Mode.GESTURE || mode == Mode.NONE || mode == Mode.SNAPPED || (mode == Mode.DRAW && drawStrokeId == null)) -> {
                    holdHandler.removeCallbacks(longPressRunnable)
                    begin(e, index)
                }
                mode == Mode.GESTURE -> {
                    holdHandler.removeCallbacks(longPressRunnable)
                    gesture.pointersChanged(e, excludeIndex = -1)
                }
                mode == Mode.RULER -> rulerPointersChanged(e, excludeIndex = -1)
                else -> Unit
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> moveDraw(e)
                Mode.ERASE -> forEachSample(e) { x, y, _ -> eraseTo(x, y) }
                Mode.PATH -> forEachSample(e) { x, y, _ -> traceTo(x, y) }
                Mode.MOVE -> {
                    // Small jitter of a tap does not nudge the selection.
                    if (!moveTravelled && hypot(e.x - downX, e.y - downY) > touchSlop) moveTravelled = true
                    if (moveTravelled) forEachSample(e) { x, y, _ -> dragTo(x, y) }
                }
                Mode.GESTURE -> {
                    if (hypot(e.x - downX, e.y - downY) > touchSlop) holdHandler.removeCallbacks(longPressRunnable)
                    gesture.move(e)
                }
                Mode.LASER -> {
                    val i = e.findPointerIndex(activePointerId)
                    if (i >= 0) {
                        val p = FloatArray(2)
                        for (k in 0..e.historySize) {
                            p[0] = if (k == e.historySize) e.getX(i) else e.getHistoricalX(i, k)
                            p[1] = if (k == e.historySize) e.getY(i) else e.getHistoricalY(i, k)
                            viewToWorld.mapPoints(p)
                            laser.add(p[0], p[1])
                        }
                    }
                }
                Mode.RULER -> moveRuler(e)
                Mode.RULER_DRAW -> forEachSample(e) { x, y, _ -> rulerDrawTo(x, y) }
                Mode.COMPASS -> forEachSample(e) { x, y, _ -> compassTo(x, y) }
                Mode.PDF_TEXT -> forEachSample(e) { x, y, _ -> pdfTextTo(x, y) }
                Mode.SPACE -> {
                    val i = e.findPointerIndex(activePointerId)
                    if (i >= 0) spaceTo(toPage(spacePage, e.getX(i), e.getY(i))[1])
                }
                Mode.NONE, Mode.SNAPPED -> Unit
            }

            MotionEvent.ACTION_POINTER_UP -> when (mode) {
                Mode.DRAW -> if (e.getPointerId(index) == activePointerId) endDraw(e, index)
                Mode.GESTURE -> gesture.pointersChanged(e, excludeIndex = index)
                Mode.RULER -> rulerPointersChanged(e, excludeIndex = index)
                else -> Unit
            }

            MotionEvent.ACTION_UP -> {
                holdHandler.removeCallbacks(longPressRunnable)
                when (mode) {
                    Mode.DRAW -> endDraw(e, index)
                    Mode.ERASE -> endErase()
                    Mode.PATH -> endTrace()
                    Mode.MOVE -> if (isSelectionTap(e)) tapOnSelection(e.x, e.y) else endMove()
                    Mode.GESTURE -> gesture.end(e)
                    Mode.LASER -> laser.end()
                    Mode.RULER -> endRulerMove()
                    Mode.RULER_DRAW -> endRulerDraw()
                    Mode.COMPASS -> endCompass()
                    Mode.PDF_TEXT -> endPdfText()
                    Mode.SPACE -> endSpace()
                    Mode.NONE, Mode.SNAPPED -> Unit
                }
                mode = Mode.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                holdHandler.removeCallbacks(holdRunnable)
                holdHandler.removeCallbacks(longPressRunnable)
                laser.end()
                drawStrokeId?.let {
                    inProgressView.cancelStroke(it, e)
                    wetStrokes.remove(it)
                }
                drawStrokeId = null
                when (mode) {
                    Mode.ERASE -> endErase()
                    Mode.PATH -> {
                        tracePoints.clear()
                        fingerLasso = false
                        pageLayer.invalidate()
                    }
                    Mode.MOVE -> {
                        moveDx = 0f
                        moveDy = 0f
                        moveKx = 1f
        moveKy = 1f
                        moveAngle = 0f
                        vertexPoints = null
                        vertexIndex = -1
                        connectors = emptyList()
                        clearSnap()
                        pageLayer.invalidate()
                    }
                    Mode.RULER -> endRulerMove()
                    Mode.RULER_DRAW -> {
                        rulerEdge = 0
                        pageLayer.invalidate()
                    }
                    Mode.SPACE -> {
                        spaceY0 = Float.NaN
                        pageLayer.invalidate()
                    }
                    Mode.COMPASS -> {
                        compassSamples.clear()
                        pageLayer.invalidate()
                    }
                    Mode.PDF_TEXT -> clearPdfText()
                    else -> Unit
                }
                mode = Mode.NONE
            }
        }
        return true
    }

    /** A first pointer (or the pen joining fingers): what it does, and that starting. */
    private fun begin(e: MotionEvent, index: Int) {
        stopFling()
        mode = classify(e, index)
        // Drawing, erasing or tracing elsewhere lets go of the selection.
        if (mode != Mode.MOVE && mode != Mode.GESTURE && mode != Mode.RULER && (tool != Tool.LASSO || mode != Mode.PATH)) clearSelection()
        when (mode) {
            Mode.DRAW -> startDraw(e, index)
            Mode.ERASE -> startErase(e, index)
            Mode.PATH -> startTrace(e, index)
            Mode.MOVE -> {
                downX = e.getX(index)
                downY = e.getY(index)
                moveDownAt = e.eventTime
                moveTravelled = false
                moveByFinger = e.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
                startMove(e, index)
            }
            Mode.GESTURE -> {
                gesture.start(e)
                activePointerId = e.getPointerId(index)
                downX = e.x
                downY = e.y
                holdHandler.removeCallbacks(longPressRunnable)
                holdHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }
            Mode.LASER -> {
                activePointerId = e.getPointerId(index)
                val p = floatArrayOf(e.getX(index), e.getY(index)).also(viewToWorld::mapPoints)
                laser.start(p[0], p[1])
                pageLayer.postInvalidateOnAnimation()
            }
            Mode.RULER -> startRulerMove(e)
            Mode.RULER_DRAW -> startRulerDraw(e, index)
            Mode.COMPASS -> startCompass(e, index)
            Mode.PDF_TEXT -> startPdfText(e, index)
            Mode.SPACE -> startSpace(e, index)
            Mode.NONE, Mode.SNAPPED -> Unit
        }
    }

    private fun isPen(e: MotionEvent, index: Int): Boolean {
        val t = e.getToolType(index)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER ||
            (t == MotionEvent.TOOL_TYPE_UNKNOWN && e.isFromSource(InputDevice.SOURCE_STYLUS))
    }

    private fun classify(e: MotionEvent, index: Int): Mode {
        val toolType = e.getToolType(index)
        // Some sources (e.g. injected `input stylus` events on Android 10) report an
        // unknown tool type; trust the stylus source bit in that case only.
        val pen = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_MOUSE ||
            (toolType == MotionEvent.TOOL_TYPE_UNKNOWN && e.isFromSource(InputDevice.SOURCE_STYLUS))
        val eraserEnd = toolType == MotionEvent.TOOL_TYPE_ERASER
        val penButton = e.buttonState and
            (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
        if (tops.isEmpty()) return Mode.GESTURE
        val world = floatArrayOf(e.getX(index), e.getY(index)).also(viewToWorld::mapPoints)
        if (compassOn && pen && !penButton) {
            activePage = pageAt(e.getX(index), e.getY(index))
            return Mode.COMPASS
        }
        if (pdfTextOn && pen && !penButton && !eraserEnd) {
            activePage = pageAt(e.getX(index), e.getY(index))
            if (source?.pdfWords(activePage) != null) return Mode.PDF_TEXT
        }
        if (insertingSpace && (pen || toolType == MotionEvent.TOOL_TYPE_FINGER)) {
            activePage = pageAt(e.getX(index), e.getY(index))
            return Mode.SPACE
        }

        // Fingers on the ruler move it.
        if (!pen && !eraserEnd && ruler.hit(world[0], world[1])) return Mode.RULER

        // Fingers move the selection in any tool, the pen only with the lasso: with a pen or
        // marker it writes, also next to a line just tapped.
        if ((pen && tool == Tool.LASSO) || toolType == MotionEvent.TOOL_TYPE_FINGER) {
            val p = toPage(selectionPage, e.getX(index), e.getY(index))
            val bounds = selectionBounds()
            if (bounds != null) {
                val grab = HANDLE_GRAB_DP * density / currentScale()
                // On a small selection the handles would cover it all: inside it, it moves.
                val raw = rawSelectionBounds()
                val inside = raw != null && raw.width() < 2 * grab && raw.height() < 2 * grab && raw.contains(p[0], p[1])
                vertexIndex = if (inside) -1 else vertexAt(p[0], p[1], grab)
                rotating = !inside && vertexIndex < 0 && hypot(p[0] - bounds.centerX(), p[1] - (bounds.top - rotateHandleOffset())) < grab
                val axis = when {
                    inside || vertexIndex >= 0 || rotating -> null
                    hypot(p[0] - bounds.right, p[1] - bounds.bottom) < grab -> Axis.BOTH
                    // Side handles from outside the shape only: on a slim one they reach over it.
                    raw != null && p[0] > raw.right && hypot(p[0] - bounds.right, p[1] - bounds.centerY()) < grab -> Axis.X
                    raw != null && p[1] > raw.bottom && hypot(p[0] - bounds.centerX(), p[1] - bounds.bottom) < grab -> Axis.Y
                    else -> null
                }
                resizing = axis != null
                resizeAxis = axis ?: Axis.BOTH
                if (vertexIndex >= 0 || rotating || resizing || bounds.contains(p[0], p[1])) {
                    activePage = selectionPage
                    return Mode.MOVE
                }
            }
        }
        activePage = pageAt(e.getX(index), e.getY(index))
        val drawing = tool == Tool.PEN || tool == Tool.FOUNTAIN || tool == Tool.HIGHLIGHTER || tool == Tool.NEON || tool == Tool.SPOILER
        if (pen && !penButton && drawing && ruler.edgeNear(world[0], world[1], RULER_REACH_DP * density / currentScale()) != 0) return Mode.RULER_DRAW
        return when {
            eraserEnd || (pen && penButton) -> Mode.ERASE
            !pen -> Mode.GESTURE
            else -> when (tool) {
                Tool.PEN, Tool.FOUNTAIN, Tool.HIGHLIGHTER, Tool.NEON, Tool.SPOILER -> Mode.DRAW
                Tool.ERASER -> Mode.ERASE
                Tool.LASSO, Tool.AREA -> Mode.PATH
                Tool.LASER -> Mode.LASER
            }
        }
    }

    /** Index of the corner of the selected shape within [grab] of the point, or -1. */
    private fun vertexAt(x: Float, y: Float, grab: Float): Int {
        val shape = selectedShape() ?: return -1
        val pts = handlePoints(shape) ?: return -1
        var best = grab
        var found = -1
        val last = if (closedPolyline(pts)) pts.size - 2 else pts.size
        for (i in 0 until last step 2) {
            val d = hypot(pts[i] - x, pts[i + 1] - y)
            if (d < best) {
                best = d
                found = i / 2
            }
        }
        return found
    }

    private fun closedPolyline(pts: FloatArray) = pts.size >= 6 && hypot(pts[0] - pts[pts.size - 2], pts[1] - pts[pts.size - 1]) < 0.5f

    /** Calls [block] with every sample (historical first) of the active pointer, in page coordinates. */
    private inline fun forEachSample(e: MotionEvent, block: (Float, Float, Float) -> Unit) {
        val index = e.findPointerIndex(activePointerId)
        if (index < 0) return
        val toPage = viewToPageMatrix(activePage)
        val p = FloatArray(2)
        for (h in 0..e.historySize) {
            val last = h == e.historySize
            p[0] = if (last) e.getX(index) else e.getHistoricalX(index, h)
            p[1] = if (last) e.getY(index) else e.getHistoricalY(index, h)
            val pressure = if (last) e.getPressure(index) else e.getHistoricalPressure(index, h)
            toPage.mapPoints(p)
            block(p[0], p[1], pressure)
        }
    }

    // --- zoom window

    /**
     * The zoom window at the bottom, while it is open. Made once and then only hidden: taking
     * the ink view out of the window while its frame is drawn crashes the render thread.
     */
    private var padView: ZoomPad? = null
    internal val zoomPad: ZoomPad? get() = padView?.takeIf { it.visibility == VISIBLE }
    private var padTouch = false
    private var zoomRegionPage = -1
    private val zoomRegion = RectF()

    val zoomPadOpen get() = zoomPad != null

    /** Opens the zoom window on the page in view, the part a little above the middle of the screen. */
    fun openZoomPad() {
        if (zoomPad != null || tops.isEmpty()) return
        val h = max(height * ZoomPad.HEIGHT_SHARE, ZoomPad.MIN_HEIGHT_DP * density).toInt()
        val pad = padView ?: ZoomPad(context, this).also {
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, h, android.view.Gravity.BOTTOM))
            it.ink.addFinishedStrokesListener(this)
            padView = it
        }
        if (pad.layoutParams.height != h) pad.layoutParams = pad.layoutParams.apply { height = h }
        pad.visibility = VISIBLE
        val page = currentPage
        val p = toPage(page, width / 2f, (height - h) / 2f)
        val top = max(p[1] - pageWidthOf(page) / ZoomPad.DEFAULT_MAGNIFICATION * 0.3f, pageHeightOf(page) * 0.1f)
        pad.post { pad.place(page, pageWidthOf(page) * ZoomPad.LEFT_MARGIN, top) }
        listener?.onZoomPadChanged(true)
    }

    fun closeZoomPad() {
        val pad = zoomPad ?: return
        pad.cancelWriting()
        pad.visibility = GONE
        padTouch = false
        zoomRegionPage = -1
        pageLayer.invalidate()
        listener?.onZoomPadChanged(false)
    }

    internal val pageCount get() = tops.size
    internal fun pageWidthOf(page: Int) = widths.getOrElse(page) { PAGE_W }
    internal fun pageHeightOf(page: Int) = heights.getOrElse(page) { PAGE_H }
    internal fun deskColor() = (background as? android.graphics.drawable.ColorDrawable)?.color ?: PaperStyle.of(dark).desk
    internal fun isPenTool(e: MotionEvent, index: Int) = isPen(e, index)
    internal fun canWriteInPad() = tool == Tool.PEN || tool == Tool.FOUNTAIN || tool == Tool.HIGHLIGHTER || tool == Tool.NEON || tool == Tool.SPOILER

    /** A line begun in the zoom window: its live brush, stored on [page] when it ends. */
    internal fun startPadStroke(ink: InProgressStrokesView, e: MotionEvent, pointerId: Int, page: Int, padToPage: Matrix): InProgressStrokeId? {
        if (doc(page) == null) return null
        clearSelection()
        val (brush, wet) = newWet(page)
        val id = ink.startStroke(e, pointerId, brush, padToPage, Matrix())
        wetStrokes[id] = wet
        return id
    }

    internal fun drawPageRegion(canvas: Canvas, page: Int, pageToCanvas: Matrix, region: RectF) = pageLayer.drawRegion(canvas, page, pageToCanvas, region)

    /** The part shown in the zoom window, framed on its page and kept in sight above the window. */
    internal fun showZoomRegion(page: Int, region: RectF, padHeight: Int) {
        zoomRegionPage = page
        zoomRegion.set(region)
        if (page in tops.indices && width > 0) {
            val r = RectF(region).also { pageMatrix(page).mapRect(it) }
            val room = (height - padHeight).toFloat()
            var dx = 0f
            var dy = 0f
            if (r.top < 0f || r.bottom > room) dy = room / 2f - r.centerY()
            if (r.left < 0f || r.right > width) dx = width / 2f - r.centerX()
            if (dx != 0f || dy != 0f) {
                stopFling()
                worldToView.postTranslate(dx, dy)
                applyViewport()
            }
        }
        pageLayer.invalidate()
    }

    // --- ink

    private fun startDraw(e: MotionEvent, index: Int) {
        if (doc(activePage) == null) return
        requestUnbufferedDispatch(e)
        predictor.record(e)
        val (brush, wet) = newWet(activePage)
        activePointerId = e.getPointerId(index)
        val id = inProgressView.startStroke(e, activePointerId, brush, viewToPageMatrix(activePage), Matrix())
        wetStrokes[id] = wet
        drawStrokeId = id
        hideCursor()

        strokeSamples.clear()
        strokePressures.clear()
        val p = toPage(activePage, e.getX(index), e.getY(index))
        strokeSamples += ShapeRecognizer.P(p[0], p[1])
        strokePressures += e.getPressure(index)
        holdAnchorX = e.getX(index)
        holdAnchorY = e.getY(index)
        holdHandler.removeCallbacks(holdRunnable)
        holdHandler.postDelayed(holdRunnable, HOLD_MS)
    }

    /** The live brush of a new line with the current tool on [page], and what to store it as. */
    private fun newWet(page: Int): Pair<Brush, WetStroke> {
        val kind = when (tool) {
            Tool.HIGHLIGHTER -> InkKind.HIGHLIGHTER
            Tool.FOUNTAIN -> InkKind.FOUNTAIN
            Tool.NEON -> InkKind.NEON
            Tool.SPOILER -> InkKind.SPOILER
            else -> penKind
        }
        val color = colorOf(kind)
        val size = sizeOf(kind)
        val shownDark = (paper.color?.let(PaperStyle::isDark) ?: dark) && source?.pdfBitmap(page) == null
        val shown = InkColors.forPaper(color, shownDark)
        // Without pressure the pen line is as wide all along as a medium press makes it.
        val even = kind == InkKind.PEN && !penPressure
        val brush = when {
            // A neon line is drawn as its tube and light core while the pen is down; the glow comes when it is stored.
            kind == InkKind.NEON -> InkBrushes.neonLive(shown, Neon.core(shown), Neon.CORE, size)
            even -> InkBrushes.create(InkKind.LINE, shown, InkBrushes.lineWidth(size))
            else -> InkBrushes.create(kind, shown, size)
        }
        return brush to WetStroke(page, kind, color, size, if (kind == InkKind.DASHED) dashStyle else null, even)
    }

    /** The pen line's width follows the pressure; off, every line is as wide as a medium press. */
    var penPressure = true

    private fun colorOf(kind: InkKind) = when (kind) {
        InkKind.PEN, InkKind.PENCIL, InkKind.DASHED, InkKind.LINE -> penColor
        InkKind.FOUNTAIN -> fountainColor
        InkKind.HIGHLIGHTER -> highlighterColor
        InkKind.NEON -> neonColor
        InkKind.SPOILER -> spoilerColor
    }

    private fun sizeOf(kind: InkKind) = when (kind) {
        InkKind.PEN, InkKind.PENCIL, InkKind.DASHED, InkKind.LINE -> penSize
        InkKind.FOUNTAIN -> fountainSize
        InkKind.HIGHLIGHTER -> highlighterSize
        InkKind.NEON -> neonSize
        InkKind.SPOILER -> spoilerSize
    }

    /**
     * Kind and size a straight line or ideal shape drawn with [kind] at [size] gets: a pen or
     * fountain pen gives an even line, as thick everywhere as the pen line looks.
     */
    private fun evenKind(kind: InkKind, size: Float, pressure: Float = 0.5f): Pair<InkKind, Float> = when (kind) {
        InkKind.PEN -> InkKind.LINE to InkBrushes.lineWidth(size, pressure)
        InkKind.FOUNTAIN -> InkKind.LINE to size * FOUNTAIN_TO_LINE
        else -> kind to size
    }

    private fun moveDraw(e: MotionEvent) {
        val id = drawStrokeId ?: return
        val index = e.findPointerIndex(activePointerId)
        if (index < 0) return
        predictor.record(e)
        val predicted = predictor.predict()
        try {
            inProgressView.addToStroke(e, activePointerId, id, predicted)
        } finally {
            predicted?.recycle()
        }
        forEachSample(e) { x, y, pressure ->
            strokeSamples += ShapeRecognizer.P(x, y)
            strokePressures += pressure
        }
        // Restart the hold timer whenever the pen really moves (not just pressure jitter).
        if (hypot(e.getX(index) - holdAnchorX, e.getY(index) - holdAnchorY) > HOLD_SLOP_DP * density) {
            holdAnchorX = e.getX(index)
            holdAnchorY = e.getY(index)
            holdHandler.removeCallbacks(holdRunnable)
            holdHandler.postDelayed(holdRunnable, HOLD_MS)
        }
    }

    private fun endDraw(e: MotionEvent, index: Int) {
        holdHandler.removeCallbacks(holdRunnable)
        val id = drawStrokeId ?: return
        drawStrokeId = null
        val canceled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            e.flags and MotionEvent.FLAG_CANCELED != 0
        if (canceled) {
            inProgressView.cancelStroke(id, e)
            wetStrokes.remove(id)
        } else {
            inProgressView.finishStroke(e, e.getPointerId(index), id)
        }
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        for ((id, stroke) in strokes) {
            val wet = wetStrokes.remove(id) ?: continue
            // Store the chosen colour so the page looks right in either theme.
            val stored = if (wet.even) {
                Stroke(InkBrushes.create(InkKind.PEN, wet.color, wet.size), PageEdits.steadyPressure(stroke.inputs))
            } else if (wet.kind == InkKind.NEON) {
                // The live brush has its light core as a second coat; the page draws that itself.
                Stroke(InkBrushes.create(InkKind.NEON, wet.color, wet.size), stroke.inputs)
            } else if (stroke.brush.colorIntArgb == wet.color && stroke.brush.size == wet.size) {
                stroke
            } else {
                stroke.copy(stroke.brush.copyWithColorIntArgb(colorIntArgb = wet.color, size = wet.size))
            }
            val doc = doc(wet.page) ?: continue
            if (scribbleErase && (wet.kind == InkKind.PEN || wet.kind == InkKind.PENCIL || wet.kind == InkKind.FOUNTAIN)) {
                val gone = runCatching { Scribble.covered(stored, doc.strokes.filter(::editable)) }.getOrNull()
                if (gone != null) {
                    val drop: MutableSet<PageStroke> = Collections.newSetFromMap(IdentityHashMap())
                    drop.addAll(gone)
                    doc.edit(strokes = doc.strokes.filterNot { it in drop })
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    continue
                }
            }
            val aligned = if ((wet.kind == InkKind.HIGHLIGHTER || wet.kind == InkKind.SPOILER) && straightHighlighter) {
                runCatching { alignHighlight(wet.page, stored, wet.color, wet.size, wet.kind) }.getOrNull()
            } else {
                null
            }
            val added = PageStroke(wet.kind, aligned ?: stored, wet.dash)
            doc.add(added)
            PageEdits.bounds(added)?.let { listener?.onInkAdded(wet.page, it) }
        }
        inProgressView.removeFinishedStrokes(strokes.keys)
        padView?.ink?.removeFinishedStrokes(strokes.keys)
        pageLayer.invalidate()
        zoomPad?.redraw()
        listener?.onDocumentChanged()
    }

    /**
     * A highlighter pass made level and fitted behind the text under it (handwriting, a photo
     * or a PDF page); null when the stroke is not a pass along a line.
     */
    internal fun alignHighlight(page: Int, stroke: Stroke, color: Int, size: Float, kind: InkKind = InkKind.HIGHLIGHTER): Stroke? {
        val src = source ?: return null
        val doc = doc(page) ?: return null
        val inputs = stroke.inputs
        val n = inputs.size
        if (n < 2) return null
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val p = StrokeInput()
        for (i in 0 until n) {
            inputs.populate(i, p)
            xs[i] = p.x
            ys[i] = p.y
        }
        // Over PDF text: along the line of words the pass goes over.
        src.pdfWords(page)?.let { words -> dev.inknotes.data.PdfWords.snapPass(words, xs, ys) }?.let { b ->
            val path = listOf(ShapeRecognizer.P(b[0], b[1]), ShapeRecognizer.P(b[2], b[1]))
            val thickness = if (kind == InkKind.SPOILER) b[3] * SPOILER_COVER else b[3]
            return Stroke(InkBrushes.create(kind, color, thickness), shapeInputs(path, 0.5f))
        }
        val identity = Matrix()
        val band = HighlightAligner.align(xs, ys, size) { canvas, strip ->
            pageRenderer.pageHeight = heights.getOrElse(page) { PAGE_H }
            pageRenderer.pageWidth = widths.getOrElse(page) { PAGE_W }
            pageRenderer.pdf = src.pdfBitmap(page)
            // Dark letters are looked for: a PDF shown dark has light ones.
            pageRenderer.pdfDark = false
            pageRenderer.drawBackdrop(canvas)
            pageRenderer.pdfDark = pdfDark
            for (img in doc.images) pageRenderer.drawImage(canvas, img, src.image(img.file))
            // Ink of any colour counts as dark letters.
            canvas.saveLayer(null, inkMaskPaint)
            for (s in doc.strokes) {
                if (s.kind.isInk && PageCache.strokeBounds(s)?.let { RectF.intersects(it, strip) } == true) pageRenderer.ink.draw(canvas, s.stroke, identity)
            }
            canvas.restore()
        } ?: return null
        val pressure = 0.5f
        val path = listOf(ShapeRecognizer.P(band.x0, band.y), ShapeRecognizer.P(band.x1, band.y))
        // A hint band hides the letters whole: a little taller than the text.
        val thickness = if (kind == InkKind.SPOILER) band.thickness * SPOILER_COVER else band.thickness
        return Stroke(InkBrushes.create(kind, color, thickness), shapeInputs(path, pressure))
    }

    private val inkMaskPaint = Paint().apply { colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN) }

    /** Pen held still: replace the wet stroke with the recognised ideal shape. */
    private fun snapToShape() {
        val id = drawStrokeId ?: return
        if (mode != Mode.DRAW) return
        // A shape with a point that is not a number would be refused by the ink library: none then.
        val shape = runCatching { ShapeRecognizer.recognize(strokeSamples) }.getOrNull()
            ?.takeIf { sh -> sh.paths.all { path -> path.all { it.x.isFinite() && it.y.isFinite() } } } ?: return
        val wet = wetStrokes.remove(id) ?: return
        inProgressView.cancelStroke(id)
        drawStrokeId = null
        mode = Mode.SNAPPED

        val pressure = strokePressures.filter { it > 0f }.sorted().let { if (it.isEmpty()) 0.5f else it[it.size / 2] }
        // As thick as the pen is set, however hard it was pressed.
        val (kind, size) = evenKind(wet.kind, wet.size)
        val brush = InkBrushes.create(kind, wet.color, size)
        val doc = doc(wet.page)
        var paths = ShapeRecognizer.level(shape).paths
        // Ends of lines stick to the corners and lines already there.
        val open = shape.kind == ShapeRecognizer.Kind.LINE || shape.kind == ShapeRecognizer.Kind.ARROW || shape.kind == ShapeRecognizer.Kind.POLYLINE
        if (doc != null && open) {
            val targets = Magnet.targets(doc.strokes)
            val reach = MAGNET_DP * density / currentScale()
            val line = paths[0].toMutableList()
            for (end in listOf(0, line.lastIndex)) {
                targets.snap(line[end].x, line[end].y, reach)?.let { line[end] = ShapeRecognizer.P(it.x, it.y) }
            }
            paths = listOf(line) + paths.drop(1)
        }
        val shapeFills = ArrayList<PageFill>()
        if (shape.kind == ShapeRecognizer.Kind.ARROW && kind == InkKind.LINE) {
            // A solid head, the shaft ending inside it.
            val tail = paths[0].first()
            val tip = paths[0].last()
            val len = paths[1].maxOf { ShapeRecognizer.dist(it, tip) }
            val head = arrowHead(tail.x, tail.y, tip.x, tip.y, len)
            shapeFills += PageFill(head, FillPattern.INK, wet.color)
            val d = ShapeRecognizer.dist(tail, tip)
            val k = ((d - len * 0.6f) / d).coerceAtLeast(0f)
            paths = listOf(listOf(tail, ShapeRecognizer.P(tail.x + (tip.x - tail.x) * k, tail.y + (tip.y - tail.y) * k)))
        }
        val shapeStrokes = paths.map { path -> PageStroke(kind, Stroke(brush, shapeInputs(path, pressure)), if (kind == InkKind.DASHED) wet.dash else null) }
        doc?.let { it.edit(strokes = it.strokes + shapeStrokes, fills = it.fills + shapeFills) }
        shapeStrokes.mapNotNull { PageEdits.bounds(it) }.reduceOrNull { a, b -> RectF(a).apply { union(b) } }?.let { listener?.onInkAdded(wet.page, it) }
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        changed()
    }

    /** Even, slim solid arrowhead with its tip at ([x1], [y1]), [len] long. */
    private fun arrowHead(x0: Float, y0: Float, x1: Float, y1: Float, len: Float): FloatArray {
        val a = atan2(y1 - y0, x1 - x0)
        val bx = x1 - len * kotlin.math.cos(a)
        val by = y1 - len * kotlin.math.sin(a)
        val hw = len * ARROW_HALF_WIDTH
        return floatArrayOf(x1, y1, bx - hw * kotlin.math.sin(a), by + hw * kotlin.math.cos(a), bx + hw * kotlin.math.sin(a), by - hw * kotlin.math.cos(a))
    }

    /** Evenly spaced pen inputs along [path] with constant pressure, so the ink is uniform. */
    private fun shapeInputs(path: List<ShapeRecognizer.P>, pressure: Float): MutableStrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val input = StrokeInput()
        var t = 0L
        fun add(x: Float, y: Float) {
            input.update(
                x = x,
                y = y,
                elapsedTimeMillis = t,
                toolType = InputToolType.STYLUS,
                strokeUnitLengthCm = StrokeInput.NO_STROKE_UNIT_LENGTH,
                pressure = pressure,
                tiltRadians = StrokeInput.NO_TILT,
                orientationRadians = StrokeInput.NO_ORIENTATION,
            )
            batch.add(input)
            t += 4
        }
        add(path[0].x, path[0].y)
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val n = ceil(ShapeRecognizer.dist(a, b) / SHAPE_STEP).toInt().coerceAtLeast(1)
            for (k in 1..n) add(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n)
        }
        return batch
    }

    // --- eraser

    private fun startErase(e: MotionEvent, index: Int) {
        val doc = doc(activePage) ?: return
        doc.beginSession()
        activePointerId = e.getPointerId(index)
        val p = toPage(activePage, e.getX(index), e.getY(index))
        lastX = p[0]
        lastY = p[1]
        eraseSegment(lastX, lastY, lastX, lastY)
        showCursor(activePage, lastX, lastY)
    }

    private fun eraseTo(x: Float, y: Float) {
        if (hypot(x - lastX, y - lastY) < 0.5f) return
        eraseSegment(lastX, lastY, x, y)
        lastX = x
        lastY = y
        showCursor(activePage, x, y)
    }

    private fun eraseSegment(ax: Float, ay: Float, bx: Float, by: Float) {
        val doc = doc(activePage) ?: return
        val r = eraserRadius()
        var changed = false
        val strokes: List<PageStroke>
        val fills: List<PageFill>
        // Far strokes are ruled out by their remembered bounds: a full page has thousands.
        val target = eraserTarget
        fun near(s: PageStroke): Boolean {
            if (!target.strokes(s.kind) || !editable(s)) return false
            val b = PageCache.strokeBounds(s) ?: return false
            return PageEdits.segmentNear(b, ax, ay, bx, by, r + s.stroke.brush.size)
        }
        if (eraserMode == EraserMode.WHOLE) {
            strokes = doc.strokes.filterNot { near(it) && PageEdits.strokeTouched(it, ax, ay, bx, by, r) }
            fills = if (target.fills) doc.fills.filterNot { editable(it) && PageEdits.fillTouched(it, bx, by, r) } else doc.fills
            changed = strokes.size != doc.strokes.size || fills.size != doc.fills.size
        } else {
            val kept = ArrayList<PageStroke>(doc.strokes.size)
            for (s in doc.strokes) {
                val pieces = if (near(s)) PageEdits.splitStroke(s, ax, ay, bx, by, r) else null
                if (pieces == null) {
                    kept += s
                } else {
                    kept += pieces
                    changed = true
                }
            }
            strokes = kept
            fills = doc.fills.map { f ->
                if (target.fills && editable(f) && PageEdits.segmentNear(f.bounds, ax, ay, bx, by, r)) {
                    changed = true
                    PageEdits.cutFill(f, ax, ay, bx, by, r)
                } else {
                    f
                }
            }
        }
        if (changed) {
            doc.set(strokes, fills)
            pageLayer.invalidate()
        }
    }

    private fun endErase() {
        doc(activePage)?.commitSession()
        hideCursor()
        listener?.onDocumentChanged()
    }

    /** Eraser keeps a constant on-screen size regardless of zoom. */
    private fun eraserRadius() = eraserRadiusDp * density / currentScale()

    // --- lasso / area outline

    private fun startTrace(e: MotionEvent, index: Int) {
        activePointerId = e.getPointerId(index)
        fingerLasso = false
        if (tool == Tool.LASSO) clearSelection() else clearPendingArea()
        tracePoints.clear()
        val p = toPage(activePage, e.getX(index), e.getY(index))
        tracePoints += p[0]
        tracePoints += p[1]
        hideCursor()
        pageLayer.invalidate()
    }

    private fun traceTo(x: Float, y: Float) {
        tracePoints += x
        tracePoints += y
        pageLayer.invalidate()
    }

    /** The finger stayed still: from here it draws a lasso. */
    private fun startFingerLasso() {
        if (mode != Mode.GESTURE || tops.isEmpty()) return
        mode = Mode.PATH
        fingerLasso = true
        activePage = pageAt(downX, downY)
        clearSelection()
        tracePoints.clear()
        val p = toPage(activePage, downX, downY)
        tracePoints += p[0]
        tracePoints += p[1]
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        pageLayer.invalidate()
    }

    private fun endTrace() {
        val raw = tracePoints.toFloatArray()
        val points = PageEdits.simplify(raw, 1.5f)
        tracePoints.clear()
        pageLayer.invalidate()
        val lasso = tool == Tool.LASSO || fingerLasso
        fingerLasso = false
        // Barely moved: a tap. It picks the object (lasso) or fills the shape (hatch tool).
        val tap = raw.size >= 2 && run {
            var far = 0f
            for (i in raw.indices step 2) far = max(far, hypot(raw[i] - raw[0], raw[i + 1] - raw[1]))
            far < TAP_TRACE_DP * density / currentScale()
        }
        if (tap) {
            if (lasso) selectAt(activePage, raw[0], raw[1]) else autoArea(activePage, raw[0], raw[1])
            return
        }
        if (points.size < 6) return
        if (lasso) {
            select(points)
        } else {
            pendingArea = points
            pendingPieces = null
            areaPage = activePage
            listener?.onPendingAreaChanged(true)
            carvePendingArea()
        }
    }

    /** [outline] on [page] less the closed shapes inside it; the outline itself if nothing is left. */
    private fun carve(page: Int, outline: FloatArray): List<FloatArray> =
        carve(doc(page)?.strokes.orEmpty(), widths.getOrElse(page) { PAGE_W }, heights.getOrElse(page) { PAGE_H }, outline)

    private fun carve(strokes: List<PageStroke>, w: Float, h: Float, outline: FloatArray): List<FloatArray> =
        runCatching { AreaFinder.carve(strokes, w, h, outline) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(outline)

    /** Works out [pendingPieces] on the area worker, then shows them in the preview. */
    private fun carvePendingArea() {
        val outline = pendingArea ?: return
        val page = areaPage
        val doc = doc(page) ?: return
        val strokes = doc.strokes
        val w = widths.getOrElse(page) { PAGE_W }
        val h = heights.getOrElse(page) { PAGE_H }
        areaExec.execute {
            val pieces = carve(strokes, w, h, outline)
            holdHandler.post {
                // Traced again or let go meanwhile.
                if (pendingArea !== outline) return@post
                pendingPieces = pieces
                pageLayer.invalidate()
            }
        }
    }

    private fun select(lasso: FloatArray) {
        val doc = doc(activePage) ?: return
        val bounds = PageFill(lasso, FillPattern.SOLID, 0).bounds
        val items = ArrayList<Any>()
        doc.strokes.filterTo(items) { s -> editable(s) && PageCache.strokeBounds(s)?.let { RectF.intersects(it, bounds) } == true && PageEdits.strokeInLasso(s, lasso, bounds) }
        doc.fills.filterTo(items) { editable(it) && PageEdits.fillInLasso(it, lasso) }
        doc.images.filterTo(items) { editable(it) && PageEdits.imageInLasso(it, lasso) }
        selectionPage = activePage
        reselect(items)
    }

    /**
     * A finger tap: opens or closes a hint band, shows how an answer was found, fills the shape
     * (hatch tool) or selects the object under it; on empty paper it lets go of the selection.
     */
    private fun fingerTap(viewX: Float, viewY: Float) {
        if (tops.isEmpty()) return
        val page = pageAt(viewX, viewY)
        if (page !in tops.indices) return
        val p = toPage(page, viewX, viewY)
        val slop = TAP_SLOP_DP * density / currentScale()
        val doc = doc(page) ?: return
        doc.strokes.lastOrNull { it.kind == InkKind.SPOILER && PageEdits.strokeTouched(it, p[0], p[1], p[0], p[1], slop) }?.let { band ->
            if (!revealed.remove(band)) revealed += band
            pageLayer.invalidate()
            return
        }
        pageLayer.answerAt(page, p[0], p[1], 8 * density / currentScale())?.let {
            listener?.onAnswerTapped(it)
            return
        }
        if (source?.followLink(page, p[0], p[1]) == true) return
        if (listener?.listenAt(page, p[0], p[1]) == true) return
        if (tool == Tool.AREA) autoArea(page, p[0], p[1]) else selectAt(page, p[0], p[1])
    }

    /**
     * Selects the object at the point: just that one line, fill or picture. Tapping it again
     * takes in everything joined to it (the whole table or stamp), see [tapOnSelection].
     */
    private fun selectAt(page: Int, x: Float, y: Float) {
        val doc = doc(page) ?: return
        val hit = hitAt(doc, x, y)
        if (hit == null) {
            clearSelection()
            return
        }
        selectionPage = page
        // The whole figure first (a stamp, a table, a drawing); a second tap picks a part of it.
        reselect(Objects.joined(doc, hit, ::editable))
    }

    /** The topmost stroke, fill or picture at the point (page coordinates), or null. */
    private fun hitAt(doc: PageDocument, x: Float, y: Float): Any? {
        val slop = TAP_SLOP_DP * density / currentScale()
        return doc.strokes.lastOrNull { s ->
            editable(s) && PageCache.strokeBounds(s)?.let { b -> x >= b.left - slop && x <= b.right + slop && y >= b.top - slop && y <= b.bottom + slop } == true &&
                PageEdits.strokeTouched(s, x, y, x, y, slop)
        } ?: doc.fills.lastOrNull { editable(it) && PageEdits.fillTouched(it, x, y, slop) }
            ?: doc.images.lastOrNull { editable(it) && PageEdits.bounds(it).contains(x, y) }
    }

    /** A quick touch inside the selection that did not move it (a finger, or the pen with the lasso). */
    private fun isSelectionTap(e: MotionEvent): Boolean =
        !moveTravelled && !rotating && e.eventTime - moveDownAt < TAP_TIMEOUT_MS * 2 && (moveByFinger || tool == Tool.LASSO)

    /**
     * Tap inside the selection: on another object selects that one instead; on the one
     * selected line grows the selection to everything joined to it; on a line of a larger
     * selection narrows it back to that line.
     */
    private fun tapOnSelection(viewX: Float, viewY: Float) {
        // A tap on a corner handle or the size handle changes nothing either.
        vertexIndex = -1
        vertexPoints = null
        resizing = false
        moveDx = 0f
        moveDy = 0f
        moveKx = 1f
        moveKy = 1f
        moveAngle = 0f
        moveTargets = null
        clearSnap()
        val page = selectionPage
        val doc = doc(page)
        val p = toPage(page, viewX, viewY)
        val hit = doc?.let { hitAt(it, p[0], p[1]) }
        when {
            doc == null || hit == null -> pageLayer.invalidate()
            hit !in selected -> reselect(Objects.joined(doc, hit, ::editable))
            selected.size > 1 -> reselect(listOf(hit))
            else -> {
                val group = Objects.joined(doc, hit, ::editable)
                if (group.size > 1) reselect(group) else pageLayer.invalidate()
            }
        }
    }

    /**
     * Hatch tool tap: the closed shape around the point is filled with the current pattern.
     * The shape is found on a worker thread (a full page takes a moment) from the strokes as
     * they are now.
     */
    private fun autoArea(page: Int, x: Float, y: Float) {
        val doc = doc(page) ?: return
        val strokes = doc.strokes
        val w = widths.getOrElse(page) { PAGE_W }
        val h = heights.getOrElse(page) { PAGE_H }
        val pattern = fillPattern
        val color = fillColor
        val style = fillStyle
        areaExec.execute {
            val outline = runCatching { AreaFinder.find(strokes, w, h, x, y) }.getOrNull()
            holdHandler.post {
                // The page may have gone (deleted, the notebook closed) while the shape was looked for.
                if (doc(page) !== doc || source == null) return@post
                if (outline == null) {
                    listener?.onHint("Контур не замкнут: коснитесь внутри замкнутой фигуры или обведите область")
                    return@post
                }
                clearPendingArea()
                doc.edit(fills = doc.fills + PageFill(outline, pattern, color, style = style))
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                changed()
            }
        }
    }

    // One worker, ending when idle: nothing to shut down when the editor goes away.
    private val areaExec = java.util.concurrent.ThreadPoolExecutor(0, 1, 10, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue())

    // --- inserting space

    /**
     * One-shot mode: the next stroke down the page (pen or finger) opens that much room at the
     * place it began, moving everything below down; a stroke up closes empty room. What no
     * longer fits goes to a new page after this one.
     */
    var insertingSpace = false
        set(value) {
            field = value
            if (!value) spaceY0 = Float.NaN
            pageLayer.invalidate()
        }
    private var spacePage = 0
    private var spaceY0 = Float.NaN
    private var spaceDy = 0f

    private fun startSpace(e: MotionEvent, index: Int) {
        activePointerId = e.getPointerId(index)
        spacePage = activePage
        spaceY0 = toPage(spacePage, e.getX(index), e.getY(index))[1].coerceIn(0f, source?.pageHeight(spacePage) ?: 0f)
        spaceDy = 0f
        pageLayer.invalidate()
    }

    private fun spaceTo(y: Float) {
        val doc = doc(spacePage) ?: return
        var dy = y - spaceY0
        // Closing room: no further than the nearest writing below the line reaching what is above.
        if (dy < 0f) dy = max(dy, -closableSpace(doc, spaceY0))
        spaceDy = dy
        pageLayer.invalidate()
    }

    /** Elements whose middle is below [y0]: they move. */
    private fun below(doc: PageDocument, y0: Float): Triple<List<PageStroke>, List<PageFill>, List<PageImage>> = Triple(
        doc.strokes.filter { (PageCache.strokeBounds(it)?.centerY() ?: -1f) >= y0 },
        doc.fills.filter { it.bounds.centerY() >= y0 },
        doc.images.filter { PageEdits.bounds(it).centerY() >= y0 },
    )

    /** Free paper between [y0] and the first element below it, and above it the last one reaching down. */
    private fun closableSpace(doc: PageDocument, y0: Float): Float {
        val (s, f, i) = below(doc, y0)
        val moving: Set<Any> = Collections.newSetFromMap<Any>(IdentityHashMap()).apply { addAll(s); addAll(f); addAll(i) }
        val topBelow = (s.mapNotNull { PageCache.strokeBounds(it)?.top } + f.map { it.bounds.top } + i.map { PageEdits.bounds(it).top }).minOrNull() ?: return 0f
        val bottomAbove = (
            doc.strokes.filter { it !in moving }.mapNotNull { PageCache.strokeBounds(it)?.bottom } +
                doc.fills.filter { it !in moving }.map { it.bounds.bottom } + doc.images.filter { it !in moving }.map { PageEdits.bounds(it).bottom }
            ).filter { it <= topBelow }.maxOrNull() ?: 0f
        return max(0f, topBelow - max(bottomAbove, 0f) - SPACE_GAP)
    }

    private fun endSpace() {
        val page = spacePage
        val y0 = spaceY0
        val dy = spaceDy
        spaceY0 = Float.NaN
        insertingSpace = false
        listener?.onSpaceDone()
        val doc = doc(page)
        if (doc == null || y0.isNaN() || abs(dy) < 1f) {
            pageLayer.invalidate()
            return
        }
        val (s, f, i) = below(doc, y0)
        val t = PageEdits.Transform.translation(0f, dy)
        val height = source?.pageHeight(page) ?: PAGE_H
        val bottomOf = { x: Any -> when (x) { is PageStroke -> PageCache.strokeBounds(x)?.bottom ?: 0f; is PageFill -> x.bounds.bottom; else -> PageEdits.bounds(x as PageImage).bottom } }
        val over: Set<Any> = Collections.newSetFromMap<Any>(IdentityHashMap()).apply {
            if (dy > 0f) for (x in s + f + i) if (bottomOf(x) + dy > height - SPACE_GAP) add(x)
        }
        val moved = Collections.newSetFromMap<Any>(IdentityHashMap()).apply { addAll(s); addAll(f); addAll(i) }
        val edit = {
            if (over.isNotEmpty()) {
                // What no longer fits keeps its layout at the top of a new page.
                val next = listener?.addPageAfter(page)
                val dst = next?.let(::doc)
                if (next != null && dst != null) {
                    val topOf = { x: Any -> when (x) { is PageStroke -> PageCache.strokeBounds(x)?.top ?: 0f; is PageFill -> x.bounds.top; else -> PageEdits.bounds(x as PageImage).top } }
                    val top = over.minOf(topOf)
                    val shift = PageEdits.Transform.translation(0f, SPACE_TOP - top)
                    dst.edit(
                        dst.strokes + s.filter { it in over }.map { PageEdits.transform(it, shift) },
                        dst.fills + f.filter { it in over }.map { PageEdits.transform(it, shift) },
                        dst.images + i.filter { it in over }.map { PageEdits.transform(it, shift) },
                    )
                }
            }
            doc.edit(
                doc.strokes.filter { it !in over }.map { if (it in moved) PageEdits.transform(it, t) else it },
                doc.fills.filter { it !in over }.map { if (it in moved) PageEdits.transform(it, t) else it },
                doc.images.filter { it !in over }.map { if (it in moved) PageEdits.transform(it, t) else it },
            )
        }
        listener?.editsTogether(edit) ?: edit()
        changed()
    }

    // --- lines and arrows that follow what they connect

    /**
     * A straight line or arrow not selected with one or both ends on the selection: the ends
     * on it move with it ([moveStart], [moveEnd]); [startHead], [endHead] are arrowheads there.
     */
    private class Connector(
        val line: PageStroke,
        val pts: FloatArray,
        val moveStart: Boolean,
        val moveEnd: Boolean,
        val startHead: PageFill?,
        val endHead: PageFill?,
    )

    private var connectors: List<Connector> = emptyList()
    private val connectorSet: Set<Any> get() = connectors.flatMapTo(Collections.newSetFromMap(IdentityHashMap())) { listOfNotNull(it.line, it.startHead, it.endHead) }

    private fun findConnectors(doc: PageDocument): List<Connector> {
        val tol = CONNECT_DP * density / currentScale()
        val targets = Magnet.targets(doc.strokes.filter { it in selected })
        val boxes = doc.images.filter { it in selected }.map { PageEdits.bounds(it) } +
            doc.fills.filter { it in selected && it.pattern != FillPattern.INK }.map { RectF(it.bounds) }
        fun touches(x: Float, y: Float) = targets.snap(x, y, tol) != null ||
            boxes.any { x in it.left - tol..it.right + tol && y in it.top - tol..it.bottom + tol }
        val heads = doc.fills.filter { it !in selected && it.pattern == FillPattern.INK && it.outline.size == 6 && it.cuts.isEmpty() }
        val out = ArrayList<Connector>()
        for (st in doc.strokes) {
            if (st in selected || (st.kind != InkKind.LINE && st.kind != InkKind.DASHED)) continue
            val pl = Magnet.polyline(st) ?: continue
            if (pl.size < 4) continue
            val n = pl.size
            fun headAt(x: Float, y: Float) = heads.firstOrNull { PageEdits.inside(x, y, it.outline) }
            val sh = headAt(pl[0], pl[1])
            val eh = headAt(pl[n - 2], pl[n - 1])
            val ms = touches(pl[0], pl[1]) || (sh != null && touches(sh.outline[0], sh.outline[1]))
            val me = touches(pl[n - 2], pl[n - 1]) || (eh != null && touches(eh.outline[0], eh.outline[1]))
            if (ms || me) out += Connector(st, pl, ms, me, sh, eh)
            if (out.size >= MAX_CONNECTORS) break
        }
        return out
    }

    /** The connector's line and heads with its attached ends moved by ([dx], [dy]). */
    private fun follow(c: Connector, dx: Float, dy: Float): Pair<FloatArray, List<PageFill>> {
        val p = c.pts.copyOf()
        val n = p.size
        if (c.moveStart) { p[0] += dx; p[1] += dy }
        if (c.moveEnd) { p[n - 2] += dx; p[n - 1] += dy }
        // Both ends on the selection: the whole line moves, bends included.
        if (c.moveStart && c.moveEnd) for (k in 2 until n - 2 step 2) { p[k] += dx; p[k + 1] += dy }
        val heads = ArrayList<PageFill>()
        fun head(h: PageFill?, ex: Float, ey: Float, px: Float, py: Float, oldEx: Float, oldEy: Float) {
            h ?: return
            val o = h.outline
            val len = hypot(o[0] - (o[2] + o[4]) / 2, o[1] - (o[3] + o[5]) / 2)
            val reach = hypot(o[0] - oldEx, o[1] - oldEy)
            val d = hypot(ex - px, ey - py).coerceAtLeast(1e-3f)
            val tx = ex + (ex - px) / d * reach
            val ty = ey + (ey - py) / d * reach
            heads += h.copy(outline = arrowHead(px, py, tx, ty, len))
        }
        head(c.startHead, p[0], p[1], p[2], p[3], c.pts[0], c.pts[1])
        head(c.endHead, p[n - 2], p[n - 1], p[n - 4], p[n - 3], c.pts[n - 2], c.pts[n - 1])
        return p to heads
    }

    // --- dragging the selection

    private fun startMove(e: MotionEvent, index: Int) {
        activePointerId = e.getPointerId(index)
        val p = toPage(activePage, e.getX(index), e.getY(index))
        moveStartX = p[0]
        moveStartY = p[1]
        moveDx = 0f
        moveDy = 0f
        moveKx = 1f
        moveKy = 1f
        moveAngle = 0f
        val raw = rawSelectionBounds() ?: RectF()
        anchorX = raw.left
        anchorY = raw.top
        cornerX = raw.right
        cornerY = raw.bottom
        turnX = raw.centerX()
        turnY = raw.centerY()
        clearSnap()
        connectors = emptyList()
        val doc = doc(selectionPage)
        moveTargets = null
        moveAnchors = FloatArray(0)
        if (doc == null) return
        if (vertexIndex >= 0) {
            val shape = selectedShape()
            val pts = shape?.let(::handlePoints)
            if (pts == null) {
                vertexIndex = -1
            } else {
                vertexLine = when {
                    Magnet.arc(shape) != null -> Line.ARC
                    Magnet.curve(shape) != null -> Line.CURVE
                    else -> Line.PIECES
                }
                vertexStart = pts.copyOf()
                vertexClosed = closedPolyline(pts)
                moveTargets = Magnet.targets(doc.strokes) { it === shape }
            }
            return
        }
        if (rotating || resizing) return
        connectors = findConnectors(doc)
        moveBounds = RectF(raw)
        guideBoxes = guideTargets(doc)
        // What the selection can stick to, and its own points that stick.
        val anchors = ArrayList<Float>()
        for (s in doc.strokes) if (s in selected) Magnet.points(s)?.let { for (v in it) anchors += v }
        for (f in doc.fills) if (f in selected) Magnet.points(f)?.let { for (v in it) anchors += v }
        if (anchors.isEmpty()) return
        val stride = max(1, anchors.size / 2 / MAX_ANCHORS)
        moveAnchors = FloatArray((anchors.size / 2 + stride - 1) / stride * 2).also { out ->
            var k = 0
            for (i in 0 until anchors.size / 2 step stride) {
                out[k++] = anchors[i * 2]
                out[k++] = anchors[i * 2 + 1]
            }
        }
        moveTargets = Magnet.targets(doc.strokes) { it in selected }.takeIf { !it.isEmpty }
    }

    private fun clearSnap() {
        snapX = Float.NaN
        snapY = Float.NaN
        guideLines = FloatArray(0)
    }

    /** Selection bounds when the drag began, and what its edges and middle may line up with. */
    private var moveBounds = RectF()
    private var guideBoxes: List<RectF> = emptyList()

    /** Guides shown while dragging: x0, y0, x1, y1 of each line, in page units. */
    private var guideLines = FloatArray(0)

    /**
     * Boxes of what a moved selection lines up with: pictures, hatching and even lines (shapes,
     * stamps, tables) that are not selected, and the page itself (its edges and middle).
     */
    private fun guideTargets(doc: PageDocument): List<RectF> {
        val out = ArrayList<RectF>()
        out += RectF(0f, 0f, source?.pageWidth(selectionPage) ?: PAGE_W, source?.pageHeight(selectionPage) ?: PAGE_W)
        for (img in doc.images) if (img !in selected) out += PageEdits.bounds(img)
        for (f in doc.fills) if (f !in selected && f.pattern != FillPattern.INK) out += RectF(f.bounds)
        for (st in doc.strokes) {
            if (st in selected || st.kind != InkKind.LINE) continue
            PageCache.strokeBounds(st)?.let { b -> if (b.width() > 4f || b.height() > 4f) out += RectF(b) }
            if (out.size > MAX_GUIDE_TARGETS) break
        }
        return out
    }

    /**
     * Lines the dragged selection up with another object: its left edge, middle or right edge
     * with one of theirs, likewise top, middle and bottom. Returns the shift that does it and
     * records the guides to draw.
     */
    private fun guideMove(dx: Float, dy: Float, reach: Float): FloatArray {
        val b = moveBounds
        if (b.isEmpty && b.width() <= 0f && b.height() <= 0f) return floatArrayOf(0f, 0f)
        val mine = floatArrayOf(b.left + dx, b.centerX() + dx, b.right + dx)
        val mineY = floatArrayOf(b.top + dy, b.centerY() + dy, b.bottom + dy)
        var bestX = reach
        var shiftX = 0f
        var lineX: RectF? = null
        var atX = 0f
        var bestY = reach
        var shiftY = 0f
        var lineY: RectF? = null
        var atY = 0f
        for (t in guideBoxes) {
            val theirs = floatArrayOf(t.left, t.centerX(), t.right)
            val theirsY = floatArrayOf(t.top, t.centerY(), t.bottom)
            for (m in mine) for (v in theirs) {
                val d = abs(v - m)
                if (d < bestX) { bestX = d; shiftX = v - m; lineX = t; atX = v }
            }
            for (m in mineY) for (v in theirsY) {
                val d = abs(v - m)
                if (d < bestY) { bestY = d; shiftY = v - m; lineY = t; atY = v }
            }
        }
        val lines = ArrayList<Float>()
        val top = b.top + dy + shiftY
        val bottom = b.bottom + dy + shiftY
        val left = b.left + dx + shiftX
        val right = b.right + dx + shiftX
        lineX?.let { t -> lines += listOf(atX, min(t.top, top), atX, max(t.bottom, bottom)) }
        lineY?.let { t -> lines += listOf(min(t.left, left), atY, max(t.right, right), atY) }
        guideLines = lines.toFloatArray()
        return floatArrayOf(shiftX, shiftY)
    }

    /** Distance of the rotate handle above the selection frame, in page units. */
    private fun rotateHandleOffset() = ROTATE_HANDLE_DP * density / currentScale()

    private fun dragTo(x: Float, y: Float) {
        if (rotating) {
            // Angle swept around the centre since the pen went down; sticks to multiples of 15°.
            val a = Math.toDegrees(
                (atan2(y - turnY, x - turnX) - atan2(moveStartY - turnY, moveStartX - turnX)).toDouble(),
            ).toFloat()
            var angle = ((a % 360f) + 540f) % 360f - 180f
            val nearest = Math.round(angle / SNAP_DEGREES) * SNAP_DEGREES
            if (abs(angle - nearest) < SNAP_TOLERANCE) angle = nearest
            moveAngle = angle
        } else if (resizing) {
            // Around the top-left corner: in proportion by the corner, one way by a side.
            val w = cornerX - anchorX
            val h = cornerY - anchorY
            when (resizeAxis) {
                Axis.BOTH -> {
                    val span = w + h
                    if (span > 0f) moveKx = ((cornerX + x - moveStartX - anchorX) + (cornerY + y - moveStartY - anchorY)) / span
                    moveKx = moveKx.coerceIn(MIN_RESIZE, MAX_RESIZE)
                    moveKy = moveKx
                }
                Axis.X -> if (w > 0f) moveKx = ((w + x - moveStartX) / w).coerceIn(MIN_RESIZE, MAX_RESIZE)
                Axis.Y -> if (h > 0f) moveKy = ((h + y - moveStartY) / h).coerceIn(MIN_RESIZE, MAX_RESIZE)
            }
        } else if (vertexIndex >= 0) {
            val pts = vertexStart.copyOf()
            var vx = pts[vertexIndex * 2] + x - moveStartX
            var vy = pts[vertexIndex * 2 + 1] + y - moveStartY
            clearSnap()
            moveTargets?.snap(vx, vy, MAGNET_DP * density / currentScale())?.let {
                vx = it.x
                vy = it.y
                snapX = it.x
                snapY = it.y
                snapPage = selectionPage
            }
            // Straight pieces stick a little to multiples of 15° from the next corner.
            if (snapX.isNaN() && vertexLine == Line.PIECES) level(pts, vertexIndex, vx, vy)?.let {
                vx = it[0]
                vy = it[1]
            }
            pts[vertexIndex * 2] = vx
            pts[vertexIndex * 2 + 1] = vy
            // A closed shape's first corner is also its last point.
            if (vertexClosed && vertexIndex == 0) {
                pts[pts.size - 2] = vx
                pts[pts.size - 1] = vy
            }
            vertexPoints = pts
        } else {
            moveDx = x - moveStartX
            moveDy = y - moveStartY
            magnetMove()
        }
        pageLayer.invalidate()
    }

    /** Corner [i] of [pts] at ([x], [y]), turned onto a multiple of 15° from a neighbour when near one. */
    private fun level(pts: FloatArray, i: Int, x: Float, y: Float): FloatArray? {
        val n = if (vertexClosed) pts.size / 2 - 1 else pts.size / 2
        val neighbours = listOfNotNull(
            if (i > 0) i - 1 else if (vertexClosed) n - 1 else null,
            if (i < n - 1) i + 1 else if (vertexClosed) 0 else null,
        )
        var best: FloatArray? = null
        var moved = Float.MAX_VALUE
        for (j in neighbours) {
            val q = Magnet.level(x, y, pts[j * 2], pts[j * 2 + 1]) ?: continue
            val d = hypot(q[0] - x, q[1] - y)
            if (d < moved) {
                moved = d
                best = q
            }
        }
        return best
    }

    /** Pulls the moved selection so its nearest point sticks to a line end, corner or line. */
    private fun magnetMove() {
        clearSnap()
        val reach = MAGNET_DP * density / currentScale()
        val targets = moveTargets
        if (targets == null) {
            snapToGuides(reach)
            return
        }
        var best = Float.MAX_VALUE
        var cx = 0f
        var cy = 0f
        val a = moveAnchors
        for (i in a.indices step 2) {
            val px = a[i] + moveDx
            val py = a[i + 1] + moveDy
            val snap = targets.snap(px, py, reach) ?: continue
            // A connection point beats a line at the same distance.
            val d = hypot(snap.x - px, snap.y - py) * if (snap.point) 0.7f else 1f
            if (d < best) {
                best = d
                cx = snap.x - px
                cy = snap.y - py
                snapX = snap.x
                snapY = snap.y
            }
        }
        if (best == Float.MAX_VALUE) {
            // Nothing to connect to: line up with other objects instead.
            snapToGuides(reach)
            return
        }
        snapPage = selectionPage
        moveDx += cx
        moveDy += cy
    }

    private fun snapToGuides(reach: Float) {
        if (guideBoxes.isEmpty()) return
        val shift = guideMove(moveDx, moveDy, GUIDE_REACH * reach)
        moveDx += shift[0]
        moveDy += shift[1]
        snapPage = selectionPage
    }

    private fun endMove() {
        val doc = doc(selectionPage)
        clearSnap()
        moveTargets = null
        val corners = vertexPoints
        if (vertexIndex >= 0) {
            vertexIndex = -1
            vertexPoints = null
            val shape = selectedShape()
            if (doc != null && corners != null && shape != null) {
                val reshaped = Magnet.restroke(shape, shapeThrough(corners))
                doc.edit(strokes = doc.strokes.map { if (it === shape) reshaped else it })
                reselect(listOf(reshaped))
                changed()
            } else {
                pageLayer.invalidate()
            }
            return
        }
        val dx = moveDx
        val dy = moveDy
        val kx = moveKx
        val ky = moveKy
        val angle = moveAngle
        moveDx = 0f
        moveDy = 0f
        moveKx = 1f
        moveKy = 1f
        moveAngle = 0f
        rotating = false
        if (doc == null || selected.isEmpty() || (abs(dx) + abs(dy) < 0.5f && abs(kx - 1f) < 0.005f && abs(ky - 1f) < 0.005f && abs(angle) < 0.1f)) {
            pageLayer.invalidate()
            return
        }
        val t = PageEdits.Transform.of(kx, ky, anchorX, anchorY, angle, turnX, turnY, dx, dy)
        // Dropped mostly on another page: the selection moves over to it.
        val target = dropPage(t)
        val dst = target?.takeIf { it != selectionPage }?.let(::doc)
        if (target != null && dst != null) {
            val src = selectionPage
            val shift = PageEdits.Transform.of(kx, ky, anchorX, anchorY, angle, turnX, turnY, dx + lefts[src] - lefts[target], dy + tops[src] - tops[target])
            val strokes = doc.strokes.filter { it in selected }.map { PageEdits.transform(it, shift) }
            val fills = doc.fills.filter { it in selected }.map { PageEdits.transform(it, shift) }
            val images = doc.images.filter { it in selected }.map { PageEdits.transform(it, shift) }
            // Off one page and onto the other: one step to undo.
            val edit = {
                doc.edit(doc.strokes.filterNot { it in selected }, doc.fills.filterNot { it in selected }, doc.images.filterNot { it in selected })
                dst.edit(dst.strokes + strokes, dst.fills + fills, dst.images + images)
            }
            listener?.editsTogether(edit) ?: edit()
            selectionPage = target
            reselect(strokes + fills + images)
            changed()
            return
        }
        val moved = ArrayList<Any>()
        // Lines and arrows with an end on the selection follow a plain move.
        val follows = IdentityHashMap<Any, Any>()
        if (kx == 1f && ky == 1f && angle == 0f) {
            for (c in connectors) {
                val (pts, heads) = follow(c, dx, dy)
                follows[c.line] = Magnet.restroke(c.line, pts)
                var k = 0
                for (h in listOfNotNull(c.startHead, c.endHead)) follows[h] = heads[k++]
            }
        }
        connectors = emptyList()
        val strokes = doc.strokes.map { s ->
            if (s in selected) PageEdits.transform(s, t).also(moved::add) else (follows[s] as? PageStroke) ?: s
        }
        val fills = doc.fills.map { f ->
            if (f in selected) PageEdits.transform(f, t).also(moved::add) else (follows[f] as? PageFill) ?: f
        }
        val images = doc.images.map { img ->
            if (img in selected) PageEdits.transform(img, t).also(moved::add) else img
        }
        doc.edit(strokes, fills, images)
        reselect(moved)
        changed()
    }

    /** Page under the centre of the selection after [t], or null if it is off every page. */
    private fun dropPage(t: PageEdits.Transform): Int? {
        val r = rawSelectionBounds() ?: return null
        val c = t.map(r.centerX(), r.centerY())
        val wx = c[0] + lefts[selectionPage]
        val wy = c[1] + tops[selectionPage]
        for (i in tops.indices) {
            if (wy >= tops[i] && wy <= tops[i] + heights[i] && wx >= lefts[i] && wx <= lefts[i] + widths[i]) return i
        }
        return null
    }

    // --- ruler

    private fun startRulerMove(e: MotionEvent) {
        rulerX = e.x
        rulerY = e.y
        rulerTurn = Float.NaN
        hideCursor()
    }

    /** Fingers added or lifted: the move goes on from where they are now. */
    private fun rulerPointersChanged(e: MotionEvent, excludeIndex: Int) {
        val ids = (0 until e.pointerCount).filter { it != excludeIndex }
        if (ids.isEmpty()) return
        rulerX = ids.map { e.getX(it) }.average().toFloat()
        rulerY = ids.map { e.getY(it) }.average().toFloat()
        rulerTurn = if (ids.size >= 2) fingerAngle(e, ids[0], ids[1]) else Float.NaN
        rulerTurn0 = rulerTurn
        rulerAngle0 = ruler.angle
    }

    private fun fingerAngle(e: MotionEvent, a: Int, b: Int) =
        Math.toDegrees(atan2((e.getY(b) - e.getY(a)).toDouble(), (e.getX(b) - e.getX(a)).toDouble())).toFloat()

    private fun moveRuler(e: MotionEvent) {
        val n = e.pointerCount
        val x = (0 until n).map { e.getX(it) }.average().toFloat()
        val y = (0 until n).map { e.getY(it) }.average().toFloat()
        val scale = currentScale()
        ruler.cx += (x - rulerX) / scale
        ruler.cy += (y - rulerY) / scale
        rulerX = x
        rulerY = y
        if (n >= 2 && !rulerTurn.isNaN()) {
            // Turned about the middle of the fingers: they stay on the same marks.
            val pivot = floatArrayOf(x, y).also(viewToWorld::mapPoints)
            val before = ruler.angle
            ruler.angle = ruler.snapAngle(rulerAngle0 + fingerAngle(e, 0, 1) - rulerTurn0)
            val d = Math.toRadians((ruler.angle - before).toDouble())
            val dx = ruler.cx - pivot[0]
            val dy = ruler.cy - pivot[1]
            ruler.cx = pivot[0] + (dx * kotlin.math.cos(d) - dy * kotlin.math.sin(d)).toFloat()
            ruler.cy = pivot[1] + (dx * kotlin.math.sin(d) + dy * kotlin.math.cos(d)).toFloat()
        }
        pageLayer.invalidate()
    }

    private fun endRulerMove() {
        rulerTurn = Float.NaN
        pageLayer.invalidate()
    }

    /** Ink tool used for a line along the ruler: kind, colour and width. */
    private fun rulerInk(): Triple<InkKind, Int, Float> {
        val kind = when (tool) {
            Tool.HIGHLIGHTER -> InkKind.HIGHLIGHTER
            Tool.FOUNTAIN -> InkKind.FOUNTAIN
            Tool.NEON -> InkKind.NEON
            Tool.SPOILER -> InkKind.SPOILER
            else -> penKind
        }
        val (even, size) = evenKind(kind, sizeOf(kind))
        return Triple(even, colorOf(kind), size)
    }

    private fun startRulerDraw(e: MotionEvent, index: Int) {
        activePointerId = e.getPointerId(index)
        val w = floatArrayOf(e.getX(index), e.getY(index)).also(viewToWorld::mapPoints)
        rulerEdge = ruler.edgeNear(w[0], w[1], RULER_REACH_DP * density / currentScale())
        val (kind, _, size) = rulerInk()
        rulerOffset = size / 2
        rulerFrom = if (rulerEdge == Ruler.ARC) ruler.arcAngle(w[0], w[1]) else ruler.along(w[0], w[1])
        rulerTo = rulerFrom
        hideCursor()
        pageLayer.invalidate()
    }

    /** Page point of the active page to world, onto the ruler's line. */
    private fun rulerDrawTo(x: Float, y: Float) {
        if (rulerEdge == 0) return
        val a = if (rulerEdge == Ruler.ARC) ruler.arcAngle(x + lefts[activePage], y + tops[activePage]) else ruler.along(x + lefts[activePage], y + tops[activePage])
        rulerFrom = kotlin.math.min(rulerFrom, a)
        rulerTo = max(rulerTo, a)
        pageLayer.invalidate()
    }

    private fun endRulerDraw() {
        val edge = rulerEdge
        rulerEdge = 0
        pageLayer.invalidate()
        if (edge == Ruler.ARC) {
            if (rulerTo - rulerFrom < 1f) return
            val doc = doc(activePage) ?: return
            val (kind, color, size) = rulerInk()
            doc.add(PageStroke(kind, Stroke(InkBrushes.create(kind, color, size), shapeInputs(arcPath(activePage), 0.6f)), if (kind == InkKind.DASHED) dashStyle else null))
            changed()
            return
        }
        if (edge == 0 || rulerTo - rulerFrom < MIN_RULER_LINE) return
        val doc = doc(activePage) ?: return
        val (kind, color, size) = rulerInk()
        // Ends stick to what is there, keeping to the ruler's line.
        val targets = Magnet.targets(doc.strokes)
        val reach = MAGNET_DP * density / currentScale()
        fun end(along: Float): FloatArray {
            val w = ruler.world(along, ruler.edgeLine(edge, rulerOffset))
            val px = w[0] - lefts[activePage]
            val py = w[1] - tops[activePage]
            val snap = targets.snap(px, py, reach) ?: return floatArrayOf(px, py)
            val a = ruler.along(snap.x + lefts[activePage], snap.y + tops[activePage])
            val q = ruler.world(a, ruler.edgeLine(edge, rulerOffset))
            return floatArrayOf(q[0] - lefts[activePage], q[1] - tops[activePage])
        }
        val a = end(rulerFrom)
        val b = end(rulerTo)
        val path = listOf(ShapeRecognizer.P(a[0], a[1]), ShapeRecognizer.P(b[0], b[1]))
        doc.add(PageStroke(kind, Stroke(InkBrushes.create(kind, color, size), shapeInputs(path, 0.6f)), if (kind == InkKind.DASHED) dashStyle else null))
        changed()
    }

    // --- compass

    /** The pen draws circles: down at the centre, out to the radius; going round draws only an arc. */
    var compassOn = false
        set(value) {
            field = value
            compassSamples.clear()
            pageLayer.invalidate()
        }
    private var compassX = 0f
    private var compassY = 0f
    private val compassSamples = ArrayList<FloatArray>()

    private fun startCompass(e: MotionEvent, index: Int) {
        activePointerId = e.getPointerId(index)
        val p = toPage(activePage, e.getX(index), e.getY(index))
        compassX = p[0]
        compassY = p[1]
        compassSamples.clear()
        hideCursor()
        pageLayer.invalidate()
    }

    private fun compassTo(x: Float, y: Float) {
        compassSamples += floatArrayOf(x, y)
        pageLayer.invalidate()
    }

    /** Radius and the arc (from, sweep, degrees; sweep 360 = the whole circle) of the compass line now. */
    private fun compassShape(): Triple<Float, Float, Float>? {
        val last = compassSamples.lastOrNull() ?: return null
        val r = hypot(last[0] - compassX, last[1] - compassY)
        if (r < MIN_COMPASS_RADIUS) return null
        // Where the pen went round at about the final radius: the arc it covered.
        var start = Float.NaN
        var prev = 0f
        var sweep = 0f
        var lo = 0f
        var hi = 0f
        for (s in compassSamples) {
            if (hypot(s[0] - compassX, s[1] - compassY) < 0.8f * r) continue
            val a = Math.toDegrees(kotlin.math.atan2((s[1] - compassY).toDouble(), (s[0] - compassX).toDouble())).toFloat()
            if (start.isNaN()) {
                start = a
                prev = a
                continue
            }
            var d = a - prev
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            sweep += d
            lo = kotlin.math.min(lo, sweep)
            hi = max(hi, sweep)
            prev = a
        }
        val span = hi - lo
        return if (start.isNaN() || span < MIN_ARC_DEGREES || span > 330f) Triple(r, 0f, 360f) else Triple(r, start + lo, span)
    }

    private fun compassPoints(r: Float, from: Float, sweep: Float): List<ShapeRecognizer.P> {
        val steps = max(8, (sweep / 4f).toInt())
        return (0..steps).map { k ->
            val t = Math.toRadians((from + sweep * k / steps).toDouble())
            ShapeRecognizer.P(compassX + r * kotlin.math.cos(t).toFloat(), compassY + r * kotlin.math.sin(t).toFloat())
        }
    }

    private fun endCompass() {
        val shape = compassShape()
        compassSamples.clear()
        pageLayer.invalidate()
        val (r, from, sweep) = shape ?: return
        val doc = doc(activePage) ?: return
        val (kind, color, size) = rulerInk()
        doc.add(PageStroke(kind, Stroke(InkBrushes.create(kind, color, size), shapeInputs(compassPoints(r, from, sweep), 0.6f)), if (kind == InkKind.DASHED) dashStyle else null))
        changed()
    }

    /** Points of the arc drawn along the protractor (page units of [page]), outside its edge by half the line. */
    private fun arcPath(page: Int): List<ShapeRecognizer.P> {
        val steps = max(2, ((rulerTo - rulerFrom) / 1.5f).toInt())
        return (0..steps).map { k ->
            val w = ruler.arcPoint(rulerFrom + (rulerTo - rulerFrom) * k / steps, rulerOffset)
            ShapeRecognizer.P(w[0] - lefts[page], w[1] - tops[page])
        }
    }

    // --- PDF text

    /** The pen picks words of a PDF page's text, from where it lands to where it is lifted. */
    var pdfTextOn = false
        set(value) {
            field = value
            if (!value) clearPdfText()
        }

    /** Page and words (indices into its [PageSource.pdfWords]) picked; -1 when none. */
    var pdfTextPage = -1
        private set
    private var pdfTextFrom = -1
    private var pdfTextTo = -1

    private fun startPdfText(e: MotionEvent, index: Int) {
        activePointerId = e.getPointerId(index)
        val words = source?.pdfWords(activePage).orEmpty()
        val p = toPage(activePage, e.getX(index), e.getY(index))
        val at = dev.inknotes.data.PdfWords.wordAt(words, p[0], p[1], PDF_WORD_REACH_DP * density / currentScale())
        hideCursor()
        if (at < 0) {
            clearPdfText()
            return
        }
        pdfTextPage = activePage
        pdfTextFrom = at
        pdfTextTo = at
        pageLayer.invalidate()
    }

    private fun pdfTextTo(x: Float, y: Float) {
        if (pdfTextPage != activePage || pdfTextFrom < 0) return
        val words = source?.pdfWords(activePage) ?: return
        val at = dev.inknotes.data.PdfWords.wordAt(words, x, y, Float.MAX_VALUE)
        if (at >= 0 && at != pdfTextTo) {
            pdfTextTo = at
            pageLayer.invalidate()
        }
    }

    private fun endPdfText() {
        if (pdfTextPage < 0) listener?.onPdfTextPicked(-1, -1, -1)
        else listener?.onPdfTextPicked(pdfTextPage, minOf(pdfTextFrom, pdfTextTo), maxOf(pdfTextFrom, pdfTextTo))
    }

    fun clearPdfText() {
        val had = pdfTextPage >= 0
        pdfTextPage = -1
        pdfTextFrom = -1
        pdfTextTo = -1
        pageLayer.invalidate()
        if (had) listener?.onPdfTextPicked(-1, -1, -1)
    }

    /** The picked words marked with the highlighter: one level band a line. */
    fun highlightPdfText() {
        val page = pdfTextPage
        val words = source?.pdfWords(page) ?: return
        val doc = doc(page) ?: return
        val color = highlighterColor
        val added = dev.inknotes.data.PdfWords.boxes(words, pdfTextFrom, pdfTextTo).map { b ->
            val h = (b[3] - b[1]) * 1.15f
            val cy = (b[1] + b[3]) / 2
            val path = listOf(ShapeRecognizer.P(b[0], cy), ShapeRecognizer.P(b[2], cy))
            PageStroke(InkKind.HIGHLIGHTER, Stroke(InkBrushes.create(InkKind.HIGHLIGHTER, color, h), shapeInputs(path, 0.5f)))
        }
        clearPdfText()
        if (added.isEmpty()) return
        doc.edit(strokes = doc.strokes + added)
        changed()
    }

    // --- eyedropper

    private fun pick(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // What is on screen now, drawn once: the loupe drawn later is not in it.
                pickShot = runCatching {
                    Bitmap.createBitmap(pageLayer.width, pageLayer.height, Bitmap.Config.ARGB_8888)
                        .also { pageLayer.draw(Canvas(it)) }
                }.getOrNull()
                pickAt(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> pickAt(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                pickAt(e.x, e.y)
                val color = pickColor
                val found = !pickX.isNaN()
                pickingColor = false
                if (found) listener?.onColorPicked(color)
            }
            MotionEvent.ACTION_CANCEL -> endPick()
        }
        return true
    }

    /** Average of the few pixels under the point; nothing outside the pages. */
    private fun pickAt(x: Float, y: Float) {
        val shot = pickShot ?: return
        val cx = x.toInt()
        val cy = y.toInt()
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (yy in cy - 1..cy + 1) for (xx in cx - 1..cx + 1) {
            if (xx !in 0 until shot.width || yy !in 0 until shot.height) continue
            val c = shot.getPixel(xx, yy)
            if (Color.alpha(c) < 128) continue
            r += Color.red(c)
            g += Color.green(c)
            b += Color.blue(c)
            a++
            n++
        }
        if (n == 0) {
            pickX = Float.NaN
        } else {
            pickX = x
            pickY = y
            pickColor = Color.rgb(r / n, g / n, b / n)
        }
        pageLayer.invalidate()
    }

    private fun endPick() {
        pickShot?.recycle()
        pickShot = null
        pickX = Float.NaN
        pageLayer.invalidate()
    }

    private fun drawLoupe(canvas: Canvas) {
        if (pickX.isNaN()) return
        val r = LOUPE_DP * density
        val cx = pickX
        // Above the finger or pen tip, below it near the top edge.
        val cy = if (pickY - r * 2.2f > 0f) pickY - r * 2.2f else pickY + r * 2.2f
        loupePaint.style = Paint.Style.FILL
        loupePaint.color = pickColor
        canvas.drawCircle(cx, cy, r, loupePaint)
        loupePaint.style = Paint.Style.STROKE
        loupePaint.strokeWidth = 4 * density
        loupePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, loupePaint)
        loupePaint.strokeWidth = 1.5f * density
        loupePaint.color = 0xFF555555.toInt()
        canvas.drawCircle(cx, cy, r + 2 * density, loupePaint)
        // Cross-hair on the sampled point.
        val h = 7 * density
        loupePaint.color = Color.WHITE
        loupePaint.strokeWidth = 3 * density
        canvas.drawLine(pickX - h, pickY, pickX + h, pickY, loupePaint)
        canvas.drawLine(pickX, pickY - h, pickX, pickY + h, loupePaint)
        loupePaint.color = Color.BLACK
        loupePaint.strokeWidth = 1.2f * density
        canvas.drawLine(pickX - h, pickY, pickX + h, pickY, loupePaint)
        canvas.drawLine(pickX, pickY - h, pickX, pickY + h, loupePaint)
    }

    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- hover

    private fun onHover(e: MotionEvent): Boolean {
        if (!isPen(e.getToolType(0)) || tops.isEmpty()) return false
        zoomPad?.let { pad ->
            if (e.y >= pad.top) {
                hideCursor()
                return true
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val page = pageAt(e.x, e.y)
                val p = toPage(page, e.x, e.y)
                showCursor(page, p[0], p[1])
            }
            MotionEvent.ACTION_HOVER_EXIT -> hideCursor()
        }
        return true
    }

    private fun showCursor(page: Int, x: Float, y: Float) {
        cursorPage = page
        cursorX = x
        cursorY = y
        pageLayer.invalidate()
    }

    private fun hideCursor() {
        if (cursorX.isNaN()) return
        cursorX = Float.NaN
        cursorY = Float.NaN
        pageLayer.invalidate()
    }

    // ------------------------------------------------------------- viewport

    private fun currentScale(): Float {
        val v = FloatArray(9)
        worldToView.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    // --- fling: the pages run on after the fingers let go

    private var flingVx = 0f
    private var flingVy = 0f
    private var flingAt = 0L
    private val config = android.view.ViewConfiguration.get(context)

    private val flingStep = object : Runnable {
        override fun run() {
            if (flingVx == 0f && flingVy == 0f) return
            val now = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
            val dt = ((now - flingAt) / 1000f).coerceIn(0f, 0.05f)
            flingAt = now
            // Slowing down evenly in proportion: the distance left is v·τ.
            val decay = kotlin.math.exp(-dt / FLING_TAU)
            val dx = flingVx * FLING_TAU * (1 - decay)
            val dy = flingVy * FLING_TAU * (1 - decay)
            flingVx *= decay
            flingVy *= decay
            val before = FloatArray(9).also(worldToView::getValues)
            worldToView.postTranslate(dx, dy)
            applyViewport()
            val after = FloatArray(9).also(worldToView::getValues)
            // Up against the end of the pages on an axis: that way it stops.
            if (abs(dx) > 0.5f && abs(after[Matrix.MTRANS_X] - before[Matrix.MTRANS_X]) < 0.3f * abs(dx)) flingVx = 0f
            if (abs(dy) > 0.5f && abs(after[Matrix.MTRANS_Y] - before[Matrix.MTRANS_Y]) < 0.3f * abs(dy)) flingVy = 0f
            if (hypot(flingVx, flingVy) < FLING_STOP_DP * density) {
                stopFling()
                return
            }
            postOnAnimation(this)
        }
    }

    private fun startFling(vx: Float, vy: Float) {
        val speed = hypot(vx, vy)
        if (speed < max(config.scaledMinimumFlingVelocity.toFloat(), FLING_MIN_DP * density)) return
        val k = min(1f, config.scaledMaximumFlingVelocity / speed)
        flingVx = vx * k
        flingVy = vy * k
        flingAt = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
        removeCallbacks(flingStep)
        postOnAnimation(flingStep)
    }

    /** A touch, a jump to a page or a new layout stops the pages where they are. */
    private fun stopFling() {
        flingVx = 0f
        flingVy = 0f
        removeCallbacks(flingStep)
    }

    override fun onDetachedFromWindow() {
        stopFling()
        super.onDetachedFromWindow()
    }

    /** Clamps zoom and pan so the pages cannot be lost off screen, then updates the inverse. */
    private fun applyViewport() {
        if (width == 0) return
        val scale = currentScale()
        val clamped = scale.coerceIn(fitScale * MIN_ZOOM, fitScale * MAX_ZOOM)
        if (clamped != scale) worldToView.postScale(clamped / scale, clamped / scale, width / 2f, height / 2f)

        val v = FloatArray(9)
        worldToView.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val margin = PAGE_MARGIN * density
        v[Matrix.MTRANS_X] = clampX(v[Matrix.MTRANS_X], worldWidth * s)
        v[Matrix.MTRANS_Y] = clampAxis(v[Matrix.MTRANS_Y], totalHeight * s, height.toFloat(), margin)
        worldToView.setValues(v)
        worldToView.invert(viewToWorld)
        pageLayer.invalidate()

        val visible = pageAt(width / 2f, height / 2f)
        if (visible != currentPage && visible in tops.indices) {
            currentPage = visible
            listener?.onVisiblePageChanged(visible)
        }
    }

    /**
     * Sideways the pages may go past the edges of the part of the view kept for them (see
     * [pageShift]) by up to half its width, or half the page when the page is narrower: so
     * the page's edge can be brought towards the middle, where it is easy to write.
     */
    private fun clampX(t: Float, content: Float): Float {
        val l = pagesLeft()
        val r = pagesRight()
        val extra = min(content, r - l) * SIDE_PAN
        val lo = min(l, r - content) - extra
        val hi = max(l, r - content) + extra
        return t.coerceIn(lo, hi)
    }

    private fun clampAxis(t: Float, content: Float, viewport: Float, margin: Float): Float =
        if (content + 2 * margin <= viewport) {
            (viewport - content) / 2
        } else {
            t.coerceIn(viewport - content - margin, margin)
        }

    private inner class FingerGesture {
        private var startTime = 0L
        private var maxPointers = 0
        private var travel = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var lastSpan = 0f

        /**
         * Direction the fingers keep to, decided once they have moved: a scroll that starts
         * up or down stays straight (the page can go sideways now, it should not drift while
         * paging), one that starts sideways stays level, and a slanted one or a pinch is free.
         */
        private var axis = ScrollAxis.UNDECIDED
        private var moveX = 0f
        private var moveY = 0f
        private var pinch = 0f

        /** Recent places of the fingers (time, x, y), for the speed at which they let go. */
        private val sampleT = LongArray(SAMPLES)
        private val sampleX = FloatArray(SAMPLES)
        private val sampleY = FloatArray(SAMPLES)
        private var samples = 0

        fun start(e: MotionEvent) {
            startTime = e.eventTime
            maxPointers = 1
            travel = 0f
            axis = ScrollAxis.UNDECIDED
            moveX = 0f
            moveY = 0f
            pinch = 0f
            rebase(e, -1)
            samples = 0
            sample(e.eventTime)
        }

        fun pointersChanged(e: MotionEvent, excludeIndex: Int) {
            maxPointers = max(maxPointers, e.pointerCount - if (excludeIndex >= 0) 1 else 0)
            rebase(e, excludeIndex)
            // The middle of the fingers jumps when one comes or goes: speed from here on.
            samples = 0
            sample(e.eventTime)
        }

        private fun sample(t: Long) {
            if (samples == SAMPLES) {
                System.arraycopy(sampleT, 1, sampleT, 0, SAMPLES - 1)
                System.arraycopy(sampleX, 1, sampleX, 0, SAMPLES - 1)
                System.arraycopy(sampleY, 1, sampleY, 0, SAMPLES - 1)
                samples--
            }
            sampleT[samples] = t
            sampleX[samples] = lastX
            sampleY[samples] = lastY
            samples++
        }

        /** Speed of the fingers (px/s) over their last moments, or null when they had stopped. */
        private fun velocity(upTime: Long): Pair<Float, Float>? {
            if (samples < 2 || upTime - sampleT[samples - 1] > FLING_STILL_MS) return null
            var first = samples - 1
            while (first > 0 && sampleT[samples - 1] - sampleT[first - 1] <= FLING_WINDOW_MS) first--
            val dt = (sampleT[samples - 1] - sampleT[first]) / 1000f
            if (dt <= 0f) return null
            return (sampleX[samples - 1] - sampleX[first]) / dt to (sampleY[samples - 1] - sampleY[first]) / dt
        }

        fun move(e: MotionEvent) {
            val (x, y, span) = focus(e, -1)
            var dx = x - lastX
            var dy = y - lastY
            if (e.pointerCount >= 2 && lastSpan > 0f && span > 0f) {
                val k = span / lastSpan
                worldToView.postScale(k, k, x, y)
                travel += abs(span - lastSpan)
                pinch += abs(span - lastSpan)
                if (pinch > touchSlop) axis = ScrollAxis.FREE
            }
            if (axis == ScrollAxis.UNDECIDED) {
                moveX += dx
                moveY += dy
                if (hypot(moveX, moveY) > touchSlop) {
                    axis = when {
                        abs(moveY) > abs(moveX) * AXIS_RATIO -> ScrollAxis.VERTICAL
                        abs(moveX) > abs(moveY) * AXIS_RATIO -> ScrollAxis.HORIZONTAL
                        else -> ScrollAxis.FREE
                    }
                }
            }
            when (axis) {
                ScrollAxis.VERTICAL -> dx = 0f
                ScrollAxis.HORIZONTAL -> dy = 0f
                else -> Unit
            }
            worldToView.postTranslate(dx, dy)
            travel += abs(dx) + abs(dy)
            lastX = x
            lastY = y
            lastSpan = span
            sample(e.eventTime)
            applyViewport()
        }

        fun end(e: MotionEvent) {
            val quick = e.eventTime - startTime < TAP_TIMEOUT_MS
            if (quick && travel < touchSlop * 2) {
                when (maxPointers) {
                    1 -> fingerTap(e.x, e.y)
                    2 -> listener?.onUndoGesture()
                    3 -> listener?.onRedoGesture()
                }
                return
            }
            // Let go while moving: the page runs on and slows down. Not after a pinch.
            if (pinch > touchSlop) return
            var (vx, vy) = velocity(e.eventTime) ?: return
            when (axis) {
                ScrollAxis.VERTICAL -> vx = 0f
                ScrollAxis.HORIZONTAL -> vy = 0f
                else -> Unit
            }
            startFling(vx, vy)
        }

        private fun rebase(e: MotionEvent, excludeIndex: Int) {
            val (x, y, span) = focus(e, excludeIndex)
            lastX = x
            lastY = y
            lastSpan = span
        }

        private fun focus(e: MotionEvent, excludeIndex: Int): Triple<Float, Float, Float> {
            var sx = 0f
            var sy = 0f
            var n = 0
            for (i in 0 until e.pointerCount) {
                if (i == excludeIndex) continue
                sx += e.getX(i)
                sy += e.getY(i)
                n++
            }
            if (n == 0) return Triple(lastX, lastY, 0f)
            val fx = sx / n
            val fy = sy / n
            var span = 0f
            for (i in 0 until e.pointerCount) {
                if (i == excludeIndex) continue
                span += hypot(e.getX(i) - fx, e.getY(i) - fy)
            }
            return Triple(fx, fy, if (n >= 2) span / n else 0f)
        }
    }

    // -------------------------------------------------------------- drawing

    /** Builds and renders a throwaway stroke so the first real stroke doesn't pay for native init. */
    private fun warmUpInk() {
        val batch = MutableStrokeInputBatch()
        for (i in 0 until 12) batch.add(InputToolType.STYLUS, 4f + i * 4f, 4f + i * 2f, i * 8L)
        val canvas = Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))
        for (kind in InkKind.entries) {
            pageRenderer.ink.draw(canvas, Stroke(InkBrushes.create(kind, Color.BLACK, 3f), batch), Matrix())
        }
    }

    private inner class PageLayer(context: Context) : View(context) {
        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = SELECTION_COLOR
        }
        private val selectionTint = Paint().apply { color = 0x141E5BD8 }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val anglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SELECTION_COLOR
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = 0x99000000.toInt()
        }
        private val tracePath = Path()
        private val answerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = ANSWER_TYPEFACE }
        private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val bracketPath = Path()
        private val graphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val graphFill = Paint().apply { style = Paint.Style.FILL }
        private val zoomFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ZoomPad.ZOOM_COLOR
        }

        /** [region] of [page] drawn through [pageToCanvas]: what the zoom window shows. */
        fun drawRegion(canvas: Canvas, page: Int, pageToCanvas: Matrix, region: RectF) {
            val src = source ?: return
            if (page !in tops.indices) return
            canvas.save()
            canvas.concat(pageToCanvas)
            canvas.clipRect(region)
            pageRenderer.pageHeight = heights[page]
            pageRenderer.pageWidth = widths[page]
            pageRenderer.pdf = src.pdfBitmap(page)
            pageRenderer.drawBackground(canvas, edgeWidth = 0f)
            val doc = src.loadedDocument(page)
            if (doc != null) {
                val hidden = hiddenOf(doc)
                drawElements(canvas, src, doc, pageToCanvas, region, exclude = hidden)
                drawSpoilers(canvas, doc, pageToCanvas, region, hidden)
                drawAnswers(canvas, src.answers(page), widths[page])
            }
            canvas.restore()
        }

        override fun onDraw(canvas: Canvas) {
            val src = source ?: return
            val scale = currentScale()
            dashPaint.strokeWidth = 1.5f * density / scale
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density / scale, 4f * density / scale), 0f)
            val viewTop = floatArrayOf(0f, 0f).also(viewToWorld::mapPoints)[1]
            val viewBottom = floatArrayOf(0f, height.toFloat()).also(viewToWorld::mapPoints)[1]

            for (i in tops.indices) {
                if (tops[i] > viewBottom || tops[i] + heights[i] < viewTop) continue
                val toScreen = pageMatrix(i)
                canvas.save()
                canvas.concat(toScreen)
                pageRenderer.pageHeight = heights[i]
                pageRenderer.pageWidth = widths[i]
                val pdf = src.pdfBitmap(i)
                pageRenderer.pdf = pdf
                val doc = src.loadedDocument(i)
                if (doc == null) {
                    // Still loading: its thumbnail, sharpened as soon as the page is in.
                    pageRenderer.drawBackground(canvas, edgeWidth = 1f / scale)
                    src.thumbnail(i)?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, widths[i], heights[i]), thumbPaint) }
                    canvas.restore()
                    continue
                }
                val sel: Set<Any> = when {
                    i != selectionPage || selected.isEmpty() -> emptySet()
                    // Lines that follow the dragged selection are drawn with it, as they will be.
                    dragging && connectors.isNotEmpty() && moveKx == 1f && moveKy == 1f && moveAngle == 0f -> Collections.newSetFromMap<Any>(IdentityHashMap()).apply { addAll(selected); addAll(connectorSet) }
                    else -> selected
                }
                val hidden = hiddenOf(doc)
                val out: Set<Any> = if (hidden.isEmpty()) sel else Collections.newSetFromMap<Any>(IdentityHashMap()).apply { addAll(sel); addAll(hidden) }
                val look = PageCache.Look(widths[i], heights[i], pageRenderer.paper, pageRenderer.dark, pageRenderer.photo, pdf, doc.images.map { src.image(it.file) }, pdfDark && pdf != null)
                // The cached picture holds the paper too: the background is drawn only without it.
                when (cache.draw(canvas, doc, look, scale, out, visibleRect(i), if (pdf != null) src.pdfRegion(i) else null) { src.image(it) }) {
                    PageCache.Result.DRAWN -> {
                        pageRenderer.drawEdge(canvas, edgeWidth = 1f / scale)
                        canvas.clipRect(0f, 0f, widths[i], heights[i])
                        drawSpoilers(canvas, doc, toScreen, null, out)
                    }
                    // Zoomed in: only what is on screen, a small part of the page.
                    PageCache.Result.ZOOMED -> {
                        pageRenderer.drawBackground(canvas, edgeWidth = 1f / scale)
                        drawElements(canvas, src, doc, toScreen, visibleRect(i), exclude = out)
                        drawSpoilers(canvas, doc, toScreen, visibleRect(i), out)
                    }
                    // A page appearing while scrolling: its thumbnail until the picture is rendered.
                    PageCache.Result.NOT_READY -> {
                        pageRenderer.drawBackground(canvas, edgeWidth = 1f / scale)
                        src.thumbnail(i)?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, widths[i], heights[i]), thumbPaint) }
                    }
                }
                // The selection lies on top; while dragged it is drawn above all pages instead.
                if (sel.isNotEmpty() && !dragging) drawElements(canvas, src, doc, toScreen, null, only = sel)
                drawAnswers(canvas, src.answers(i), widths[i])
                if (!(dragging && i == selectionPage)) drawOverlays(canvas, i)
                if (i == zoomRegionPage && zoomPad != null) {
                    zoomFramePaint.strokeWidth = 2f * density / scale
                    canvas.drawRect(zoomRegion, zoomFramePaint)
                }
                canvas.restore()
            }
            cache.endFrame()
            drawDragged(canvas, src)
            drawRuler(canvas, scale)
            drawCompass(canvas, scale)
            drawPdfText(canvas)
            // The pointer's trail fades on its own: frames keep coming while it is lit.
            if (laser.active && laser.draw(canvas, worldToView)) postInvalidateOnAnimation()

            if (!cursorX.isNaN() && cursorPage in tops.indices) {
                val p = pageToViewPoint(cursorPage, cursorX, cursorY)
                val radius = when {
                    mode == Mode.ERASE || tool == Tool.ERASER -> eraserRadiusDp * density
                    tool == Tool.HIGHLIGHTER -> highlighterSize / 2 * scale
                    tool == Tool.SPOILER -> spoilerSize / 2 * scale
                    tool == Tool.FOUNTAIN -> max(fountainSize / 4 * scale, 2 * density)
                    tool == Tool.NEON -> max(neonSize / 2 * scale, 2 * density)
                    else -> max(penSize / 2 * scale, 2 * density)
                }
                canvas.drawCircle(p[0], p[1], radius, cursorPaint)
            }
            drawLoupe(canvas)
        }

        /** Answers after "=": blue text, or a matrix in round brackets, centred on the line. */
        private fun drawAnswers(canvas: Canvas, answers: List<PageAnswer>, pageWidth: Float) {
            for (a in answers) {
                val color = when {
                    a.error && dark -> 0xFFE57373.toInt()
                    a.error -> 0xFFC62828.toInt()
                    dark -> 0xFF82AAFF.toInt()
                    else -> ANSWER_COLOR
                }
                answerPaint.color = color
                answerPaint.textSize = a.size * if (a.error) 0.75f else 1.1f
                val ts = answerPaint.textSize
                when (val ans = a.answer) {
                    is Answer.Text -> {
                        answerPaint.textAlign = Paint.Align.LEFT
                        // An "=" near the right edge: a smaller answer, or else one under the line.
                        val room = pageWidth - ANSWER_MARGIN - a.x
                        val wide = ans.lines.maxOf { answerPaint.measureText(it) }
                        var x = a.x
                        var y = a.y
                        if (wide > room) {
                            answerPaint.textSize = ts * max(MIN_ANSWER_SCALE, room / wide)
                            val w = ans.lines.maxOf { answerPaint.measureText(it) }
                            if (w > room) {
                                answerPaint.textSize = ts
                                x = max(ANSWER_MARGIN, pageWidth - ANSWER_MARGIN - wide)
                                y = a.y + a.size * (0.9f + 0.65f * ans.lines.size)
                            }
                        }
                        val size = answerPaint.textSize
                        val lineH = size * 1.25f
                        var baseline = y - (ans.lines.size - 1) * lineH / 2 + size * 0.36f
                        val textW = ans.lines.maxOf { answerPaint.measureText(it) }
                        bounds(a).set(x, baseline - size, x + textW, baseline + (ans.lines.size - 1) * lineH + size * 0.3f)
                        for (line in ans.lines) {
                            canvas.drawText(line, x, baseline, answerPaint)
                            baseline += lineH
                        }
                    }
                    is Answer.Graph -> {
                        drawGraph(canvas, a, ans, color)
                        graphShapes[a]?.box?.let { bounds(a).set(it) }
                    }
                    is Answer.Surface -> {
                        val box = RectF(a.x, a.y, a.x + a.size * Plot.WIDTH, a.y + a.size * Plot.HEIGHT)
                        graphFill.color = if (dark) 0x1882AAFF else 0x0D1A56DB
                        canvas.drawRoundRect(box, a.size * 0.25f, a.size * 0.25f, graphFill)
                        // Drawn once into a picture: hundreds of patches every frame would slow scrolling.
                        if (dark != surfaceDark) {
                            surfaceDark = dark
                            surfaceBitmaps.clear()
                        }
                        val bmp = surfaceBitmaps.getOrPut(a) { SurfaceArt.bitmap(ans, SURFACE_PX, color, dark) }
                        canvas.drawBitmap(bmp, null, box, surfacePaint)
                        bounds(a).set(box)
                    }
                    is Answer.Grid -> {
                        val rows = ans.rows
                        val cols = rows[0].size
                        val colW = FloatArray(cols) { c -> rows.maxOf { answerPaint.measureText(it[c]) } }
                        val gap = ts * 0.8f
                        val rowH = ts * 1.3f
                        val pad = ts * 0.45f
                        val top = a.y - rowH * rows.size / 2
                        val bottom = top + rowH * rows.size
                        val x0 = a.x + pad * 1.4f
                        val width = colW.sum() + gap * (cols - 1)
                        bracketPaint.color = color
                        bracketPaint.strokeWidth = ts * 0.07f
                        bracketPath.rewind()
                        bracketPath.moveTo(a.x + pad, top)
                        bracketPath.quadTo(a.x - pad * 0.3f, a.y, a.x + pad, bottom)
                        val xr = x0 + width + pad * 0.4f
                        bracketPath.moveTo(xr, top)
                        bracketPath.quadTo(xr + pad * 1.3f, a.y, xr, bottom)
                        canvas.drawPath(bracketPath, bracketPaint)
                        bounds(a).set(a.x, top, xr + pad, bottom)
                        answerPaint.textAlign = Paint.Align.CENTER
                        for ((r, row) in rows.withIndex()) {
                            var x = x0
                            val baseline = top + rowH * r + rowH / 2 + ts * 0.36f
                            for (c in 0 until cols) {
                                canvas.drawText(row[c], x + colW[c] / 2, baseline, answerPaint)
                                x += colW[c] + gap
                            }
                        }
                    }
                }
            }
        }

        /** What a graph needs every frame, made once per answer: the curve, grid and labels. */
        // [a] is not kept: the shapes are cached weakly by their answer, which must stay collectable.
        private inner class GraphShape(a: PageAnswer, private val g: Answer.Graph) {
            private val left = a.x
            private val top = a.y
            val w = a.size * Plot.WIDTH
            val h = a.size * Plot.HEIGHT
            val box = RectF(a.x, a.y, a.x + w, a.y + h)
            private fun mx(v: Float) = left + (v - g.x0) / (g.x1 - g.x0) * w
            private fun my(v: Float) = top + h - (v - g.y0) / (g.y1 - g.y0) * h
            val ax = if (0f in g.x0..g.x1) mx(0f) else a.x
            val ay = if (0f in g.y0..g.y1) my(0f) else a.y + h
            val grid = ArrayList<Float>().apply {
                for (t in Plot.ticks(g.x0, g.x1)) addAll(listOf(mx(t), a.y, mx(t), a.y + h))
                for (t in Plot.ticks(g.y0, g.y1, 4)) addAll(listOf(a.x, my(t), a.x + w, my(t)))
            }.toFloatArray()
            val axes = floatArrayOf(a.x, ay, a.x + w, ay, ax, a.y, ax, a.y + h)
            val labelSize = a.size * 0.32f

            /** Numbers along the axes with their places, skipping 0 and the box edges. */
            val labels: List<Triple<String, Float, Float>> = buildList {
                val ts = labelSize
                for (t in Plot.ticks(g.x0, g.x1)) {
                    val x = mx(t)
                    if (t == 0f || x < a.x + ts || x > a.x + w - ts) continue
                    add(Triple(Printer.number(t.toDouble()), x, (ay + ts * 1.1f).coerceAtMost(a.y + h - ts * 0.3f)))
                }
                for (t in Plot.ticks(g.y0, g.y1, 4)) {
                    val y = my(t)
                    if (t == 0f || y < a.y + ts || y > a.y + h - ts * 0.5f) continue
                    add(Triple(Printer.number(t.toDouble()), -((ax + ts * 0.3f).coerceAtMost(a.x + w - ts * 2f)), y + ts * 0.35f))
                }
            }

            /** The curve as one path: the GPU keeps it between frames while the page only moves. */
            val curve = Path().apply {
                for (seg in g.segments) {
                    moveTo(a.x + seg[0] * w, a.y + h - seg[1] * h)
                    var i = 2
                    while (i < seg.size) {
                        lineTo(a.x + seg[i] * w, a.y + h - seg[i + 1] * h)
                        i += 2
                    }
                }
            }
        }

        private val graphShapes = java.util.WeakHashMap<PageAnswer, GraphShape>()
        private val surfaceBitmaps = java.util.WeakHashMap<PageAnswer, Bitmap>()
        private var surfaceDark = false
        private val surfacePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        /** Where each answer was last drawn (page units), to find the one a finger taps. */
        private val answerBounds = java.util.WeakHashMap<PageAnswer, RectF>()

        private fun bounds(a: PageAnswer): RectF = answerBounds.getOrPut(a) { RectF() }

        fun answerAt(page: Int, x: Float, y: Float, slop: Float): PageAnswer? =
            source?.answers(page)?.firstOrNull { a ->
                answerBounds[a]?.let { r -> x in r.left - slop..r.right + slop && y in r.top - slop..r.bottom + slop } == true
            }

        /** A graph: a light box, grid, axes with a few numbers, and the curve. */
        private fun drawGraph(canvas: Canvas, a: PageAnswer, g: Answer.Graph, color: Int) {
            val shape = graphShapes.getOrPut(a) { GraphShape(a, g) }
            val box = shape.box
            graphFill.color = if (dark) 0x1882AAFF else 0x0D1A56DB
            val corner = a.size * 0.25f
            canvas.drawRoundRect(box, corner, corner, graphFill)
            canvas.save()
            canvas.clipRect(box)
            graphPaint.color = (color and 0x00FFFFFF) or 0x22000000
            graphPaint.strokeWidth = a.size * 0.02f
            canvas.drawLines(shape.grid, graphPaint)
            graphPaint.color = (color and 0x00FFFFFF) or 0x88000000.toInt()
            graphPaint.strokeWidth = a.size * 0.035f
            canvas.drawLines(shape.axes, graphPaint)
            answerPaint.color = (color and 0x00FFFFFF) or 0xAA000000.toInt()
            answerPaint.textSize = shape.labelSize
            for ((text, x, y) in shape.labels) {
                // A negative x marks a label of the vertical axis (left-aligned).
                answerPaint.textAlign = if (x < 0) Paint.Align.LEFT else Paint.Align.CENTER
                canvas.drawText(text, abs(x), y, answerPaint)
            }
            graphPaint.color = color
            graphPaint.strokeWidth = a.size * 0.07f
            canvas.drawPath(shape.curve, graphPaint)
            canvas.restore()
            // The axis letters, outside the clip so they are never cut.
            answerPaint.textAlign = Paint.Align.LEFT
            answerPaint.textSize = a.size * 0.38f
            val ts = shape.labelSize
            canvas.drawText(g.variable, box.right - answerPaint.measureText(g.variable) - ts * 0.3f, shape.ay - ts * 0.4f, answerPaint)
            canvas.drawText("y", shape.ax + ts * 0.35f, box.top + answerPaint.textSize, answerPaint)
        }

        /** Area preview, lasso/outline being traced and selection frame of page [i]. */
        private fun drawOverlays(canvas: Canvas, i: Int) {
            if (!spaceY0.isNaN() && spacePage == i) {
                val w = source?.pageWidth(i) ?: PAGE_W
                val y1 = spaceY0 + spaceDy
                spacePaint.color = if (spaceDy >= 0f) 0x302979FF else 0x30E53935
                canvas.drawRect(0f, min(spaceY0, y1), w, max(spaceY0, y1), spacePaint)
                guidePaint.strokeWidth = 1.5f * density / currentScale()
                guidePaint.pathEffect = null
                canvas.drawLine(0f, spaceY0, w, spaceY0, guidePaint)
            }
            source?.links(i)?.takeIf { it.isNotEmpty() }?.let { links ->
                val scale = currentScale()
                linkPaint.strokeWidth = 1.2f * density / scale
                linkPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f * density / scale, 3f * density / scale), 0f)
                val pad = 2f * density / scale
                for (r in links) canvas.drawRoundRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad, 3 * pad, 3 * pad, linkPaint)
            }
            source?.soundMarks(i)?.takeIf { it.isNotEmpty() }?.let { marks ->
                val scale = currentScale()
                soundPaint.strokeWidth = 2f * density / scale
                for (r in marks) canvas.drawLine(r.left, r.bottom + 2f * density / scale, r.right, r.bottom + 2f * density / scale, soundPaint)
            }
            val flash = flashRect
            if (flash != null && flashPage == i) canvas.drawRoundRect(flash, FLASH_PAD, FLASH_PAD, flashPaint)
            val area = pendingArea
            if (area != null && areaPage == i) {
                val preview = PageFill(area, fillPattern, fillColor, style = fillStyle)
                // What will be filled, once known: the traced outline less the shapes inside it.
                for (piece in pendingPieces ?: listOf(area)) pageRenderer.drawFill(canvas, PageFill(piece, fillPattern, fillColor, style = fillStyle), 110)
                canvas.drawPath(preview.path, dashPaint)
            }
            if (tracePoints.size >= 4 && activePage == i) {
                tracePath.rewind()
                tracePath.moveTo(tracePoints[0], tracePoints[1])
                for (k in 2 until tracePoints.size step 2) tracePath.lineTo(tracePoints[k], tracePoints[k + 1])
                canvas.drawPath(tracePath, dashPaint)
            }
            if (selectionPage == i) {
                selectionBounds()?.let { r ->
                    val scale = currentScale()
                    canvas.save()
                    canvas.rotate(moveAngle, turnX, turnY)
                    canvas.drawRect(r, selectionTint)
                    canvas.drawRect(r, dashPaint)
                    val handle = HANDLE_DP * density / scale
                    // Rotate handle above the frame, on a short stem.
                    val hx = r.centerX()
                    val hy = r.top - rotateHandleOffset()
                    handlePaint.style = Paint.Style.STROKE
                    handlePaint.strokeWidth = 1.5f * density / scale
                    handlePaint.color = SELECTION_COLOR
                    canvas.drawLine(hx, r.top, hx, hy + handle, handlePaint)
                    drawHandle(canvas, hx, hy, handle, scale)
                    // Rotation arrow inside the handle.
                    handlePaint.strokeWidth = 1.5f * density / scale
                    canvas.drawArc(hx - handle * 0.5f, hy - handle * 0.5f, hx + handle * 0.5f, hy + handle * 0.5f, -60f, 270f, false, handlePaint)
                    // Corner handle for resizing, side handles for stretching one way.
                    drawHandle(canvas, r.right, r.bottom, handle, scale)
                    drawHandle(canvas, r.right, r.centerY(), handle * SIDE_HANDLE, scale)
                    drawHandle(canvas, r.centerX(), r.bottom, handle * SIDE_HANDLE, scale)
                    canvas.restore()
                    if (!dragging) selectedShape()?.let(::handlePoints)?.let { drawCorners(canvas, it, scale) }
                    if (rotating && mode == Mode.MOVE) {
                        anglePaint.textSize = 14f * density / scale
                        canvas.drawText("${Math.round(moveAngle)}°", hx + handle * 1.6f, hy + anglePaint.textSize / 3, anglePaint)
                    }
                }
            }
        }

        private fun drawHandle(canvas: Canvas, x: Float, y: Float, radius: Float, scale: Float) {
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = Color.WHITE
            canvas.drawCircle(x, y, radius, handlePaint)
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = 2f * density / scale
            handlePaint.color = SELECTION_COLOR
            canvas.drawCircle(x, y, radius, handlePaint)
        }

        private val thumbPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        /** Part of page [i] on screen, in its page units. */
        private fun visibleRect(i: Int): RectF = RectF(0f, 0f, width.toFloat(), height.toFloat()).also { viewToPageMatrix(i).mapRect(it) }

        /**
         * Elements of [doc] drawn one by one: those inside [visible] (all when null), without
         * [exclude], or [only] those. Pictures under fills under highlighter under ink.
         */
        private fun drawElements(
            canvas: Canvas,
            src: PageSource,
            doc: PageDocument,
            toScreen: Matrix,
            visible: RectF?,
            exclude: Set<Any> = emptySet(),
            only: Set<Any>? = null,
        ) {
            fun wanted(x: Any) = (only == null || x in only) && x !in exclude
            fun seen(b: RectF?) = visible == null || b == null || RectF.intersects(b, visible)
            for (img in doc.images) if (wanted(img) && seen(PageEdits.bounds(img))) pageRenderer.drawImage(canvas, img, src.image(img.file))
            pageRenderer.drawFills(canvas, doc.fills.filter { wanted(it) && seen(it.bounds) }, Hatch.budgetFor(doc.fills.size))
            val highlights = doc.strokes.filter { it.kind == InkKind.HIGHLIGHTER && wanted(it) && seen(PageCache.strokeBounds(it)) }
            if (highlights.isNotEmpty()) {
                pageRenderer.beginHighlighter(canvas)
                for (s in highlights) pageRenderer.drawStroke(canvas, s, toScreen)
                pageRenderer.endHighlighter(canvas)
            }
            pageRenderer.drawInk(canvas, doc.strokes.filter { it.kind.isInk && wanted(it) && seen(PageCache.strokeBounds(it)) }, toScreen)
            // Hint bands of the selection: the rest are drawn over the page's picture.
            if (only != null) drawSpoilers(canvas, doc, toScreen, visible, emptySet(), only)
        }

        /** Hint-marker bands above everything else: hidden, or tinted where a tap opened them. */
        private fun drawSpoilers(canvas: Canvas, doc: PageDocument, toScreen: Matrix, visible: RectF?, exclude: Set<Any>, only: Set<Any>? = null) {
            for (s in doc.strokes) {
                if (s.kind != InkKind.SPOILER || s in exclude || (only != null && s !in only)) continue
                if (visible != null && PageCache.strokeBounds(s)?.let { RectF.intersects(it, visible) } == false) continue
                pageRenderer.drawSpoiler(canvas, s, toScreen, s in revealed)
            }
        }

        /** The ruler, a line being drawn along it, and the ruler's angle while it turns. */
        private fun drawRuler(canvas: Canvas, scale: Float) {
            if (!ruler.shown) return
            canvas.save()
            canvas.concat(worldToView)
            val pointer = if (!cursorX.isNaN() && cursorPage in tops.indices) floatArrayOf(cursorX + lefts[cursorPage], cursorY + tops[cursorPage]) else null
            ruler.draw(canvas, dark, 1f / scale, showAngle = mode == Mode.RULER, pointer = pointer)
            if (mode == Mode.RULER_DRAW && rulerEdge == Ruler.ARC && rulerTo > rulerFrom) {
                val (_, color, size) = rulerInk()
                previewPaint.color = InkColors.forPaper(color, dark)
                previewPaint.strokeWidth = size
                tracePath.rewind()
                val pts = arcPath(activePage)
                tracePath.moveTo(pts[0].x + lefts[activePage], pts[0].y + tops[activePage])
                for (p in pts.drop(1)) tracePath.lineTo(p.x + lefts[activePage], p.y + tops[activePage])
                canvas.drawPath(tracePath, previewPaint)
            } else if (mode == Mode.RULER_DRAW && rulerEdge != 0 && rulerTo > rulerFrom) {
                val (_, color, size) = rulerInk()
                val a = ruler.world(rulerFrom, ruler.edgeLine(rulerEdge, rulerOffset))
                val b = ruler.world(rulerTo, ruler.edgeLine(rulerEdge, rulerOffset))
                previewPaint.color = InkColors.forPaper(color, dark)
                previewPaint.strokeWidth = size
                canvas.drawLine(a[0], a[1], b[0], b[1], previewPaint)
            }
            canvas.restore()
        }

        private val compassText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2979FF.toInt() }
        private val pdfPickPaint = Paint().apply { color = 0x552979FF }

        /** Words of a PDF picked with the pen: a blue band over each line. */
        fun drawPdfText(canvas: Canvas) {
            val page = pdfTextPage
            if (page !in tops.indices) return
            val words = source?.pdfWords(page) ?: return
            canvas.save()
            canvas.concat(pageMatrix(page))
            for (b in dev.inknotes.data.PdfWords.boxes(words, pdfTextFrom, pdfTextTo)) canvas.drawRect(b[0], b[1], b[2], b[3], pdfPickPaint)
            canvas.restore()
        }

        /** The circle being drawn with the compass, its centre and radius in millimetres. */
        private fun drawCompass(canvas: Canvas, scale: Float) {
            if (mode != Mode.COMPASS || activePage !in tops.indices) return
            val last = compassSamples.lastOrNull() ?: return
            canvas.save()
            canvas.concat(pageMatrix(activePage))
            val (_, color, size) = rulerInk()
            previewPaint.color = 0xFF2979FF.toInt()
            previewPaint.strokeWidth = 1.5f / scale
            canvas.drawLine(compassX, compassY, last[0], last[1], previewPaint)
            canvas.drawCircle(compassX, compassY, 3f / scale, previewPaint)
            compassShape()?.let { (r, from, sweep) ->
                previewPaint.color = InkColors.forPaper(color, dark)
                previewPaint.strokeWidth = size
                tracePath.rewind()
                val pts = compassPoints(r, from, sweep)
                tracePath.moveTo(pts[0].x, pts[0].y)
                for (p in pts.drop(1)) tracePath.lineTo(p.x, p.y)
                canvas.drawPath(tracePath, previewPaint)
                compassText.textSize = 15f * density / scale
                canvas.drawText("r = ${"%.1f".format(r * dev.inknotes.data.Notebook.MM_PER_UNIT).replace('.', ',')} мм", last[0] + 10f / scale, last[1] - 10f / scale, compassText)
            }
            canvas.restore()
        }

        private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        /** The selection being dragged, unclipped by its page, with its frame. */
        private fun drawDragged(canvas: Canvas, src: PageSource) {
            val page = selectionPage
            if (!dragging || page !in tops.indices) return
            val doc = src.document(page)
            val toScreen = pageMatrix(page)
            canvas.save()
            canvas.concat(toScreen)
            val corners = vertexPoints
            val shape = selectedShape()
            if (corners != null && shape != null) {
                // A corner being dragged: the shape as it will be.
                previewPaint.color = InkColors.forPaper(shape.stroke.brush.colorIntArgb, dark)
                previewPaint.strokeWidth = shape.stroke.brush.size
                val line = shapeThrough(corners)
                tracePath.rewind()
                tracePath.moveTo(line[0], line[1])
                for (k in 2 until line.size step 2) tracePath.lineTo(line[k], line[k + 1])
                canvas.drawPath(tracePath, previewPaint)
                drawCorners(canvas, corners, currentScale())
                drawSnap(canvas, page)
                canvas.restore()
                return
            }
            canvas.save()
            // Same order as PageEdits.Transform.of: scale, then turn, then move.
            canvas.translate(moveDx, moveDy)
            canvas.rotate(moveAngle, turnX, turnY)
            canvas.scale(moveKx, moveKy, anchorX, anchorY)
            for (img in doc.images) if (img in selected) pageRenderer.drawImage(canvas, img, src.image(img.file))
            pageRenderer.drawFills(canvas, doc.fills.filter { it in selected })
            for (s in doc.strokes) if (s in selected && s.kind == InkKind.HIGHLIGHTER) pageRenderer.drawStroke(canvas, s, toScreen)
            pageRenderer.drawInk(canvas, doc.strokes.filter { it in selected && it.kind.isInk }, toScreen)
            for (s in doc.strokes) if (s in selected && s.kind == InkKind.SPOILER) pageRenderer.drawSpoiler(canvas, s, toScreen, s in revealed)
            canvas.restore()
            if (moveKx == 1f && moveKy == 1f && moveAngle == 0f) {
                for (c in connectors) {
                    val (pts, heads) = follow(c, moveDx, moveDy)
                    previewPaint.color = InkColors.forPaper(c.line.stroke.brush.colorIntArgb, dark)
                    previewPaint.strokeWidth = c.line.stroke.brush.size
                    tracePath.rewind()
                    tracePath.moveTo(pts[0], pts[1])
                    for (k in 2 until pts.size step 2) tracePath.lineTo(pts[k], pts[k + 1])
                    canvas.drawPath(tracePath, previewPaint)
                    pageRenderer.drawFills(canvas, heads)
                }
            }
            drawOverlays(canvas, page)
            drawSnap(canvas, page)
            canvas.restore()
        }

        /** Ring where the moved line or corner snapped; guides the selection lines up along. */
        private fun drawSnap(canvas: Canvas, page: Int) {
            if (snapPage != page) return
            val scale = currentScale()
            val g = guideLines
            if (g.isNotEmpty()) {
                guidePaint.strokeWidth = 1.5f * density / scale
                guidePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * density / scale, 4f * density / scale), 0f)
                for (i in g.indices step 4) canvas.drawLine(g[i], g[i + 1], g[i + 2], g[i + 3], guidePaint)
            }
            if (snapX.isNaN()) return
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = 2f * density / scale
            handlePaint.color = SNAP_COLOR
            canvas.drawCircle(snapX, snapY, SNAP_RING_DP * density / scale, handlePaint)
            handlePaint.style = Paint.Style.FILL
            canvas.drawCircle(snapX, snapY, 2.5f * density / scale, handlePaint)
        }

        /** Square handles on the corners of a shape: drag one to reshape it. */
        private fun drawCorners(canvas: Canvas, pts: FloatArray, scale: Float) {
            val h = CORNER_DP * density / scale
            val closed = closedPolyline(pts)
            val last = if (closed) pts.size - 2 else pts.size
            for (k in 0 until last step 2) {
                handlePaint.style = Paint.Style.FILL
                handlePaint.color = Color.WHITE
                canvas.drawRect(pts[k] - h, pts[k + 1] - h, pts[k] + h, pts[k + 1] + h, handlePaint)
                handlePaint.style = Paint.Style.STROKE
                handlePaint.strokeWidth = 2f * density / scale
                handlePaint.color = SELECTION_COLOR
                canvas.drawRect(pts[k] - h, pts[k + 1] - h, pts[k] + h, pts[k + 1] + h, handlePaint)
            }
        }
    }

    companion object {
        /** Page width in page units (1000 units = 210 mm for A4). */
        const val PAGE_W = 1000f
        private const val ANSWER_MARGIN = 8f
        private const val MIN_ANSWER_SCALE = 0.6f

        /** Height of an ordinary (A4) page; imported PDF pages keep their own aspect. */
        const val PAGE_H = 1414f

        /** Vertical gap between pages in page units. */
        const val PAGE_GAP = 40f

        private const val ANSWER_COLOR = 0xFF1E5BD8.toInt()

        /** Answers look written by hand where the system has such a font. */
        private val ANSWER_TYPEFACE: android.graphics.Typeface =
            android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL)

                private const val PAGE_MARGIN = 16f // dp
        private const val SELECTION_PAD_DP = 8f
        private const val FLASH_PAD = 8f
        private const val FLASH_MS = 4000L
        private const val DUPLICATE_OFFSET = 24f
        private const val MIN_ZOOM = 0.5f

        /** Finger places kept for the speed at which they let go, and how far back it looks. */
        private const val SAMPLES = 16
        private const val FLING_WINDOW_MS = 90L

        /** Fingers resting this long before letting go: no fling. */
        private const val FLING_STILL_MS = 60L

        /** Time constant of the slowing down (s): a page runs on v·τ after the fingers. */
        private const val FLING_TAU = 0.4f

        /** Slower than this (dp/s) the pages do not run on, and a run ends. */
        private const val FLING_MIN_DP = 250f
        private const val FLING_STOP_DP = 20f

        /** How far past their edges the pages go sideways: this share of the view's width. */
        private const val SIDE_PAN = 0.5f

        /** A finger scroll this much more along one axis than the other keeps to that axis. */
        private const val AXIS_RATIO = 2f

        /** Most of the view's width [pageShift] may keep free beside the pages. */
        const val MAX_PAGE_SHIFT = 0.45f
        private const val MAX_ZOOM = 6f
        private const val TAP_TIMEOUT_MS = 300L
        private const val SELECTION_COLOR = 0xFF1E5BD8.toInt()
        private const val HANDLE_DP = 9f
        private const val HANDLE_GRAB_DP = 28f

        /** Radius of the eyedropper's colour circle. */
        private const val LOUPE_DP = 34f
        private const val ROTATE_HANDLE_DP = 30f
        private const val SNAP_DEGREES = 15f
        private const val SNAP_TOLERANCE = 4f
        private const val MIN_RESIZE = 0.1f

        /** Side handles, a little smaller than the corner one. */
        private const val SIDE_HANDLE = 0.8f
        private const val MAX_RESIZE = 10f

        /** Hold-to-snap: pen still (within the slop) for this long at the end of a stroke. */
        const val HOLD_MS = 550L
        private const val HOLD_SLOP_DP = 6f

        /** Spacing of generated inputs along snapped shapes, in page units. */
        private const val SHAPE_STEP = 2f

        /** A finger held this long without moving starts a lasso. */
        private const val LONG_PRESS_MS = 450L

        /** A pen trace shorter than this (on screen) is a tap. */
        private const val TAP_TRACE_DP = 8f

        /** How far from a line a tap still hits it. */
        private const val TAP_SLOP_DP = 10f

        /** How far (on screen) line ends, corners and moved things are pulled to stick. */
        private const val MAGNET_DP = 16f
        private const val MAX_ANCHORS = 64

        /** An end of a line this close (dp) to the selection is attached to it; at most so many lines follow. */
        private const val CONNECT_DP = 6f
        private const val MAX_CONNECTORS = 100

        /** Space kept between writing and a closed gap or the page's bottom edge, and where moved-over writing starts on a new page. */
        private const val SPACE_GAP = 6f
        private const val SPACE_TOP = 40f

        /** Width of the picture a surface answer is drawn into. */
        private const val SURFACE_PX = 900

        /** Objects a dragged selection is lined up with (beyond that, the first ones). */
        private const val MAX_GUIDE_TARGETS = 400

        /** Guides catch the selection from a little closer than line ends do. */
        private const val GUIDE_REACH = 0.6f
        private const val GUIDE_COLOR = 0xFFE040FB.toInt()
        private const val SNAP_RING_DP = 9f
        private const val SNAP_COLOR = 0xFFE65100.toInt()
        private const val CORNER_DP = 6f

        /** How close to the ruler's edge (on screen) the pen draws along it. */
        private const val RULER_REACH_DP = 22f
        private const val MIN_RULER_LINE = 2f

        /** Even line in place of a fountain pen: about its average width. */
        private const val FOUNTAIN_TO_LINE = 0.5f

        /** Lines that can be turned into one another ([restyleSelection]); markers cannot. */
        /** The compass draws nothing smaller; an arc shorter than this is taken for a whole circle. */
        const val MIN_COMPASS_RADIUS = 4f

        /** How far from a word the pen may land to start picking PDF text. */
        const val PDF_WORD_REACH_DP = 24f
        const val MIN_ARC_DEGREES = 25f

        val LINE_KINDS = setOf(InkKind.PEN, InkKind.LINE, InkKind.PENCIL, InkKind.DASHED, InkKind.FOUNTAIN, InkKind.NEON)

        /** Widths the selection's line width can be set to (page units, as the line shows). */
        const val MIN_LINE_WIDTH = 0.5f
        const val MAX_LINE_WIDTH = 40f
        private const val ARROW_HALF_WIDTH = 0.34f

        /** A hint band is this much taller than the text it is fitted over. */
        private const val SPOILER_COVER = 1.3f

        fun isPen(toolType: Int) =
            toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
    }
}
