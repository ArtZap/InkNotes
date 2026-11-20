package dev.inknotes.math

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The writer's placements: exponents written low are read as exponents once they say so. */
class PlacementsTest {

    @After
    fun reset() {
        Tune.personal = null
    }

    private fun ink(x: Float, top: Float, w: Float, h: Float) = Ink(floatArrayOf(x, x + w, x, x + w), floatArrayOf(top, top + h / 3, top + 2 * h / 3, top + h))

    /** x at 100…124, then a small 2 whose foot is three quarters down the x: low for an exponent. */
    private fun lowSquare(dx: Float = 0f) = listOf(ink(100f + dx, 100f, 14f, 24f), ink(116f + dx, 99f, 9f, 19f))

    private fun reader(labels: Map<Ink, String>) = RunReader { run, _ -> listOf(run.sortedBy { it.left }.mapNotNull { labels[it] }.joinToString("")) }

    private fun read(strokes: List<Ink>) = MathLayout.formula(strokes, 24f, 112f).joinToString("") { p ->
        when (p) {
            is Piece.Text -> p.s
            is Piece.Hole -> p.glyphs.joinToString("") { g -> if (g[0].width < 10f) "2" else "x" }
        }
    }

    @Test
    fun aLowExponentFollowsTheWritersHabit() {
        assertEquals("by the general rules: beside", "x2", read(lowSquare()))
        val p = Placements()
        val x = Box(100f, 100f, 114f, 124f)
        // The writer raised such 2s eight times, and kept bigger ones on the line.
        repeat(8) { p.add(Placements.of(Box(116f, 99f + it * 0.3f, 125f, 118f + it * 0.3f), x, 24f, Correct.UP)) }
        repeat(4) { p.add(Placements.of(Box(116f, 100f, 130f, 124f), x, 24f, Correct.LINE)) }
        Tune.personal = p
        assertEquals("x^{2}", read(lowSquare()))
        // A symbol of full size beside stays beside.
        assertEquals("xx", read(listOf(ink(100f, 100f, 14f, 24f), ink(118f, 100f, 14f, 24f))).replace("2", "x"))
    }

    @Test
    fun tooFewOrOneSidedSaysNothing() {
        val p = Placements()
        val x = Box(100f, 100f, 114f, 124f)
        repeat(12) { p.add(Placements.of(Box(116f, 99f, 125f, 118f), x, 24f, Correct.UP)) }
        assertNull("only exponents seen: no boundary to learn", p.level(Box(116f, 99f, 125f, 118f), x, 24f))
        assertNull(Placements().level(Box(116f, 99f, 125f, 118f), x, 24f))
    }

    @Test
    fun observedFromACorrection() {
        val strokes = lowSquare()
        val labels = mapOf(strokes[0] to "x", strokes[1] to "2")
        val side = Correct.side(strokes, 24f, 112f, reader(labels))!!
        val raised = side.initial.toMutableList().also { it[1] = Correct.Pick(it[1].choice, Correct.UP) }
        val seen = Placements.observe(side, raised, 24f)
        assertEquals(1, seen.size)
        assertEquals(Correct.UP, seen[0].level)
        val back = Placements.parse(Placements().also { p -> seen.forEach(p::add) }.serialize())
        assertEquals(1, back.count())
    }
}
