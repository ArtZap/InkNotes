package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormulaSourceTest {
    private fun upright(x: Float, order: Int) = Ink(floatArrayOf(x, x + 2f, x + 4f), floatArrayOf(100f, 120f, 140f), order)

    @Test
    fun readingThatParsesIsChosen() {
        val ink = listOf(upright(100f, 0), upright(140f, 1), upright(180f, 2))
        val reader = RunReader { _, _ -> listOf("x+)", "x+1") }
        assertEquals("x+1", MathPage.formulaSource(ink, reader))
    }

    @Test
    fun equationSidesParseEachOnItsOwn() {
        val ink = listOf(upright(100f, 0), upright(140f, 1))
        val reader = RunReader { _, _ -> listOf("a=b+1") }
        assertEquals("a=b+1", MathPage.formulaSource(ink, reader))
    }

    @Test
    fun nothingWrittenNothingRead() {
        assertNull(MathPage.formulaSource(emptyList(), RunReader { _, _ -> emptyList() }))
    }
}
