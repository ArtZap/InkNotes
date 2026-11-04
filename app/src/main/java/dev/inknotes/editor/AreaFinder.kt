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
 * fills on a tap, and the part of a traced outline that is not inside other closed shapes.
 *
 * The lines are drawn into two masks: as they are, and as walls. A shape not quite closed has
 * its gap at the end of a line that stops short of another; the walls close such gaps (a round
 * plug at a loose end, reaching up to 2·[GAP] past it) and nothing else, so a narrow channel
 * between two lines (a circle close to the sides of a box) stays open. The region is
 * flood-filled inside the walls, then widened in the thin mask into narrow corners that lead
 * nowhere else; a plugged gap stays shut. Closed shapes inside become holes, joined to the
 * outline by hairline cuts, so each result is a single polygon like a traced area.
 */
object AreaFinder {

    /** Outline in page units (x, y pairs), or null when the point is on a line or the region is open. */
    fun find(strokes: List<PageStroke>, pageW: Float, pageH: Float, x: Float, y: Float): FloatArray? {
        if (pageW < 1f || pageH < 1f || x !in 0f..pageW || y !in 0f..pageH) return null
        val m = Walls(strokes, pageW, pageH)
        val seed = m.at(x, y)
        val inside = BooleanArray(m.size)
        when {
            !m.thick[seed] -> {
                if (!m.flood(intArrayOf(seed), inside, m.thick, null)) return null
                m.widen(inside, null)
            }
            !m.thin[seed] -> {
                // A tap in a narrow place: the region it opens into, or the narrow pocket itself.
                when (val open = m.nearestOpen(seed, inside)) {
                    OPEN -> return null
                    POCKET -> Unit
                    else -> {
                        inside.fill(false)
                        if (!m.flood(intArrayOf(open), inside, m.thick, null)) return null
                        m.widen(inside, null)
                        if (!inside[seed]) return null
                    }
                }
            }
            else -> return null
        }
        m.underLines(inside, null)
        return m.polygons(inside).maxByOrNull { abs(area(it)) }
    }

    /**
     * A traced [outline] (page units) without the closed shapes inside it: the part reachable
     * from the outline's edge without crossing a line. May come apart into several polygons
     * when lines cut the outline through; empty when nothing of it is left.
     */
    fun carve(strokes: List<PageStroke>, pageW: Float, pageH: Float, outline: FloatArray): List<FloatArray> {
        if (pageW < 1f || pageH < 1f || outline.size < 6) return emptyList()
        val m = Walls(strokes, pageW, pageH)
        val within = m.polygon(outline)
        val edge = ArrayList<Int>()
        for (i in 0 until m.size) if (within[i] && m.onEdge(i, within)) edge += i
        val inside = BooleanArray(m.size)
        m.flood(edge.filter { !m.thick[it] }.toIntArray(), inside, m.thick, within)
        m.widen(inside, within, edge)
        m.underLines(inside, within)
        return m.polygons(inside)
    }

    private const val OPEN = -1
    private const val POCKET = -2

    /** The page's lines rasterized at [k] mask pixels per page unit, with a one-pixel frame. */
    private class Walls(strokes: List<PageStroke>, pageW: Float, pageH: Float) {
        val k = min(1f, sqrt(MAX_PIXELS / (pageW * pageH)))
        val w = ceil(pageW * k).toInt() + 2
        val h = ceil(pageH * k).toInt() + 2
        val size = w * h

        /** The lines and the plugs at loose ends: what the flood fill may not cross. */
        val thick: BooleanArray

        /** Lines as they are. */
        val thin: BooleanArray

        init {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(bitmap)
            canvas.translate(1f, 1f)
            canvas.scale(k, k)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val paths = ArrayList<Pair<Path, Float>>()
            val lines = ArrayList<Line>()
            val p = StrokeInput()
            for (s in strokes) {
                if (!s.kind.isInk) continue
                val inputs = s.stroke.inputs
                if (inputs.size == 0) continue
                val path = Path()
                val xs = FloatArray(inputs.size)
                val ys = FloatArray(inputs.size)
                for (i in 0 until inputs.size) {
                    inputs.populate(i, p)
                    xs[i] = p.x
                    ys[i] = p.y
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                if (inputs.size == 1) path.lineTo(p.x + 0.01f, p.y)
                paths += path to s.stroke.brush.size
                lines += Line(xs, ys, s.stroke.brush.size / 2)
            }
            for ((path, width) in paths) {
                paint.strokeWidth = width
                canvas.drawPath(path, paint)
            }
            thin = read(bitmap, WALL)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            for (plug in plugs(lines)) canvas.drawCircle(plug[0], plug[1], plug[2], fill)
            thick = read(bitmap, WALL)
            bitmap.recycle()
        }

        /** A stroke's centre line and half its width. */
        private class Line(val xs: FloatArray, val ys: FloatArray, val half: Float) {
            /** Length along the line up to each point. */
            val along = FloatArray(xs.size).also {
                for (i in 1 until xs.size) it[i] = it[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            }
        }

        /**
         * Round plugs (x, y, radius) closing the gaps at loose ends: an end of a line that does
         * not touch other ink (or its own line farther along) but comes within 2·[GAP] of it.
         * The plug reaches just over to the line it falls short of.
         */
        private fun plugs(lines: List<Line>): List<FloatArray> {
            // Segments by grid cell, for the lines near an end.
            val cell = 16f
            val grid = HashMap<Long, MutableList<IntArray>>()
            fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
            for ((li, l) in lines.withIndex()) {
                for (i in 0 until l.xs.size - 1) {
                    val pad = l.half + 1f
                    val x0 = ((minOf(l.xs[i], l.xs[i + 1]) - pad) / cell).toInt()
                    val x1 = ((maxOf(l.xs[i], l.xs[i + 1]) + pad) / cell).toInt()
                    val y0 = ((minOf(l.ys[i], l.ys[i + 1]) - pad) / cell).toInt()
                    val y1 = ((maxOf(l.ys[i], l.ys[i + 1]) + pad) / cell).toInt()
                    if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > MAX_CELLS_PER_SEGMENT) continue
                    for (cx in x0..x1) for (cy in y0..y1) grid.getOrPut(key(cx, cy)) { ArrayList(2) } += intArrayOf(li, i)
                }
            }
            val out = ArrayList<FloatArray>()
            for ((li, l) in lines.withIndex()) {
                val n = l.xs.size
                if (n < 2 || l.along[n - 1] < 2 * l.half) continue
                for (end in listOf(0, n - 1)) {
                    val ex = l.xs[end]
                    val ey = l.ys[end]
                    // Its own line near this end is the end itself, not something it reaches.
                    val skip = 2 * (l.half + 2 * GAP)
                    val reach = l.half + 2 * GAP + MAX_HALF
                    var bestGap = Float.MAX_VALUE
                    var bestDist = 0f
                    val seen = HashSet<Long>()
                    for (cx in ((ex - reach) / cell).toInt()..((ex + reach) / cell).toInt()) {
                        for (cy in ((ey - reach) / cell).toInt()..((ey + reach) / cell).toInt()) {
                            for (seg in grid[key(cx, cy)].orEmpty()) {
                                val (oi, si) = seg[0] to seg[1]
                                if (!seen.add((oi.toLong() shl 32) or si.toLong())) continue
                                val o = lines[oi]
                                if (oi == li) {
                                    val near = if (end == 0) o.along[si] < skip else o.along[si + 1] > o.along[n - 1] - skip
                                    if (near) continue
                                }
                                val d = PageEdits.distanceToSegment(ex, ey, o.xs[si], o.ys[si], o.xs[si + 1], o.ys[si + 1])
                                val gap = d - o.half - l.half
                                if (gap < bestGap) {
                                    bestGap = gap
                                    bestDist = d
                                }
                            }
                        }
                    }
                    // Touching already, or nothing near to close the gap to.
                    if (bestGap <= TOUCH || bestGap > 2 * GAP) continue
                    out += floatArrayOf(ex, ey, bestDist + 1f)
                }
            }
            return out
        }

        fun read(bitmap: Bitmap, threshold: Int): BooleanArray {
            val stride = bitmap.rowBytes
            val bytes = ByteBuffer.allocate(stride * h)
            bitmap.copyPixelsToBuffer(bytes)
            val raw = bytes.array()
            return BooleanArray(size) { i -> (raw[(i / w) * stride + i % w].toInt() and 0xFF) > threshold }
        }

        /** Mask pixel of a page point. */
        fun at(x: Float, y: Float): Int {
            val sx = (x * k + 1).toInt().coerceIn(1, w - 2)
            val sy = (y * k + 1).toInt().coerceIn(1, h - 2)
            return sy * w + sx
        }

        /** Pixels inside [outline] (page units). */
        fun polygon(outline: FloatArray): BooleanArray {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(bitmap)
            canvas.translate(1f, 1f)
            canvas.scale(k, k)
            val path = Path().apply {
                moveTo(outline[0], outline[1])
                for (i in 2 until outline.size step 2) lineTo(outline[i], outline[i + 1])
                close()
            }
            canvas.drawPath(path, Paint().apply { style = Paint.Style.FILL })
            val mask = read(bitmap, 127)
            bitmap.recycle()
            // The frame is never part of an area: the flood fill steps off it.
            for (x in 0 until w) {
                mask[x] = false
                mask[(h - 1) * w + x] = false
            }
            for (y in 0 until h) {
                mask[y * w] = false
                mask[y * w + w - 1] = false
            }
            return mask
        }

        fun onBorder(i: Int): Boolean {
            val x = i % w
            val y = i / w
            return x == 0 || y == 0 || x == w - 1 || y == h - 1
        }

        fun onEdge(i: Int, within: BooleanArray) = !within[i - 1] || !within[i + 1] || !within[i - w] || !within[i + w]

        private val queue = IntArray(size)

        /** Offsets of the four neighbours of a pixel. */
        private val around = intArrayOf(-1, 1, -w, w)

        /**
         * Flood fill from [seeds] through pixels free of [wall] (and in [within], when given).
         * False when it reaches the mask's frame, that is, the region is not closed.
         */
        fun flood(seeds: IntArray, inside: BooleanArray, wall: BooleanArray, within: BooleanArray?): Boolean {
            var head = 0
            var tail = 0
            for (s in seeds) if (!inside[s]) {
                inside[s] = true
                queue[tail++] = s
            }
            var closed = true
            while (head < tail) {
                val i = queue[head++]
                if (onBorder(i)) {
                    if (within == null) return false
                    closed = false
                    continue
                }
                for (d in around) {
                    val j = i + d
                    if (inside[j] || wall[j] || (within != null && !within[j])) continue
                    inside[j] = true
                    queue[tail++] = j
                }
            }
            return closed
        }

        /**
         * From a pixel under the thick lines but off the thin ones: the nearest pixel of an
         * open region ([OPEN] if the frame comes first). When there is none, the narrow pocket
         * walked through is left in [inside] and the answer is [POCKET].
         */
        fun nearestOpen(seed: Int, inside: BooleanArray): Int {
            var head = 0
            var tail = 0
            queue[tail++] = seed
            inside[seed] = true
            while (head < tail) {
                val i = queue[head++]
                if (onBorder(i)) return OPEN
                for (d in around) {
                    val j = i + d
                    if (inside[j] || thin[j]) continue
                    if (!thick[j]) return j
                    inside[j] = true
                    queue[tail++] = j
                }
            }
            return POCKET
        }

        /**
         * Widens the flood-filled region in the thin mask: up to the lines' edges (the band the
         * thick lines took), then into every narrow place that leads nowhere but back into the
         * region. [edge] (traced areas) also starts narrow places that the outline cuts across.
         */
        fun widen(inside: BooleanArray, within: BooleanArray?, edge: List<Int> = emptyList()) {
            fun ok(j: Int) = within == null || within[j]
            // Up to the lines: the band of width GAP that the thick lines added.
            var head = 0
            var tail = 0
            for (i in 0 until size) if (inside[i]) queue[tail++] = i
            repeat(ceil(GAP * k).toInt() + 1) {
                val end = tail
                while (head < end) {
                    val i = queue[head++]
                    if (onBorder(i)) continue
                    for (d in around) {
                        val j = i + d
                        if (inside[j] || thin[j] || !ok(j)) continue
                        inside[j] = true
                        queue[tail++] = j
                    }
                }
            }
            // Narrow places: free of lines but closed in the thick mask, not taken yet.
            fun narrow(j: Int) = !inside[j] && !thin[j] && thick[j] && ok(j)
            val seen = BooleanArray(size)
            val part = ArrayList<Int>()
            fun explore(start: Int) {
                if (seen[start] || !narrow(start)) return
                part.clear()
                var leaks = false
                seen[start] = true
                part += start
                var n = 0
                while (n < part.size) {
                    val i = part[n++]
                    if (onBorder(i)) {
                        if (within == null) leaks = true
                        continue
                    }
                    for (d in around) {
                        val j = i + d
                        // Into another region: a gap in the outline, not a corner of this one.
                        if (!inside[j] && !thick[j] && ok(j)) leaks = true
                        if (!seen[j] && narrow(j)) {
                            seen[j] = true
                            part += j
                        }
                    }
                }
                if (!leaks) for (i in part) inside[i] = true
            }
            for (i in 0 until size) {
                if (!inside[i] || onBorder(i)) continue
                explore(i - 1)
                explore(i + 1)
                explore(i - w)
                explore(i + w)
            }
            for (i in edge) explore(i)
        }

        /** One pixel under the lines, so no seam of paper shows between a fill and its outline. */
        fun underLines(inside: BooleanArray, within: BooleanArray?) {
            val add = ArrayList<Int>()
            for (i in 0 until size) {
                if (!inside[i] || onBorder(i)) continue
                for (d in around) {
                    val j = i + d
                    if (!inside[j] && thin[j] && (within == null || within[j])) add += j
                }
            }
            for (j in add) inside[j] = true
        }

        /** The regions of [inside] as polygons in page units, holes joined in. */
        fun polygons(inside: BooleanArray): List<FloatArray> {
            val loops = trace(inside, w, h).map { simplify(it, 0.8f) }.filter { it.size >= 6 }
            // Outlines run one way round, holes the other (see trace).
            val outers = loops.filter { area(it) > 0f && area(it) >= MIN_PIECE * k * k }.sortedBy { area(it) }
            val holes = loops.filter { area(it) < 0f && -area(it) >= MIN_HOLE * k * k }.sortedBy { area(it) }
            val owned = HashMap<FloatArray, MutableList<FloatArray>>()
            for (hole in holes) {
                // The smallest outline around the hole is the one it is cut out of.
                val owner = outers.firstOrNull { contains(it, hole[0], hole[1]) } ?: continue
                owned.getOrPut(owner) { ArrayList() } += hole
            }
            return outers.mapNotNull { outer ->
                var poly = outer
                for (hole in owned[outer].orEmpty().take(MAX_HOLES)) {
                    if (poly.size + hole.size + 4 > MAX_FLOATS) break
                    poly = bridge(poly, hole)
                }
                if (poly.size > MAX_FLOATS) null else FloatArray(poly.size) { i -> (poly[i] - 1f) / k }
            }
        }
    }

    /** Is ([x], [y]) inside polygon [p] (even-odd)? */
    private fun contains(p: FloatArray, x: Float, y: Float): Boolean {
        var c = false
        var j = p.size - 2
        for (i in p.indices step 2) {
            val yi = p[i + 1]
            val yj = p[j + 1]
            if ((yi > y) != (yj > y) && x < (p[j] - p[i]) * (y - yi) / (yj - yi) + p[i]) c = !c
            j = i
        }
        return c
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

    /** Signed area (shoelace): positive for outlines as [trace] makes them, negative for holes. */
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

    /** Gaps up to twice this at the loose end of a line still close the shape. */
    private const val GAP = 3f

    /** An end this close to other ink (page units, edge to edge) touches it: no gap. */
    private const val TOUCH = 0.6f

    /** Widest half of a line looked for around an end (brushes go up to 40 wide). */
    private const val MAX_HALF = 20f

    /** A segment spread over more grid cells than this (a huge stroke) is left out of the search. */
    private const val MAX_CELLS_PER_SEGMENT = 20_000L

    /** Mask alpha above which a pixel is a line. */
    private const val WALL = 60

    /** Holes smaller than this (page units²) are specks, not shapes: even the inside of a small "o" stays unfilled. */
    private const val MIN_HOLE = 6f

    /** Pieces of a carved area smaller than this (page units²) are dropped. */
    private const val MIN_PIECE = 40f
    private const val MAX_HOLES = 400
    private const val MAX_FLOATS = 200_000
}
