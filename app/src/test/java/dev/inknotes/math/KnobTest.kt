package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Numbers turned on a scale beside them: a = 3, the lines below change. */
class KnobTest {
    private val strokes = ArrayList<Ink>()
    private val labels = HashMap<Ink, String>()

    private fun glyph(label: String, x: Float, top: Float, w: Float = 14f, h: Float = 24f) {
        val ink = Ink(floatArrayOf(x, x + w, x, x + w), floatArrayOf(top, top + h / 3, top + 2 * h / 3, top + h), strokes.size)
        strokes += ink
        labels[ink] = label
    }

    private fun dash(x: Float, y: Float, w: Float = 18f) {
        strokes += Ink(floatArrayOf(x, x + w / 2, x + w), floatArrayOf(y, y + 0.6f, y), strokes.size)
    }

    private fun equals(x: Float, cy: Float) {
        dash(x, cy - 4)
        dash(x, cy + 4)
    }

    private val reader = RunReader { run, _ -> listOf(run.sortedBy { it.left }.mapNotNull { labels[it] }.joinToString("")) }

    /** a = [value] (as written), then a + 2 = under it. */
    private fun page(value: String) {
        strokes.clear()
        labels.clear()
        glyph("a", 100f, 58f, 14f, 16f)
        equals(122f, 66f)
        glyph(value, 148f, 50f, 14f * value.length)
        glyph("a", 100f, 104f, 14f, 20f)
        glyph("+", 120f, 106f, 12f, 12f)
        glyph("2", 132f, 100f)
        equals(154f, 112f)
    }

    private fun text(a: PageAnswer) = (a.answer as Answer.Text).lines.joinToString("\n")

    @Test
    fun aNumberGivenToALetterIsAKnob() {
        page("3")
        val knobs = ArrayList<Knob>()
        val answers = MathPage.analyze(strokes, reader, knobs = knobs)
        assertEquals("5", text(answers.single()))
        val k = knobs.single()
        assertEquals("a", k.name)
        assertEquals(3.0, k.written, 0.0)
        assertEquals(1.0, k.step, 0.0)
        assertTrue(!k.set)
        assertTrue(k.left >= 147f && k.right <= 163f)
    }

    @Test
    fun theValueSetOnTheScaleIsUsedBelow() {
        page("3")
        val knobs = ArrayList<Knob>()
        MathPage.analyze(strokes, reader, knobs = knobs)
        val key = knobs.single().key
        val again = ArrayList<Knob>()
        val answers = MathPage.analyze(strokes, reader, scaled = mapOf(key to 4.5), knobs = again)
        assertEquals("6.5", text(answers.single()).replace(",", "."))
        assertTrue(again.single().set)
        assertEquals(4.5, again.single().value, 0.0)
        assertEquals(3.0, again.single().written, 0.0)
    }

    @Test
    fun stepFollowsTheLastWrittenDigit() {
        assertEquals(1.0, Knob.stepOf(12.0), 0.0)
        assertEquals(0.1, Knob.stepOf(2.5), 1e-12)
        assertEquals(0.01, Knob.stepOf(-0.25), 1e-12)
        page("-2")
        val knobs = ArrayList<Knob>()
        MathPage.analyze(strokes, reader, knobs = knobs)
        assertEquals(-2.0, knobs.single().written, 0.0)
    }

    @Test
    fun graphsAndFormulasAreNotKnobs() {
        strokes.clear()
        labels.clear()
        // y = 3 is a graph; b = a + 1 is a formula.
        glyph("y", 100f, 58f, 14f, 16f)
        equals(122f, 66f)
        glyph("3", 148f, 50f)
        glyph("b", 100f, 108f, 14f, 16f)
        equals(122f, 116f)
        glyph("x+1", 148f, 104f, 40f)
        val knobs = ArrayList<Knob>()
        MathPage.analyze(strokes, reader, knobs = knobs)
        assertTrue(knobs.map { it.name }.toString(), knobs.isEmpty())
    }
}
