package dev.inknotes.math

import dev.inknotes.search.Spelling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingTest {
    @Test
    fun wordsOfALine() {
        val w = Spelling.words("Теорема Коши-Буняковского: x² ≤ 1, it's ok")
        assertEquals(listOf("Теорема", "Коши-Буняковского", "x", "it's", "ok"), w.map { it.text })
        assertEquals(Spelling.Lang.RU, w[1].lang)
        assertEquals(Spelling.Lang.EN, w[3].lang)
        assertEquals(8, w[1].start)
    }

    @Test
    fun whatIsChecked() {
        val ok = Spelling.words("превет мир МФТИ iPhone ДЗ матан x2 mixedлат Ёлка").filter { it.checkable }.map { it.text }
        assertEquals(listOf("превет", "мир", "матан", "Ёлка"), ok)
    }

    private fun letters(x0: Float, n: Int) = (0 until n).map { Spelling.Box(x0 + it * 12f, 100f, x0 + it * 12f + 10f, 120f) }

    @Test
    fun eachWordGetsItsStrokes() {
        val text = "мама мыла раму"
        val boxes = letters(0f, 4) + letters(80f, 4) + letters(160f, 4)
        val p = Spelling.places(text, Spelling.words(text), boxes)
        assertEquals(3, p.size)
        assertEquals(80f, p[1].left, 0.01f)
        assertEquals(126f, p[1].right, 0.01f)
        assertEquals(120f, p[2].base, 0.01f)
    }

    @Test
    fun whenGroupsDoNotMatchTheLineIsSharedByLetters() {
        val text = "мама мыла"
        // Written without a gap: one group, two words.
        val boxes = letters(0f, 8)
        val p = Spelling.places(text, Spelling.words(text), boxes)
        assertTrue(p[0].right <= p[1].left)
        assertTrue(p[1].right <= 94f)
    }

    @Test
    fun formulasAreNotChecked() {
        assertTrue(Spelling.prose("Пусть функция непрерывна"))
        assertTrue(!Spelling.prose("sin x = 0,5"))
        assertTrue(!Spelling.prose("(a+b)^2 ≤ 2(a²+b²)"))
        assertTrue(Spelling.words("arcsin lim предел").filter { it.checkable }.map { it.text } == listOf("предел"))
    }
}
