package dev.inknotes.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesView
import dev.inknotes.data.InkTextures
import kotlin.math.max
import kotlin.math.min

/**
 * The zoom window: a strip at the bottom of the editor showing a small part of the page
 * ([region], page units) magnified. The pen writes large in it and the line lands small on the
 * page; a finger drags the part shown. When the writing nears the right edge the part moves on,
 * like the zoom box of paper-notebook apps.
 */
@SuppressLint("ViewConstructor")
class ZoomPad(context: Context, private val host: PageEditorView) : FrameLayout(context) {

    private val density = resources.displayMetrics.density

    /** Page of the part shown, and the part itself (page units). */
    var page = 0
        private set
    val region = RectF()

    /** How many times larger than the page fitted to the width (2…6). */
    var magnification = DEFAULT_MAGNIFICATION
        private set

    val ink = InProgressStrokesView(context).apply { textureBitmapStore = InkTextures.delegate }
    private val surface = Surface(context)

    private val barHeight get() = BAR_DP * density
    private val area = RectF()
    private val pageToPad = Matrix()
    private val padToPage = Matrix()

    private var strokeId: InProgressStrokeId? = null
    private var pointerId = -1
    private var strokeRight = Float.NEGATIVE_INFINITY
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private var pressed = -1

    private enum class Button(val glyph: String, val hint: String) {
        LEFT("◀", "Левее"),
        RIGHT("▶", "Правее"),
        UP("▲", "Выше"),
        DOWN("▼", "Ниже"),
        NEW_LINE("↵", "Новая строка"),
        LESS("−", "Меньше"),
        MORE("+", "Крупнее"),
        CLOSE("✕", "Закрыть окно увеличения"),
    }

    private val buttonRects = Button.entries.associateWith { RectF() }

    init {
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ink, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentDescription = "Окно увеличения"
    }

    /** Shows [page] from [x], [y] (page units, the top left of the part shown). */
    fun place(page: Int, x: Float, y: Float) {
        this.page = page.coerceIn(0, max(0, host.pageCount - 1))
        val w = regionWidth()
        val h = regionHeight(w)
        region.set(x, y, x + w, y + h)
        clamp()
        changed()
    }

    fun redraw() = surface.invalidate()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (region.width() > 0f) place(page, region.left, region.top)
    }

    private fun regionWidth() = host.pageWidthOf(page) / magnification

    private fun regionHeight(w: Float): Float {
        val aw = max(1f, width.toFloat())
        val ah = max(1f, height - barHeight)
        return w * ah / aw
    }

    private fun clamp() {
        val pw = host.pageWidthOf(page)
        val ph = host.pageHeightOf(page)
        if (region.width() > pw) region.offsetTo(0f, region.top)
        else region.offsetTo(region.left.coerceIn(0f, pw - region.width()), region.top)
        if (region.height() > ph) region.offsetTo(region.left, 0f)
        else region.offsetTo(region.left, region.top.coerceIn(0f, ph - region.height()))
    }

    private fun changed() {
        area.set(0f, barHeight, width.toFloat(), height.toFloat())
        val s = if (region.width() > 0f) area.width() / region.width() else 1f
        pageToPad.reset()
        pageToPad.setTranslate(-region.left, -region.top)
        pageToPad.postScale(s, s)
        pageToPad.postTranslate(0f, barHeight)
        pageToPad.invert(padToPage)
        host.showZoomRegion(page, region, height)
        surface.invalidate()
    }

    private fun moveBy(dx: Float, dy: Float) {
        region.offset(dx, dy)
        clamp()
        changed()
    }

    /** The next line: back to the left margin, one window lower (the next page after the last line). */
    private fun newLine() {
        val margin = host.pageWidthOf(page) * LEFT_MARGIN
        val next = region.top + region.height() * LINE_STEP
        if (next + region.height() > host.pageHeightOf(page) + region.height() * 0.5f && page + 1 < host.pageCount) {
            page += 1
            place(page, margin, host.pageHeightOf(page) * TOP_MARGIN)
        } else {
            region.offsetTo(margin, next)
            clamp()
            changed()
        }
    }

    private fun press(b: Button) {
        when (b) {
            Button.LEFT -> moveBy(-region.width() * STEP, 0f)
            Button.RIGHT -> moveBy(region.width() * STEP, 0f)
            Button.UP -> moveBy(0f, -region.height() * STEP)
            Button.DOWN -> moveBy(0f, region.height() * STEP)
            Button.NEW_LINE -> newLine()
            Button.LESS, Button.MORE -> {
                magnification = (magnification + if (b == Button.MORE) 0.5f else -0.5f).coerceIn(MIN_MAGNIFICATION, MAX_MAGNIFICATION)
                val cx = region.left
                val cy = region.top
                val w = regionWidth()
                region.set(cx, cy, cx + w, cy + regionHeight(w))
                clamp()
                changed()
            }
            Button.CLOSE -> host.closeZoomPad()
        }
    }

    // ---------------------------------------------------------------- touch

    /** Events of the pad (coordinates of the pad); the editor hands them on. */
    fun handleTouch(e: MotionEvent): Boolean {
        val index = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (strokeId != null || dragging) return true
                val x = e.getX(index)
                val y = e.getY(index)
                if (y < barHeight) {
                    pressed = Button.entries.indexOfFirst { buttonRects[it]!!.contains(x, y) }
                    surface.invalidate()
                    return true
                }
                if (host.isPenTool(e, index) && host.canWriteInPad()) {
                    requestUnbufferedDispatch(e)
                    pointerId = e.getPointerId(index)
                    strokeId = host.startPadStroke(ink, e, pointerId, page, padToPage)
                    strokeRight = x
                } else {
                    dragging = true
                    pointerId = e.getPointerId(index)
                    lastX = x
                    lastY = y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i < 0) return true
                val id = strokeId
                if (id != null) {
                    ink.addToStroke(e, pointerId, id)
                    strokeRight = max(strokeRight, e.getX(i))
                } else if (dragging) {
                    val s = region.width() / max(1f, area.width())
                    moveBy(-(e.getX(i) - lastX) * s, -(e.getY(i) - lastY) * s)
                    lastX = e.getX(i)
                    lastY = e.getY(i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(index) != pointerId && pressed < 0) return true
                if (pressed >= 0) {
                    val b = Button.entries[pressed]
                    pressed = -1
                    if (buttonRects[b]!!.contains(e.getX(index), e.getY(index))) press(b)
                    surface.invalidate()
                    return true
                }
                val id = strokeId
                strokeId = null
                dragging = false
                if (id != null) {
                    ink.finishStroke(e, pointerId, id)
                    // Writing reached the right part of the window: the window moves on.
                    if (strokeRight > area.left + area.width() * ADVANCE_AT) {
                        val pw = host.pageWidthOf(page)
                        if (region.right < pw - 1f) moveBy(min(region.width() * ADVANCE_BY, pw - region.right), 0f)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                strokeId?.let { ink.cancelStroke(it, e) }
                strokeId = null
                dragging = false
                pressed = -1
                surface.invalidate()
            }
        }
        return true
    }

    // --------------------------------------------------------------- drawing

    private inner class Surface(context: Context) : View(context) {
        private val barPaint = Paint().apply { color = 0xF0263238.toInt() }
        private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
        }
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ZOOM_COLOR
        }
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x552979FF
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(host.deskColor())
            canvas.save()
            canvas.clipRect(area)
            host.drawPageRegion(canvas, page, pageToPad, region)
            canvas.restore()
            // Past this line the window moves on by itself.
            guidePaint.strokeWidth = 1.5f * density
            guidePaint.pathEffect = DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
            val gx = area.left + area.width() * ADVANCE_AT
            canvas.drawLine(gx, area.top, gx, area.bottom, guidePaint)
            edgePaint.strokeWidth = 2 * density
            canvas.drawLine(0f, area.top, width.toFloat(), area.top, edgePaint)
            drawBar(canvas)
        }

        private fun drawBar(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), barHeight, barPaint)
            val size = barHeight - 8 * density
            val gap = 6 * density
            var x = 8 * density
            glyphPaint.textSize = 18 * density
            for (b in Button.entries) {
                val r = buttonRects[b]!!
                if (b == Button.CLOSE) r.set(width - 8 * density - size, 4 * density, width - 8 * density, 4 * density + size)
                else {
                    r.set(x, 4 * density, x + size, 4 * density + size)
                    x += size + gap
                }
                buttonPaint.color = if (pressed == b.ordinal) 0x66FFFFFF else 0x33FFFFFF
                canvas.drawRoundRect(r, 8 * density, 8 * density, buttonPaint)
                canvas.drawText(b.glyph, r.centerX(), r.centerY() + glyphPaint.textSize * 0.35f, glyphPaint)
            }
            glyphPaint.textSize = 13 * density
            glyphPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("×" + "%.1f".format(magnification).replace(".0", ""), x + gap, barHeight / 2 + 5 * density, glyphPaint)
            glyphPaint.textAlign = Paint.Align.CENTER
        }
    }

    companion object {
        const val BAR_DP = 44f
        const val DEFAULT_MAGNIFICATION = 3f
        const val MIN_MAGNIFICATION = 1.5f
        const val MAX_MAGNIFICATION = 6f

        /** Share of the window past which a line moves the window on, and by how much of it. */
        const val ADVANCE_AT = 0.72f
        const val ADVANCE_BY = 0.55f
        const val STEP = 0.5f
        const val LINE_STEP = 0.85f
        const val LEFT_MARGIN = 0.06f
        const val TOP_MARGIN = 0.05f

        /** Frame of the part shown, on the page and over the window. */
        const val ZOOM_COLOR = 0xFF2979FF.toInt()

        /** Height of the window: a share of the editor, at least this many dp. */
        const val HEIGHT_SHARE = 0.36f
        const val MIN_HEIGHT_DP = 200f
    }
}
