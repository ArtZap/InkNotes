package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfWordsTest {
    // Two lines of text, 10 high, 20 apart.
    private val words = PdfWords.numberLines(
        listOf(
            PdfWord("Первая", 10f, 10f, 60f, 20f),
            PdfWord("строка", 65f, 10f, 110f, 20f),
            PdfWord("тек-", 115f, 11f, 140f, 20f),
            PdfWord("ста", 10f, 30f, 35f, 40f),
            PdfWord("дальше", 40f, 30f, 90f, 40f),
        ),
    )

    @Test
    fun linesAreNumbered() {
        assertEquals(listOf(0, 0, 0, 1, 1), words.map { it.line })
    }

    @Test
    fun textJoinsWordsLinesAndHyphens() {
        assertEquals("строка тек-", PdfWords.text(words, 1, 2))
        assertEquals("строка текста дальше", PdfWords.text(words, 4, 1))
        assertEquals("Первая\nдальше", PdfWords.text(listOf(words[0], words[4]), 0, 1))
    }

    @Test
    fun oneBoxALine() {
        val b = PdfWords.boxes(words, 1, 3)
        assertEquals(2, b.size)
        assertEquals(listOf(65f, 10f, 140f, 20f), b[0].toList())
        assertEquals(listOf(10f, 30f, 35f, 40f), b[1].toList())
    }

    @Test
    fun wordUnderThePen() {
        assertEquals(1, PdfWords.wordAt(words, 80f, 15f, 5f))
        assertEquals(4, PdfWords.wordAt(words, 95f, 36f, 10f))
        assertEquals(-1, PdfWords.wordAt(words, 300f, 300f, 10f))
    }

    @Test
    fun markerPassLaysAlongTheLine() {
        // A wavy pass a bit low over "строка тек-", just touching "Первая".
        val xs = floatArrayOf(58f, 80f, 100f, 138f)
        val ys = floatArrayOf(19f, 22f, 18f, 21f)
        val band = PdfWords.snapPass(words, xs, ys)!!
        assertEquals(65f, band[0])
        assertEquals(140f, band[2])
        assertEquals(15f, band[1], 0.6f)
    }

    @Test
    fun aPassDownSeveralLinesIsLeftAlone() {
        assertNull(PdfWords.snapPass(words, floatArrayOf(20f, 30f), floatArrayOf(5f, 45f)))
    }

    @Test
    fun darkMatrixTurnsWhiteToPaperAndBlackToInk() {
        val m = PdfWords.darkMatrix(30f, 230f)
        fun apply(r: Float, g: Float, b: Float) = FloatArray(3) { i -> m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4] }
        apply(255f, 255f, 255f).forEach { assertEquals(30f, it, 0.5f) }
        apply(0f, 0f, 0f).forEach { assertEquals(230f, it, 0.5f) }
        assertEquals(1f, m[18])
    }
}
