package dev.inknotes.math

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
}

/** A formula as text with gaps: [Text] is structure found from the layout, [Hole] a run to read. */
sealed class Piece {
    class Text(val s: String) : Piece()
    class Hole(val strokes: List<Ink>, val h: Float) : Piece()
}

/**
 * Turns the strokes of one handwritten formula into [Piece]s from their 2-D arrangement:
 * fraction bars with something above and below, raised exponents and lowered indices,
 * radicals, tall brackets (a matrix when they hold several rows, a determinant between bars)
 * and the integral sign with its limits. Everything else is left to the handwriting reader.
 */
object MathLayout {

    fun formula(strokes: List<Ink>, h: Float, axis: Float): List<Piece> {
        val out = ArrayList<Piece>()
        Builder(h, out).flow(items(strokes, h), axis)
        // Join neighbouring text pieces.
        val merged = ArrayList<Piece>()
        for (p in out) {
            val last = merged.lastOrNull()
            if (p is Piece.Text && last is Piece.Text) merged[merged.size - 1] = Piece.Text(last.s + p.s) else merged += p
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

    internal class Frac(override val box: Box, val num: List<Item>, val den: List<Item>) : Item()
    internal class Root(override val box: Box, val inner: List<Item>) : Item()
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
            tallShape(Glyph(listOf(it)), h) in CONTAINER_SHAPES || isRadical(it, h)
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
            val stackGap = if ((a.isDash && a.width > 0.5f * h) || (b.isDash && b.width > 0.5f * h)) 0.1f * h else 0.3f * h
            val joined = ((xOverlap > 0.35f * narrow || crossing) && yGap < stackGap) ||
                // the flag of 1, the bar of a crossed 7 or z: a short straight tick touching a stroke
                tick(a, b, h) || tick(b, a, h) ||
                // the top bar of a 5 drawn as its own stroke, just touching the body's top end
                cap(a, b, h) || cap(b, a, h) ||
                // the dot of i or j, the dots of ÷
                (dot && yGap < 0.45f * h && (b.cx in a.left - 0.1f * h..a.right + 0.1f * h || a.cx in b.left - 0.1f * h..b.right + 0.1f * h))
            if (joined) parent[find(i)] = find(j)
        }
        return strokes.indices.groupBy(::find).values.map { idx -> G(Glyph(idx.map { strokes[it] })) }
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

    /** Dash [d] lies on top of [s] over part of it, an end touching [s] (the bar of a 5). */
    internal fun cap(d: Ink, s: Ink, h: Float): Boolean {
        if (!d.isDash || d.width > 1.2f * max(s.width, 0.3f * h) || s.height < 0.3f * h) return false
        if (min(d.right, s.right) - max(d.left, s.left) <= 0f || d.cy > s.top + 0.2f * s.height) return false
        val tol = max(0.12f * h, 0.12f * s.height)
        for (end in listOf(0, d.xs.lastIndex)) {
            for (k in s.xs.indices) if (abs(s.xs[k] - d.xs[end]) < tol && abs(s.ys[k] - d.ys[end]) < tol) return true
        }
        return false
    }

    private fun tick(t: Ink, other: Ink, h: Float): Boolean {
        if (max(t.width, t.height) > 0.45f * h || t.xs.size < 2 || !straight(t, 0.2f)) return false
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

    /** A horizontal stroke with real strokes (not dots) both above and below it. */
    private fun isBar(s: Ink, all: List<Ink>, h: Float): Boolean {
        if (!s.isDash || s.width < 0.45f * h) return false
        // The top of a 5 drawn as its own stroke, under a fraction bar, is no bar itself.
        if (all.any { it !== s && cap(s, it, h) }) return false
        fun over(o: Ink) = o.cx in s.left - 0.1f * h..s.right + 0.1f * h && o.height + o.width > 0.3f * h
        val above = all.any { it !== s && over(it) && it.bottom <= s.cy + 0.1f * h && s.top - it.bottom < 0.9f * h }
        val below = all.any { it !== s && over(it) && it.top >= s.cy - 0.1f * h && it.top - s.bottom < 0.9f * h }
        return above && below
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

        fun flow(items0: List<Item>, axis: Float?) {
            if (items0.isEmpty()) throw MathError("пусто")
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
                out += Piece.Hole(run.sortedBy { it.box.left }.flatMap { g -> g.strokes.sortedBy { it.order } }, h)
                run.clear()
            }
            // Raised and lowered parts are wrapped in ^{…} and _{…}.
            fun enter(level: Level) {
                if (level == cur) return
                flush()
                if (cur != Level.BASE) text("}")
                when (level) {
                    Level.SUP -> text("^{")
                    Level.SUB -> text("_{")
                    Level.BASE -> {}
                }
                cur = level
            }
            for (item in items) {
                if (item !is G) {
                    enter(Level.BASE)
                    flush()
                    emit(item)
                    ref = item.box
                    refStructure = true
                    continue
                }
                val b = item.box
                val ax = axisAt(b.cx)
                val level = level(b, ref, ax)
                val tiny = max(b.width, b.height) < 0.22f * h
                val r = ref
                // A dot about the middle of the line is multiplication, even drawn as a tiny
                // ring or a little high: not a degree sign (those sit at the top), a prime or
                // an exponent.
                if (tiny && abs(b.cy - ax) < 0.2f * h && (r == null || b.cy > r.top + 0.15f * r.height)) {
                    enter(Level.BASE)
                    flush()
                    text("*")
                    continue
                }
                // A small circle up at the top right of a number is a degree sign: 30°, 45°.
                if (r != null && !refStructure && item.glyph.strokes.size == 1 && ring(item.glyph.strokes[0], h) &&
                    max(b.width, b.height) < 0.5f * max(r.height, 0.75f * h) && b.bottom < r.top + 0.55f * r.height && b.left > r.cx &&
                    // the dot of an i or j has its stem under it
                    items.none { o -> o !== item && b.cx in o.box.left - 0.1f * h..o.box.right + 0.1f * h && o.box.top > b.bottom - 0.1f * h && o.box.top - b.bottom < 0.8f * h }
                ) {
                    enter(Level.BASE)
                    flush()
                    text("°")
                    continue
                }
                if (level == Level.SUP && item.glyph.strokes.size == 1 && b.height < 0.6f * h && b.width < 0.6f * b.height && straight(item.glyph.strokes[0], 0.12f)) {
                    enter(Level.BASE)
                    flush()
                    text("'")
                    continue
                }
                val sign = sign(item.glyph)
                if (sign != null) {
                    // Plus and minus by shape: among letters the reader takes + for t. A sign is
                    // in an exponent only when written wholly above the symbol before it.
                    val signLevel = if (level == Level.SUP && !refStructure && ref != null && b.bottom > ref.top + 0.1f * ref.height) Level.BASE else level
                    enter(signLevel)
                    flush()
                    text(sign)
                    // A new term starts: what follows a + or − on the line is not raised over
                    // the symbol before the sign (y⁵ + 3x, with a long-tailed y).
                    if (signLevel == Level.BASE) ref = null
                    continue
                }
                if (level == Level.BASE && tiny && abs(b.cy - ax) < 0.25f * h) {
                    // A dot in the middle of the line is multiplication.
                    enter(Level.BASE)
                    flush()
                    text("*")
                    continue
                }
                enter(level)
                run += item.glyph
                if (level == Level.BASE && !item.glyph.isDash && b.height >= 0.35f * h) {
                    ref = b
                    refStructure = false
                }
            }
            enter(Level.BASE)
            flush()
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
            return if (crossesV && crossesD) "+" else null
        }

        private fun level(b: Box, ref: Box?, axis: Float): Level {
            if (ref == null) return Level.BASE
            // Written wholly above the top of the symbol before it: an exponent of almost any size.
            if (b.bottom <= ref.top + 0.15f * ref.height && b.height < 1.2f * max(ref.height, 0.75f * h) && b.width < 3 * h) return Level.SUP
            val small = b.height < 0.85f * max(ref.height, 0.75f * h)
            // A dash is raised only when clearly above the symbol before it (x⁻¹), not a + or − beside it.
            val dash = b.width >= 2.2f * max(b.height, 0.5f)
            val raisedBy = if (dash) 0.15f else 0.3f
            if (small && b.bottom < ref.top + 0.55f * ref.height && b.cy < ref.top + raisedBy * ref.height) return Level.SUP
            // An exponent written only a little raised (x2 for x²): small, its bottom clearly
            // above the baseline, and not a dot or sign in the middle of the line.
            if (!dash && b.height < 0.7f * max(ref.height, 0.75f * h) && max(b.width, b.height) >= 0.25f * h &&
                b.bottom < ref.top + 0.6f * ref.height && b.cy < ref.top + 0.4f * ref.height && b.bottom < axis
            ) {
                return Level.SUP
            }
            // A decimal comma hangs a little below the line; it is not an index.
            val comma = b.height < 0.35f * h && b.width < 0.6f * b.height && b.top > axis + 0.15f * h
            if (small && !comma && b.height < 0.75f * h && max(b.width, b.height) >= 0.22f * h && b.top > axis + 0.05f * h && b.bottom > axis + 0.6f * h) return Level.SUB
            return Level.BASE
        }

        private fun emit(item: Item) {
            when (item) {
                is Frac -> { text("\\frac{"); flow(item.num, null); text("}{"); flow(item.den, null); text("}") }
                is Root -> { text("\\sqrt{"); flow(item.inner, null); text("}") }
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
                        if (item.lo.isEmpty()) throw MathError("нет нижнего предела") else flow(item.lo, null)
                        text("}^{")
                        if (item.hi.isEmpty()) throw MathError("нет верхнего предела") else flow(item.hi, null)
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
            val bars = pool.filterIsInstance<G>().filter { it.glyph.isDash && it.box.width >= 0.45f * h }.sortedByDescending { it.box.width }
            for (bar in bars) {
                if (bar !in pool) continue
                val bb = bar.box
                fun stack(up: Boolean): List<Item> {
                    val cand = pool.filter {
                        it !== bar && it.box.cx in bb.left - 0.15f * h..bb.right + 0.15f * h &&
                            (if (up) it.box.bottom <= bb.cy + 0.15f * h else it.box.top >= bb.cy - 0.15f * h)
                    }
                    val got = ArrayList<Item>()
                    var grew = true
                    while (grew) {
                        grew = false
                        for (c in cand) {
                            if (c in got) continue
                            if (vGap(c.box, bb) <= 0.8f * h || got.any { vGap(c.box, it.box) <= 0.45f * h }) {
                                got += c
                                grew = true
                            }
                        }
                    }
                    return got
                }
                val num = stack(true)
                val den = stack(false)
                if (num.isEmpty() || den.isEmpty()) continue
                if ((num + den).all { max(it.box.width, it.box.height) < 0.25f * h }) continue // ÷
                pool.removeAll(num.toSet() + den.toSet() + bar)
                pool += Frac(Box.ofBoxes((num + den).map { it.box } + bb), num, den)
            }
            return pool
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
            for (r in pool.filterIsInstance<G>().filter { it.glyph.strokes.size == 1 && isRadical(it.glyph.strokes[0], h) }) {
                val rb = r.box
                val inner = pool.filter {
                    it !== r && it.box.cx > rb.left + 0.25f * rb.width && it.box.cx < rb.right + 0.1f * h &&
                        it.box.cy > rb.top && it.box.cy < rb.bottom + 0.2f * h
                }
                if (inner.isEmpty()) continue
                pool.removeAll(inner.toSet() + r)
                pool += Root(Box.ofBoxes(inner.map { it.box } + rb), inner)
            }
            return pool
        }

        private fun talls(pool: MutableList<Item>): List<Item> {
            val shapes = HashMap<Item, Char>()
            // A "}" is never written in a formula here: such a tall curve is the tail of a y or a
            // g, read as a letter.
            for (it in pool) if (it is G) tallShape(it.glyph, h)?.takeIf { c -> c != '}' }?.let { c -> shapes[it] = c }
            MathPage.trace?.invoke("    talls h=$h: " + pool.filterIsInstance<G>().filter { it.box.height > 0.9f * h }.joinToString { "${it.box.left.toInt()},${it.box.top.toInt()} ${it.box.width.toInt()}x${it.box.height.toInt()} n=${it.glyph.strokes.size} -> ${shapes[it]}" })
            if (shapes.values.any { it == '{' }) throw MathError("фигурная скобка")
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
                val hi = pool.filter { near(it) && it.box.cy < ib.cy && it.box.bottom < ib.top + 0.35f * ib.height }
                val lo = pool.filter { near(it) && it.box.cy > ib.cy && it.box.top > ib.bottom - 0.35f * ib.height }
                pool.removeAll(hi.toSet() + lo.toSet() + s)
                pool += IntSign(Box.ofBoxes(listOf(ib) + (hi + lo).map { it.box }), lo, hi)
                used += s
            }
            // Unpaired tall brackets are ordinary brackets.
            for ((s, c) in shapes) {
                if (s in used || s !in pool) continue
                pool.remove(s)
                pool += Lit(s.box, if (c == '|') "|" else c.toString())
            }
            return pool
        }

        private fun bracketed(box: Box, content: List<Item>, open: String, close: String, det: Boolean): Item {
            if (content.isEmpty()) throw MathError("пустые скобки")
            val rows = rows(content)
            // Rows of a matrix stand under one another; x and its raised exponent do not.
            val aligned = rows.size >= 2 && rows.all { row ->
                val l = row.minOf { it.box.left }
                val r = row.maxOf { it.box.right }
                rows.all { other ->
                    other === row || min(r, other.maxOf { it.box.right }) - max(l, other.minOf { it.box.left }) > 0.5f * min(r - l, other.maxOf { it.box.right } - other.minOf { it.box.left })
                }
            }
            if (!aligned) return Group(box, open, close, content)
            val cols = columns(content)
            val cells = rows.map { row ->
                List(cols.size) { c -> row.filter { it.box.cx in cols[c].first..cols[c].second } }
            }
            if (cells.any { r -> r.any { it.isEmpty() } }) throw MathError("пустая клетка матрицы")
            return Grid(box, cells, det)
        }

        /** Groups items into rows by vertical overlap, top to bottom. */
        private fun rows(items: List<Item>): List<List<Item>> {
            val sorted = items.sortedBy { it.box.cy }
            val rows = ArrayList<MutableList<Item>>()
            var bottom = -Float.MAX_VALUE
            for (it in sorted) {
                if (rows.isEmpty() || it.box.top > bottom - 0.15f * h) {
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
    private const val INTEGRAL_MIN = 1.15f

    /** Round brackets this tall (of the line height) and up are told by their shape. */
    private const val PAREN_MIN = 0.95f

    /** A symbol this many line heights tall is a bracket, integral or brace. */
    const val TALL = 1.45f
}
