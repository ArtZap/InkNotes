package dev.inknotes.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a hand-drawn stroke into an ideal shape (the "hold to snap" gesture).
 * Pure geometry on page coordinates, no Android types, so it is unit-tested on the JVM.
 */
object ShapeRecognizer {

    class P(val x: Float, val y: Float)

    enum class Kind { LINE, ARROW, POLYLINE, CIRCLE, ELLIPSE, TRIANGLE, RECTANGLE, SQUARE, POLYGON, HEART,
        EQUILATERAL, ISOSCELES, RIGHT_TRIANGLE, RHOMBUS, PARALLELOGRAM, TRAPEZOID, STAR, ARC,
    }

    /** [paths]: one or more polylines to draw as strokes; closed shapes repeat the first point. */
    class Shape(val kind: Kind, val paths: List<List<P>>)

    private const val MIN_LENGTH = 15f
    private const val RIGHT_ANGLE_TOLERANCE = 0.19f // ~11°
    private const val PARALLEL_TOLERANCE = 0.17f // ~10°
    private const val PENTAGRAM_TURN = 1.95f // exterior turn ~112°+ (a regular pentagram turns 144°)
    private const val ARC_MIN_SWEEP = 0.5f // ~30°
    private const val ARC_MAX_SWEEP = 5.9f // ~340°
    private const val LEVEL_TOLERANCE = 0.07f // ~4°

    /**
     * A shape of straight pieces turned a little so its longest side lies at a multiple of
     * 15° (level, upright, 45°…) when it was drawn within a few degrees of one; round shapes
     * and others as they are.
     */
    fun level(shape: Shape): Shape {
        if (shape.kind == Kind.CIRCLE || shape.kind == Kind.ELLIPSE || shape.kind == Kind.ARC || shape.kind == Kind.HEART) return shape
        val path = shape.paths.firstOrNull() ?: return shape
        var longest = 0f
        var angle = 0f
        for (i in 1 until path.size) {
            val d = dist(path[i - 1], path[i])
            if (d > longest) {
                longest = d
                angle = atan2(path[i].y - path[i - 1].y, path[i].x - path[i - 1].x)
            }
        }
        if (longest < MIN_LENGTH) return shape
        val step = (PI / 12).toFloat()
        val turn = Math.round(angle / step) * step - angle
        if (abs(turn) > LEVEL_TOLERANCE || abs(turn) < 1e-4f) return shape
        val cx = path.map { it.x }.average().toFloat()
        val cy = path.map { it.y }.average().toFloat()
        val c = cos(turn)
        val sn = sin(turn)
        fun turned(p: P) = P(cx + (p.x - cx) * c - (p.y - cy) * sn, cy + (p.x - cx) * sn + (p.y - cy) * c)
        return Shape(shape.kind, shape.paths.map { it.map(::turned) })
    }

    fun recognize(input: List<P>): Shape? {
        if (input.size < 2) return null
        val length = pathLength(input)
        if (length < MIN_LENGTH) return null
        val pts = resample(input, 96)
        val closeGap = dist(pts.first(), pts.last())
        return if (closeGap < length * 0.15f) closedShape(pts, length) else openShape(pts, length)
    }

    // ------------------------------------------------------------------ open

    private fun openShape(pts: List<P>, length: Float): Shape {
        val first = pts.first()
        val last = pts.last()
        val corners = rdp(pts, max(length * 0.04f, 2f))
        if (corners.size == 2 || dist(first, last) > length * 0.94f) return Shape(Kind.LINE, listOf(listOf(first, last)))
        arrow(pts, corners, length)?.let { return it }
        arc(pts, corners)?.let { return it }
        return Shape(Kind.POLYLINE, listOf(corners))
    }

    /**
     * Single-stroke arrow: a long shaft, then the pen goes back and forth near the tip to
     * draw the head. The tip is the corner farthest (along the path) where the shaft ends.
     */
    private fun arrow(pts: List<P>, corners: List<P>, length: Float): Shape? {
        if (corners.size < 4) return null
        val tail = corners[0]
        val tip = corners[1]
        val shaft = dist(tail, tip)
        if (shaft < length * 0.55f) return null
        val head = corners.drop(2)
        if (head.any { dist(it, tip) > shaft * 0.45f }) return null
        // Head points must lie behind the tip (pointing back along the shaft).
        val ux = (tip.x - tail.x) / shaft
        val uy = (tip.y - tail.y) / shaft
        if (head.any { (it.x - tip.x) * ux + (it.y - tip.y) * uy > 0.15f * shaft }) return null
        val headLen = head.maxOf { dist(it, tip) }.coerceIn(shaft * 0.08f, shaft * 0.35f)
        val angle = atan2(uy, ux)
        fun wing(sign: Int) = P(
            tip.x - headLen * cos(angle + sign * PI.toFloat() / 6),
            tip.y - headLen * sin(angle + sign * PI.toFloat() / 6),
        )
        return Shape(Kind.ARROW, listOf(listOf(tail, tip), listOf(wing(+1), tip, wing(-1))))
    }

    /**
     * Circular arc: a curved open stroke (it simplifies to several corners) whose points sit
     * on one circle. Least-squares circle fit (Kåsa), then the same sweep as drawn.
     */
    private fun arc(pts: List<P>, corners: List<P>): Shape? {
        if (corners.size < 4) return null
        val (cx, cy, r) = fitCircle(pts) ?: return null
        val residual = pts.sumOf { abs(dist(it, P(cx, cy)) - r).toDouble() }.toFloat() / pts.size
        if (residual > 0.04f * r) return null
        var sweep = 0f
        var prev = atan2(pts[0].y - cy, pts[0].x - cx)
        for (p in pts.drop(1)) {
            val a = atan2(p.y - cy, p.x - cx)
            sweep += wrap(a - prev)
            prev = a
        }
        if (abs(sweep) !in ARC_MIN_SWEEP..ARC_MAX_SWEEP) return null
        val start = atan2(pts[0].y - cy, pts[0].x - cx)
        val n = 64
        val path = List(n + 1) { k ->
            val a = start + sweep * k / n
            P(cx + r * cos(a), cy + r * sin(a))
        }
        return Shape(Kind.ARC, listOf(path))
    }

    /** Center x, y and radius of the circle closest to [pts]; null if they are collinear. */
    private fun fitCircle(pts: List<P>): Triple<Float, Float, Float>? {
        // Solve x² + y² + D·x + E·y + F = 0 in the least-squares sense (around the mean for precision).
        val mx = pts.map { it.x.toDouble() }.average()
        val my = pts.map { it.y.toDouble() }.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        for (p in pts) {
            val x = p.x - mx
            val y = p.y - my
            val z = x * x + y * y
            sxx += x * x; syy += y * y; sxy += x * y; sx += x; sy += y
            sxz += x * z; syz += y * z; sz += z
        }
        val n = pts.size.toDouble()
        // Normal equations for (D, E, F).
        val m = arrayOf(
            doubleArrayOf(sxx, sxy, sx, -sxz),
            doubleArrayOf(sxy, syy, sy, -syz),
            doubleArrayOf(sx, sy, n, -sz),
        )
        for (col in 0 until 3) {
            val pivot = (col until 3).maxBy { abs(m[it][col]) }
            if (abs(m[pivot][col]) < 1e-9) return null
            val t = m[col]; m[col] = m[pivot]; m[pivot] = t
            for (row in 0 until 3) {
                if (row == col) continue
                val f = m[row][col] / m[col][col]
                for (k in col until 4) m[row][k] -= f * m[col][k]
            }
        }
        val d = m[0][3] / m[0][0]
        val e = m[1][3] / m[1][1]
        val f = m[2][3] / m[2][2]
        val cx = -d / 2
        val cy = -e / 2
        val r2 = cx * cx + cy * cy - f
        if (r2 <= 0) return null
        return Triple((cx + mx).toFloat(), (cy + my).toFloat(), sqrt(r2).toFloat())
    }

    /** Angle difference folded into -π..π. */
    private fun wrap(a: Float): Float {
        var x = a
        while (x > PI) x -= 2 * PI.toFloat()
        while (x < -PI) x += 2 * PI.toFloat()
        return x
    }

    // ---------------------------------------------------------------- closed

    private fun closedShape(raw: List<P>, length: Float): Shape {
        val pts = raw.dropLast(1) + raw.first()
        val oval = ellipse(pts)
        val ovalError = meanDistance(pts, oval.paths[0])
        val corners = closedCorners(pts, length)
        val polygonError = if (corners.size in 3..8) meanDistance(pts, corners + corners.first()) else Float.MAX_VALUE
        heart(pts)?.let { (shape, error) ->
            if (error < 0.8f * minOf(ovalError, polygonError)) return shape
        }
        star(pts, corners)?.let { star ->
            if (meanDistance(pts, star.paths[0]) < ovalError * 0.75f) return star
        }
        if (corners.size !in 3..8) return oval
        // A circle also simplifies to a handful of "corners"; whichever ideal shape the
        // drawn points hug more closely wins.
        return if (polygonError < ovalError * 0.75f) polygon(corners) ?: oval else oval
    }

    /**
     * Upright heart stretched to the drawn bounding box, with its fit error. The error is
     * measured both ways (drawing to template and back), so a shape that follows only part of
     * the heart outline, like a circle, does not pass; null if the fit is poor in absolute terms.
     */
    private fun heart(pts: List<P>): Pair<Shape, Float>? {
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val w = maxX - minX
        val h = maxY - minY
        if (w <= 0f || h <= 0f || w / h !in 0.5f..2f) return null
        val template = HEART.map { P(minX + it.x * w, minY + it.y * h) }
        val error = max(meanDistance(pts, template), meanDistance(template, pts))
        if (error > 0.06f * hypot(w, h)) return null
        return Shape(Kind.HEART, listOf(template)) to error
    }

    /** Classic heart curve, closed, normalized to the unit square (y down, lobes on top). */
    private val HEART: List<P> by lazy {
        val n = 96
        val raw = List(n + 1) { k ->
            val t = 2 * PI * k / n
            val x = 16 * sin(t).let { it * it * it }
            val y = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))
            P(x.toFloat(), y.toFloat())
        }
        val x0 = raw.minOf { it.x }
        val x1 = raw.maxOf { it.x }
        val y0 = raw.minOf { it.y }
        val y1 = raw.maxOf { it.y }
        raw.map { P((it.x - x0) / (x1 - x0), (it.y - y0) / (y1 - y0)) }
    }

    /** Mean distance from [pts] to the polyline [path]. */
    private fun meanDistance(pts: List<P>, path: List<P>): Float =
        pts.sumOf { p -> (1 until path.size).minOf { distToSegment(p, path[it - 1], path[it]) }.toDouble() }.toFloat() / pts.size

    /** Corners of a closed path: RDP from the point farthest from the start, both halves. */
    private fun closedCorners(pts: List<P>, length: Float): List<P> {
        val start = pts.first()
        val far = pts.indices.maxBy { dist(pts[it], start) }
        val eps = max(length * 0.035f, 2f)
        val a = rdp(pts.subList(0, far + 1), eps)
        val b = rdp(pts.subList(far, pts.size), eps)
        val ring = (a + b.drop(1)).dropLast(1).toMutableList()
        // The start point is a corner only if the path really turns there.
        if (ring.size > 3 && cornerAngles(ring)[0] < 0.6f) ring.removeAt(0)
        return mergeClose(ring, length * 0.06f)
    }

    private fun mergeClose(ring: MutableList<P>, minDist: Float): List<P> {
        var i = 0
        while (ring.size > 3 && i < ring.size) {
            val next = ring[(i + 1) % ring.size]
            if (dist(ring[i], next) < minDist) ring.removeAt((i + 1) % ring.size) else i++
        }
        return ring
    }

    /** Exterior turning angle (radians) at each vertex of a closed ring. */
    private fun cornerAngles(ring: List<P>): List<Float> = ring.indices.map { i ->
        val a = ring[(i - 1 + ring.size) % ring.size]
        val b = ring[i]
        val c = ring[(i + 1) % ring.size]
        val v1x = b.x - a.x
        val v1y = b.y - a.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val d = hypot(v1x, v1y) * hypot(v2x, v2y)
        if (d == 0f) 0f else acos(((v1x * v2x + v1y * v2y) / d).coerceIn(-1f, 1f))
    }

    private fun polygon(corners: List<P>): Shape? {
        val closed = { ring: List<P> -> listOf(ring + ring.first()) }
        when (corners.size) {
            3 -> return triangle(corners)
            4 -> {
                val interior = cornerAngles(corners).map { PI.toFloat() - it }
                if (interior.all { abs(it - PI.toFloat() / 2) < 0.26f }) return rectangle(corners)
                return quadrilateral(corners)
            }
        }
        val sides = corners.indices.map { dist(corners[it], corners[(it + 1) % corners.size]) }
        val mean = sides.average().toFloat()
        val regular = sides.all { abs(it - mean) < mean * 0.25f }
        if (!regular) return Shape(Kind.POLYGON, closed(corners))
        // Regular n-gon around the centroid, rotated to match the first drawn vertex.
        val ring = regularRing(corners)
        return Shape(Kind.POLYGON, closed(ring))
    }

    /** Equilateral, right or isosceles triangles are made exact; any other stays as drawn. */
    private fun triangle(c: List<P>): Shape {
        val closed = { ring: List<P> -> listOf(ring + ring.first()) }
        val sides = List(3) { dist(c[it], c[(it + 1) % 3]) } // side i: c[i] → c[i+1]
        val mean = sides.average().toFloat()
        if (sides.all { abs(it - mean) < mean * 0.08f }) {
            return Shape(Kind.EQUILATERAL, regularRing(c).let(closed))
        }
        val interior = cornerAngles(c).map { PI.toFloat() - it }
        val right = interior.indices.firstOrNull { abs(interior[it] - PI.toFloat() / 2) < RIGHT_ANGLE_TOLERANCE }
        if (right != null) {
            // Keep the right-angle vertex and leg lengths; turn both legs symmetrically to 90°.
            val v = c[right]
            val p = c[(right + 2) % 3]
            val q = c[(right + 1) % 3]
            val a1 = atan2(p.y - v.y, p.x - v.x)
            val delta = wrap(atan2(q.y - v.y, q.x - v.x) - a1)
            val mid = a1 + delta / 2
            val half = (if (delta >= 0) 1 else -1) * PI.toFloat() / 4
            var lp = dist(v, p)
            var lq = dist(v, q)
            if (abs(lp - lq) < max(lp, lq) * 0.08f) {
                lp = (lp + lq) / 2
                lq = lp
            }
            val ring = c.toMutableList()
            ring[(right + 2) % 3] = P(v.x + lp * cos(mid - half), v.y + lp * sin(mid - half))
            ring[(right + 1) % 3] = P(v.x + lq * cos(mid + half), v.y + lq * sin(mid + half))
            return Shape(Kind.RIGHT_TRIANGLE, closed(ring))
        }
        // Isosceles: the two sides meeting at an apex are about equal.
        for (apex in 0 until 3) {
            val left = sides[(apex + 2) % 3] // c[apex-1] → c[apex]
            val rightSide = sides[apex] // c[apex] → c[apex+1]
            if (abs(left - rightSide) < max(left, rightSide) * 0.08f) {
                val a = c[apex]
                val b = c[(apex + 2) % 3]
                val d = c[(apex + 1) % 3]
                val base = dist(b, d)
                val ux = (d.x - b.x) / base
                val uy = (d.y - b.y) / base
                val mx = (b.x + d.x) / 2
                val my = (b.y + d.y) / 2
                val h = (a.x - mx) * -uy + (a.y - my) * ux // signed height along the base normal
                val ring = c.toMutableList()
                ring[apex] = P(mx - uy * h, my + ux * h)
                return Shape(Kind.ISOSCELES, closed(ring))
            }
        }
        return Shape(Kind.TRIANGLE, closed(c))
    }

    /** Rhombus, parallelogram or trapezoid made exact; other quadrilaterals stay as drawn. */
    private fun quadrilateral(c: List<P>): Shape {
        val closed = { ring: List<P> -> listOf(ring + ring.first()) }
        // Opposite sides run the same way round a simple quadrilateral; in a crossed one
        // (an hourglass) they run against each other.
        fun parallel(a: P, b: P, d: P, e: P): Boolean {
            if ((b.x - a.x) * (e.x - d.x) + (b.y - a.y) * (e.y - d.y) <= 0f) return false
            val diff = abs(wrap(2 * (atan2(b.y - a.y, b.x - a.x) - atan2(e.y - d.y, e.x - d.x)))) / 2
            return diff < PARALLEL_TOLERANCE
        }
        val par02 = parallel(c[0], c[1], c[3], c[2])
        val par13 = parallel(c[1], c[2], c[0], c[3])
        if (par02 && par13) {
            val sides = List(4) { dist(c[it], c[(it + 1) % 4]) }
            val mean = sides.average().toFloat()
            val cx = c.map { it.x }.average().toFloat()
            val cy = c.map { it.y }.average().toFloat()
            if (sides.all { abs(it - mean) < mean * 0.12f }) {
                // Rhombus: perpendicular diagonals crossing at the centre, lengths as drawn.
                val a = dist(c[0], c[2]) / 2
                val b = dist(c[1], c[3]) / 2
                val t = atan2(c[2].y - c[0].y, c[2].x - c[0].x)
                val side = if (signedArea(c) >= 0) 1 else -1
                val ux = cos(t)
                val uy = sin(t)
                val vx = -uy * side
                val vy = ux * side
                val ring = listOf(P(cx - a * ux, cy - a * uy), P(cx - b * vx, cy - b * vy), P(cx + a * ux, cy + a * uy), P(cx + b * vx, cy + b * vy))
                return Shape(Kind.RHOMBUS, closed(ring))
            }
            // Parallelogram from the averaged side vectors around the centre.
            val ax = ((c[1].x - c[0].x) + (c[2].x - c[3].x)) / 2
            val ay = ((c[1].y - c[0].y) + (c[2].y - c[3].y)) / 2
            val bx = ((c[3].x - c[0].x) + (c[2].x - c[1].x)) / 2
            val by = ((c[3].y - c[0].y) + (c[2].y - c[1].y)) / 2
            val ring = listOf(
                P(cx - ax / 2 - bx / 2, cy - ay / 2 - by / 2),
                P(cx + ax / 2 - bx / 2, cy + ay / 2 - by / 2),
                P(cx + ax / 2 + bx / 2, cy + ay / 2 + by / 2),
                P(cx - ax / 2 + bx / 2, cy - ay / 2 + by / 2),
            )
            return Shape(Kind.PARALLELOGRAM, closed(ring))
        }
        if (par02 || par13) {
            // Trapezoid: rotate so the parallel bases are r[0]→r[1] and r[3]→r[2].
            val shift = if (par02) 0 else 1
            val r = List(4) { c[(it + shift) % 4] }
            val l1 = dist(r[0], r[1])
            val l2 = dist(r[3], r[2])
            // Common direction of both bases, weighted by length.
            val dx = (r[1].x - r[0].x) + (r[2].x - r[3].x)
            val dy = (r[1].y - r[0].y) + (r[2].y - r[3].y)
            val len = hypot(dx, dy)
            if (len < 1e-3f) return Shape(Kind.POLYGON, closed(c))
            val ux = dx / len
            val uy = dy / len
            var m1 = P((r[0].x + r[1].x) / 2, (r[0].y + r[1].y) / 2)
            var m2 = P((r[3].x + r[2].x) / 2, (r[3].y + r[2].y) / 2)
            val legs = dist(r[1], r[2]) to dist(r[0], r[3])
            if (abs(legs.first - legs.second) < max(legs.first, legs.second) * 0.1f) {
                // Isosceles: both base midpoints on one axis perpendicular to the bases.
                val along = ((m2.x - m1.x) * ux + (m2.y - m1.y) * uy) / 2
                m1 = P(m1.x + ux * along, m1.y + uy * along)
                m2 = P(m2.x - ux * along, m2.y - uy * along)
            }
            val ring = listOf(
                P(m1.x - ux * l1 / 2, m1.y - uy * l1 / 2),
                P(m1.x + ux * l1 / 2, m1.y + uy * l1 / 2),
                P(m2.x + ux * l2 / 2, m2.y + uy * l2 / 2),
                P(m2.x - ux * l2 / 2, m2.y - uy * l2 / 2),
            )
            return Shape(Kind.TRAPEZOID, closed(List(4) { ring[(it - shift + 4) % 4] }))
        }
        return Shape(Kind.POLYGON, closed(c))
    }

    /**
     * Five-pointed star: either the outline (10 corners, radii alternating long and short)
     * or the pentagram drawn as five crossing lines (5 corners, each a sharp turn).
     */
    private fun star(pts: List<P>, corners: List<P>): Shape? {
        val cx = pts.dropLast(1).map { it.x }.average().toFloat()
        val cy = pts.dropLast(1).map { it.y }.average().toFloat()
        val center = P(cx, cy)
        val dir = if (corners.size >= 3 && signedArea(corners) < 0) -1 else 1
        if (corners.size == 10) {
            val radii = corners.map { dist(it, center) }
            val even = radii.filterIndexed { i, _ -> i % 2 == 0 }
            val odd = radii.filterIndexed { i, _ -> i % 2 == 1 }
            val (outer, inner, first) = when {
                even.min() > odd.max() * 1.3f -> Triple(even.average().toFloat(), odd.average().toFloat(), 0)
                odd.min() > even.max() * 1.3f -> Triple(odd.average().toFloat(), even.average().toFloat(), 1)
                else -> return null
            }
            val start = atan2(corners[first].y - cy, corners[first].x - cx)
            val ring = List(10) { k ->
                val a = start + dir * PI.toFloat() * k / 5
                val r = if (k % 2 == 0) outer else inner
                P(cx + r * cos(a), cy + r * sin(a))
            }
            return Shape(Kind.STAR, listOf(ring + ring.first()))
        }
        if (corners.size == 5 && cornerAngles(corners).all { it > PENTAGRAM_TURN }) {
            val r = corners.map { dist(it, center) }.average().toFloat()
            val start = atan2(corners[0].y - cy, corners[0].x - cx)
            val step = if (wrap(atan2(corners[1].y - cy, corners[1].x - cx) - start) >= 0) 1 else -1
            val ring = List(5) { k ->
                val a = start + step * 4 * PI.toFloat() * k / 5
                P(cx + r * cos(a), cy + r * sin(a))
            }
            return Shape(Kind.STAR, listOf(ring + ring.first()))
        }
        return null
    }

    /** Regular polygon with as many vertices as [corners], matching their size and rotation. */
    private fun regularRing(corners: List<P>): List<P> {
        val cx = corners.map { it.x }.average().toFloat()
        val cy = corners.map { it.y }.average().toFloat()
        val r = corners.map { dist(it, P(cx, cy)) }.average().toFloat()
        val start = atan2(corners[0].y - cy, corners[0].x - cx)
        val dir = if (signedArea(corners) >= 0) 1 else -1
        val n = corners.size
        return List(n) { k ->
            val a = start + dir * 2 * PI.toFloat() * k / n
            P(cx + r * cos(a), cy + r * sin(a))
        }
    }

    /** Rectangle aligned with the average direction of the drawn edges; square if sides match. */
    private fun rectangle(c: List<P>): Shape {
        // Average edge angle modulo 90°, so all four edges vote for one orientation.
        var sx = 0f
        var sy = 0f
        for (i in 0 until 4) {
            val a = atan2(c[(i + 1) % 4].y - c[i].y, c[(i + 1) % 4].x - c[i].x) * 4
            sx += cos(a)
            sy += sin(a)
        }
        val theta = atan2(sy, sx) / 4
        val ux = cos(theta)
        val uy = sin(theta)
        val cx = c.map { it.x }.average().toFloat()
        val cy = c.map { it.y }.average().toFloat()
        val us = c.map { (it.x - cx) * ux + (it.y - cy) * uy }
        val vs = c.map { -(it.x - cx) * uy + (it.y - cy) * ux }
        var halfU = (us.max() - us.min()) / 2
        var halfV = (vs.max() - vs.min()) / 2
        val midU = (us.max() + us.min()) / 2
        val midV = (vs.max() + vs.min()) / 2
        val square = abs(halfU - halfV) < max(halfU, halfV) * 0.15f
        if (square) {
            val h = (halfU + halfV) / 2
            halfU = h
            halfV = h
        }
        fun corner(su: Int, sv: Int): P {
            val u = midU + su * halfU
            val v = midV + sv * halfV
            return P(cx + u * ux - v * uy, cy + u * uy + v * ux)
        }
        val ring = listOf(corner(-1, -1), corner(1, -1), corner(1, 1), corner(-1, 1))
        return Shape(if (square) Kind.SQUARE else Kind.RECTANGLE, listOf(ring + ring.first()))
    }

    /** Ellipse from the covariance of uniformly resampled boundary points; circle if round. */
    private fun ellipse(pts: List<P>): Shape {
        val ring = pts.dropLast(1)
        val cx = ring.map { it.x }.average().toFloat()
        val cy = ring.map { it.y }.average().toFloat()
        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for (p in ring) {
            val dx = p.x - cx
            val dy = p.y - cy
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        sxx /= ring.size
        syy /= ring.size
        sxy /= ring.size
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(0f, tr * tr / 4 - det))
        val l1 = tr / 2 + disc
        val l2 = max(0f, tr / 2 - disc)
        // For points spread evenly along an ellipse boundary, variance along an axis = a²/2.
        var a = sqrt(2 * l1)
        var b = sqrt(2 * l2)
        val theta = 0.5f * atan2(2 * sxy, sxx - syy)
        val circle = b > 0f && a / b < 1.18f
        if (circle) {
            val r = ring.map { dist(it, P(cx, cy)) }.average().toFloat()
            a = r
            b = r
        }
        val n = 72
        val out = List(n + 1) { k ->
            val t = 2 * PI.toFloat() * k / n
            val ex = a * cos(t)
            val ey = b * sin(t)
            P(cx + ex * cos(theta) - ey * sin(theta), cy + ex * sin(theta) + ey * cos(theta))
        }
        return Shape(if (circle) Kind.CIRCLE else Kind.ELLIPSE, listOf(out))
    }

    // --------------------------------------------------------------- helpers

    fun dist(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)

    private fun pathLength(pts: List<P>): Float = (1 until pts.size).sumOf { dist(pts[it - 1], pts[it]).toDouble() }.toFloat()

    private fun signedArea(ring: List<P>): Float =
        ring.indices.sumOf { i ->
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            (a.x * b.y - b.x * a.y).toDouble()
        }.toFloat() / 2

    /** [n] points evenly spaced along the path (timing-independent). */
    private fun resample(pts: List<P>, n: Int): List<P> {
        val total = pathLength(pts)
        if (total == 0f) return listOf(pts.first(), pts.last())
        val step = total / (n - 1)
        val out = ArrayList<P>(n)
        out += pts.first()
        var acc = 0f
        var prev = pts.first()
        var i = 1
        while (i < pts.size && out.size < n - 1) {
            val cur = pts[i]
            val d = dist(prev, cur)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val q = P(prev.x + (cur.x - prev.x) * t, prev.y + (cur.y - prev.y) * t)
                out += q
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        out += pts.last()
        return out
    }

    /** Ramer–Douglas–Peucker simplification; keeps both endpoints. */
    private fun rdp(pts: List<P>, eps: Float): List<P> {
        if (pts.size < 3) return pts
        val a = pts.first()
        val b = pts.last()
        var maxD = -1f
        var idx = 0
        for (i in 1 until pts.size - 1) {
            val d = distToSegment(pts[i], a, b)
            if (d > maxD) {
                maxD = d
                idx = i
            }
        }
        if (maxD <= eps) return listOf(a, b)
        val left = rdp(pts.subList(0, idx + 1), eps)
        val right = rdp(pts.subList(idx, pts.size), eps)
        return left.dropLast(1) + right
    }

    private fun distToSegment(p: P, a: P, b: P): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
