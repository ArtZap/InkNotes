package dev.inknotes.editor

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Trimming, as in drawing programs: the part of a line touched is taken away up to where it
 * meets other lines or shapes on either side; a free end goes as far as the line's end. A line
 * that meets nothing goes whole. Plain geometry on polylines; the editor builds the strokes.
 *
 * A place on a polyline is a parameter s = i + t: segment i (from point i to i + 1), t of the
 * way along it.
 */
object Trim {

    /** A polyline: x and y of its points. */
    class Line(val xs: FloatArray, val ys: FloatArray) {
        val size get() = xs.size
        val left = xs.minOrNull() ?: 0f
        val right = xs.maxOrNull() ?: 0f
        val top = ys.minOrNull() ?: 0f
        val bottom = ys.maxOrNull() ?: 0f
    }

    /**
     * What is left of [target] when trimmed at the place nearest to ([x], [y]): the ranges of
     * the parameter to keep (empty: the whole line goes), or null when the line is farther than
     * [reach] from the touch. [others]: every other line and shape outline that can stop the
     * cut; an end of one that comes within [touch] of the target meets it too (hand-drawn
     * lines stop short of each other).
     */
    fun keep(target: Line, others: List<Line>, x: Float, y: Float, reach: Float, touch: Float): List<Pair<Float, Float>>? {
        if (target.size < 2) return null
        val (s0, d) = nearest(target, x, y)
        if (d > reach) return null
        val meets = contacts(target, others, touch)
        val end = (target.size - 1).toFloat()
        // Contacts right at the touch do not stop it: a cut always takes something away.
        val eps = 1e-3f
        val before = meets.filter { it < s0 - eps }.maxOrNull()
        val after = meets.filter { it > s0 + eps }.minOrNull()
        val out = ArrayList<Pair<Float, Float>>()
        if (before != null && before > eps) out += 0f to before
        if (after != null && after < end - eps) out += after to end
        return out
    }

    /** The parameter of the point of [line] nearest to ([x], [y]) and its distance. */
    fun nearest(line: Line, x: Float, y: Float): Pair<Float, Float> {
        var best = Float.MAX_VALUE
        var at = 0f
        for (i in 0 until line.size - 1) {
            val ax = line.xs[i]
            val ay = line.ys[i]
            val dx = line.xs[i + 1] - ax
            val dy = line.ys[i + 1] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
            val d = hypot(ax + t * dx - x, ay + t * dy - y)
            if (d < best) {
                best = d
                at = i + t
            }
        }
        return at to best
    }

    /** Where on [target] the [others] cross it or end against it, as parameters. */
    fun contacts(target: Line, others: List<Line>, touch: Float): List<Float> {
        val out = ArrayList<Float>()
        for (o in others) {
            if (o.size < 2) continue
            // Far apart: nothing to find.
            if (o.right < target.left - touch || o.left > target.right + touch || o.bottom < target.top - touch || o.top > target.bottom + touch) continue
            for (i in 0 until target.size - 1) {
                val ax = target.xs[i]
                val ay = target.ys[i]
                val bx = target.xs[i + 1]
                val by = target.ys[i + 1]
                if (max(ax, bx) < o.left - touch || min(ax, bx) > o.right + touch || max(ay, by) < o.top - touch || min(ay, by) > o.bottom + touch) continue
                for (j in 0 until o.size - 1) {
                    cross(ax, ay, bx, by, o.xs[j], o.ys[j], o.xs[j + 1], o.ys[j + 1])?.let { out += i + it }
                }
            }
            // The ends of the other line that stop at the target (a T drawn by hand).
            for (k in listOf(0, o.size - 1)) {
                val (s, d) = nearest(target, o.xs[k], o.ys[k])
                if (d <= touch) out += s
            }
        }
        return out.sorted()
    }

    /** Where segment a–b crosses segment c–d, as the fraction along a–b; null when they do not. */
    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float? {
        val rx = bx - ax
        val ry = by - ay
        val sx = dx - cx
        val sy = dy - cy
        val den = rx * sy - ry * sx
        if (den == 0f) return null
        val t = ((cx - ax) * sy - (cy - ay) * sx) / den
        val u = ((cx - ax) * ry - (cy - ay) * rx) / den
        return if (t in 0f..1f && u in 0f..1f) t else null
    }
}
