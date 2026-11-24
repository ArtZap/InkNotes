package dev.inknotes.math

import dev.inknotes.data.tr
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One pen stroke in page coordinates; [order] is its place in writing order, [times] the
 * milliseconds of each point since the stroke began (for the handwriting reader).
 */
class Ink(val xs: FloatArray, val ys: FloatArray, val order: Int = 0, val times: LongArray? = null) {
    val left = xs.min()
    val right = xs.max()
    val top = ys.min()
    val bottom = ys.max()
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2

    /** Mostly horizontal: a minus, fraction bar or half of "=". */
    val isDash get() = width >= 2.2f * max(height, 0.5f)
}

class Box(var left: Float, var top: Float, var right: Float, var bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2

    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    companion object {
        fun of(strokes: List<Ink>) = Box(strokes.minOf { it.left }, strokes.minOf { it.top }, strokes.maxOf { it.right }, strokes.maxOf { it.bottom })
        fun ofBoxes(boxes: List<Box>) = boxes.reduce(Box::union)
    }
}

/** Reads a run of handwriting on one level (digits, letters, operators); candidates best first. */
fun interface RunReader {
    fun read(strokes: List<Ink>, lineHeight: Float): List<String>

    /** Whether the reader was sure of its first guess for [strokes], read before: true when it cannot tell. */
    fun sure(strokes: List<Ink>): Boolean = true
}

/** A formula as text with gaps: [Text] is structure found from the layout, [Hole] a run to read. */
sealed class Piece {
    /**
     * Structure found from the layout. A sign told by its shape (+, −, a dot, °, a prime) keeps
     * the [strokes] it was drawn with and the [level] it is on (0 the line, 1 raised, 2 lowered),
     * so that it can be corrected like a symbol read.
     */
    class Text(val s: String, val strokes: List<Ink> = emptyList(), val level: Int = 0) : Piece()

    /** A run to read, on one [level], made of [glyphs] left to right (each its strokes). */
    class Hole(val strokes: List<Ink>, val h: Float, val glyphs: List<List<Ink>> = listOf(strokes), val level: Int = 0) : Piece()
}

/**
 * Turns the strokes of one handwritten formula into [Piece]s from their 2-D arrangement:
 * fraction bars with something above and below, raised exponents and lowered indices,
 * radicals, tall brackets (a matrix when they hold several rows, a determinant between bars)
 * and the integral sign with its limits. Everything else is left to the handwriting reader.
 */
object MathLayout {
    /** An exponent is smaller than this part of what it is raised over (a matrix's rows are alike). */
    private const val EXPONENT_SIZE = 0.75f

    /** The farthest a long bar's numerator or denominator stands from it, in line heights. */
    private const val LONG_BAR_REACH_MAX = 2.5f


    /**
     * Thresholds moved a little, to read a formula again: when a reading with them gives
     * another answer, the layout is not clear-cut and the answer is shown as unsure. Chosen on
     * CROHME (tools/formula-bench.sh): each flags far more wrong answers than right ones.
     */
    enum class Shift(val deltas: Map<String, Float>) {
        NONE(emptyMap()),
        SUP_EASIER(mapOf("supBottom" to 0.05f, "supRaised" to 0.05f, "supLowBottom" to 0.04f, "supLowMiddle" to 0.04f, "supAbove" to 0.05f)),
        SUP_HARDER(mapOf("supBottom" to -0.1f, "supRaised" to -0.1f, "supLowBottom" to -0.08f, "supLowMiddle" to -0.08f, "supAbove" to -0.1f)),
        SUB_EASIER(mapOf("subRelBelow" to -0.05f, "subRelTop" to -0.05f, "subBottom" to -0.05f, "subTop" to -0.025f)),
        SUB_HARDER(mapOf("subRelBelow" to 0.1f, "subRelTop" to 0.1f, "subBottom" to 0.1f, "subTop" to 0.05f)),
        JOIN_EASIER(mapOf("joinGap" to 0.08f, "joinOverlap" to -0.1f)),
        JOIN_HARDER(mapOf("joinGap" to -0.06f, "joinOverlap" to 0.1f)),
        FRAC_EASIER(mapOf("barWidth" to -0.1f, "fracReach" to 0.2f)),
        FRAC_HARDER(mapOf("barWidth" to 0.1f, "fracReach" to -0.2f)),
        ;

        companion object {
            val CHECKS = entries - NONE
        }
    }

    /**
     * The formula as text with holes to read. With a [shift] the thresholds are moved for this
     * one call; calls take turns, as the thresholds are shared.
     */
    fun formula(strokes: List<Ink>, h: Float, axis: Float, shift: Shift = Shift.NONE): List<Piece> = synchronized(Tune) {
        if (shift == Shift.NONE) return@synchronized layOut(strokes, h, axis)
        val saved = shift.deltas.keys.associateWith { Tune.values()[it]!! }
        try {
            for ((k, d) in shift.deltas) Tune.set(k, saved[k]!! + d)
            layOut(strokes, h, axis)
        } finally {
            for ((k, v) in saved) Tune.set(k, v)
        }
    }

    /** The same layout: the same text around holes of the same strokes. */
    fun same(a: List<Piece>, b: List<Piece>): Boolean = a.size == b.size && a.indices.all { i ->
        val x = a[i]
        val y = b[i]
        when (x) {
            is Piece.Text -> y is Piece.Text && x.s == y.s
            is Piece.Hole -> y is Piece.Hole && x.strokes == y.strokes
        }
    }

    private fun layOut(strokes: List<Ink>, h: Float, axis: Float): List<Piece> {
        val out = ArrayList<Piece>()
        Builder(h, out).flow(items(strokes, h), axis)
        // Join neighbouring text pieces.
        val merged = ArrayList<Piece>()
        for (p in out) {
            val last = merged.lastOrNull()
            if (p is Piece.Text && last is Piece.Text && p.strokes.isEmpty() && last.strokes.isEmpty()) merged[merged.size - 1] = Piece.Text(last.s + p.s) else merged += p
        }
        return merged
    }

    // ------------------------------------------------------------ glyphs

    internal class Glyph(val strokes: List<Ink>) {
        val box = Box.of(strokes)
        val isDash get() = strokes.size == 1 && strokes[0].isDash
    }

    internal sealed class Item {
        abstract val box: Box
    }

    internal class G(val glyph: Glyph) : Item() {
        override val box get() = glyph.box
    }

    internal class Frac(override val box: Box, val num: List<Item>, val den: List<Item>, val bar: Box) : Item()
    internal class Root(override val box: Box, val inner: List<Item>, val degree: List<Item> = emptyList()) : Item()
    internal class Group(override val box: Box, val open: String, val close: String, val inner: List<Item>) : Item()
    internal class Grid(override val box: Box, val rows: List<List<List<Item>>>, val det: Boolean) : Item()
    internal class IntSign(override val box: Box, val lo: List<Item>, val hi: List<Item>) : Item()
    internal class Lit(override val box: Box, val s: String) : Item()

    /** Fraction bars stay alone; other strokes that overlap like the parts of one symbol are joined. */
    internal fun items(strokes: List<Ink>, h: Float): List<Item> {
        val bars = strokes.filter { isBar(it, strokes, h) }.toSet()
        // Brackets, ∫ and √ hold other symbols without being part of them; a plain upright
        // (the stem of d, a 1) is not one of them.
        val containers = strokes.filter {
            (tallShape(Glyph(listOf(it)), h) in CONTAINER_SHAPES && !crossedUpright(it, strokes, h)) || isRadical(it, h)
        }.toSet()
        val rings = BooleanArray(strokes.size) { ring(strokes[it], h) }
        val parent = IntArray(strokes.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) x = parent[x]
            return x
        }
        for (i in strokes.indices) for (j in i + 1 until strokes.size) {
            val a = strokes[i]
            val b = strokes[j]
            if (a in bars || b in bars) continue
            // Over and under a fraction bar: numerator and denominator, however close (a small ½).
            // Not the body of a 5 and its own bar, level with a long low √ beside them (325²).
            val fiveBar = Tune.rootNotBar > 0f && (cap(a, b, h) || cap(b, a, h))
            if (!fiveBar && bars.any { bar ->
                    bar.cy > min(a.bottom, b.bottom) - 0.05f * h && bar.cy < max(a.top, b.top) + 0.05f * h &&
                        bar.left < a.right && bar.right > a.left && bar.left < b.right && bar.right > b.left
                }
            ) continue
            if (a in containers || b in containers) {
                // Only the hooks of a square bracket join a tall stroke.
                if (!hookOf(a, b, h) && !hookOf(b, a, h)) continue
            }
            val xOverlap = min(a.right, b.right) - max(a.left, b.left)
            val yGap = max(a.top, b.top) - min(a.bottom, b.bottom)
            val narrow = max(min(a.width, b.width), 0.08f * h)
            // A degree sign close to the top right of a digit is not part of it.
            if ((rings[i] && degreeBeside(a, b, h)) || (rings[j] && degreeBeside(b, a, h))) continue
            val dot = min(max(a.width, a.height), max(b.width, b.height)) < 0.2f * h
            // Crossing strokes (+, x, t) touch at a point; a vertical line has no width to overlap.
            val crossing = yGap <= 0f && (a.cx in b.left..b.right || b.cx in a.left..a.right)
            // A dash joins what it touches (the top of a 5, the bar of T or π); over a gap it is
            // a bar of its own (√, an overline).
            val stackGap = if ((a.isDash && a.width > 0.5f * h) || (b.isDash && b.width > 0.5f * h)) 0.1f * h else Tune.joinGap * h
            // Two whole symbols one above the other (an exponent written over the next letter, 8
            // over a d) are taller together than any symbol: not the parts of one, like the
            // loops of an 8 or a B.
            val stacked = yGap >= -0.05f * h && !a.isDash && !b.isDash && min(a.height, b.height) >= 0.4f * h &&
                max(a.bottom, b.bottom) - min(a.top, b.top) > 1.3f * h
            val joined = ((xOverlap > Tune.joinOverlap * narrow || crossing) && yGap < stackGap && !stacked) ||
                // the flag of 1, the bar of a crossed 7 or z: a short straight tick touching a stroke
                tick(a, b, h) || tick(b, a, h) ||
                // the top bar of a 5 drawn as its own stroke, just touching the body's top end
                cap(a, b, h) || cap(b, a, h) ||
                // the dot of i or j, the dots of ÷
                (dot && yGap < Tune.dotGap * h && (b.cx in a.left - 0.1f * h..a.right + 0.1f * h || a.cx in b.left - 0.1f * h..b.right + 0.1f * h)) ||
                // a big dot well over a short stem: an i written with a thick pen, dotted high
                iDot(a, b, h) || iDot(b, a, h) ||
                // an x written as two arcs back to back, ")(" touching in the middle
                arcsOfX(a, b, h) || arcsOfX(b, a, h)
            // A decimal comma touching the digit before it (11,88 with the comma under the 8):
            // a short straight tick hanging under its right part is not part of it; joined,
            // the digit stood tall as a brace.
            // The number goes on right after it on the line (8 of 11,88): a comma has digits after it.
            fun commaUnder(c: Ink, d: Ink) = Tune.commaApart > 0f && max(c.width, c.height) < 0.45f * h && c.height < 0.45f * d.height &&
                d.height in 0.5f * h..1.4f * h && c.top > d.bottom - 0.2f * d.height && c.bottom > d.bottom + 0.1f * h && c.cx > d.cx && straight(c, 0.2f) &&
                strokes.any { e ->
                    e !== c && e !== d && e.left > c.cx && e.left - c.right < 0.8f * h && e.height > 0.5f * d.height &&
                        abs(e.bottom - d.bottom) < 0.35f * d.height
                }
            if (joined && (commaUnder(a, b) || commaUnder(b, a))) continue
            if (joined) parent[find(i)] = find(j)
        }
        return strokes.indices.groupBy(::find).values.map { idx -> G(Glyph(idx.map { strokes[it] })) }
    }

    /**
     * [l] and [r] are the halves of an x drawn as two arcs, ")" then "(": side by side over the
     * same height, [l] bulging right and [r] bulging left, nearest each other about half way
     * down and touching there. (In CROHME Train one x in twenty is written so, and was taken
     * for two symbols, the second an index.) Two letters side by side ("cc", "()", "oc")
     * are nearest at their ends, or do not bulge towards each other.
     */
    private fun arcsOfX(l: Ink, r: Ink, h: Float): Boolean {
        if (Tune.xArcs <= 0f || l.cx >= r.cx || l.isDash || r.isDash) return false
        val lo = max(l.top, r.top)
        val hi = min(l.bottom, r.bottom)
        if (hi - lo < 0.7f * min(l.height, r.height) || max(l.height, r.height) < 0.25f * h) return false
        if (bulge(l, right = true) < 0.15f || bulge(r, right = false) < 0.15f) return false
        // Nearest points of the two strokes.
        var best = Float.MAX_VALUE
        var at = 0f
        for (i in l.xs.indices) for (j in r.xs.indices) {
            val d = hypot(l.xs[i] - r.xs[j], l.ys[i] - r.ys[j])
            if (d < best) {
                best = d
                at = (l.ys[i] + r.ys[j]) / 2
            }
        }
        val top = min(l.top, r.top)
        val tall = max(l.bottom, r.bottom) - top
        return best < 0.12f * h && (at - top) / tall in 0.2f..0.8f
    }

    /**
     * How far an open stroke bows out past both its ends, to the right ([right]) or the left,
     * in its widths: its farthest point that way must lie between the ends, one above the
     * other (")" and "ɔ" bow right, "(" and "c" left). 0 when it does not.
     */
    private fun bulge(s: Ink, right: Boolean): Float {
        val n = s.xs.size
        if (n < 3 || s.width <= 0f) return 0f
        val y0 = min(s.ys[0], s.ys[n - 1])
        val y1 = max(s.ys[0], s.ys[n - 1])
        if (y1 - y0 < 0.3f * s.height) return 0f
        val k = if (right) s.xs.indices.maxByOrNull { s.xs[it] }!! else s.xs.indices.minByOrNull { s.xs[it] }!!
        val margin = 0.15f * (y1 - y0)
        if (s.ys[k] < y0 + margin || s.ys[k] > y1 - margin) return 0f
        val out = if (right) s.xs[k] - max(s.xs[0], s.xs[n - 1]) else min(s.xs[0], s.xs[n - 1]) - s.xs[k]
        return out / s.width
    }

    /**
     * [d] is the dot of an i whose stem is [s]: a small blob over a short narrow upright
     * (lower-case height), right above it, the gap less than the stem is tall.
     */
    private fun iDot(d: Ink, s: Ink, h: Float): Boolean {
        if (max(d.width, d.height) >= 0.28f * h) return false
        if (s.width >= 0.25f * h || s.height < 0.25f * h || s.height > 0.8f * h || s.height < 2.5f * max(s.width, 0.05f * h)) return false
        val gap = s.top - d.bottom
        return gap > -0.05f * h && gap < max(0.6f * h, 1.1f * s.height) && abs(d.cx - s.cx) < 0.22f * h
    }

    /**
     * The upright of a "+" drawn a little curved, about as tall as the digits: a short dash
     * crosses it near both middles. It looks like a bracket on its own but holds nothing.
     */
    internal fun crossedUpright(s: Ink, all: List<Ink>, h: Float): Boolean {
        if (s.height >= TALL * h) return false
        return all.any { d ->
            d !== s && d.isDash && d.width in 0.4f * s.height..2f * s.height &&
                d.cy in s.top + 0.2f * s.height..s.bottom - 0.2f * s.height &&
                s.cx in d.left + 0.2f * d.width..d.right - 0.2f * d.width
        }
    }

    /** A small closed loop: a degree sign (or the letter o written small). */
    internal fun ring(s: Ink, h: Float): Boolean {
        val size = max(s.width, s.height)
        if (size > 0.45f * h || size < 0.05f * h || s.xs.size < 5) return false
        if (s.height > 1.7f * s.width || s.width > 1.7f * s.height) return false
        var len = 0f
        for (k in 1 until s.xs.size) len += hypot(s.xs[k] - s.xs[k - 1], s.ys[k] - s.ys[k - 1])
        val gap = hypot(s.xs.last() - s.xs[0], s.ys.last() - s.ys[0])
        return gap < 0.4f * size && len > 2.3f * size
    }

    /** Ring [d] is a degree sign at the top right of [s] (30°), not a part of it like the loop of P. */
    private fun degreeBeside(d: Ink, s: Ink, h: Float): Boolean =
        max(d.width, d.height) < 0.4f * s.height && d.cx > s.right - 0.3f * d.width && d.bottom < s.top + 0.55f * s.height &&
            // beside the top, not over it like the dot of an i
            (d.left > s.right || d.bottom > s.top + 0.15f * s.height)

    /** No point farther from the line between the ends than [tol] of its length (pen jitter allowed). */
    internal fun straight(s: Ink, tol: Float): Boolean {
        val dx = s.xs.last() - s.xs[0]
        val dy = s.ys.last() - s.ys[0]
        val len = hypot(dx, dy)
        if (len <= 0f) return false
        for (k in s.xs.indices) {
            val d = abs((s.xs[k] - s.xs[0]) * dy - (s.ys[k] - s.ys[0]) * dx) / len
            if (d > tol * len) return false
        }
        return true
    }

    /**
     * Dash [d] lies on top of [s] over part of it, an end touching [s] (the bar of a 5). A bar
     * drawn long, well past the body, counts only when its left end is where the body was begun
     * or at its very top: a fraction bar over a digit reaches past it on both sides.
     */
    internal fun cap(d: Ink, s: Ink, h: Float): Boolean {
        val body = max(s.width, 0.3f * h)
        if (!d.isDash || d.width > 1.7f * body || s.height < 0.3f * h) return false
        if (min(d.right, s.right) - max(d.left, s.left) <= 0f || d.cy > s.top + 0.2f * s.height) return false
        val tol = max(0.12f * h, 0.12f * s.height)
        if (d.width > 1.2f * body) {
            val end = if (d.xs[0] <= d.xs.last()) 0 else d.xs.lastIndex
            var top = 0
            for (k in s.ys.indices) if (s.ys[k] < s.ys[top]) top = k
            return listOf(0, top).any { k -> abs(s.xs[k] - d.xs[end]) < tol && abs(s.ys[k] - d.ys[end]) < tol }
        }
        for (end in listOf(0, d.xs.lastIndex)) {
            for (k in s.xs.indices) if (abs(s.xs[k] - d.xs[end]) < tol && abs(s.ys[k] - d.ys[end]) < tol) return true
        }
        return false
    }

    private fun tick(t: Ink, other: Ink, h: Float): Boolean {
        if (max(t.width, t.height) > 0.45f * h || t.xs.size < 2 || !straight(t, 0.2f)) return false
        // Hanging from the foot it is a decimal comma (1021,41), not a flag or a bar.
        if (Tune.commaSlant > 0f && t.top > other.bottom - 0.2f * other.height && t.bottom > other.bottom + 0.1f * h) return false
        val tol = 0.12f * h
        fun near(x: Float, y: Float): Boolean {
            for (k in other.xs.indices) if (abs(other.xs[k] - x) < tol && abs(other.ys[k] - y) < tol) return true
            return false
        }
        // An end touches the other stroke, or the tick crosses it.
        return near(t.xs[0], t.ys[0]) || near(t.xs.last(), t.ys.last()) || near((t.xs[0] + t.xs.last()) / 2, (t.ys[0] + t.ys.last()) / 2)
    }

    private fun hookOf(tall: Ink, hook: Ink, h: Float): Boolean =
        tall.height > TALL * h && hook.isDash && hook.width < 0.8f * h &&
            (abs(hook.cy - tall.top) < 0.25f * h || abs(hook.cy - tall.bottom) < 0.25f * h) &&
            hook.left < tall.right + 0.15f * h && hook.right > tall.left - 0.15f * h

    /**
     * A horizontal stroke with real strokes (not dots) both above and below it. A short one
     * is the bar of a small fraction (an exponent ½, ⅛) when what it holds is small and it is
     * about as wide as that.
     */
    internal fun isBar(s: Ink, all: List<Ink>, h: Float): Boolean {
        if (!s.isDash || s.width < 0.25f * h) return false
        // The top of a 5 drawn as its own stroke, under a fraction bar, is no bar itself.
        if (all.any { it !== s && cap(s, it, h) }) return false
        fun over(o: Ink) = o.cx in s.left - 0.1f * h..s.right + 0.1f * h && o.height + o.width > 0.3f * h
        val above = all.filter { it !== s && over(it) && it.bottom <= s.cy + 0.1f * h && s.top - it.bottom < 0.9f * h }
        val below = all.filter { it !== s && over(it) && it.top >= s.cy - 0.1f * h && it.top - s.bottom < 0.9f * h }
        if (above.isEmpty() || below.isEmpty()) return false
        if (s.width >= 0.45f * h) return true
        return (above + below).all { it.height < 0.6f * h } && s.width >= 0.7f * max(above.maxOf { it.width }, below.maxOf { it.width })
    }

    /** √ drawn in one stroke: low on the left, a long horizontal top over its content. */
    internal fun isRadical(s: Ink, h: Float): Boolean {
        if (s.width < 0.9f * h || s.height < 0.6f * h || s.xs.size < 4) return false
        var topMin = Float.MAX_VALUE
        var topMax = -Float.MAX_VALUE
        var leftY = 0f
        var leftN = 0
        for (k in s.xs.indices) {
            if (s.ys[k] < s.top + 0.18f * s.height) {
                topMin = min(topMin, s.xs[k])
                topMax = max(topMax, s.xs[k])
            }
            if (s.xs[k] < s.left + 0.15f * s.width) {
                leftY += s.ys[k]
                leftN++
            }
        }
        if (leftN == 0 || topMax - topMin < 0.5f * s.width) return false
        // The tick goes down and back up to the bar; a 7 ends at its lowest point.
        var low = 0
        for (k in s.ys.indices) if (s.ys[k] > s.ys[low]) low = k
        if ((low until s.ys.size).none { s.ys[it] < s.top + 0.2f * s.height }) return false
        // The tick starts well below the top bar, and the bar is at the right.
        return leftY / leftN > s.top + 0.35f * s.height && topMax > s.right - 0.1f * s.width
    }

    /**
     * √ drawn in two or three touching strokes (a short down stroke, a long up stroke, the bar):
     * a bar over at least half of it reaching its right end, the lowest point in its left part
     * and the left end well below the bar.
     */
    internal fun radicalOfParts(g: Glyph, h: Float): Boolean {
        if (g.strokes.size !in 2..3) return false
        val b = g.box
        if (b.width < 0.9f * h || b.height < 0.6f * h) return false
        val bar = g.strokes.firstOrNull { it.isDash && it.width >= 0.5f * b.width && it.cy < b.top + 0.18f * b.height && it.right > b.right - 0.1f * b.width }
            ?: return false
        var lowX = 0f
        var lowY = -Float.MAX_VALUE
        var leftY = 0f
        var leftN = 0
        for (s in g.strokes) {
            if (s === bar) continue
            // The rest is the tick: no wider than the part of the sign left of the bar's middle.
            if (s.cx > bar.cx) return false
            for (k in s.xs.indices) {
                if (s.ys[k] > lowY) { lowY = s.ys[k]; lowX = s.xs[k] }
                if (s.xs[k] < b.left + 0.15f * b.width) { leftY += s.ys[k]; leftN++ }
            }
        }
        return leftN > 0 && lowX < b.left + 0.6f * b.width && lowY > b.top + 0.6f * b.height && leftY / leftN > b.top + 0.3f * b.height
    }

    /** Shape of a tall symbol: ( ) | { } ∫, or null. */
    internal fun tallShape(g: Glyph, h: Float): Char? {
        val b = g.box
        // An integral sign is often written only a little taller than d or f.
        if (b.height < min(PAREN_MIN, if (g.strokes.size == 1) INTEGRAL_MIN else TALL) * h) return null
        val integralTall = b.height >= (if (g.strokes.size == 1) INTEGRAL_MIN else TALL) * h
        val w = max(b.width, 0.12f * b.height)
        fun meanX(from: Float, to: Float): Float? {
            var s = 0f
            var n = 0
            for (st in g.strokes) for (k in st.xs.indices) {
                val t = (st.ys[k] - b.top) / b.height
                if (t in from..to) {
                    s += st.xs[k]
                    n++
                }
            }
            return if (n == 0) null else s / n
        }
        val topX = meanX(0f, 0.1f) ?: return null
        val botX = meanX(0.9f, 1f) ?: return null
        val midX = meanX(0.4f, 0.6f) ?: return null
        val q1 = meanX(0.2f, 0.3f) ?: midX
        val q3 = meanX(0.7f, 0.8f) ?: midX
        val ends = (topX + botX) / 2
        // ∫: the ends hook away from the line of the middle; a slanted bar stays on it.
        val q35 = meanX(0.3f, 0.4f) ?: midX
        val q65 = meanX(0.6f, 0.7f) ?: midX
        val slope = (q65 - q35) / 0.3f
        val topHook = topX - (midX - 0.45f * slope)
        val botHook = (midX + 0.45f * slope) - botX
        if (integralTall && b.width < 0.8f * b.height && integralEnds(g)) {
            if (topHook > 0.2f * w && botHook > 0.2f * w) return '∫'
            // A long, almost straight ∫ with small hooks: its ends still lie either side of it.
            val s = g.strokes[0]
            val (top, bottom) = if (s.ys[0] < s.ys.last()) 0 to s.ys.lastIndex else s.ys.lastIndex to 0
            if (g.strokes.size == 1 && s.xs[top] - (midX - 0.5f * slope) > 0.3f * w && (midX + 0.5f * slope) - s.xs[bottom] > 0.12f * w) return '∫'
        }
        if (b.height < TALL * h) {
            // Round brackets as tall as the digits: a narrow arc, both ends to one side.
            if (g.strokes.size != 1 || b.height < PAREN_MIN * h || b.width > 0.45f * b.height || verticalTurns(g.strokes[0]) != 0) return null
            return when {
                topX - midX > 0.25f * w && botX - midX > 0.25f * w -> '('
                midX - topX > 0.25f * w && midX - botX > 0.25f * w -> ')'
                else -> null
            }
        }
        // A bracket drawn in one stroke runs one way, top to bottom (or back); a y goes down,
        // up its second arm and down its tail.
        val one = g.strokes.singleOrNull()
        val monotonic = one == null || verticalTurns(one) == 0
        return when {
            ends - midX > 0.2f * w && monotonic -> if (cusp(g, b, w, left = true) || (q1 + q3) / 2 - midX > 0.32f * w) '{' else '('
            midX - ends > 0.2f * w && monotonic -> if (cusp(g, b, w, left = false) || midX - (q1 + q3) / 2 > 0.32f * w) '}' else ')'
            b.width < 0.25f * b.height -> '|'
            else -> null
        }
    }

    /** Times the stroke turns between going down and going up (by more than a sixth of its height). */
    internal fun verticalTurns(s: Ink): Int {
        val step = max(0.5f, s.height / 6f)
        var turns = 0
        var dir = 0
        var ext = s.ys[0]
        for (y in s.ys) {
            when {
                dir >= 0 && y > ext -> { ext = y; if (dir == 0 && y - s.ys[0] > step) dir = 1 }
                dir <= 0 && y < ext -> { ext = y; if (dir == 0 && s.ys[0] - y > step) dir = -1 }
                dir == 1 && ext - y > step -> { turns++; dir = -1; ext = y }
                dir == -1 && y - ext > step -> { turns++; dir = 1; ext = y }
            }
        }
        return turns
    }

    /**
     * A one-stroke ∫ runs from its top end, at the right, to its bottom end, at the left. A
     * handwritten 2 has the same hooks, but starts at the left and ends at the right of its base;
     * a y ends at the bottom left too, but starts at the top left.
     */
    private fun integralEnds(g: Glyph): Boolean {
        if (g.strokes.size != 1) return true
        val s = g.strokes[0]
        val n = s.xs.size
        if (n < 2) return false
        val (top, bottom) = if (s.ys[0] < s.ys[n - 1]) 0 to n - 1 else n - 1 to 0
        val b = g.box
        return s.xs[top] > s.xs[bottom] && s.xs[top] > b.left + 0.5f * b.width &&
            s.ys[top] < b.top + 0.3f * b.height && s.ys[bottom] > b.bottom - 0.3f * b.height
    }

    /**
     * A brace has a sharp point in the middle: close to its tip the curve is already far
     * from it, where a round bracket is still almost vertical.
     */
    private fun cusp(g: Glyph, b: Box, w: Float, left: Boolean): Boolean {
        var tipX = if (left) Float.MAX_VALUE else -Float.MAX_VALUE
        var tipY = 0f
        for (s in g.strokes) for (k in s.xs.indices) {
            val t = (s.ys[k] - b.top) / b.height
            if (t !in 0.3f..0.7f) continue
            if (if (left) s.xs[k] < tipX else s.xs[k] > tipX) {
                tipX = s.xs[k]
                tipY = s.ys[k]
            }
        }
        val d = 0.12f * b.height
        var above: Float? = null
        var below: Float? = null
        var bestA = Float.MAX_VALUE
        var bestB = Float.MAX_VALUE
        for (s in g.strokes) for (k in s.xs.indices) {
            val da = abs(s.ys[k] - (tipY - d))
            val db = abs(s.ys[k] - (tipY + d))
            if (da < bestA) { bestA = da; above = s.xs[k] }
            if (db < bestB) { bestB = db; below = s.xs[k] }
        }
        if (above == null || below == null) return false
        val away = if (left) (above + below) / 2 - tipX else tipX - (above + below) / 2
        return away > 0.22f * w
    }

    // ------------------------------------------------------------ building

    private enum class Level { BASE, SUP, SUB }

    private class Builder(val h: Float, val out: MutableList<Piece>) {
        private fun text(s: String) {
            out += Piece.Text(s)
        }

        /** A sign drawn as [g], on [level]: text, with its strokes. */
        private fun signText(s: String, g: Glyph, level: Level) {
            out += Piece.Text(s, g.strokes.sortedBy { it.order }, level.ordinal)
        }

        fun flow(items0: List<Item>, axis: Float?) {
            if (items0.isEmpty()) throw MathError(tr("пусто", "empty"))
            val items = talls(radicals(fractions(items0.toMutableList()))).sortedBy { it.box.left }
            val ax0 = axis ?: median(items.filter { it.box.height > 0.4f * h }.map { it.box.cy }) ?: items.first().box.cy
            // A long line written downhill (or uphill): the middle line follows the full-size
            // symbols, so 12x + 11 at its end does not sink into indices.
            val full = items.filter { it.box.height in 0.6f * h..1.6f * h }
            val slope = if (full.size >= 3 && full.maxOf { it.box.cx } - full.minOf { it.box.cx } > 3 * h) {
                val pairs = ArrayList<Float>()
                for (i in full.indices) for (j in i + 1 until full.size) {
                    val dx = full[j].box.cx - full[i].box.cx
                    if (abs(dx) > 0.5f * h) pairs += (full[j].box.cy - full[i].box.cy) / dx
                }
                (median(pairs) ?: 0f).coerceIn(-0.2f, 0.2f)
            } else {
                0f
            }
            val pivotX = if (slope == 0f) 0f else median(full.map { it.box.cx })!!
            // Through the full-size symbols when it slants; else the given line.
            val pivotY = if (slope == 0f) ax0 else median(full.map { it.box.cy - slope * (it.box.cx - pivotX) })!!
            fun axisAt(x: Float) = pivotY + slope * (x - pivotX)
            var ref: Box? = null
            var refStructure = false
            val run = ArrayList<Glyph>()
            var cur = Level.BASE
            fun flush() {
                if (run.isEmpty()) return
                // Symbols left to right, each in its own stroke order: a + added later between
                // 2 and 3 must still read 2+3.
                val glyphs = run.sortedBy { it.box.left }.map { g -> g.strokes.sortedBy { it.order } }
                out += Piece.Hole(glyphs.flatten(), h, glyphs, cur.ordinal)
                run.clear()
            }
            // An exponent of an exponent (e^{−x²}, a^{b^c}): the last symbol at each depth of
            // the exponent being written, to tell when the next one is raised over it again.
            val supRefs = ArrayList<Box>()
            // The bar of the fraction just written (null: the last item was not one).
            var barAfter: Float? = null
            // The last item was a decimal comma (or point) low beside a number: what follows at
            // that number's size goes on with it (19,63 read 19⁶³ when 63 sat a little higher).
            var afterComma = false
            // Raised and lowered parts are wrapped in ^{…} and _{…}.
            fun enter(level: Level) {
                    if (level == cur) return
                flush()
                if (cur == Level.SUP) repeat(supRefs.size - 1) { text("}") }
                supRefs.clear()
                if (cur != Level.BASE) text("}")
                when (level) {
                    Level.SUP -> text("^{")
                    Level.SUB -> text("_{")
                    Level.BASE -> {}
                }
                cur = level
            }
            for (item in items) {
                // A small fraction up at the top right of the symbol before it is its exponent: 22^½.
                if (item is Frac && raisedFraction(item, ref)) {
                    enter(Level.SUP)
                    flush()
                    emit(item)
                    continue
                }
                if (item !is G) {
                    enter(Level.BASE)
                    flush()
                    emit(item)
                    ref = item.box
                    refStructure = true
                    barAfter = (item as? Frac)?.bar?.cy
                    continue
                }
                val b = item.box
                val ax = axisAt(b.cx)
                val r0 = ref
                val level = if (Tune.afterComma > 0f && afterComma && r0 != null && !refStructure && cur == Level.BASE &&
                    b.height > 0.6f * r0.height && b.height < 1.4f * r0.height && abs(b.bottom - r0.bottom) < 0.35f * r0.height
                ) {
                    Level.BASE
                } else {
                    level(b, ref, ax, tick = item.glyph.strokes.size == 1 && straight(item.glyph.strokes[0], 0.2f))
                }
                    afterComma = r0 != null && !refStructure && level == Level.BASE && b.height < max(0.45f, Tune.longCommaSize) * r0.height &&
                    b.width < 0.8f * max(b.height, 0.5f * b.width + 0.01f) && b.top > r0.top + 0.6f * r0.height && b.left > r0.cx
                // A short dash is a minus, not a dot (the − of f⁻¹ read f·1).
                val tiny = max(b.width, b.height) < 0.22f * h && !(Tune.shortMinus > 0f && b.width >= 3f * max(b.height, 0.5f) && b.width >= Tune.shortMinus * h)
                val r = ref
                // A dot about the middle of the line is multiplication, even drawn as a tiny
                // ring or a little high: not a degree sign (those sit at the top), a prime or
                // an exponent.
                // A dot low beside a symbol (8.5, 0.7) is a decimal point: it stays in the run.
                val point = Tune.decimalPoint > 0.5f && tiny && r != null && !refStructure && b.cy > r.top + Tune.pointLow * r.height && b.left > r.cx
                if (tiny && !point && abs(b.cy - ax) < 0.2f * h && (r == null || b.cy > r.top + 0.15f * r.height)) {
                    enter(Level.BASE)
                    flush()
                    signText("*", item.glyph, Level.BASE)
                    continue
                }
                // A small circle up at the top right of a number is a degree sign: 30°, 45°. Not
                // within an exponent already begun: the 0 of 2¹⁰.
                if (r != null && !refStructure && cur != Level.SUP && item.glyph.strokes.size == 1 && ring(item.glyph.strokes[0], h) &&
                    max(b.width, b.height) < 0.5f * max(r.height, 0.75f * h) && b.bottom < r.top + 0.55f * r.height && b.left > r.cx &&
                    // the dot of an i or j has its stem under it
                    items.none { o -> o !== item && b.cx in o.box.left - 0.1f * h..o.box.right + 0.1f * h && o.box.top > b.bottom - 0.1f * h && o.box.top - b.bottom < 0.8f * h }
                ) {
                    enter(Level.BASE)
                    flush()
                    signText("°", item.glyph, Level.BASE)
                    continue
                }
                // A short upright up at the top right is a prime (f'), unless more of an exponent
                // follows close beside it: then it is the 1 of 2¹⁰.
                val next = items.getOrNull(items.indexOf(item) + 1)
                val exponentGoesOn = next is G && r != null && next.box.left - b.right < 0.6f * h && next.box.bottom < r.top + Tune.supBottom * r.height &&
                    next.box.height < 1.2f * max(b.height, 0.3f * h)
                // Nor after a minus or plus: the 1 of x⁻¹, 2ᵖ⁻¹.
                val prev = items.getOrNull(items.indexOf(item) - 1)
                val afterSign = Tune.primeAfterSign > 0f && prev is G && (prev.box.width >= 2.2f * max(prev.box.height, 0.5f) || sign(prev.glyph) != null)
                if (level == Level.SUP && !exponentGoesOn && !afterSign && item.glyph.strokes.size == 1 && b.height < 0.6f * h && b.width < 0.6f * b.height && straight(item.glyph.strokes[0], 0.12f)) {
                    enter(Level.BASE)
                    flush()
                    signText("'", item.glyph, Level.BASE)
                    continue
                }
                val sign = sign(item.glyph)
                if (sign != null) {
                    // Plus and minus by shape: among letters the reader takes + for t. A sign is
                    // in an exponent only when written wholly above the symbol before it.
                    val signLevel = when {
                        level == Level.SUP && !refStructure && ref != null && b.bottom > ref.top + 0.1f * ref.height -> Level.BASE
                        // Right after a fraction, a sign level with its bar is on the line, not an
                        // index of the fraction (a/b + 1 read a/b₊1): the line's middle, taken
                        // from the numbers over and under bars, can be far from the bars.
                        level == Level.SUB && Tune.signByBar > 0f && refStructure && barAfter?.let { abs(b.cy - it) < 0.35f * h } == true -> Level.BASE
                        else -> level
                    }
                    enter(signLevel)
                    flush()
                    signText(sign, item.glyph, signLevel)
                    // A new term starts: what follows a + or − on the line is not raised over
                    // the symbol before the sign (y⁵ + 3x, with a long-tailed y).
                    if (signLevel == Level.BASE) ref = null
                    continue
                }
                if (level == Level.BASE && tiny && !point && abs(b.cy - ax) < 0.25f * h) {
                    // A dot in the middle of the line is multiplication.
                    enter(Level.BASE)
                    flush()
                    signText("*", item.glyph, Level.BASE)
                    continue
                }
                enter(level)
                if (level == Level.SUP) {
                    // Deeper while raised over the last symbol of each depth reached so far.
                    var depth = 1
                    while (depth < supRefs.size + 1 && depth <= supRefs.size && raisedOver(b, supRefs[depth - 1])) depth++
                    val now = max(supRefs.size, 1)
                    if (depth > now) {
                        flush()
                        repeat(depth - now) { text("^{") }
                    } else if (depth < now) {
                        flush()
                        repeat(now - depth) { text("}") }
                    }
                    while (supRefs.size > depth) supRefs.removeAt(supRefs.size - 1)
                    if (!item.glyph.isDash) {
                        if (supRefs.size < depth) supRefs += b else supRefs[depth - 1] = b
                    }
                }
                run += item.glyph
                // A decimal comma is no symbol to measure the next one by: the digit before it is.
                if (level == Level.BASE && !item.glyph.isDash && b.height >= 0.35f * h && !(Tune.afterComma > 0f && afterComma)) {
                    ref = b
                    refStructure = false
                }
            }
            enter(Level.BASE)
            flush()
        }

        /** [b] is an exponent of [r], a symbol of an exponent: up at its top, not taller than it. */
        private fun raisedOver(b: Box, r: Box): Boolean =
            b.left > r.cx && b.height < Tune.nestSize * r.height && b.bottom < r.top + Tune.nestBottom * r.height && b.cy < r.top + Tune.nestMiddle * r.height

        /** How far right of its top end an upright ends at the bottom, in its heights. */
        private fun hookRight(v: Ink): Float {
            val down = v.ys.last() >= v.ys[0]
            val top = if (down) v.xs[0] else v.xs.last()
            val bottom = if (down) v.xs.last() else v.xs[0]
            return (bottom - top) / max(v.height, 0.5f)
        }

        /** "+" (a short upright and a dash crossing near both middles) or "-" (a lone short dash). */
        private fun sign(g: Glyph): String? {
            // A pen dot left at the end of a stroke is not part of the shape.
            val st = g.strokes.filter { max(it.width, it.height) >= 0.15f * h }.ifEmpty { return null }
            if (st.size == 1 && st[0].isDash && st[0].width < 1.3f * h && st[0].width > 0.2f * h) return "-"
            if (st.size != 2 || Box.of(st).height > 1.1f * h) return null
            val (v, d) = if (st[0].isDash) st[1] to st[0] else st[0] to st[1]
            // Upright and roughly straight: a crossed 7 has the same cross, but its stroke slants
            // and bends at the top.
            if (!d.isDash || v.width > 0.4f * v.height) return null
            if (!straight(v, 0.25f) || !straight(d, 0.3f)) return null
            if (v.height !in 0.5f * d.width..2f * d.width) return null
            val crossesV = d.cy in v.top + 0.25f * v.height..v.bottom - 0.25f * v.height
            val crossesD = v.cx in d.left + 0.25f * d.width..d.right - 0.25f * d.width
            // A t: as tall as a letter, the bar in its upper half, the upright ending in a hook to
            // the right (2t = after t = 7 read 2+). On CROHME: 21 % of the t's, 0.3 % of the +'s.
            if (v.height > Tune.tTall * h && d.cy < v.top + Tune.tBar * v.height && hookRight(v) > Tune.tHook) return null
            return if (crossesV && crossesD) "+" else null
        }

        /** [f] is written as an exponent of [ref]: its bar no lower than ref's top, all of it small. */
        private fun raisedFraction(f: Frac, ref: Box?): Boolean {
            val r = ref ?: return false
            val size = max(r.height, 0.75f * h)
            return f.bar.cy < r.top + 0.25f * r.height && f.box.bottom < r.bottom && f.box.left > r.cx &&
                (f.num + f.den).all { it.box.height < 0.8f * size }
        }

        private fun level(b: Box, ref: Box?, axis: Float, tick: Boolean = false): Level {
            if (ref == null) return Level.BASE
            // The writer's own way of placing exponents and indexes, where it is clear.
            val dashLike = b.width >= 2.2f * max(b.height, 0.5f)
            if (!dashLike && max(b.width, b.height) >= 0.22f * h) Tune.personal?.level(b, ref, h)?.let { return Level.entries[it] }
            // Written wholly above the top of the symbol before it: an exponent of almost any size.
            if (b.bottom <= ref.top + Tune.supAbove * ref.height && b.height < 1.2f * max(ref.height, 0.75f * h) && b.width < 3 * h) return Level.SUP
            val small = b.height < Tune.supSmall * max(ref.height, 0.75f * h)
            // A dash is raised only when clearly above the symbol before it (x⁻¹), not a + or − beside it.
            val dash = b.width >= 2.2f * max(b.height, 0.5f)
            val raisedBy = if (dash) 0.15f else Tune.supRaised
            if (small && b.bottom < ref.top + Tune.supBottom * ref.height && b.cy < ref.top + raisedBy * ref.height) return Level.SUP
            // An exponent written only a little raised (x2 for x²): small, its bottom clearly
            // above the baseline, and not a dot or sign in the middle of the line.
            if (!dash && b.height < Tune.supLowSize * max(ref.height, 0.75f * h) && max(b.width, b.height) >= 0.25f * h &&
                b.bottom < ref.top + Tune.supLowBottom * ref.height && b.cy < ref.top + Tune.supLowMiddle * ref.height && b.bottom < axis
            ) {
                return Level.SUP
            }
            // A decimal comma hangs a little below the line; it is not an index.
            // Measured on the symbol before it too (327,2 in a numerator, where the line's middle
            // says little): short, thin, starting low on that symbol.
            // a tick may slant: as wide as high
            val slant = if (Tune.commaSlant > 0f) 1.0f else 0.6f
            val comma = (
                b.width < 0.6f * b.height && (
                    (b.height < 0.35f * h && b.top > axis + 0.15f * h) ||
                        (Tune.commaRel > 0f && b.height < 0.4f * ref.height && b.height < 0.45f * h && b.top > ref.top + 0.7f * ref.height && b.bottom < ref.bottom + 0.3f * ref.height)
                    )
                ) ||
                // a longer one (−0,1169), or slanting as a tick (0,1), when it clearly hangs below the digit's foot
                (Tune.longCommas > 0f && tick && b.width < slant * b.height && b.height < Tune.longCommaSize * ref.height &&
                    b.height < 0.5f * h && b.top > ref.top + 0.7f * ref.height && b.bottom > ref.bottom + 0.1f * ref.height && b.bottom < ref.bottom + Tune.commaHang * ref.height)
            if (small && !comma && b.height < Tune.subSize * h && max(b.width, b.height) >= 0.22f * h && b.top > axis + Tune.subTop * h && b.bottom > axis + Tune.subBottom * h) return Level.SUB
            // An index beside the symbol it belongs to (T₈₅₀, L₀): smaller than it, starting well
            // down it and reaching below its foot. The line's own middle can be wrong when much
            // of the formula is indices.
            if (!comma && !dash && b.height < Tune.subRelSize * ref.height && max(b.width, b.height) >= 0.18f * ref.height &&
                b.top > ref.top + Tune.subRelTop * ref.height && b.bottom > ref.bottom + Tune.subRelBelow * ref.height && b.left > ref.cx
            ) {
                return Level.SUB
            }
            return Level.BASE
        }

        private fun emit(item: Item) {
            when (item) {
                is Frac -> { text("\\frac{"); flow(item.num, null); text("}{"); flow(item.den, null); text("}") }
                is Root -> {
                    text("\\sqrt")
                    if (item.degree.isNotEmpty()) { text("["); flow(item.degree, null); text("]") }
                    text("{"); flow(item.inner, null); text("}")
                }
                is Group -> { text(item.open); flow(item.inner, null); text(item.close) }
                is Grid -> {
                    if (item.det) text("\\det{")
                    text("\\mat{")
                    item.rows.forEachIndexed { r, row ->
                        if (r > 0) text("\\\\")
                        row.forEachIndexed { c, cell ->
                            if (c > 0) text("&")
                            flow(cell, null)
                        }
                    }
                    text("}")
                    if (item.det) text("}")
                }
                is IntSign -> {
                    text("\\int")
                    if (item.lo.isNotEmpty() || item.hi.isNotEmpty()) {
                        text("_{")
                        if (item.lo.isEmpty()) throw MathError(tr("нет нижнего предела", "no lower limit")) else flow(item.lo, null)
                        text("}^{")
                        if (item.hi.isEmpty()) throw MathError(tr("нет верхнего предела", "no upper limit")) else flow(item.hi, null)
                        text("}")
                    }
                    text(" ")
                }
                is Lit -> text(item.s)
                is G -> error("glyph")
            }
        }

        /** The tick of a √: the lowest point in the left half, the right end high up. */
        private fun checkShape(g: Glyph): Boolean {
            if (g.strokes.size != 1) return false
            val st = g.strokes[0]
            val b = g.box
            var low = 0
            for (k in st.ys.indices) if (st.ys[k] > st.ys[low]) low = k
            val rightEnd = if (st.xs.last() > st.xs[0]) st.xs.size - 1 else 0
            return st.xs[low] < b.left + 0.6f * b.width && st.ys[rightEnd] < b.top + 0.25f * b.height &&
                st.xs[rightEnd] > b.right - 0.2f * b.width
        }

        private fun vGap(a: Box, b: Box) = max(0f, max(a.top, b.top) - min(a.bottom, b.bottom))

        private fun fractions(pool: MutableList<Item>): MutableList<Item> {
            val inks = pool.filterIsInstance<G>().flatMap { it.glyph.strokes }
            val bars = pool.filterIsInstance<G>().filter {
                it.glyph.isDash && (it.box.width >= Tune.barWidth * h || isBar(it.glyph.strokes[0], inks, h)) &&
                    // a long low √ (its bar over 325² + 38²) is as flat as a fraction bar: it is the root
                    !(Tune.rootNotBar > 0f && it.glyph.strokes.size == 1 && isRadical(it.glyph.strokes[0], h))
            }.sortedByDescending { it.box.width }
            for (bar in bars) {
                if (bar !in pool) continue
                val bb = bar.box
                // A tall bracket reaching across the bar ((22)^½ written tight) is beside the
                // fraction, not in it; brackets wholly under the bar, 1/(x + 1), are in it.
                fun across(it: Item) = it is G && it.box.top < bb.cy && it.box.bottom > bb.cy &&
                    tallShape(it.glyph, h) in BRACKETS
                // A long bar holds what stands farther from it: under a bar five lines wide
                // nothing but its denominator stands.
                val reach = max(Tune.fracReach * h, min(Tune.longBarReach * bb.width, LONG_BAR_REACH_MAX * h))
                fun stack(up: Boolean): List<Item> {
                    // A comma of the numerator hanging down across the bar (269,15 over 2183,6):
                    // small, begun above the bar, it is the numerator's, not the denominator's.
                    fun commaAcross(it: Item) = Tune.commaAcross > 0f && it.box.top < bb.cy && it.box.bottom <= bb.cy + 0.15f * h &&
                        max(it.box.width, it.box.height) < 0.5f * h
                    val cand = pool.filter {
                        it !== bar && it.box.cx in bb.left - 0.15f * h..bb.right + 0.15f * h && !across(it) &&
                            (if (up) it.box.bottom <= bb.cy + 0.15f * h else it.box.top >= bb.cy - 0.15f * h && !commaAcross(it))
                    }
                    val got = ArrayList<Item>()
                    var grew = true
                    while (grew) {
                        grew = false
                        for (c in cand) {
                            if (c in got) continue
                            if (vGap(c.box, bb) <= reach || got.any { vGap(c.box, it.box) <= Tune.fracStep * h }) {
                                got += c
                                grew = true
                            }
                        }
                    }
                    if (!up || got.isEmpty()) return got
                    // An exponent of the numerator may run on past the end of the bar (888¹²³ over
                    // 3): small symbols well above the bar, one after another from what reaches
                    // its end. Something on the line beside the fraction comes down to the bar.
                    grew = true
                    while (grew) {
                        grew = false
                        val reach = got.maxOf { it.box.right }
                        if (reach < bb.right - 0.3f * h) break
                        val last = got.maxBy { it.box.right }
                        for (c in pool) {
                            if (c === bar || c in got || c.box.left <= bb.right || c.box.left > reach + 0.6f * h) continue
                            if (c.box.bottom > bb.cy - 0.1f * h || c.box.height > 0.85f * h) continue
                            if (min(c.box.bottom, last.box.bottom) - max(c.box.top, last.box.top) <= 0f) continue
                            got += c
                            grew = true
                            break
                        }
                    }
                    return got
                }
                val num = stack(true)
                val den = stack(false)
                if (num.isEmpty() || den.isEmpty()) continue
                if ((num + den).all { max(it.box.width, it.box.height) < 0.25f * h }) continue // ÷
                pool.removeAll(num.toSet() + den.toSet() + bar)
                pool += Frac(Box.ofBoxes((num + den).map { it.box } + bb), num, den, bb)
            }
            return pool
        }

        /**
         * The degree of a root (∛): small symbols in the crook of the √, over its tick and left
         * of where its bar starts.
         */
        private fun degreeOf(pool: List<Item>, r: Item, rb: Box): List<Item> {
            val found = pool.filter {
                it !== r && it is G && !it.glyph.isDash && it.box.height < 0.6f * rb.height && it.box.height < 0.8f * h &&
                    it.box.left > rb.left - 0.4f * h && it.box.right > rb.left + 0.05f * rb.width && it.box.cx < rb.left + Tune.degreeRight * rb.width &&
                    it.box.bottom < rb.top + Tune.degreeBottom * rb.height && it.box.bottom > rb.top + Tune.degreeTop * rb.height ||
                    // written wholly before a root with no tick (∛ as a tall bracket with a bar):
                    // small, right at its left edge, in its upper part
                    Tune.degreeBefore > 0f && it !== r && it is G && !it.glyph.isDash && it.box.height < 0.75f * h && it.box.height < 0.4f * rb.height &&
                    it.box.right in rb.left - 0.3f * h..rb.left + 0.05f * rb.width && it.box.left > rb.left - 1.2f * h &&
                    it.box.bottom < rb.top + 0.65f * rb.height && it.box.top > rb.top
            }
            // One short number or letter, not a whole term written before the root.
            return if (found.size in 1..2 && found.maxOf { it.box.right } - found.minOf { it.box.left } < 1.2f * h) found else emptyList()
        }

        private fun radicals(pool: MutableList<Item>): MutableList<Item> {
            // √ in two strokes: a tick, and a bar over the content starting at the tick's top.
            for (bar in pool.filterIsInstance<G>().filter { it.glyph.isDash && it.box.width > 0.6f * h }) {
                if (bar !in pool) continue
                val bb = bar.box
                val tick = pool.filterIsInstance<G>().firstOrNull { t ->
                    t !== bar && t.box.right in bb.left - 0.35f * h..bb.left + 0.3f * h &&
                        abs(t.box.top - bb.cy) < 0.35f * h && t.box.height > 0.5f * h && t.box.bottom > bb.cy + 0.5f * h &&
                        checkShape(t.glyph)
                } ?: continue
                val inner = pool.filter {
                    it !== bar && it !== tick && it.box.cx in bb.left..bb.right + 0.1f * h &&
                        it.box.top >= bb.cy - 0.1f * h && it.box.top < bb.cy + 1.2f * h
                }
                if (inner.isEmpty()) continue
                pool.removeAll(inner.toSet() + bar + tick)
                pool += Root(Box.ofBoxes(inner.map { it.box } + bb + tick.box), inner)
            }
            // Narrowest first: a root inside another is whole before the outer one takes it in.
            val radicals = pool.filterIsInstance<G>().filter { (it.glyph.strokes.size == 1 && isRadical(it.glyph.strokes[0], h)) || radicalOfParts(it.glyph, h) }
            for (r in radicals.sortedBy { it.box.width }) {
                if (r !in pool) continue
                val rb = r.box
                val degree = degreeOf(pool, r, rb)
                // The content starts after the bottom of the tick, however wide the root: the 1
                // of √(1 + √2) sits right after it.
                val start = min(tickBottomX(r.glyph) ?: (rb.left + 0.25f * rb.width), rb.left + 0.25f * rb.width)
                val inner = pool.filter {
                    it !== r && it !in degree && it.box.cx > start && it.box.cx < rb.right + 0.1f * h &&
                        it.box.cy > rb.top && (it.box.cy < rb.bottom + 0.2f * h ||
                            // a decimal comma hanging under the root's foot (√(133,3² + …))
                            Tune.rootNotBar > 0f && max(it.box.width, it.box.height) < 0.5f * h && it.box.top < rb.bottom + 0.15f * h)
                }
                if (inner.isEmpty()) continue
                pool.removeAll(inner.toSet() + degree + r)
                pool += Root(Box.ofBoxes(inner.map { it.box } + degree.map { it.box } + rb), inner, degree)
            }
            return pool
        }

        /** Where the tick of a √ bottoms out: the lowest point of its first stroke. */
        private fun tickBottomX(g: Glyph): Float? {
            val k = g.strokes.maxByOrNull { it.height } ?: return null
            var low = 0
            for (i in k.ys.indices) if (k.ys[i] > k.ys[low]) low = i
            return k.xs[low]
        }

        private fun talls(pool: MutableList<Item>): List<Item> {
            val shapes = HashMap<Item, Char>()
            // A "}" is never written in a formula here: such a tall curve is the tail of a y or a
            // g, read as a letter.
            for (it in pool) if (it is G) tallShape(it.glyph, h)?.takeIf { c -> c != '}' }?.let { c -> shapes[it] = c }
            MathPage.trace?.invoke("    talls h=$h: " + pool.filterIsInstance<G>().filter { it.box.height > 0.9f * h }.joinToString { "${it.box.left.toInt()},${it.box.top.toInt()} ${it.box.width.toInt()}x${it.box.height.toInt()} n=${it.glyph.strokes.size} -> ${shapes[it]}" })
            if (shapes.values.any { it == '{' }) throw MathError(tr("фигурная скобка", "curly brace"))
            fun overlap(a: Box, b: Box) = min(a.bottom, b.bottom) - max(a.top, b.top) >= 0.6f * min(a.height, b.height)
            // Between the middles of the brackets: a curved bracket reaches under its first column.
            fun inside(l: Box, r: Box) = pool.filter {
                !shapes.containsKey(it) && it.box.cx > l.cx && it.box.cx < r.cx &&
                    it.box.cy > min(l.top, r.top) && it.box.cy < max(l.bottom, r.bottom)
            }

            // Brackets, innermost first.
            val opens = shapes.filter { it.value == '(' }.keys.sortedByDescending { it.box.left }
            val used = HashSet<Item>()
            for (l in opens) {
                val r = shapes.filter { it.value == ')' && it.key !in used && it.key.box.left > l.box.right && overlap(it.key.box, l.box) }
                    .keys.minByOrNull { it.box.left } ?: continue
                used += r
                used += l
                val content = inside(l.box, r.box)
                pool.removeAll(content.toSet() + l + r)
                pool += bracketed(l.box.union(r.box), content, "(", ")", det = false)
            }
            // Vertical bars in pairs: |…| or a determinant. A bar drawn a little curved looks like
            // a bracket; an unpaired one next to a bar is taken as a bar.
            val loose = shapes.filter { (it.value == '(' || it.value == ')') && it.key !in used }.keys
            val straight = shapes.filter { it.value == '|' }.keys
            val bars = (straight + loose.filter { l -> straight.any { overlap(it.box, l.box) } })
                .sortedBy { it.box.left }.toMutableList()
            while (bars.size >= 2) {
                val l = bars.removeAt(0)
                val r = bars.firstOrNull { overlap(it.box, l.box) } ?: continue
                bars.remove(r)
                used += l
                used += r
                val content = inside(l.box, r.box)
                pool.removeAll(content.toSet() + l + r)
                pool += bracketed(l.box.union(r.box), content, "|", "|", det = true)
            }
            // Integral signs and their limits.
            for ((s, c) in shapes) {
                if (c != '∫' || s !in pool) continue
                val ib = s.box
                fun near(it: Item) = it !== s && !shapes.containsKey(it) && it.box.height < 1.2f * h &&
                    it.box.right > ib.left - 0.8f * h && it.box.left < ib.right + 1.2f * h
                // What stands right over or under a symbol of the integrand belongs to it (√x⁸ dx:
                // the 8 over the d), not to the limits.
                val integrand = pool.filter { it !== s && !shapes.containsKey(it) && it.box.left > ib.right - 0.1f * h && abs(it.box.cy - ib.cy) < 0.35f * ib.height }
                fun onIntegrand(it: Item) = integrand.any { o ->
                    o !== it && min(o.box.right, it.box.right) - max(o.box.left, it.box.left) > 0.5f * it.box.width
                }
                val hi = pool.filter { near(it) && it.box.cy < ib.cy && it.box.bottom < ib.top + 0.35f * ib.height && !onIntegrand(it) }
                val lo = pool.filter { near(it) && it.box.cy > ib.cy && it.box.top > ib.bottom - 0.35f * ib.height && !onIntegrand(it) }
                pool.removeAll(hi.toSet() + lo.toSet() + s)
                pool += IntSign(Box.ofBoxes(listOf(ib) + (hi + lo).map { it.box }), lo, hi)
                used += s
            }
            // Unpaired tall brackets are ordinary brackets. A round one without its pair is left to
            // the reader: it is as likely a thin 1 or 0 ("= 13" read "= (3", "−60" read "−6)").
            for ((s, c) in shapes) {
                if (s in used || s !in pool) continue
                if (Tune.unpairedRead > 0f && (c == '(' || c == ')')) continue
                pool.remove(s)
                pool += Lit(s.box, if (c == '|') "|" else c.toString())
            }
            return pool
        }

        private fun bracketed(box: Box, content: List<Item>, open: String, close: String, det: Boolean): Item {
            if (content.isEmpty()) throw MathError(tr("пустые скобки", "empty brackets"))
            val rows = rows(content)
            // Rows of a matrix stand under one another; x and its raised exponent do not.
            val aligned = rows.size >= 2 && rows.all { row ->
                val l = row.minOf { it.box.left }
                val r = row.maxOf { it.box.right }
                rows.all { other ->
                    other === row || min(r, other.maxOf { it.box.right }) - max(l, other.minOf { it.box.left }) > 0.5f * min(r - l, other.maxOf { it.box.right } - other.minOf { it.box.left })
                }
            }
            if (!aligned || rows.zipWithNext().any { (up, down) -> exponents(up, down) }) return Group(box, open, close, content)
            val cols = columns(content)
            val cells = rows.map { row ->
                List(cols.size) { c -> row.filter { it.box.cx in cols[c].first..cols[c].second } }
            }
            if (cells.any { r -> r.any { it.isEmpty() } }) throw MathError(tr("пустая клетка матрицы", "empty matrix cell"))
            return Grid(box, cells, det)
        }

        /**
         * Whether a row [up] is the exponents of the row [down] under it, not a row of a matrix:
         * (x³ − x² − x), n(n² + 2). Every item of it is smaller than the items under it and
         * stands raised at the right of one of them.
         */
        private fun exponents(up: List<Item>, down: List<Item>): Boolean {
            val base = down.maxOf { it.box.height }
            return up.all { e ->
                e.box.height < EXPONENT_SIZE * base && down.any { o ->
                    e.box.cy < o.box.cy && e.box.left > o.box.right - 0.4f * o.box.width && e.box.left < o.box.right + 0.6f * h
                }
            }
        }

        /** Groups items into rows by vertical overlap, top to bottom. */
        private fun rows(items: List<Item>): List<List<Item>> {
            val sorted = items.sortedBy { it.box.cy }
            val rows = ArrayList<MutableList<Item>>()
            var bottom = -Float.MAX_VALUE
            for (it in sorted) {
                // A comma hanging just under its row (−14,68 in brackets) is no row of its own.
                val hanging = Tune.commaAcross > 0f && rows.isNotEmpty() && max(it.box.width, it.box.height) < 0.5f * h &&
                    it.box.top < bottom + 0.3f * h && rows.last().any { o -> abs(o.box.cx - it.box.cx) < 1.5f * h }
                if (rows.isEmpty() || it.box.top > bottom - 0.15f * h && !hanging) {
                    rows += mutableListOf(it)
                } else {
                    rows.last() += it
                }
                bottom = if (rows.last().size == 1) it.box.bottom else max(bottom, it.box.bottom)
            }
            return rows
        }

        /** Column extents: x ranges where items of any row stand, split at wide gaps. */
        private fun columns(items: List<Item>): List<Pair<Float, Float>> {
            val spans = items.map { it.box.left to it.box.right }.sortedBy { it.first }
            val out = ArrayList<Pair<Float, Float>>()
            for ((l, r) in spans) {
                val last = out.lastOrNull()
                if (last != null && l < last.second + 0.5f * h) out[out.size - 1] = last.first to max(last.second, r) else out += l to r
            }
            return out
        }
    }

    internal fun median(v: List<Float>): Float? = if (v.isEmpty()) null else v.sorted()[v.size / 2]

    private val CONTAINER_SHAPES = setOf('(', ')', '∫', '{', '}')
    private val BRACKETS = setOf('(', ')', '|', '{', '}')
    private val INTEGRAL_MIN get() = Tune.integralMin

    /** Round brackets this tall (of the line height) and up are told by their shape. */
    private val PAREN_MIN get() = Tune.parenMin

    /** A symbol this many line heights tall is a bracket, integral or brace. */
    val TALL get() = Tune.tall
}

/**
 * Thresholds of the layout, in line heights unless said otherwise. Chosen on handwritten
 * datasets by the formula bench (FormulaBench / LayoutTuner in the unit tests): change them
 * only with its report in hand.
 */
object Tune {
    /** The writer's placements (null: none, as in tests and stands); see [Placements]. */
    @Volatile
    var personal: Placements? = null

    /** Above 0: an equation with another "=" close on its line is a link of a chain of working, not solved. */
    @JvmField var chains = 1f

    /** Above 0: a decimal comma is also told by the digit before it (short, thin, low on it), not only by the line's middle. */
    @JvmField var commaRel = 1f

    /** Above 0: after a decimal comma low beside a number, a symbol of that number's size stays on its line. */
    @JvmField var afterComma = 1f

    /** Above 0: a long straight slanted line crossing a formula's line is a drawing's, not a symbol of the formula. */
    @JvmField var drawingLines = 1f

    /** Above 0: a stroke touching a symbol already taken into a side (the bar of a 5 just over the line) is taken too. */
    @JvmField var touchingStrokes = 1f

    /** Above 0: a decimal comma between two digits of a side may be a long slanting tick, up to 0.55 of a line. */
    @JvmField var longCommas = 1f

    /** Above 0: a stroke shaped as a root is no fraction bar, however flat. */
    @JvmField var rootNotBar = 1f

    /** Above 0: a small symbol right before a root, in its upper part, is the root's degree (∛). */
    @JvmField var degreeBefore = 1f

    /** Above 0: a side whose layout fails at the line's height is laid out again at its own symbols' height. */
    @JvmField var sideHeight = 1f

    /** Above 0: a comma touching the digit before it, hanging under its right part, stays a symbol of its own. */
    @JvmField var commaApart = 0f

    /** Above 0: a hanging comma tick may slant, as wide as high (0,1), and still be no index. */
    @JvmField var commaSlant = 1f

    /** How long a hanging comma tick may be, of the digit before it (1021,41: a 7 under a thin 14). */
    @JvmField var longCommaSize = 0.55f

    /** How far below the digit's foot a comma tick may reach, of the digit (2446,59: 0.7). */
    @JvmField var commaHang = 0.8f

    /** Above 0: a small stroke begun above a fraction bar and hanging across it is the numerator's (its comma). */
    @JvmField var commaAcross = 1f

    /** Above 0: two dashes whose left ends start on one upright are the bars of an F (or E), not "=". */
    @JvmField var letterBars = 1f

    /** Above 0: a "<" or ">" at the end of a long line is an arrowhead (of an axis), not a sign. */
    @JvmField var arrowHeads = 1f

    /** Above 0: an inequality worked on with "=" after it (D ≥ ∛… = 6,2) is not solved; nor is a system with ∞ solutions shown. */
    @JvmField var workedInequalities = 1f

    /** A dash at least this long (and three times as long as high) is a minus, not a dot (0: off). */
    @JvmField var shortMinus = 0.1f

    /** Above 0: an upright right after a minus or plus is a 1, not a prime. */
    @JvmField var primeAfterSign = 1f

    /** Above 0: a sign level with the bar of the fraction before it is on the line, not an index. */
    @JvmField var signByBar = 1f

    /** Above 0: an x drawn as two arcs ")(" is one symbol (MathLayout.arcsOfX). */
    @JvmField var xArcs = 1f

    /** Strokes overlapping by this part of the narrower one are parts of one symbol… */
    @JvmField var joinOverlap = 0.35f

    /** …when no farther apart vertically than this. */
    @JvmField var joinGap = 0.156f

    /** An exponent: its bottom no lower than this part of the symbol before it from the top. */
    @JvmField var supAbove = 0.206f
    @JvmField var supSmall = 1.6f
    @JvmField var supRaised = 0.3f
    @JvmField var supBottom = 0.605f

    /** An exponent written only a little raised (x2 for x²). */
    @JvmField var supLowSize = 0.7f
    @JvmField var supLowBottom = 0.66f
    @JvmField var supLowMiddle = 0.484f

    /** A dot is a decimal point, not multiplication, when its middle is this low on the symbol before it. */
    @JvmField var pointLow = 0.55f

    /**
     * 1: a dot low between digits is a decimal point (English handwriting, 8.5); 0: a dot is
     * multiplication wherever it sits (Russian handwriting: decimals take a comma, and a low
     * dot is a hasty ·). Set by the app from the handwriting language.
     */
    @JvmField var decimalPoint = 0f

    /** An index measured on the symbol before it: smaller than this part of it, its top this far down it, its bottom this far below its foot. */
    @JvmField var subRelSize = 0.7f
    @JvmField var subRelTop = 0.4f
    @JvmField var subRelBelow = 0.15f

    /** The dot of an i or j joins the stroke below it across at most this gap. */
    @JvmField var dotGap = 0.45f

    /** The degree of a root: its middle left of this part of the √, its bottom above this part of its height… */
    @JvmField var degreeRight = 0.3f
    @JvmField var degreeBottom = 0.6f

    /** …and below this part of it: in the crook, not wholly above the √ (the upper limit of an ∫ before it). */
    @JvmField var degreeTop = 0.1f

    /** An exponent of an exponent, measured on the symbol of the exponent before it: smaller than this… */
    @JvmField var nestSize = 1.0f

    /** …its bottom above this part of that symbol's height, its middle above this part. */
    @JvmField var nestBottom = 0.55f
    @JvmField var nestMiddle = 0.2f

    /** An index: smaller than this, its top below the middle line by [subTop], its bottom by [subBottom]. */
    @JvmField var subSize = 1.03f
    @JvmField var subTop = 0.036f
    @JvmField var subBottom = 0.54f

    /** A fraction bar at least this wide; numerator and denominator within [fracReach] of it, their parts within [fracStep]. */
    @JvmField var barWidth = 0.45f
    @JvmField var fracReach = 1.237f

    /** A long bar reaches this part of its width (0: as any bar, [fracReach]). */
    @JvmField var longBarReach = 0.25f

    /** A cross is a t, not "+", when its upright is taller than this part of the line, its bar above [tBar] of it and its end [tHook] of its height to the right. */
    @JvmField var tTall = 0.75f
    @JvmField var tBar = 0.5f
    @JvmField var tHook = 0.3f

    /** Above 0: a round bracket without its pair is read by the reader, not taken as a bracket. */
    @JvmField var unpairedRead = 0f
    @JvmField var fracStep = 0.557f

    @JvmField var integralMin = 1.15f
    @JvmField var parenMin = 0.95f
    @JvmField var tall = 1.305f

    /** Widest gap between symbols of one side of "=". */
    @JvmField var sideGap = 1.8f

    fun values(): Map<String, Float> = Tune::class.java.declaredFields.filter { it.type == Float::class.javaPrimitiveType }
        .associate { it.name to it.getFloat(this) }

    fun set(name: String, v: Float) {
        Tune::class.java.getDeclaredField(name).setFloat(this, v)
    }
}
