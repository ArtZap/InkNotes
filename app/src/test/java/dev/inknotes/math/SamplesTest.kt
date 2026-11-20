package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** The writer's own symbols as a second voice: near shapes are told apart, far ones are not guessed. */
class SamplesTest {

    private val rnd = Random(7)

    /** A drawn symbol from [points] (a unit box), placed at [x], [y], size [s], a little shaky. */
    private fun draw(points: List<Pair<Float, Float>>, x: Float = 100f, y: Float = 100f, s: Float = 24f, shake: Float = 0.04f, order: Int = 0): Ink {
        val xs = FloatArray(points.size) { x + s * (points[it].first + shake * (rnd.nextFloat() - 0.5f)) }
        val ys = FloatArray(points.size) { y + s * (points[it].second + shake * (rnd.nextFloat() - 0.5f)) }
        return Ink(xs, ys, order)
    }

    // The writer's own: a 1 with a flag, a 7 with a long top, an l with a loop.
    private val one = listOf(0.25f to 0.25f, 0.5f to 0f, 0.5f to 0.5f, 0.5f to 1f)
    private val seven = listOf(0f to 0f, 0.5f to 0f, 1f to 0f, 0.7f to 0.5f, 0.4f to 1f)
    private val loopL = List(16) { k -> val t = k / 15f; (0.5f + 0.25f * sin(6.28f * t)) to (1f - t + 0.15f * cos(6.28f * t)).coerceIn(0f, 1f) }

    @Test
    fun tellsTheWritersSymbolsApart() {
        val s = Samples()
        repeat(3) {
            s.add("1", listOf(draw(one)))
            s.add("7", listOf(draw(seven, s = 20f)))
        }
        val v = s.vote(listOf(draw(one, x = 300f, y = 40f, s = 30f)))
        assertNotNull(v)
        assertEquals("1", v!!.token)
        assertTrue("near and clearly not a 7: sure (${v.distance})", v.sure)
        assertEquals("7", s.vote(listOf(draw(seven, x = 10f, s = 40f)))!!.token)
    }

    @Test
    fun notSureOfAShapeNeverSeen() {
        val s = Samples()
        repeat(3) {
            s.add("1", listOf(draw(one)))
            s.add("7", listOf(draw(seven)))
        }
        val v = s.vote(listOf(draw(loopL)))
        assertTrue("a loop is neither: not sure (${v?.token} ${v?.distance})", v == null || !v.sure)
    }

    @Test
    fun oneSampleIsNotEnough() {
        val s = Samples()
        s.add("1", listOf(draw(one)))
        assertTrue(!s.vote(listOf(draw(one)))!!.sure)
    }

    @Test
    fun keptBetweenRuns() {
        val s = Samples()
        repeat(2) { s.add("x", listOf(draw(one), draw(seven, order = 1))) }
        val back = Samples.parse(s.serialize())
        assertEquals(2, back.count())
        assertEquals("x", back.vote(listOf(draw(one), draw(seven, order = 1)))!!.token)
        assertNull(Samples.shape(emptyList()))
    }

    @Test
    fun sureReadingsChangeWhatIsRead() {
        val s = Samples()
        repeat(3) { s.add("1", listOf(draw(one))) }
        repeat(3) { s.add("7", listOf(draw(seven))) }
        // ML Kit read the writer's 1 as l: the samples put 1 first.
        val glyph = listOf(draw(one))
        val adjusted = Voices.adjust(listOf("l"), listOf(glyph), s)
        assertTrue(adjusted.toString(), adjusted == listOf("1", "l") || adjusted == listOf("l", "1"))
        // In a run, the reading with the writer's symbol comes second.
        val run = listOf(listOf(draw(seven, x = 70f)), glyph)
        assertEquals(listOf("7l", "71"), Voices.adjust(listOf("7l"), run, s))
    }
}
