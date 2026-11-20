package dev.inknotes.search

import kotlin.math.max

/**
 * Spelling of handwritten lines, the part without Android: words of a recognised line, which
 * of them are worth checking and in which language, and where on the page each one is.
 * The words themselves are looked up by the system's spell checker ([SpellCheck]).
 */
object Spelling {
    enum class Lang { RU, EN }

    /** A word of a line: [start]…[end] in its text. */
    class Word(val text: String, val start: Int, val end: Int) {
        /** The language its letters are of; null when mixed or not letters. */
        val lang: Lang?
            get() = when {
                text.all { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' || it == '-' } -> Lang.RU
                text.all { it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '\'' } -> Lang.EN
                else -> null
            }

        /**
         * Worth looking up: three letters or more, one alphabet, not an abbreviation in
         * capitals (МФТИ, SQL) and no capital inside (iPhone, МатАн).
         */
        val checkable: Boolean
            get() = text.length >= 3 && lang != null && text.lowercase() !in MATH && text.first().isLetter() && text.last().isLetter() &&
                text.drop(1).none { it.isUpperCase() }
    }

    /**
     * A line of writing, not a formula: no "=", and mostly letters. Function names (sin, lim)
     * are not words to check either.
     */
    fun prose(line: String): Boolean {
        if ('=' in line) return false
        val signs = line.count { !it.isLetter() && !it.isWhitespace() }
        val letters = line.count { it.isLetter() }
        return letters >= 3 && signs <= letters * 0.3f
    }

    /** Names in formulas, not misspelt words. */
    val MATH = setOf("sin", "cos", "tg", "ctg", "tan", "cot", "arcsin", "arccos", "arctg", "arcctg", "lim", "log", "lg", "ln", "exp", "max", "min", "sup", "inf", "det", "rank", "grad", "div", "rot", "const", "sgn", "sh", "ch", "th", "cth", "mod")

    /** The words of [line]: letters with - and ' inside; digits and signs split them. */
    fun words(line: String): List<Word> {
        val out = ArrayList<Word>()
        var i = 0
        while (i < line.length) {
            if (!line[i].isLetter()) {
                i++
                continue
            }
            var j = i
            while (j < line.length && (line[j].isLetter() || (line[j] in "-'’" && j + 1 < line.length && line[j + 1].isLetter()))) j++
            out += Word(line.substring(i, j).replace('’', '\''), i, j)
            i = j
        }
        return out
    }

    /** A stroke of the line: its box on the page. */
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Where a word is on the page: [left]…[right], the bottom of its letters at [base]. */
    class Place(val left: Float, val right: Float, val base: Float)

    /**
     * Where each word of [text] is, from the boxes of the line's strokes. The strokes fall
     * into groups at the gaps between words; when there are as many groups as words written
     * apart (by spaces), each word gets its group. Otherwise the line is shared by letters.
     */
    fun places(text: String, words: List<Word>, boxes: List<Box>): List<Place> {
        if (words.isEmpty() || boxes.isEmpty()) return emptyList()
        val left = boxes.minOf { it.left }
        val right = boxes.maxOf { it.right }
        val heights = boxes.map { it.bottom - it.top }.sorted()
        val h = max(heights[heights.size / 2], 1f)
        val base = boxes.map { it.bottom }.sorted()[boxes.size / 2]
        // Strokes into groups: a gap wider than ~half a letter starts a new one.
        val groups = ArrayList<MutableList<Box>>()
        var reach = Float.NEGATIVE_INFINITY
        for (b in boxes.sortedBy { it.left }) {
            if (groups.isEmpty() || b.left - reach > GAP * h) groups += mutableListOf(b) else groups.last() += b
            reach = max(reach, b.right)
        }
        // Pieces of the text between spaces, and which of them each word is in.
        val pieces = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            val s = i
            while (i < text.length && !text[i].isWhitespace()) i++
            pieces += s until i
        }
        fun groupBase(g: List<Box>) = g.map { it.bottom }.sorted()[(g.size - 1) / 2]
        if (pieces.size == groups.size) {
            return words.map { w ->
                val k = pieces.indexOfFirst { w.start in it }.coerceAtLeast(0)
                val g = groups[k]
                Place(g.minOf { it.left }, g.maxOf { it.right }, groupBase(g))
            }
        }
        val per = (right - left) / max(text.length, 1)
        return words.map { w -> Place(left + w.start * per, left + w.end * per, base) }
    }

    /** A word that may be misspelt, where it is on the page ([h]: letter height there). */
    class Typo(val word: String, val suggestions: List<String>, val left: Float, val right: Float, val base: Float, val h: Float)

    /** Letter heights between strokes that are still one word. */
    const val GAP = 0.5f
}
