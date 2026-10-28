package dev.inknotes.editor

import android.graphics.RectF
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageStroke
import java.util.WeakHashMap
import kotlin.math.hypot

/**
 * Lines that stick together. Strokes made of straight pieces (ideal shapes, stamps, tables,
 * lines along the ruler) have connection points at their corners and ends; a line drawn,
 * a corner dragged or a selection moved near them snaps onto such a point, or else onto the
 * nearest point of a line. Page coordinates; main thread only.
 */
object Magnet {

    /** Where something snapped to: a connection point, or a point on a line. */
    class Snap(val x: Float, val y: Float, val point: Boolean)

    private val polylines = WeakHashMap<PageStroke, FloatArray>()
    private val notPolyline: MutableSet<PageStroke> = java.util.Collections.newSetFromMap(WeakHashMap())

    /**
     * The corners of [s] (x, y pairs; a closed shape repeats its first corner) if it is made
     * of a few straight pieces, else null (handwriting, circles).
     */
    fun polyline(s: PageStroke): FloatArray? {
        polylines[s]?.let { return it }
        if (s in notPolyline || s.kind == InkKind.HIGHLIGHTER || s.kind == InkKind.SPOILER) return null
        val found = findPolyline(s)
        if (found == null) notPolyline += s else polylines[s] = found
        return found
    }

    private fun findPolyline(s: PageStroke): FloatArray? {
        val inputs = s.stroke.inputs
        val n = inputs.size
        if (n < 2 || n > MAX_INPUTS) return null
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val p = StrokeInput()
        var pMin = Float.MAX_VALUE
        var pMax = -Float.MAX_VALUE
        for (i in 0 until n) {
            inputs.populate(i, p)
            xs[i] = p.x
            ys[i] = p.y
            pMin = minOf(pMin, p.pressure)
            pMax = maxOf(pMax, p.pressure)
        }
        // Lines the app draws itself have one steady pressure; a hand never does.
        if (pMax - pMin > 0.001f) return null
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        rdp(xs, ys, 0, n - 1, EPSILON, keep)
        val count = keep.count { it }
        if (count > MAX_CORNERS) return null
        val out = FloatArray(count * 2)
        var k = 0
        for (i in 0 until n) if (keep[i]) {
            out[k++] = xs[i]
            out[k++] = ys[i]
        }
        // Long enough to connect to: not a dot.
        if (hypot(out[0] - out[2], out[1] - out[3]) < 2f && count == 2) return null
        return out
    }

    private fun rdp(xs: FloatArray, ys: FloatArray, a: Int, b: Int, eps: Float, keep: BooleanArray) {
        if (b <= a + 1) return
        var best = -1f
        var idx = -1
        for (i in a + 1 until b) {
            val d = PageEdits.distanceToSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
            if (d > best) {
                best = d
                idx = i
            }
        }
        if (best > eps) {
            keep[idx] = true
            rdp(xs, ys, a, idx, eps, keep)
            rdp(xs, ys, idx, b, eps, keep)
        }
    }

    /** Connection points of [s]: the corners and ends of its pieces. */
    fun points(s: PageStroke): FloatArray? {
        val pl = polyline(s) ?: return null
        val closed = pl.size >= 6 && hypot(pl[0] - pl[pl.size - 2], pl[1] - pl[pl.size - 1]) < 0.5f
        return if (closed) pl.copyOf(pl.size - 2) else pl
    }

    /** Corners of a small solid (an arrowhead): its tip snaps like a line's end. */
    fun points(f: PageFill): FloatArray? = f.outline.takeIf { f.cuts.isEmpty() && it.size <= 8 }

    /** What [targets] offer to snap to, gathered once for a gesture. */
    class Targets(val points: FloatArray, val lines: List<FloatArray>) {
        val isEmpty get() = points.isEmpty() && lines.isEmpty()

        /** Nearest connection point within [radius], else the nearest point on a line within [radius] · 0.7. */
        fun snap(x: Float, y: Float, radius: Float): Snap? {
            var best = radius
            var bx = 0f
            var by = 0f
            var found = false
            for (i in points.indices step 2) {
                val d = hypot(points[i] - x, points[i + 1] - y)
                if (d < best) {
                    best = d
                    bx = points[i]
                    by = points[i + 1]
                    found = true
                }
            }
            if (found) return Snap(bx, by, true)
            best = radius * LINE_REACH
            for (pl in lines) {
                for (i in 0 until pl.size - 2 step 2) {
                    val ax = pl[i]
                    val ay = pl[i + 1]
                    val dx = pl[i + 2] - ax
                    val dy = pl[i + 3] - ay
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
                    val px = ax + t * dx
                    val py = ay + t * dy
                    val d = hypot(px - x, py - y)
                    if (d < best) {
                        best = d
                        bx = px
                        by = py
                        found = true
                    }
                }
            }
            return if (found) Snap(bx, by, false) else null
        }
    }

    /** Connection points and lines of the straight-piece strokes in [strokes] near [area] (all when null). */
    fun targets(strokes: List<PageStroke>, area: RectF? = null, except: (PageStroke) -> Boolean = { false }): Targets {
        val pts = ArrayList<Float>()
        val lines = ArrayList<FloatArray>()
        for (s in strokes) {
            if (except(s)) continue
            if (area != null) {
                val b = PageCache.strokeBounds(s) ?: continue
                if (!RectF.intersects(b, area)) continue
            }
            val pl = polyline(s) ?: continue
            lines += pl
            points(s)?.let { for (v in it) pts += v }
        }
        return Targets(pts.toFloatArray(), lines)
    }

    /** A stroke along [points] with the brush and kind of [template], evenly sampled at a steady pressure. */
    fun restroke(template: PageStroke, points: FloatArray): PageStroke {
        val batch = MutableStrokeInputBatch()
        var t = 0L
        fun add(x: Float, y: Float) {
            batch.add(InputToolType.STYLUS, x, y, t, pressure = 0.6f)
            t += 4
        }
        add(points[0], points[1])
        for (i in 2 until points.size step 2) {
            val x0 = points[i - 2]
            val y0 = points[i - 1]
            val n = (hypot(points[i] - x0, points[i + 1] - y0) / STEP).toInt().coerceAtLeast(1)
            for (k in 1..n) add(x0 + (points[i] - x0) * k / n, y0 + (points[i + 1] - y0) * k / n)
        }
        return PageStroke(template.kind, Stroke(template.stroke.brush, batch))
    }

    private const val STEP = 2f
    private const val EPSILON = 0.6f
    private const val MAX_CORNERS = 16
    private const val MAX_INPUTS = 4000

    /** Lines pull from a little less far than points, so a point near a line still wins. */
    private const val LINE_REACH = 0.7f
}
