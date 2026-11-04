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

    private val arcs = WeakHashMap<PageStroke, FloatArray>()
    private val notArc: MutableSet<PageStroke> = java.util.Collections.newSetFromMap(WeakHashMap())

    /**
     * A circular arc the app drew (a recognised arc, or one reshaped): its two ends and the
     * point halfway along (x, y pairs); null for anything else.
     */
    fun arc(s: PageStroke): FloatArray? {
        arcs[s]?.let { return it }
        if (s in notArc || s.kind == InkKind.HIGHLIGHTER || s.kind == InkKind.SPOILER) return null
        val found = findArc(s)
        if (found == null) notArc += s else arcs[s] = found
        return found
    }

    private fun findArc(s: PageStroke): FloatArray? {
        val inputs = s.stroke.inputs
        val n = inputs.size
        if (n < MIN_ARC_INPUTS || n > MAX_INPUTS) return null
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
        if (pMax - pMin > 0.001f) return null
        val chord = hypot(xs[0] - xs[n - 1], ys[0] - ys[n - 1])
        if (chord < 2f) return null
        // The bend point: farthest from the line through the ends (the middle of a circular
        // arc; where the two arcs of a bent one meet, see arcThrough).
        var m = 0
        var far = -1f
        for (i in 1 until n - 1) {
            val d = kotlin.math.abs((xs[i] - xs[0]) * (ys[n - 1] - ys[0]) - (ys[i] - ys[0]) * (xs[n - 1] - xs[0])) / chord
            if (d > far) {
                far = d
                m = i
            }
        }
        if (m == 0 || far < 2 * ARC_TOLERANCE) return null
        val h = floatArrayOf(xs[0], ys[0], xs[m], ys[m], xs[n - 1], ys[n - 1])
        val fit = arcThrough(h)
        if (fit.size <= 6 || fit.size / 2 > 4 * ARC_SEGMENTS + 1) return null
        val tolerance = maxOf(ARC_TOLERANCE, chord * 0.01f)
        for (i in 0 until n) if (distanceToLine(xs[i], ys[i], fit) > tolerance) return null
        // Not three points of a polyline that happen to fit: curved on both sides of the bend
        // or at least on one.
        val q1 = m / 2
        val q2 = (m + n - 1) / 2
        if (PageEdits.distanceToSegment(xs[q1], ys[q1], xs[0], ys[0], xs[m], ys[m]) < ARC_TOLERANCE &&
            PageEdits.distanceToSegment(xs[q2], ys[q2], xs[m], ys[m], xs[n - 1], ys[n - 1]) < ARC_TOLERANCE
        ) {
            return null
        }
        return h
    }

    /** Distance from ([x], [y]) to the polyline [pl] (x, y pairs). */
    private fun distanceToLine(x: Float, y: Float, pl: FloatArray): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until pl.size - 2 step 2) {
            best = minOf(best, PageEdits.distanceToSegment(x, y, pl[i], pl[i + 1], pl[i + 2], pl[i + 3]))
        }
        return best
    }

    private val curves = WeakHashMap<PageStroke, FloatArray>()
    private val notCurve: MutableSet<PageStroke> = java.util.Collections.newSetFromMap(WeakHashMap())

    /**
     * A smooth open curve the app drew, not an arc: its ends and the top of each bend
     * (x, y pairs), the points [curveThrough] draws it through again; null for anything else.
     */
    fun curve(s: PageStroke): FloatArray? {
        curves[s]?.let { return it }
        if (s in notCurve || s.kind == InkKind.HIGHLIGHTER || s.kind == InkKind.SPOILER || arc(s) != null) return null
        val found = findCurve(s)
        if (found == null) notCurve += s else curves[s] = found
        return found
    }

    private fun findCurve(s: PageStroke): FloatArray? {
        val inputs = s.stroke.inputs
        val n = inputs.size
        if (n < MIN_ARC_INPUTS || n > MAX_INPUTS) return null
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
        if (pMax - pMin > 0.001f) return null
        if (hypot(xs[0] - xs[n - 1], ys[0] - ys[n - 1]) < 2f) return null
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        rdp(xs, ys, 0, n - 1, EPSILON, keep)
        val idx = (0 until n).filter { keep[it] }
        if (idx.size < MIN_CURVE_CORNERS) return null
        // Turns at the corners: all gentle for a curve, a sharp one is a corner of straight pieces.
        val turns = FloatArray(idx.size)
        for (k in 1 until idx.size - 1) {
            val a = idx[k - 1]
            val b = idx[k]
            val c = idx[k + 1]
            val t = wrap(kotlin.math.atan2(ys[c] - ys[b], xs[c] - xs[b]) - kotlin.math.atan2(ys[b] - ys[a], xs[b] - xs[a]))
            if (kotlin.math.abs(t) > MAX_CURVE_TURN) return null
            turns[k] = t
        }
        // Bends: runs of corners turning the same way; each gives the point farthest from its chord.
        val keys = ArrayList<Float>()
        keys += xs[0]
        keys += ys[0]
        var start = 0
        var sign = 0f
        fun bend(from: Int, to: Int) {
            val a = idx[from]
            val b = idx[to]
            var best = -1f
            var at = -1
            for (i in a + 1 until b) {
                val d = PageEdits.distanceToSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > best) {
                    best = d
                    at = i
                }
            }
            if (at >= 0 && best > ARC_TOLERANCE) {
                keys += xs[at]
                keys += ys[at]
            }
        }
        for (k in 1 until idx.size - 1) {
            if (kotlin.math.abs(turns[k]) < STRAIGHT_TURN) continue
            val sg = kotlin.math.sign(turns[k])
            if (sign != 0f && sg != sign) {
                // The curve turns the other way from here: a new bend starts.
                bend(start, k)
                start = k
            }
            sign = sg
        }
        bend(start, idx.size - 1)
        keys += xs[n - 1]
        keys += ys[n - 1]
        if (keys.size < 6 || keys.size > MAX_CURVE_KEYS * 2) return null
        return keys.toFloatArray()
    }

    private fun wrap(a: Float): Float {
        val twoPi = (2 * Math.PI).toFloat()
        var x = a % twoPi
        if (x > Math.PI) x -= twoPi
        if (x < -Math.PI) x += twoPi
        return x
    }

    /** A smooth line through [keys] (x, y pairs): a centripetal Catmull-Rom spline, sampled. */
    fun curveThrough(keys: FloatArray): FloatArray {
        val m = keys.size / 2
        if (m < 3) return keys
        fun x(i: Int) = keys[i.coerceIn(0, m - 1) * 2]
        fun y(i: Int) = keys[i.coerceIn(0, m - 1) * 2 + 1]
        val out = ArrayList<Float>()
        out += x(0)
        out += y(0)
        for (i in 0 until m - 1) {
            // Ends: a mirrored point beyond them, so the line leaves the end smoothly.
            val p0x = if (i == 0) 2 * x(0) - x(1) else x(i - 1)
            val p0y = if (i == 0) 2 * y(0) - y(1) else y(i - 1)
            val p3x = if (i == m - 2) 2 * x(m - 1) - x(m - 2) else x(i + 2)
            val p3y = if (i == m - 2) 2 * y(m - 1) - y(m - 2) else y(i + 2)
            val p1x = x(i)
            val p1y = y(i)
            val p2x = x(i + 1)
            val p2y = y(i + 1)
            fun knot(ax: Float, ay: Float, bx: Float, by: Float) = kotlin.math.sqrt(hypot(bx - ax, by - ay)).coerceAtLeast(1e-3f)
            val t1 = knot(p0x, p0y, p1x, p1y)
            val t2 = t1 + knot(p1x, p1y, p2x, p2y)
            val t3 = t2 + knot(p2x, p2y, p3x, p3y)
            for (k in 1..CURVE_SAMPLES) {
                val t = t1 + (t2 - t1) * k / CURVE_SAMPLES
                fun lerp(a: Float, b: Float, ta: Float, tb: Float) = if (tb - ta < 1e-6f) a else (a * (tb - t) + b * (t - ta)) / (tb - ta)
                val a1x = lerp(p0x, p1x, 0f, t1)
                val a1y = lerp(p0y, p1y, 0f, t1)
                val a2x = lerp(p1x, p2x, t1, t2)
                val a2y = lerp(p1y, p2y, t1, t2)
                val a3x = lerp(p2x, p3x, t2, t3)
                val a3y = lerp(p2y, p3y, t2, t3)
                val b1x = lerp(a1x, a2x, 0f, t2)
                val b1y = lerp(a1y, a2y, 0f, t2)
                val b2x = lerp(a2x, a3x, t1, t3)
                val b2y = lerp(a2y, a3y, t1, t3)
                out += lerp(b1x, b2x, t1, t2)
                out += lerp(b1y, b2y, t1, t2)
            }
        }
        return out.toFloatArray()
    }

    /**
     * Points of the curve from the first point of [h] through the second to the third (x, y
     * pairs). The middle point is the top of the bend: the curve there runs along the line
     * between the ends. It is two circular arcs meeting there, each through its end: one circle
     * (the arc as before) when the point is in the middle, and a sharp bend near one end with a
     * long sweep to the other (a tick) when it is off to one side. Straight pieces when the
     * three are in a row.
     */
    fun arcThrough(h: FloatArray): FloatArray {
        val ax = h[0].toDouble()
        val ay = h[1].toDouble()
        val len = hypot(h[4] - h[0], h[5] - h[1]).toDouble()
        if (len < 1e-3) return floatArrayOf(h[0], h[1], h[4], h[5])
        // The line between the ends is the x axis, the first end the origin.
        val ux = (h[4] - h[0]) / len
        val uy = (h[5] - h[1]) / len
        val vx = -uy
        val vy = ux
        val px = (h[2] - ax) * ux + (h[3] - ay) * uy
        val py = (h[2] - ax) * vx + (h[3] - ay) * vy
        if (kotlin.math.abs(py) < 0.5) return floatArrayOf(h[0], h[1], h[2], h[3], h[4], h[5])
        // Both arcs turn the same way: the one that runs left to right through the bend.
        val turn = if (py > 0) -1.0 else 1.0
        val twoPi = 2 * Math.PI
        val out = ArrayList<Float>()
        /** The arc through (qx, 0), level at the bend; from its end to the bend or the other way. */
        fun piece(qx: Double, toBend: Boolean) {
            // Centre straight under the bend, as far from it as from (qx, 0).
            val cy = (py * py - (qx - px) * (qx - px)) / (2 * py)
            val r = kotlin.math.abs(py - cy)
            val aEnd = kotlin.math.atan2(-cy, qx - px)
            val aBend = kotlin.math.atan2(py - cy, 0.0)
            val (a0, a1) = if (toBend) aEnd to aBend else aBend to aEnd
            val sweep = turn * (((turn * (a1 - a0)) % twoPi + twoPi) % twoPi)
            val steps = maxOf(4, kotlin.math.ceil(ARC_SEGMENTS * kotlin.math.abs(sweep) / Math.PI).toInt())
            for (k in (if (out.isEmpty()) 0 else 1)..steps) {
                val a = a0 + sweep * k / steps
                val x = px + r * kotlin.math.cos(a)
                val y = cy + r * kotlin.math.sin(a)
                out += (ax + x * ux + y * vx).toFloat()
                out += (ay + x * uy + y * vy).toFloat()
            }
        }
        piece(0.0, toBend = true)
        val bend = out.size
        piece(len, toBend = false)
        // Exactly through the handles, whatever the rounding.
        out[0] = h[0]
        out[1] = h[1]
        out[bend - 2] = h[2]
        out[bend - 1] = h[3]
        out[out.size - 2] = h[4]
        out[out.size - 1] = h[5]
        return out.toFloatArray()
    }

    /**
     * ([x], [y]) moved onto the nearest line from ([fromX], [fromY]) at a multiple of 15°,
     * when it is within a few degrees of one; null otherwise.
     */
    fun level(x: Float, y: Float, fromX: Float, fromY: Float): FloatArray? {
        val d = hypot(x - fromX, y - fromY)
        if (d < 1f) return null
        val step = Math.toRadians(LEVEL_STEP_DEGREES.toDouble())
        val a = kotlin.math.atan2((y - fromY).toDouble(), (x - fromX).toDouble())
        val snapped = Math.round(a / step) * step
        if (kotlin.math.abs(snapped - a) > Math.toRadians(LEVEL_TOLERANCE_DEGREES.toDouble())) return null
        return floatArrayOf(fromX + d * kotlin.math.cos(snapped).toFloat(), fromY + d * kotlin.math.sin(snapped).toFloat())
    }

    /** Connection points of [s]: the corners and ends of its pieces, the ends of an arc. */
    fun points(s: PageStroke): FloatArray? {
        arc(s)?.let { return floatArrayOf(it[0], it[1], it[4], it[5]) }
        curve(s)?.let { return floatArrayOf(it[0], it[1], it[it.size - 2], it[it.size - 1]) }
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
            val pl = polyline(s) ?: arc(s)?.let(::arcThrough) ?: curve(s)?.let(::curveThrough) ?: continue
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
        return template.with(Stroke(template.stroke.brush, batch))
    }

    private const val STEP = 2f
    private const val MIN_ARC_INPUTS = 12
    private const val MIN_CURVE_CORNERS = 6
    private const val MAX_CURVE_KEYS = 12
    private const val CURVE_SAMPLES = 16

    /** Turns (radians) at the corners of a curve: at most ~35°, under ~2° is straight on. */
    private const val MAX_CURVE_TURN = 0.6f
    private const val STRAIGHT_TURN = 0.035f
    private const val MAX_ARC_RADIUS = 20_000f
    private const val ARC_TOLERANCE = 0.6f
    private const val ARC_SEGMENTS = 64
    private const val LEVEL_STEP_DEGREES = 15f
    private const val LEVEL_TOLERANCE_DEGREES = 4f
    private const val EPSILON = 0.6f
    private const val MAX_CORNERS = 16
    private const val MAX_INPUTS = 4000

    /** Lines pull from a little less far than points, so a point near a line still wins. */
    private const val LINE_REACH = 0.7f
}
