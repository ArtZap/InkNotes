package dev.inknotes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.ink.strokes.StrokeInput
import dev.inknotes.data.PageStroke
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The closed region around a point, bounded by the lines on the page: what the hatch tool
 * fills on a tap. The lines are drawn into a mask a little thicker than they are (so small
 * gaps where a shape was not quite closed still hold), the region is flood-filled from the
 * point and its outline traced. Inner shapes become holes, joined to the outline by hairline
 * cuts, so the result is a single polygon like a traced area.
 */
object AreaFinder {

    /** Outline in page units (x, y pairs), or null when the point is on a line or the region is open. */
    fun find(strokes: List<PageStroke>, pageW: Float, pageH: Float, x: Float, y: Float): FloatArray? {
        if (pageW < 1f || pageH < 1f || x !in 0f..pageW || y !in 0f..pageH) return null
        val k = min(1f, sqrt(MAX_PIXELS / (pageW * pageH)))
        val w = ceil(pageW * k).toInt() + 2
        val h = ceil(pageH * k).toInt() + 2
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        canvas.translate(1f, 1f)
        canvas.scale(k, k)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        val p = StrokeInput()
        for (s in strokes) {
            if (!s.kind.isInk) continue
            val inputs = s.stroke.inputs
            if (inputs.size == 0) continue
            path.rewind()
            inputs.populate(0, p)
            path.moveTo(p.x, p.y)
            for (i in 1 until inputs.size) {
                inputs.populate(i, p)
                path.lineTo(p.x, p.y)
            }
            if (inputs.size == 1) path.lineTo(p.x + 0.01f, p.y)
            paint.strokeWidth = s.stroke.brush.size + 2 * GAP
            canvas.drawPath(path, paint)
        }
        val bytes = ByteBuffer.allocate(mask.rowBytes * h)
        mask.copyPixelsToBuffer(bytes)
        val stride = mask.rowBytes
        mask.recycle()
        val raw = bytes.array()
        val wall = BooleanArray(w * h) { i -> (raw[(i / w) * stride + i % w].toInt() and 0xFF) > WALL }

        val sx = (x * k + 1).toInt().coerceIn(1, w - 2)
        val sy = (y * k + 1).toInt().coerceIn(1, h - 2)
        if (wall[sy * w + sx]) return null
        // Flood fill; reaching the border of the mask means the region is not closed.
        val inside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        queue[tail++] = sy * w + sx
        inside[sy * w + sx] = true
        while (head < tail) {
            val i = queue[head++]
            val cx = i % w
            val cy = i / w
            if (cx == 0 || cy == 0 || cx == w - 1 || cy == h - 1) return null
            var j = i - 1
            if (!inside[j] && !wall[j]) { inside[j] = true; queue[tail++] = j }
            j = i + 1
            if (!inside[j] && !wall[j]) { inside[j] = true; queue[tail++] = j }
            j = i - w
            if (!inside[j] && !wall[j]) { inside[j] = true; queue[tail++] = j }
            j = i + w
            if (!inside[j] && !wall[j]) { inside[j] = true; queue[tail++] = j }
        }
        // Grow under the lines by the extra width they were drawn with.
        // Only pixels next to the region matter: start from all of it, then from what was added.
        var from = 0
        var to = tail
        repeat(ceil(GAP * k).toInt() + 1) {
            for (q in from until to) {
                val i = queue[q]
                val cx = i % w
                val cy = i / w
                if (cx > 1 && !inside[i - 1]) { inside[i - 1] = true; queue[tail++] = i - 1 }
                if (cx < w - 2 && !inside[i + 1]) { inside[i + 1] = true; queue[tail++] = i + 1 }
                if (cy > 1 && !inside[i - w]) { inside[i - w] = true; queue[tail++] = i - w }
                if (cy < h - 2 && !inside[i + w]) { inside[i + w] = true; queue[tail++] = i + w }
            }
            from = to
            to = tail
        }
        val loops = trace(inside, w, h).map { loop -> simplify(loop, 0.8f) }.filter { it.size >= 6 }
        if (loops.isEmpty()) return null
        // The outline is the loop with the largest area; holes go the other way round.
        val outer = loops.maxBy { abs(area(it)) }
        val minHole = MIN_HOLE * k * k
        val holes = loops.filter { it !== outer && abs(area(it)) >= minHole }.sortedByDescending { abs(area(it)) }.take(MAX_HOLES)
        var poly = outer
        for (hole in holes) poly = bridge(poly, hole)
        if (poly.size > MAX_FLOATS) return null
        // Mask pixels back to page units.
        return FloatArray(poly.size) { i -> (poly[i] - 1f) / k }
    }

    /**
     * Boundary loops of the region along pixel edges, region on the right (screen
     * coordinates): the outline runs one way, holes the other.
     */
    private fun trace(inside: BooleanArray, w: Int, h: Int): List<FloatArray> {
        // Edges keyed by their start corner (corner index = y * (w + 1) + x).
        val cw = w + 1
        val next = HashMap<Int, MutableList<Int>>()
        fun edge(ax: Int, ay: Int, bx: Int, by: Int) {
            next.getOrPut(ay * cw + ax) { ArrayList(1) } += by * cw + bx
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (!inside[y * w + x]) continue
            if (y == 0 || !inside[(y - 1) * w + x]) edge(x, y, x + 1, y) // top: left to right
            if (x == w - 1 || !inside[y * w + x + 1]) edge(x + 1, y, x + 1, y + 1) // right: down
            if (y == h - 1 || !inside[(y + 1) * w + x]) edge(x + 1, y + 1, x, y + 1) // bottom: right to left
            if (x == 0 || !inside[y * w + x - 1]) edge(x, y + 1, x, y) // left: up
        }
        val loops = ArrayList<FloatArray>()
        while (next.isNotEmpty()) {
            val start = next.keys.first()
            val pts = ArrayList<Float>()
            var cur = start
            var prev = -1
            var guard = 0
            while (guard++ < 4_000_000) {
                val outs = next[cur] ?: break
                // Where two loops touch at a corner, turn right (keep the region whole).
                val pick = if (outs.size == 1 || prev < 0) 0 else turnRight(prev, cur, outs, cw)
                val to = outs.removeAt(pick)
                if (outs.isEmpty()) next.remove(cur)
                pts += (cur % cw).toFloat()
                pts += (cur / cw).toFloat()
                prev = cur
                cur = to
                if (cur == start) break
            }
            if (pts.size >= 6) loops += pts.toFloatArray()
        }
        return loops
    }

    private fun turnRight(prev: Int, cur: Int, outs: List<Int>, cw: Int): Int {
        val dx = cur % cw - prev % cw
        val dy = cur / cw - prev / cw
        for ((i, o) in outs.withIndex()) {
            val ex = o % cw - cur % cw
            val ey = o / cw - cur / cw
            // Right turn in screen coordinates (y down): cross product > 0.
            if (dx * ey - dy * ex > 0) return i
        }
        return 0
    }

    /** Signed area (shoelace). */
    private fun area(p: FloatArray): Float {
        var a = 0f
        var j = p.size - 2
        for (i in p.indices step 2) {
            a += p[j] * p[i + 1] - p[i] * p[j + 1]
            j = i
        }
        return a / 2
    }

    /** Joins [hole] into [poly] through their closest corners: out along a cut, round the hole, back. */
    private fun bridge(poly: FloatArray, hole: FloatArray): FloatArray {
        var best = Float.MAX_VALUE
        var pi = 0
        var hi = 0
        for (i in poly.indices step 2) for (j in hole.indices step 2) {
            val d = hypot(poly[i] - hole[j], poly[i + 1] - hole[j + 1])
            if (d < best) {
                best = d
                pi = i
                hi = j
            }
        }
        val out = ArrayList<Float>(poly.size + hole.size + 4)
        for (i in 0..pi + 1) out += poly[i]
        for (n in 0..hole.size / 2) {
            val j = (hi + n * 2) % hole.size
            out += hole[j]
            out += hole[j + 1]
        }
        out += poly[pi]
        out += poly[pi + 1]
        for (i in pi + 2 until poly.size) out += poly[i]
        return out.toFloatArray()
    }

    /** Drops corners that lie (almost) on the line between their neighbours. */
    private fun simplify(p: FloatArray, eps: Float): FloatArray {
        val n = p.size / 2
        if (n < 4) return p
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        rdp(p, 0, n - 1, eps, keep)
        val out = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) {
            out += p[i * 2]
            out += p[i * 2 + 1]
        }
        return out.toFloatArray()
    }

    private fun rdp(p: FloatArray, a: Int, b: Int, eps: Float, keep: BooleanArray) {
        if (b <= a + 1) return
        var best = -1f
        var idx = -1
        for (i in a + 1 until b) {
            val d = PageEdits.distanceToSegment(p[i * 2], p[i * 2 + 1], p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1])
            if (d > best) {
                best = d
                idx = i
            }
        }
        if (best > eps) {
            keep[idx] = true
            rdp(p, a, idx, eps, keep)
            rdp(p, idx, b, eps, keep)
        }
    }

    private const val MAX_PIXELS = 1_600_000f

    /** Extra half-width the lines are drawn with: gaps up to twice this still close the shape. */
    private const val GAP = 3f

    /** Mask alpha above which a pixel is a line. */
    private const val WALL = 60
    private const val MIN_HOLE = 300f
    private const val MAX_HOLES = 80
    private const val MAX_FLOATS = 200_000
}
