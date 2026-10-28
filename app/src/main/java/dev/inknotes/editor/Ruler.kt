package dev.inknotes.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.inknotes.data.Notebook
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * A see-through ruler lying over the pages (world coordinates, so it zooms with them):
 * fingers move and turn it, a pen run along either long edge draws a straight line there.
 * Marked in millimetres of paper.
 */
class Ruler {
    var shown = false

    /** Centre and turn (degrees, clockwise) in world units. */
    var cx = 0f
    var cy = 0f
    var angle = 0f

    val length = LENGTH
    val width = WIDTH

    private val ux get() = cos(Math.toRadians(angle.toDouble())).toFloat()
    private val uy get() = sin(Math.toRadians(angle.toDouble())).toFloat()

    /** Point in the ruler's own frame: along its length (0 at the centre) and across it. */
    fun local(x: Float, y: Float): FloatArray {
        val dx = x - cx
        val dy = y - cy
        return floatArrayOf(dx * ux + dy * uy, -dx * uy + dy * ux)
    }

    fun world(along: Float, across: Float): FloatArray =
        floatArrayOf(cx + along * ux - across * uy, cy + along * uy + across * ux)

    /** True when ([x], [y]) is on the ruler, or within [margin] of it. */
    fun hit(x: Float, y: Float, margin: Float = 0f): Boolean {
        if (!shown) return false
        val l = local(x, y)
        return abs(l[0]) <= length / 2 + margin && abs(l[1]) <= width / 2 + margin
    }

    /**
     * The edge a pen at ([x], [y]) draws along: -1 (top), +1 (bottom) when within [reach]
     * of it or on the ruler itself (the nearer edge), 0 when away from the ruler.
     */
    fun edgeNear(x: Float, y: Float, reach: Float): Int {
        if (!shown) return 0
        val l = local(x, y)
        if (abs(l[0]) > length / 2 + reach) return 0
        val d = abs(l[1])
        if (d > width / 2 + reach) return 0
        return if (l[1] < 0f) -1 else 1
    }

    /** ([x], [y]) moved onto [edge], [offset] outside it (half the line width). */
    fun onEdge(x: Float, y: Float, edge: Int, offset: Float): FloatArray {
        val l = local(x, y)
        return world(l[0], edge * (width / 2 + offset))
    }

    /** Position along the ruler, for the extent of a line drawn on it. */
    fun along(x: Float, y: Float) = local(x, y)[0]

    /** Turn snapped to whole multiples of 45° when close to one. */
    fun snapAngle(a: Float): Float {
        val norm = ((a % 360f) + 540f) % 360f - 180f
        val nearest = round(norm / 45f) * 45f
        return if (abs(norm - nearest) < ANGLE_SNAP) nearest else norm
    }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ticks = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Draws the ruler on [canvas] in world units; [pixel] is one screen pixel in world units. */
    fun draw(canvas: Canvas, dark: Boolean, pixel: Float, showAngle: Boolean) {
        if (!shown) return
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angle)
        val r = RectF(-length / 2, -width / 2, length / 2, width / 2)
        body.style = Paint.Style.FILL
        body.color = if (dark) 0x7A3A4250 else 0x8CE8EEF5.toInt()
        canvas.drawRoundRect(r, 6f, 6f, body)
        body.style = Paint.Style.STROKE
        body.strokeWidth = 1.5f * pixel
        body.color = if (dark) 0xCC9AA8BA.toInt() else 0xCC5A6B80.toInt()
        canvas.drawRoundRect(r, 6f, 6f, body)
        val mm = 1f / Notebook.MM_PER_UNIT
        val ink = if (dark) 0xDDE0E6EE.toInt() else 0xDD2A3442.toInt()
        ticks.color = ink
        ticks.strokeWidth = 1.1f * pixel
        labels.color = ink
        labels.textSize = 3.2f * mm
        val count = (length / mm).toInt()
        val start = -count / 2
        for (i in start..count / 2) {
            val x = i * mm
            val h = when {
                i % 10 == 0 -> 5.5f * mm
                i % 5 == 0 -> 3.8f * mm
                else -> 2.2f * mm
            }
            // Marks on both edges, numbers in centimetres from the left end.
            canvas.drawLine(x, -width / 2, x, -width / 2 + h, ticks)
            canvas.drawLine(x, width / 2, x, width / 2 - h, ticks)
            if (i % 10 == 0) canvas.drawText("${(i - start) / 10}", x, labels.textSize * 0.35f, labels)
        }
        if (showAngle) {
            labels.textSize = 4.5f * mm
            canvas.drawText("${Math.round(((angle % 360f) + 360f) % 360f)}°", 0f, -width / 2 - 2.5f * mm, labels)
        }
        canvas.restore()
    }

    companion object {
        /** About 20 cm by 3 cm of paper. */
        val LENGTH = 200f / Notebook.MM_PER_UNIT
        val WIDTH = 30f / Notebook.MM_PER_UNIT
        private const val ANGLE_SNAP = 2.5f
    }
}
