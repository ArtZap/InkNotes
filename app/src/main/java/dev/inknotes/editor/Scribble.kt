package dev.inknotes.editor

import android.graphics.RectF
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageStroke
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Scratching out: a zigzag scribbled over writing erases the writing and itself. A stroke
 * counts as a scribble when it doubles back on itself several times and is much longer than
 * the area it covers; what it erases are the strokes lying mostly inside that area.
 */
object Scribble {

    /** Strokes of [strokes] that [stroke] scratches out, or null when it is not a scribble over anything. */
    fun covered(stroke: Stroke, strokes: List<PageStroke>): List<PageStroke>? {
        val inputs = stroke.inputs
        val n = inputs.size
        if (n < 12) return null
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val p = StrokeInput()
        for (i in 0 until n) {
            inputs.populate(i, p)
            xs[i] = p.x
            ys[i] = p.y
        }
        val box = RectF(xs.min(), ys.min(), xs.max(), ys.max())
        val diag = hypot(box.width(), box.height())
        if (diag < MIN_SIZE) return null
        var length = 0f
        for (i in 1 until n) length += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (length < MIN_LENGTH_RATIO * diag) return null
        if (reversals(xs, ys, max(1.5f, min(box.width(), box.height()) * 0.15f)) < MIN_REVERSALS) return null

        val hull = hull(xs, ys)
        val inner = RectF(box).apply { inset(box.width() * 0.08f, box.height() * 0.08f) }
        val cover = RectF()
        var any = false
        val out = ArrayList<PageStroke>()
        for (s in strokes) {
            if (!s.kind.isInk) continue
            val b = PageCache.strokeBounds(s) ?: continue
            if (!RectF.intersects(b, box)) continue
            // Even lines (tables, shapes, stamps) go only when the zigzag is all over them:
            // scratching out a cell's text must leave the cell's borders.
            if (s.kind == InkKind.LINE && !inner.contains(b)) continue
            if (!mostlyInside(s, hull)) continue
            out += s
            if (any) cover.union(b) else cover.set(b)
            any = true
        }
        if (!any) return null
        // Over writing, not beside a dot that happens to be inside a big zigzag.
        cover.intersect(box)
        if (cover.width() * cover.height() < MIN_COVER * box.width() * box.height()) return null
        return out
    }

    /** Number of times the simplified path turns back on itself (by more than ~120°). */
    private fun reversals(xs: FloatArray, ys: FloatArray, eps: Float): Int {
        val keep = BooleanArray(xs.size)
        keep[0] = true
        keep[xs.size - 1] = true
        rdp(xs, ys, 0, xs.size - 1, eps, keep)
        val idx = keep.indices.filter { keep[it] }
        var count = 0
        for (k in 1 until idx.size - 1) {
            val a = idx[k - 1]
            val b = idx[k]
            val c = idx[k + 1]
            val d1 = atan2(ys[b] - ys[a], xs[b] - xs[a])
            val d2 = atan2(ys[c] - ys[b], xs[c] - xs[b])
            var turn = abs(d2 - d1)
            if (turn > Math.PI) turn = (2 * Math.PI - turn).toFloat()
            if (turn > REVERSAL) count++
        }
        return count
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

    private fun mostlyInside(s: PageStroke, hull: FloatArray): Boolean {
        val inputs = s.stroke.inputs
        val stride = max(1, inputs.size / 40)
        val p = StrokeInput()
        var total = 0
        var hits = 0
        var i = 0
        while (i < inputs.size) {
            inputs.populate(i, p)
            total++
            if (PageEdits.inside(p.x, p.y, hull)) hits++
            i += stride
        }
        return hits >= total * INSIDE_SHARE
    }

    /** Convex hull (monotone chain) as x, y pairs. */
    private fun hull(xs: FloatArray, ys: FloatArray): FloatArray {
        val order = xs.indices.sortedWith(compareBy({ xs[it] }, { ys[it] }))
        fun cross(o: Int, a: Int, b: Int) = (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o])
        val lower = ArrayList<Int>()
        for (i in order) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], i) <= 0) lower.removeAt(lower.size - 1)
            lower += i
        }
        val upper = ArrayList<Int>()
        for (i in order.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], i) <= 0) upper.removeAt(upper.size - 1)
            upper += i
        }
        val pts = lower.dropLast(1) + upper.dropLast(1)
        val out = FloatArray(pts.size * 2)
        for ((k, i) in pts.withIndex()) {
            out[k * 2] = xs[i]
            out[k * 2 + 1] = ys[i]
        }
        return out
    }

    private const val MIN_SIZE = 12f
    private const val MIN_LENGTH_RATIO = 2.2f
    private const val MIN_REVERSALS = 4
    private const val REVERSAL = 2.1f // ~120°
    private const val INSIDE_SHARE = 0.7f
    private const val MIN_COVER = 0.2f
}
