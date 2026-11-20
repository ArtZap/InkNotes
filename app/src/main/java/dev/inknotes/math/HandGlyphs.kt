package dev.inknotes.math

import kotlin.math.max

/**
 * The writer's own digits and signs, written once in «Мой почерк для ответов», for answers
 * drawn in their hand instead of a font. Each symbol keeps up to [VARIANTS] ways it was
 * written; an answer takes them in turn, so two 1s in a row are not copies.
 *
 * A glyph is its strokes in units of the writing height H (the line from the top guide to
 * the base line): x from the glyph's left, y from the base line, up negative. Only on the
 * device.
 */
class HandGlyphs(private val byChar: MutableMap<Char, ArrayDeque<Glyph>> = HashMap()) {

    /** Strokes as x, y, x, y… in units of H; [width] in H. */
    class Glyph(val strokes: List<FloatArray>, val width: Float)

    /** A glyph placed in an answer: its strokes in page units. */
    class Placed(val strokes: List<FloatArray>)

    /** A line laid out: [glyphs] and its [width] in page units. */
    class Line(val glyphs: List<Placed>, val width: Float)

    @Synchronized
    fun add(c: Char, glyph: Glyph) {
        val list = byChar.getOrPut(c) { ArrayDeque() }
        list.addLast(glyph)
        while (list.size > VARIANTS) list.removeFirst()
    }

    @Synchronized
    fun remove(c: Char) {
        byChar.remove(c)
    }

    @Synchronized
    fun has(c: Char) = byChar[c]?.isNotEmpty() == true

    /** How many ways [c] was written. */
    @Synchronized
    fun variants(c: Char) = byChar[c]?.size ?: 0

    @Synchronized
    fun count(): Int = byChar.size

    @Synchronized
    fun isEmpty() = byChar.isEmpty()

    @Synchronized
    fun clear() = byChar.clear()

    /** The glyph that stands for [c]: itself, or the plain symbol of a raised or lowered one. */
    private fun source(c: Char): Pair<Char, Shift>? {
        if (c == ' ') return null
        SUPER.indexOf(c).takeIf { it >= 0 }?.let { return PLAIN[it] to Shift.UP }
        SUB.indexOf(c).takeIf { it >= 0 }?.let { return PLAIN[it] to Shift.DOWN }
        val alias = ALIASES[c] ?: c
        return alias to Shift.NONE
    }

    private enum class Shift { NONE, UP, DOWN }

    /** Can [text] be written in this hand: every symbol of it was written. */
    @Synchronized
    fun covers(text: String): Boolean = text.isNotEmpty() && text.all { c -> c == ' ' || source(c)?.let { byChar[it.first]?.isNotEmpty() } == true }

    /**
     * [text] laid out from [x] on the base line [y], letters [size] tall (H); null when a
     * symbol of it was never written.
     */
    @Synchronized
    fun layout(text: String, x: Float, y: Float, size: Float): Line? {
        if (!covers(text)) return null
        val out = ArrayList<Placed>()
        var pen = x
        val used = HashMap<Char, Int>()
        for (c in text) {
            if (c == ' ') {
                pen += SPACE * size
                continue
            }
            val (plain, shift) = source(c)!!
            val variants = byChar.getValue(plain)
            val n = used.getOrDefault(plain, 0)
            used[plain] = n + 1
            val g = variants[n % variants.size]
            val k = if (shift == Shift.NONE) size else size * SMALL
            val dy = when (shift) {
                Shift.NONE -> 0f
                Shift.UP -> -size * 0.55f
                Shift.DOWN -> size * 0.25f
            }
            out += Placed(g.strokes.map { s -> FloatArray(s.size) { i -> if (i % 2 == 0) pen + s[i] * k else y + dy + s[i] * k } })
            pen += (g.width + GAP) * k
        }
        return Line(out, max(pen - x - GAP * size, 0f))
    }

    /** One line per glyph: the symbol, tab, width, tab, strokes split by ';', numbers by ','. */
    @Synchronized
    fun serialize(): String = buildString {
        for ((c, list) in byChar) for (g in list) {
            append(c).append('\t').append(fmt(g.width)).append('\t')
            g.strokes.joinTo(this, ";") { s -> s.joinToString(",") { fmt(it) } }
            append('\n')
        }
    }

    companion object {
        const val VARIANTS = 3

        /** Between letters, in H. */
        const val GAP = 0.14f

        /** A space, in H. */
        const val SPACE = 0.45f

        /** Raised and lowered symbols (², ₁) at this part of the size. */
        const val SMALL = 0.62f

        /**
         * What is asked for in «Мой почерк для ответов», in order: what answers are written
         * with. Letters the answers name (x, y, e, i, π) and signs.
         */
        const val ASKED = "0123456789,.−+/()=x√π°∞e·"

        private const val SUPER = "⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺"
        private const val SUB = "₀₁₂₃₄₅₆₇₈₉₋₊"
        private const val PLAIN = "0123456789−+"

        /** Symbols written like another: a hyphen like the minus, × like the dot of multiplying. */
        private val ALIASES = mapOf('-' to '−', '×' to '·', '⋅' to '·')

        private fun fmt(v: Float) = "%.3f".format(java.util.Locale.ROOT, v)

        fun parse(text: String): HandGlyphs {
            val h = HandGlyphs()
            for (line in text.lineSequence()) {
                val parts = line.split('\t')
                if (parts.size != 3 || parts[0].length != 1) continue
                val width = parts[1].toFloatOrNull() ?: continue
                val strokes = parts[2].split(';').map { s -> s.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray() }.filter { it.size >= 2 && it.size % 2 == 0 }
                if (strokes.isNotEmpty()) h.add(parts[0][0], Glyph(strokes, width))
            }
            return h
        }

        /**
         * A glyph from strokes written in a box whose top guide is at [top] and base line at
         * [base] (in the same units as the points): measured in the height between them.
         */
        fun glyph(strokes: List<FloatArray>, top: Float, base: Float): Glyph? {
            val h = base - top
            if (h <= 0f) return null
            val pts = strokes.filter { it.size >= 2 }
            if (pts.isEmpty()) return null
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (s in pts) for (i in s.indices step 2) {
                left = minOf(left, s[i])
                right = maxOf(right, s[i])
            }
            val out = pts.map { s -> FloatArray(s.size) { i -> if (i % 2 == 0) (s[i] - left) / h else (s[i] - base) / h } }
            // A dot or a comma is narrow; it still takes a little room.
            return Glyph(out, max((right - left) / h, 0.12f))
        }
    }
}
