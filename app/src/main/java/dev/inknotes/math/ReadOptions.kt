package dev.inknotes.math

import kotlin.math.max

/**
 * Switches for how runs of symbols are read (0.62). On by default when measured better with
 * the real handwriting reader (FormulaReadTest, SymbolReadTest); the device bench turns them
 * off and on one by one to compare in one run.
 */
object ReadOptions {
    /** A sign told by its shape (′, °) may also be a raised 1 or 0. */
    @JvmField var signAlternatives = true

    /**
     * A run the reader split into another number of symbols than it has glyphs: each glyph is
     * read alone too, and their readings joined are the next guess.
     */
    @JvmField var glyphByGlyph = true

    /** A lone upright read as l or I is tried as 1 first. */
    @JvmField var oneFirst = true

    /** The reader's guesses after a bit of arithmetic come before its plain ones (first when the two disagree). */
    @JvmField var mathFirst = false

    /** The reader's guesses reordered by [cost]: its order, and how usual the symbols are in formulas. */
    @JvmField var byCost = true

    /** How much a step down the reader's list costs, against the symbols' costs ([symbolCost]). */
    @JvmField var rankWeight = 0.7f

    /** A lone letter with digits right after it in one run is the letter with an index (x1 → x₁). */
    @JvmField var digitIndex = true

    /** Strokes handed to the reader from the corner of its writing area, not where they are on the page. */
    @JvmField var fromOrigin = false

    /** A short pause between strokes of one glyph and a long one between glyphs, not one pause for all. */
    @JvmField var glyphPauses = false

    /** The switches as a whole, to set and restore them. */
    data class Setting(
        val signAlternatives: Boolean,
        val glyphByGlyph: Boolean,
        val oneFirst: Boolean,
        val byCost: Boolean,
        val mathFirst: Boolean = false,
        val fromOrigin: Boolean = false,
        val glyphPauses: Boolean = false,
    ) {
        override fun toString() = listOf(signAlternatives, glyphByGlyph, oneFirst, byCost, mathFirst, fromOrigin, glyphPauses).joinToString("") { if (it) "1" else "0" }
    }

    fun get() = Setting(signAlternatives, glyphByGlyph, oneFirst, byCost, mathFirst, fromOrigin, glyphPauses)

    fun set(v: Setting) {
        signAlternatives = v.signAlternatives
        glyphByGlyph = v.glyphByGlyph
        oneFirst = v.oneFirst
        byCost = v.byCost
        mathFirst = v.mathFirst
        fromOrigin = v.fromOrigin
        glyphPauses = v.glyphPauses
    }

    /** What the device bench compares with the shipped setting: as in 0.61 and 0.62, and each 0.63 change alone. */
    val BENCH: List<Pair<String, Setting>> = listOf(
        "as in 0.61" to Setting(signAlternatives = false, glyphByGlyph = false, oneFirst = false, byCost = false),
        "as in 0.62" to Setting(signAlternatives = true, glyphByGlyph = true, oneFirst = false, byCost = false),
        "1 first" to Setting(signAlternatives = true, glyphByGlyph = true, oneFirst = true, byCost = false),
        "math first" to Setting(signAlternatives = true, glyphByGlyph = true, oneFirst = true, byCost = true, mathFirst = true),
    )

    /** One stroke as handed to the reader: points and their times (ms from the first stroke). */
    class Stroke(val xs: FloatArray, val ys: FloatArray, val ts: LongArray)

    /** A run as handed to the reader: its strokes and the size of the writing area. */
    class Input(val strokes: List<Stroke>, val width: Float, val height: Float)

    /** Pause between strokes: one for all (as in 0.61), within a glyph, between glyphs. */
    const val PAUSE_MS = 120L
    const val PAUSE_IN_GLYPH_MS = 50L
    const val PAUSE_BETWEEN_GLYPHS_MS = 400L

    /**
     * [run] (line height [h]) as the reader gets it, by the switches above. The writing area is
     * as wide as the run (at least [h]) and 1.3 lines tall at least; [fromOrigin] puts the run
     * at its left edge and in its middle, where the area is, rather than where it is on the page.
     */
    fun input(run: List<Ink>, h: Float): Input {
        val box = Box.of(run)
        val width = max(box.width, h)
        val height = max(box.height, h * 1.3f)
        val dx = if (fromOrigin) -box.left else 0f
        val dy = if (fromOrigin) -box.top + (height - box.height) / 2 else 0f
        val glyphOf = HashMap<Ink, Int>()
        if (glyphPauses) {
            runCatching {
                MathLayout.items(run, h).filterIsInstance<MathLayout.G>().forEachIndexed { i, g -> for (s in g.glyph.strokes) glyphOf[s] = i }
            }
        }
        val out = ArrayList<Stroke>()
        var time = 0L
        var prev: Ink? = null
        for (s in run) {
            if (prev != null) {
                val a = glyphOf[prev]
                val b = glyphOf[s]
                time += when {
                    !glyphPauses || a == null || b == null -> PAUSE_MS
                    a == b -> PAUSE_IN_GLYPH_MS
                    else -> PAUSE_BETWEEN_GLYPHS_MS
                }
            }
            val n = s.xs.size
            val xs = FloatArray(n) { s.xs[it] + dx }
            val ys = FloatArray(n) { s.ys[it] + dy }
            val ts = LongArray(n) { time + (s.times?.get(it) ?: (it * 10L)) }
            out += Stroke(xs, ys, ts)
            time = ts.lastOrNull() ?: time
            prev = s
        }
        return Input(out, width, height)
    }

    /** The reader's two guess lists as one, by turns, without repeats: [mathFirst] says which goes first. */
    fun interleave(plain: List<String>, math: List<String>): List<String> {
        val (a, b) = if (mathFirst) math to plain else plain to math
        val texts = ArrayList<String>()
        for (k in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(k)?.let { if (it !in texts) texts += it }
            b.getOrNull(k)?.let { if (it !in texts) texts += it }
        }
        return texts
    }

    /** Words read as one: a function, its letters cost nothing. */
    private val WORDS = Regex("arcsin|arccos|arctg|sinh|cosh|sin|cos|tan|tg|ctg|cot|log|lg|ln|lim|exp|max|min|sqrt|mod")

    /**
     * How unusual a symbol is in a formula (0: digits and signs). The English reader takes a
     * 4 for h or t, a 5 for s, π for T, x for æ: those letters are rarer in formulas than
     * what they hide, letters outside a-z rarer still.
     */
    fun symbolCost(c: Char): Float = when {
        c.isDigit() || c in "+-−=()[]{}.,/^_*·×÷√∫π'°<>≤≥!|" -> 0f
        c in "xyzabcnkmtuvpqrsdfgijeh" -> 0.4f
        c in "ABCDEFGHIJKLMNOPQRSTUVWXYZlwo" -> 1f
        c.isLetter() && c.code < 128 -> 1f
        else -> 2f
    }

    /** A guess at rank [rank] of the reader's list: lower is likelier. */
    fun cost(text: String, rank: Int): Float = rankWeight * rank + WORDS.replace(text, "").sumOf { symbolCost(it).toDouble() }.toFloat()

    /** The reader's guesses by [cost], stable for equal costs; as they are when [byCost] is off. */
    fun rank(texts: List<String>): List<String> {
        if (!byCost || texts.size < 2) return texts
        return texts.withIndex().sortedBy { (i, t) -> cost(t, i) }.map { it.value }
    }

    /** Where function words stand in [text] (sin, cos, log…). */
    fun wordRanges(text: String): List<IntRange> = WORDS.findAll(text).map { it.range }.toList()

    /** The switches, as a key for cached readings. */
    fun key(): String = "${if (fromOrigin) 'o' else '-'}${if (glyphPauses) 'p' else '-'}"
}
