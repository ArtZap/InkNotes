package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TableSumsTest {
    private val strokes = ArrayList<Ink>()
    private val labels = HashMap<Ink, String>()

    /** A symbol drawn as a zigzag in its box, read as [label]. */
    private fun glyph(label: String, x: Float, top: Float, w: Float = 10f, h: Float = 18f) {
        val ink = Ink(floatArrayOf(x, x + w, x, x + w), floatArrayOf(top, top + h / 3, top + 2 * h / 3, top + h), strokes.size)
        strokes += ink
        labels[ink] = label
    }

    /** [text] written in the cell whose top left is ([x], [y]), a glyph per character. */
    private fun cell(text: String, x: Float, y: Float) {
        for ((i, ch) in text.withIndex()) glyph(ch.toString(), x + 6 + i * 13f, y + 6)
    }

    private val reader = RunReader { run, _ -> listOf(run.sortedBy { it.left }.mapNotNull { labels[it] }.joinToString("")) }

    @Test
    fun rowsAndColumnsTotalled() {
        // name | Jan | Feb
        // a    | 12  | 3
        // b    | 5   | 20
        val rows = listOf(0f, 30f, 60f, 90f)
        val cols = listOf(0f, 60f, 120f, 180f)
        cell("ab", 0f, 0f)
        cell("Jan", 60f, 0f)
        cell("Feb", 120f, 0f)
        cell("a", 0f, 30f)
        cell("12", 60f, 30f)
        cell("3", 120f, 30f)
        cell("b", 0f, 60f)
        cell("5", 60f, 60f)
        cell("20", 120f, 60f)
        val r = TableSums.compute(rows, cols, strokes, reader)
        assertEquals(4, r.numbers)
        assertNull(r.rows[0])
        assertEquals(15.0, r.rows[1]!!.sum, 1e-9)
        assertEquals(25.0, r.rows[2]!!.sum, 1e-9)
        assertNull(r.cols[0])
        assertEquals(17.0, r.cols[1]!!.sum, 1e-9)
        assertEquals(8.5, r.cols[1]!!.mean, 1e-9)
        assertEquals(23.0, r.cols[2]!!.sum, 1e-9)
    }

    @Test
    fun emptyCellsAndWordsAreLeftOut() {
        val rows = listOf(0f, 30f, 60f, 90f)
        val cols = listOf(0f, 60f)
        cell("7", 0f, 0f)
        cell("xy", 0f, 30f)
        cell("4", 0f, 60f)
        val r = TableSums.compute(rows, cols, strokes, reader)
        assertEquals(11.0, r.cols[0]!!.sum, 1e-9)
        assertEquals(2, r.cols[0]!!.count)
    }
}
