package dev.inknotes.editor

import android.graphics.RectF
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.inknotes.data.EraseCut
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageImage
import dev.inknotes.data.PageStroke
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Changing the width or height of drawings without distorting them: like a real drawing
 * made longer or shorter, straight lines along the direction of the change grow or shrink
 * and the gaps between parts widen or narrow, while details (arrow heads, circles, hatching,
 * letters) keep their shape. Where a selection has nothing that can take up the change
 * (a lone circle, a picture), it is stretched as usual.
 */
object Elongate {

    class Result(val strokes: List<PageStroke>, val fills: List<PageFill>, val images: List<PageImage>)

    /** Gives the elements, now inside [bounds], the width [w] and height [h], top-left fixed. */
    fun resize(strokes: List<PageStroke>, fills: List<PageFill>, images: List<PageImage>, bounds: RectF, w: Float, h: Float): Result {
        val shapes = shapesOf(strokes, fills)
        // Small separate parts (the letters x and y by the axes) follow the part they label
        // instead of being spread away from it.
        val small = smallParts(shapes, max(bounds.width(), bounds.height()))
        val main = shapes.filterIndexed { i, _ -> !small[i] }
        val mx = axisMap(main, images, bounds.left, bounds.width(), w, horizontal = true)
        val my = axisMap(main, images, bounds.top, bounds.height(), h, horizontal = false)
        val shift = Array(shapes.size) { i ->
            if (!small[i]) {
                null
            } else {
                val (ax, ay) = anchor(shapes[i], main)
                floatArrayOf(mx.map(ax) - ax, my.map(ay) - ay)
            }
        }
        fun mapX(i: Int, x: Float) = shift[i]?.let { x + it[0] } ?: mx.map(x)
        fun mapY(i: Int, y: Float) = shift[i]?.let { y + it[1] } ?: my.map(y)
        val newStrokes = strokes.mapIndexed { i, s ->
            val inputs = s.stroke.inputs
            val batch = MutableStrokeInputBatch()
            val p = StrokeInput()
            for (k in 0 until inputs.size) {
                inputs.populate(k, p)
                p.update(
                    x = mapX(i, p.x),
                    y = mapY(i, p.y),
                    elapsedTimeMillis = p.elapsedTimeMillis,
                    toolType = p.toolType,
                    strokeUnitLengthCm = p.strokeUnitLengthCm,
                    pressure = p.pressure,
                    tiltRadians = p.tiltRadians,
                    orientationRadians = p.orientationRadians,
                )
                batch.add(p)
            }
            PageStroke(s.kind, Stroke(s.stroke.brush, batch))
        }
        val newFills = fills.mapIndexed { j, f ->
            val i = strokes.size + j
            fun mapPoints(a: FloatArray) = FloatArray(a.size) { if (it % 2 == 0) mapX(i, a[it]) else mapY(i, a[it]) }
            f.copy(outline = mapPoints(f.outline), cuts = f.cuts.map { EraseCut(mapPoints(it.points), it.radius) })
        }
        val newImages = images.map { img ->
            val x0 = mx.map(img.x)
            val x1 = mx.map(img.x + img.w)
            val y0 = my.map(img.y)
            val y1 = my.map(img.y + img.h)
            img.moved(x = x0, y = y0, w = max(1f, x1 - x0), h = max(1f, y1 - y0))
        }
        return Result(newStrokes, newFills, newImages)
    }

    /**
     * Shapes of the parts much smaller than the largest part, a part being shapes that touch
     * each other. When everything is of a size (words of handwriting), nothing is small.
     */
    private fun smallParts(shapes: List<Shape>, size: Float): BooleanArray {
        val n = shapes.size
        val out = BooleanArray(n)
        if (n < 2 || n > MAX_PARTS) return out
        val tol = max(shapes.maxOf { it.width }, TOUCH * size)
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) x = parent[x]
            return x
        }
        val boxes = shapes.map { RectF(it.xs.min() - tol, it.ys.min() - tol, it.xs.max() + tol, it.ys.max() + tol) }
        for (a in 0 until n) for (b in a + 1 until n) {
            if (find(a) == find(b) || !RectF.intersects(boxes[a], boxes[b])) continue
            if (near(shapes[a], shapes[b], tol) || near(shapes[b], shapes[a], tol)) parent[find(a)] = find(b)
        }
        val groups = (0 until n).groupBy(::find)
        val diag = groups.mapValues { (_, idx) ->
            val r = RectF(boxes[idx[0]])
            for (i in idx) r.union(boxes[i])
            hypot(r.width(), r.height())
        }
        val largest = diag.values.max()
        for ((root, idx) in groups) if (diag.getValue(root) < SMALL * largest) for (i in idx) out[i] = true
        return out
    }

    /** Some point of [a] within [tol] of a segment of [b]. */
    private fun near(a: Shape, b: Shape, tol: Float): Boolean {
        val stepA = max(1, a.xs.size / NEAR_POINTS)
        val stepB = max(1, b.xs.size / NEAR_POINTS)
        for (i in a.xs.indices step stepA) {
            if (b.xs.size == 1) {
                if (hypot(a.xs[i] - b.xs[0], a.ys[i] - b.ys[0]) <= tol) return true
                continue
            }
            var j = 0
            while (j < b.xs.size - 1) {
                val k = min(j + stepB, b.xs.size - 1)
                if (PageEdits.distanceToSegment(a.xs[i], a.ys[i], b.xs[j], b.ys[j], b.xs[k], b.ys[k]) <= tol) return true
                j = k
            }
        }
        return false
    }

    /** The point of [s] nearest to the [main] parts: where it hangs on them. */
    private fun anchor(s: Shape, main: List<Shape>): Pair<Float, Float> {
        var best = Float.MAX_VALUE
        var bx = s.xs[0]
        var by = s.ys[0]
        val step = max(1, s.xs.size / NEAR_POINTS)
        for (m in main) {
            val mStep = max(1, m.xs.size / NEAR_POINTS)
            for (i in s.xs.indices step step) for (j in m.xs.indices step mStep) {
                val d = hypot(s.xs[i] - m.xs[j], s.ys[i] - m.ys[j])
                if (d < best) {
                    best = d
                    // The point of the main part: the small one moves as that point does.
                    bx = m.xs[j]
                    by = m.ys[j]
                }
            }
        }
        return bx to by
    }

    /** A polyline with the thickness of its line. */
    private class Shape(val xs: FloatArray, val ys: FloatArray, val width: Float)

    private fun shapesOf(strokes: List<PageStroke>, fills: List<PageFill>): List<Shape> {
        val out = ArrayList<Shape>()
        val p = StrokeInput()
        for (s in strokes) {
            val inputs = s.stroke.inputs
            val n = inputs.size
            if (n == 0) continue
            // Long strokes are sampled: the shape matters, not every point.
            val step = max(1, n / MAX_POINTS)
            val count = (n + step - 1) / step
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            for (k in 0 until count) {
                inputs.populate(min(n - 1, k * step), p)
                xs[k] = p.x
                ys[k] = p.y
            }
            out += Shape(xs, ys, s.stroke.brush.size)
        }
        for (f in fills) {
            val o = f.outline
            val n = o.size / 2
            out += Shape(FloatArray(n + 1) { o[2 * (it % n)] }, FloatArray(n + 1) { o[2 * (it % n) + 1] }, 0f)
        }
        return out
    }

    /** Piecewise linear map of one axis: rigid parts keep their length, the rest absorbs the change. */
    private class AxisMap(val from: FloatArray, val to: FloatArray) {
        fun map(v: Float): Float {
            if (from.size < 2) return v
            if (v <= from[0]) return to[0] + (v - from[0])
            if (v >= from.last()) return to.last() + (v - from.last())
            var lo = 0
            var hi = from.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (from[mid] <= v) lo = mid else hi = mid
            }
            val span = from[hi] - from[lo]
            val t = if (span > 0f) (v - from[lo]) / span else 0f
            return to[lo] + t * (to[hi] - to[lo])
        }
    }

    private fun axisMap(shapes: List<Shape>, images: List<PageImage>, start: Float, length: Float, target: Float, horizontal: Boolean): AxisMap {
        if (length <= 0.01f || abs(target - length) < 0.01f) return AxisMap(floatArrayOf(start, start + 1f), floatArrayOf(start, start + 1f))
        val bins = BINS
        val binW = length / bins
        val rigid = BooleanArray(bins)
        fun mark(a: Float, b: Float) {
            val i0 = floor((min(a, b) - start) / binW).toInt().coerceIn(0, bins - 1)
            val i1 = floor((max(a, b) - start) / binW).toInt().coerceIn(0, bins - 1)
            for (i in i0..i1) rigid[i] = true
        }
        for (s in shapes) {
            // Along: the coordinate being changed; across: the other one.
            val along = if (horizontal) s.xs else s.ys
            val across = if (horizontal) s.ys else s.xs
            val pad = s.width / 2
            if (along.size == 1) {
                mark(along[0] - pad, along[0] + pad)
                continue
            }
            // Runs of segments that go along the axis; a run that is a straight line is elastic.
            var k = 0
            while (k < along.size - 1) {
                val flat = abs(across[k + 1] - across[k]) <= FLAT * abs(along[k + 1] - along[k])
                if (!flat) {
                    mark(along[k] - pad, along[k + 1] + pad)
                    k++
                    continue
                }
                var end = k + 1
                while (end < along.size - 1 && abs(across[end + 1] - across[end]) <= FLAT * abs(along[end + 1] - along[end])) end++
                val len = abs(along[end] - along[k])
                var dev = 0f
                for (j in k..end) {
                    val t = if (along[end] != along[k]) (along[j] - along[k]) / (along[end] - along[k]) else 0f
                    dev = max(dev, abs(across[j] - (across[k] + t * (across[end] - across[k]))))
                }
                // Curves near their flattest point (the top of a circle) are not lines.
                val straight = len > 3 * max(s.width, 1f) && dev <= max(0.5f * s.width, STRAIGHT * len)
                if (!straight) mark(along[k] - pad, along[end] + pad)
                // The ends of a line are where something else (a head, a corner) begins.
                mark(along[k] - pad, along[k] + pad)
                mark(along[end] - pad, along[end] + pad)
                k = end
            }
        }
        // Pictures cannot be lengthened.
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (img in images) {
            val r = PageEdits.bounds(img)
            if (horizontal) mark(r.left, r.right) else mark(r.top, r.bottom)
            lo = min(lo, if (horizontal) r.left else r.top)
            hi = max(hi, if (horizontal) r.right else r.bottom)
        }
        for (s in shapes) for (v in if (horizontal) s.xs else s.ys) {
            lo = min(lo, v)
            hi = max(hi, v)
        }
        // Only gaps inside the drawing take up the change, not the margin of line width around it.
        if (lo <= hi) {
            mark(start, lo)
            mark(hi, start + length)
        }
        val elastic = rigid.count { !it } * binW
        val delta = target - length
        // Nothing can take it up: stretch the drawing evenly (the line width around it stays).
        if (elastic < 0.01f) {
            return if (hi > lo) {
                AxisMap(floatArrayOf(lo, hi), floatArrayOf(lo, max(lo + 0.05f * (hi - lo), hi + delta)))
            } else {
                AxisMap(floatArrayOf(start, start + length), floatArrayOf(start, start + target))
            }
        }
        var k = (elastic + delta) / elastic
        var rigidK = 1f
        if (k < 0f) {
            // Shorter than the details alone: the gaps close and the rest shrinks evenly.
            k = 0f
            val rest = length - elastic
            rigidK = if (rest > 0f) max(0.05f, target / rest) else 1f
        }
        val from = FloatArray(bins + 1) { start + it * binW }
        val to = FloatArray(bins + 1)
        to[0] = start
        for (i in 0 until bins) to[i + 1] = to[i] + binW * (if (rigid[i]) rigidK else k)
        return AxisMap(from, to)
    }

    private const val BINS = 240
    private const val MAX_PARTS = 400
    private const val NEAR_POINTS = 80

    /** Parts closer than this share of the drawing touch. */
    private const val TOUCH = 0.02f

    /** A part this much smaller than the largest one is a label or detail of it. */
    private const val SMALL = 0.3f
    private const val MAX_POINTS = 600

    /** A segment rising less than this per unit along the axis goes along it. */
    private const val FLAT = 0.08f
    private const val STRAIGHT = 0.01f
}
