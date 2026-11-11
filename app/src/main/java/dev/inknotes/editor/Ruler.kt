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

    /**
     * A protractor instead of the straight ruler: a half disc over its base line, centre at
     * ([cx], [cy]). The pen draws a straight line along the base or an arc along the round edge.
     */
    var protractor = false

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
        if (protractor) {
            val inDisc = l[1] <= 0f && l[0] * l[0] + l[1] * l[1] <= (RADIUS + margin) * (RADIUS + margin)
            val inBase = l[1] in 0f..BASE + margin && abs(l[0]) <= RADIUS + margin
            return inDisc || inBase
        }
        return abs(l[0]) <= length / 2 + margin && abs(l[1]) <= width / 2 + margin
    }

    /**
     * Angle (degrees, 0…180) of ([x], [y]) seen from the protractor's centre: 0 along the base
     * to the right, 90 straight up from it.
     */
    fun arcAngle(x: Float, y: Float): Float {
        val l = local(x, y)
        val a = Math.toDegrees(kotlin.math.atan2(-l[1].toDouble(), l[0].toDouble())).toFloat()
        return if (a < 0f) (if (a < -90f) 180f else 0f) else a
    }

    /** World point on the protractor's round edge at [angle] degrees, [out] beyond the edge. */
    fun arcPoint(angle: Float, out: Float): FloatArray {
        val r = RADIUS + out
        val t = Math.toRadians(angle.toDouble())
        return world((r * kotlin.math.cos(t)).toFloat(), (-r * kotlin.math.sin(t)).toFloat())
    }

    /** Offset across the ruler of the line drawn along [edge], [offset] outside it. */
    fun edgeLine(edge: Int, offset: Float): Float = if (protractor) BASE + offset else edge * (width / 2 + offset)

    /**
     * The edge a pen at ([x], [y]) draws along: -1 (top), +1 (bottom) when within [reach]
     * of it or on the ruler itself (the nearer edge), 0 when away from the ruler.
     */
    fun edgeNear(x: Float, y: Float, reach: Float): Int {
        if (!shown) return 0
        val l = local(x, y)
        if (protractor) {
            val r = kotlin.math.hypot(l[0], l[1])
            if (abs(l[0]) > RADIUS + reach) return 0
            if (l[1] > 0f) return if (l[1] <= BASE + reach) 1 else 0
            if (r > RADIUS + reach) return 0
            // On the disc: the nearer of the round edge and the base.
            return if (RADIUS - r < BASE - l[1]) ARC else 1
        }
        if (abs(l[0]) > length / 2 + reach) return 0
        val d = abs(l[1])
        if (d > width / 2 + reach) return 0
        return if (l[1] < 0f) -1 else 1
    }

    /** ([x], [y]) moved onto [edge], [offset] outside it (half the line width). */
    fun onEdge(x: Float, y: Float, edge: Int, offset: Float): FloatArray {
        val l = local(x, y)
        return world(l[0], edgeLine(edge, offset))
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
    fun draw(canvas: Canvas, dark: Boolean, pixel: Float, showAngle: Boolean, pointer: FloatArray? = null) {
        if (!shown) return
        if (protractor) return drawProtractor(canvas, dark, pixel, showAngle, pointer)
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

    private val arcPath = android.graphics.Path()

    private fun drawProtractor(canvas: Canvas, dark: Boolean, pixel: Float, showAngle: Boolean, pointer: FloatArray?) {
        val mm = 1f / Notebook.MM_PER_UNIT
        val ink = if (dark) 0xDDE0E6EE.toInt() else 0xDD2A3442.toInt()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angle)
        arcPath.rewind()
        arcPath.moveTo(-RADIUS, BASE)
        arcPath.lineTo(-RADIUS, 0f)
        arcPath.arcTo(RectF(-RADIUS, -RADIUS, RADIUS, RADIUS), 180f, 180f)
        arcPath.lineTo(RADIUS, BASE)
        arcPath.close()
        body.style = Paint.Style.FILL
        body.color = if (dark) 0x7A3A4250 else 0x8CE8EEF5.toInt()
        canvas.drawPath(arcPath, body)
        body.style = Paint.Style.STROKE
        body.strokeWidth = 1.5f * pixel
        body.color = if (dark) 0xCC9AA8BA.toInt() else 0xCC5A6B80.toInt()
        canvas.drawPath(arcPath, body)
        ticks.color = ink
        labels.color = ink
        // Degree marks along the round edge; outer numbers from the right, inner from the left.
        for (d in 0..180) {
            val t = Math.toRadians(d.toDouble())
            val c = kotlin.math.cos(t).toFloat()
            val sn = -kotlin.math.sin(t).toFloat()
            val len = when {
                d % 10 == 0 -> 6f * mm
                d % 5 == 0 -> 4f * mm
                else -> 2.2f * mm
            }
            ticks.strokeWidth = (if (d % 10 == 0) 1.4f else 1f) * pixel
            canvas.drawLine(c * RADIUS, sn * RADIUS, c * (RADIUS - len), sn * (RADIUS - len), ticks)
            if (d % 10 == 0) {
                labels.textSize = 3.2f * mm
                val r1 = RADIUS - 9f * mm
                canvas.drawText("$d", c * r1, sn * r1 + labels.textSize * 0.35f, labels)
                labels.textSize = 2.6f * mm
                val r2 = RADIUS - 14f * mm
                if (d in 10..170) canvas.drawText("${180 - d}", c * r2, sn * r2 + labels.textSize * 0.35f, labels)
            }
        }
        // Base line and the centre the angles are measured from.
        ticks.strokeWidth = 1f * pixel
        canvas.drawLine(-RADIUS, 0f, RADIUS, 0f, ticks)
        canvas.drawLine(0f, -3f * mm, 0f, 3f * mm, ticks)
        canvas.drawCircle(0f, 0f, 1f * mm, ticks)
        if (showAngle) {
            labels.textSize = 4.5f * mm
            canvas.drawText("${Math.round(((angle % 360f) + 360f) % 360f)}°", 0f, BASE + 6f * mm, labels)
        }
        canvas.restore()
        // The pen over the protractor: a ray from the centre and the angle it makes with the base.
        if (pointer != null) {
            val l = local(pointer[0], pointer[1])
            if (l[1] <= 0f && kotlin.math.hypot(l[0], l[1]) <= RADIUS * 1.6f) {
                val a = arcAngle(pointer[0], pointer[1])
                val end = arcPoint(a, 0f)
                ticks.color = 0xFF2979FF.toInt()
                ticks.strokeWidth = 1.5f * pixel
                canvas.drawLine(cx, cy, end[0], end[1], ticks)
                labels.color = 0xFF2979FF.toInt()
                labels.textSize = 5f * mm
                val at = arcPoint(a, 6f * mm)
                canvas.drawText("${Math.round(a)}°", at[0], at[1] + labels.textSize * 0.35f, labels)
            }
        }
    }

    companion object {
        /** The round edge of the protractor: radius and the strip under the base line. */
        val RADIUS = 75f / Notebook.MM_PER_UNIT
        val BASE = 8f / Notebook.MM_PER_UNIT

        /** [edgeNear] for the protractor's round edge. */
        const val ARC = -2

        /** About 20 cm by 3 cm of paper. */
        val LENGTH = 200f / Notebook.MM_PER_UNIT
        val WIDTH = 30f / Notebook.MM_PER_UNIT
        private const val ANGLE_SNAP = 2.5f
    }
}
