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

    /** Strokes handed to the reader from the corner of its writing area, not where they are on the page. */
    @JvmField var fromOrigin = false

    /** A short pause between strokes of one glyph and a long one between glyphs, not one pause for all. */
    @JvmField var glyphPauses = false

    /** The switches in order: [signAlternatives], [glyphByGlyph], [fromOrigin], [glyphPauses]. */
    fun get(): BooleanArray = booleanArrayOf(signAlternatives, glyphByGlyph, fromOrigin, glyphPauses)

    fun set(v: BooleanArray) {
        signAlternatives = v[0]
        glyphByGlyph = v[1]
        fromOrigin = v[2]
        glyphPauses = v[3]
    }

    /** What the device bench compares: as in 0.61, each switch alone, all on. */
    val BENCH: List<Pair<String, BooleanArray>> = listOf(
        "as in 0.61" to booleanArrayOf(false, false, false, false),
        "prime, degree" to booleanArrayOf(true, false, false, false),
        "glyph by glyph" to booleanArrayOf(false, true, false, false),
        "from origin" to booleanArrayOf(false, false, true, false),
        "pauses" to booleanArrayOf(false, false, false, true),
        "all" to booleanArrayOf(true, true, true, true),
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

    /** The switches, as a key for cached readings. */
    fun key(): String = "${if (fromOrigin) 'o' else '-'}${if (glyphPauses) 'p' else '-'}"
}
