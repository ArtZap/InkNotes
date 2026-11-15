package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The correction window's model: symbols, their places, the text and answer they give. */
class CorrectTest {

    private class Sheet {
        val strokes = ArrayList<Ink>()
        val labels = HashMap<Ink, String>()

        fun add(label: String?, xs: List<Float>, ys: List<Float>): Ink {
            val ink = Ink(xs.toFloatArray(), ys.toFloatArray(), strokes.size)
            strokes += ink
            if (label != null) labels[ink] = label
            return ink
        }

        fun glyph(label: String, x: Float, top: Float, w: Float = 14f, h: Float = 24f) =
            add(label, listOf(x, x + w, x, x + w), listOf(top, top + h / 3, top + 2 * h / 3, top + h))

        fun dash(x: Float, y: Float, w: Float) = add(null, listOf(x, x + w / 2, x + w), listOf(y, y + 0.6f, y))

        fun equals(x: Float, cy: Float, w: Float = 18f) {
            dash(x, cy - 4, w)
            dash(x, cy + 4, w)
        }

        fun reader(alt: Map<String, List<String>> = emptyMap()) = RunReader { run, _ ->
            val label = run.sortedBy { it.left }.mapNotNull { labels[it] }.joinToString("")
            alt[label] ?: listOf(label)
        }
    }

    /** x² + 1 = 10 with the 2 small and up at the top right of the x. */
    private fun square(): Sheet = Sheet().apply {
        glyph("x", 100f, 100f)
        glyph("2", 116f, 88f, 8f, 12f)
        glyph("+", 132f, 106f, 12f, 12f)
        glyph("1", 150f, 100f, 8f)
        equals(168f, 112f)
        glyph("1", 196f, 100f, 8f)
        glyph("0", 208f, 100f)
    }

    private fun leftOf(s: Sheet) = s.strokes.filter { it.right < 165f && s.labels[it] != null }

    @Test
    fun symbolsWithTheirPlaces() {
        val s = square()
        val side = Correct.side(leftOf(s), 24f, 112f, s.reader())!!
        assertEquals(listOf("x", "2", "+", "1"), side.syms.map { it.candidates[it.let { s2 -> side.initial[side.syms.indexOf(s2)].choice }] })
        assertEquals(Correct.UP, side.syms[1].level)
        assertEquals(Correct.LINE, side.syms[0].level)
        assertEquals("x^{2}+1", Correct.text(side, side.initial))
    }

    @Test
    fun movingASymbolDownOrUp() {
        val s = square()
        val side = Correct.side(leftOf(s), 24f, 112f, s.reader())!!
        val down = side.initial.toMutableList().also { it[1] = Correct.Pick(it[1].choice, Correct.LINE) }
        assertEquals("x2+1", Correct.text(side, down))
        val index = side.initial.toMutableList().also { it[1] = Correct.Pick(it[1].choice, Correct.DOWN) }
        assertEquals("x_{2}+1", Correct.text(side, index))
        val raised = side.initial.toMutableList().also { it[3] = Correct.Pick(it[3].choice, Correct.UP) }
        assertEquals("x^{2}+^{1}", Correct.text(side, raised))
    }

    @Test
    fun otherReadingsOfASymbolAreOnItsWheel() {
        val s = square()
        val side = Correct.side(leftOf(s), 24f, 112f, s.reader(mapOf("+1" to listOf("+1", "tl", "+7"))))!!
        val one = side.syms[3]
        assertEquals("1", one.candidates[0])
        assertTrue(one.candidates.containsAll(listOf("l", "7")))
        assertTrue("the two readings differ there", one.unsure)
        // The + read otherwise too: t is on its wheel.
        assertTrue("t" in side.syms[2].candidates)
    }

    @Test
    fun startsFromWhatThePageRead() {
        val s = square()
        // The reader's first guess is "tl"; the page took "+1" (it parses): the wheels start there.
        val side = Correct.side(leftOf(s), 24f, 112f, s.reader(mapOf("+1" to listOf("tl", "+1"))), target = MathPage.parseText("x^{2}+1"))!!
        assertEquals("x^{2}+1", Correct.text(side, side.initial))
    }

    @Test
    fun answersAsThePageDoes() {
        val solved = Correct.outcome("x^{2}+1", "10", Env())
        val lines = (solved.answer as Answer.Text).lines.joinToString(" ")
        assertTrue(lines, "3" in lines)
        assertEquals(listOf("5"), (Correct.outcome("2+3", null, Env()).answer as Answer.Text).lines)
        assertTrue(Correct.outcome("y", "x^{2}-3", Env()).answer is Answer.Graph)
        assertNull(Correct.outcome("y", "x2-3", Env()).note?.takeIf { Correct.outcome("y", "x2-3", Env()).answer != null })
        val bad = Correct.outcome("2+", null, Env())
        assertNull(bad.answer)
        assertNotNull(bad.note)
    }

    @Test
    fun aFormulaSetByHandIsTakenInsteadOfItsReading() {
        val s = Sheet()
        s.glyph("7", 100f, 100f)
        s.glyph("+", 118f, 106f, 12f, 12f)
        s.glyph("1", 136f, 100f, 8f)
        s.equals(154f, 112f)
        assertEquals("8", ((MathPage.analyze(s.strokes, s.reader()).single().answer) as Answer.Text).lines[0])
        val left = s.strokes.filter { s.labels[it] != null }
        val pinned = mapOf(Correct.fingerprint(left) to "7+4")
        val a = MathPage.analyze(s.strokes, s.reader(), pinned = pinned).single()
        assertEquals("11", (a.answer as Answer.Text).lines[0])
        assertTrue("set by hand: not unsure", !a.unsure)
    }

    @Test
    fun fingerprintIgnoresStrokeOrder() {
        val s = square()
        val left = leftOf(s)
        val shuffled = left.reversed().mapIndexed { i, k -> Ink(k.xs, k.ys, 100 + i) }
        assertEquals(Correct.fingerprint(left), Correct.fingerprint(shuffled))
    }

    @Test
    fun nothingReadAfterEqualsLeavesAMarkToTap() {
        val s = Sheet()
        s.glyph("a", 100f, 100f)
        s.glyph("b", 118f, 100f)
        s.equals(140f, 112f)
        val a = MathPage.analyze(s.strokes, s.reader(mapOf("ab" to listOf("a)("))) ).single()
        assertTrue(a.missing)
        assertNotNull(a.fix)
        assertNull(a.fix!!.right)
    }

    @Test
    fun aGraphThatDidNotComeOutLeavesAMark() {
        val s = Sheet()
        s.glyph("y", 100f, 100f)
        s.equals(122f, 112f)
        s.glyph("x", 150f, 100f)
        s.glyph("2", 166f, 88f, 8f, 12f)
        val answers = MathPage.analyze(s.strokes, s.reader(mapOf("x" to listOf(")("))))
        val mark = answers.single()
        assertTrue(mark.missing)
        assertTrue(mark.fix!!.graph)
    }

    @Test
    fun learnedChoices() {
        val l = Learned()
        assertEquals(listOf("2", "z"), l.order(listOf("2", "z")))
        repeat(2) { l.record("2", "z") }
        assertEquals(listOf("2", "z", "Z"), l.order(listOf("2", "Z", "z")))
        assertNull("twice is not yet enough", l.meant("2"))
        l.record("2", "z")
        assertEquals("z", l.meant("2"))
        assertEquals(listOf("z", "2"), l.adjust(listOf("2"), 1))
        assertEquals(listOf("x2", "xz", "x7"), l.adjust(listOf("x2", "x7"), 2))
        // Kept as often as corrected: not changed.
        repeat(3) { l.record("2", "2") }
        assertNull(l.meant("2"))
        val back = Learned.parse(l.serialize())
        assertEquals(l.serialize().lines().sorted(), back.serialize().lines().sorted())
    }

    @Test
    fun tokensAndShownForms() {
        assertEquals(listOf("\\pi", "x", "2"), Correct.tokens("\\pix2"))
        assertEquals("·", Correct.show("*"))
        assertEquals("2π", Correct.show("2\\pi"))
    }
}
