package dev.inknotes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.inknotes.math.GraphMath
import dev.inknotes.math.GraphSpec
import kotlin.math.roundToInt

/**
 * Draws a graph: grid, axes with numbers, the curves, where they cross (signed), the writer's
 * points, a legend, and the value a finger asked for. One painter for the graph's window and
 * for its picture in the notebook, so the picture is what the window showed. [unit]: pixels
 * in one dp (sizes of text and lines).
 */
class GraphPainter(private val unit: Float) {

    /** The view: x from [x0] to [x1], y from [y0] to [y1]. */
    class View(val x0: Double, val x1: Double, val y0: Double, val y1: Double) {
        val w get() = x1 - x0
        val h get() = y1 - y0
    }

    class Colors(val background: Int, val ink: Int, val grid: Int, val accent: Int, val dark: Boolean)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun curveColor(k: Int, dark: Boolean): Int = when (k) {
        0 -> if (dark) 0xFF82AAFF.toInt() else 0xFF1E5BD8.toInt()
        1 -> if (dark) 0xFFF0A23A.toInt() else 0xFFC77700.toInt()
        2 -> if (dark) 0xFF5BC98A.toInt() else 0xFF2E8B57.toInt()
        else -> if (dark) 0xFFE57AB0.toInt() else 0xFFB0306E.toInt()
    }

    fun draw(
        c: Canvas,
        w: Float,
        h: Float,
        v: View,
        fns: List<(Double) -> Double>,
        titles: List<String>,
        marks: List<GraphMath.Mark>,
        points: List<Pair<Double, Double>>,
        probe: Pair<Double, Int>?,
        colors: Colors,
    ) {
        if (w <= 1 || h <= 1 || !(v.w > 0) || !(v.h > 0)) return
        fun px(x: Double) = ((x - v.x0) / v.w * w).toFloat()
        fun py(y: Double) = ((v.y1 - y) / v.h * h).toFloat()
        fill.color = colors.background
        c.drawRect(0f, 0f, w, h, fill)
        c.save()
        c.clipRect(0f, 0f, w, h)

        // Grid and the numbers on it.
        text.textSize = 11 * unit
        text.isFakeBoldText = false
        text.color = (colors.ink and 0x00FFFFFF) or 0xB0000000.toInt()
        val sx = GraphMath.step(v.w, (w / (70 * unit)).roundToInt().coerceIn(3, 12))
        val sy = GraphMath.step(v.h, (h / (50 * unit)).roundToInt().coerceIn(3, 10))
        val axisY = py(0.0).coerceIn(0f, h - text.textSize - 2)
        val axisX = px(0.0).coerceIn(0f, w - 4 * text.textSize)
        line.pathEffect = null
        line.color = colors.grid
        line.strokeWidth = 1f
        for (x in GraphMath.ticks(v.x0, v.x1, sx)) {
            val p = px(x)
            c.drawLine(p, 0f, p, h, line)
            if (x != 0.0) c.drawText(GraphMath.label(x, sx), p + 3, axisY + text.textSize + 1, text)
        }
        for (y in GraphMath.ticks(v.y0, v.y1, sy)) {
            val p = py(y)
            c.drawLine(0f, p, w, p, line)
            if (y != 0.0) c.drawText(GraphMath.label(y, sy), axisX + 4, p - 3, text)
        }
        line.color = colors.ink
        line.strokeWidth = 1.5f * unit
        if (0.0 in v.y0..v.y1) c.drawLine(0f, py(0.0), w, py(0.0), line)
        if (0.0 in v.x0..v.x1) c.drawLine(px(0.0), 0f, px(0.0), h, line)

        // The curves.
        val curves = runCatching { GraphMath.curves(fns, v.x0, v.x1, v.y0, v.y1, (w / 2).toInt().coerceIn(200, 1200)) }.getOrDefault(emptyList())
        line.strokeWidth = 2.5f * unit
        for ((i, parts) in curves.withIndex()) {
            line.color = curveColor(i, colors.dark)
            for (seg in parts) {
                path.rewind()
                for (k in seg.indices step 2) {
                    val x = seg[k] * w
                    val y = (1 - seg[k + 1]) * h
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                c.drawPath(path, line)
            }
        }

        // Where the curves cross, signed.
        var signed = 0
        for (m in marks) {
            if (m.x !in v.x0..v.x1 || m.y !in v.y0..v.y1) continue
            val x = px(m.x)
            val y = py(m.y)
            fill.color = colors.background
            c.drawCircle(x, y, 5 * unit, fill)
            line.color = if (m.kind == GraphMath.Kind.CROSS) colors.ink else curveColor(m.a, colors.dark)
            line.strokeWidth = 2 * unit
            c.drawCircle(x, y, 5 * unit, line)
            if (signed++ < 12) label(c, "(${GraphMath.label(m.x, sx)}; ${GraphMath.label(m.y, sy)})", x, y, w, colors.ink, colors.background)
        }
        // The writer's points.
        for ((px0, py0) in points) {
            if (px0 !in v.x0..v.x1 || py0 !in v.y0..v.y1) continue
            val x = px(px0)
            val y = py(py0)
            fill.color = colors.accent
            c.drawCircle(x, y, 5.5f * unit, fill)
            label(c, "(${GraphMath.label(px0, sx)}; ${GraphMath.label(py0, sy)})", x, y, w, colors.accent, colors.background)
        }
        // The value a finger asked for.
        probe?.let { (x, i) ->
            val y = runCatching { fns[i](x) }.getOrDefault(Double.NaN)
            if (!y.isFinite()) return@let
            val p = px(x)
            val q = py(y)
            line.color = (colors.ink and 0x00FFFFFF) or 0x99000000.toInt()
            line.strokeWidth = 1.5f * unit
            line.pathEffect = DashPathEffect(floatArrayOf(6 * unit, 4 * unit), 0f)
            c.drawLine(p, 0f, p, h, line)
            c.drawLine(0f, q, w, q, line)
            line.pathEffect = null
            fill.color = curveColor(i, colors.dark)
            c.drawCircle(p, q, 6 * unit, fill)
            label(c, "x = ${GraphMath.label(x, sx / 10)}, y = ${GraphMath.label(y, sy / 10)}", p, q, w, curveColor(i, colors.dark), colors.background, bold = true)
        }
        // Which curve is which.
        if (titles.isNotEmpty()) {
            text.textSize = 12 * unit
            text.isFakeBoldText = false
            var y = 8 * unit + text.textSize
            val boxW = titles.maxOf { text.measureText(it) } + 30 * unit
            fill.color = (colors.background and 0x00FFFFFF) or 0xE0000000.toInt()
            c.drawRect(RectF(4 * unit, 4 * unit, 4 * unit + boxW, 8 * unit + titles.size * text.textSize * 1.35f + 2 * unit), fill)
            for ((i, t) in titles.withIndex()) {
                line.color = curveColor(i, colors.dark)
                line.strokeWidth = 3 * unit
                c.drawLine(10 * unit, y - text.textSize * 0.35f, 22 * unit, y - text.textSize * 0.35f, line)
                text.color = colors.ink
                c.drawText(t, 28 * unit, y, text)
                y += text.textSize * 1.35f
            }
        }
        c.restore()
    }

    /** A label beside a point, on a backing so the grid does not cross it. */
    private fun label(c: Canvas, s: String, at: Float, atY: Float, w: Float, color: Int, back: Int, bold: Boolean = false) {
        text.textSize = 11 * unit
        text.isFakeBoldText = bold
        val tw = text.measureText(s)
        val x = (at + 8 * unit).coerceAtMost(w - tw - 4).coerceAtLeast(2f)
        val y = (atY - 8 * unit).coerceAtLeast(text.textSize + 2)
        fill.color = (back and 0x00FFFFFF) or 0xD8000000.toInt()
        c.drawRect(x - 2, y - text.textSize, x + tw + 2, y + 3, fill)
        text.color = color
        c.drawText(s, x, y, text)
        text.isFakeBoldText = false
    }

    companion object {
        /**
         * The picture of [spec], [width]×[height] px, for the notebook: on light or [dark]
         * paper, with its crossings and points. The view is the one the window ended on.
         */
        fun picture(spec: GraphSpec, width: Int, height: Int, dark: Boolean): Bitmap {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val unit = width / 420f
            val fns = spec.fns()
            val marks = runCatching { GraphMath.marks(fns, spec.x0, spec.x1) }.getOrDefault(emptyList())
            val colors = if (dark) {
                Colors(0xF2202124.toInt(), 0xFFE8EAED.toInt(), 0x33FFFFFF, 0xFFF48FB1.toInt(), true)
            } else {
                Colors(0xF8FFFFFF.toInt(), 0xFF202124.toInt(), 0x22000000, 0xFFD81B60.toInt(), false)
            }
            GraphPainter(unit).draw(
                Canvas(bmp), width.toFloat(), height.toFloat(), View(spec.x0, spec.x1, spec.y0, spec.y1),
                fns, spec.titles, marks, spec.points, null, colors,
            )
            return bmp
        }
    }
}
