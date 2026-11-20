package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a run is handed to the handwriting reader: as in 0.61 by default, and the switches. */
class ReadOptionsTest {

    private fun stroke(x: Float, y: Float, w: Float, h: Float, order: Int) =
        Ink(floatArrayOf(x, x + w, x, x + w), floatArrayOf(y, y + h / 3, y + 2 * h / 3, y + h), order, longArrayOf(0, 10, 20, 30))

    // Two glyphs, the first of two strokes: an x and a 1.
    private val run = listOf(
        Ink(floatArrayOf(700f, 714f), floatArrayOf(1400f, 1424f), 0, longArrayOf(0, 40)),
        Ink(floatArrayOf(714f, 700f), floatArrayOf(1400f, 1424f), 1, longArrayOf(0, 40)),
        stroke(740f, 1400f, 3f, 24f, 2),
    )

    private fun <T> with(origin: Boolean, pauses: Boolean, f: () -> T): T {
        val (o, p) = ReadOptions.fromOrigin to ReadOptions.glyphPauses
        ReadOptions.fromOrigin = origin
        ReadOptions.glyphPauses = pauses
        try {
            return f()
        } finally {
            ReadOptions.fromOrigin = o
            ReadOptions.glyphPauses = p
        }
    }

    @Test
    fun asBeforeByDefault() {
        val input = with(false, false) { ReadOptions.input(run, 24f) }
        assertEquals(700f, input.strokes[0].xs[0])
        assertEquals(1400f, input.strokes[0].ys[0])
        // Times as 0.61 put them: each stroke after the last point of the one before and 120 ms.
        assertEquals(listOf(0L, 40L), input.strokes[0].ts.toList())
        assertEquals(listOf(160L, 200L), input.strokes[1].ts.toList())
        assertEquals(320L, input.strokes[2].ts[0])
        assertEquals(43f, input.width)
        assertEquals(24f * 1.3f, input.height, 1e-4f)
    }

    @Test
    fun fromTheCornerOfTheArea() {
        val input = with(true, false) { ReadOptions.input(run, 24f) }
        val xs = input.strokes.flatMap { it.xs.toList() }
        val ys = input.strokes.flatMap { it.ys.toList() }
        assertEquals(0f, xs.min())
        assertTrue(xs.max() <= input.width)
        // In the middle of the area, top to bottom.
        assertEquals(input.height - ys.max(), ys.min(), 1e-3f)
    }

    @Test
    fun shortPausesWithinAGlyph() {
        val input = with(false, true) { ReadOptions.input(run, 24f) }
        assertEquals(40L + ReadOptions.PAUSE_IN_GLYPH_MS, input.strokes[1].ts[0])
        assertEquals(input.strokes[1].ts.last() + ReadOptions.PAUSE_BETWEEN_GLYPHS_MS, input.strokes[2].ts[0])
    }

    @Test
    fun cachedReadingsAreKeptApartBySwitches() {
        val keys = listOf(false to false, true to false, false to true, true to true).map { (o, p) -> with(o, p) { ReadOptions.key() } }
        assertEquals(4, keys.toSet().size)
    }

    @Test
    fun usualSymbolsComeFirst() {
        // 5π under 5T, x under æ: one step down the reader's list costs less than an unusual symbol;
        // three steps cost more.
        assertEquals(listOf("5π", "5T"), ReadOptions.rank(listOf("5T", "5π")))
        assertEquals(listOf("5T", "5t", "5π"), ReadOptions.rank(listOf("5T", "5t", "5π")))
        assertEquals(listOf("x", "æ"), ReadOptions.rank(listOf("æ", "x")))
        // Functions cost nothing for their letters: cos x over cos æ only for the x.
        assertEquals("cosx", ReadOptions.rank(listOf("cosæ", "cosx")).first())
        // Equal symbols keep the reader's order; far down the list a guess does not jump up.
        assertEquals(listOf("ab", "ba"), ReadOptions.rank(listOf("ab", "ba")))
        assertEquals("s", ReadOptions.rank(listOf("s", "g", "y", "5")).first())
        // Off: as read.
        ReadOptions.byCost = false
        try {
            assertEquals(listOf("5T", "5π"), ReadOptions.rank(listOf("5T", "5π")))
        } finally {
            ReadOptions.byCost = true
        }
    }

    @Test
    fun guessListsByTurns() {
        assertEquals(listOf("a", "b", "c", "d"), ReadOptions.interleave(listOf("a", "c"), listOf("b", "d", "c")))
        ReadOptions.mathFirst = true
        try {
            assertEquals(listOf("b", "a", "d", "c"), ReadOptions.interleave(listOf("a", "c"), listOf("b", "d", "c")))
        } finally {
            ReadOptions.mathFirst = false
        }
    }

    @Test
    fun settingsRoundTrip() {
        val shipped = ReadOptions.get()
        try {
            for ((_, v) in ReadOptions.BENCH) {
                ReadOptions.set(v)
                assertEquals(v, ReadOptions.get())
            }
        } finally {
            ReadOptions.set(shipped)
        }
        assertEquals("1111000", shipped.toString())
    }
}
