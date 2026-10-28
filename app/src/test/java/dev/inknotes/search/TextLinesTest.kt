package dev.inknotes.search

import dev.inknotes.search.TextLines.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLinesTest {
    /** A "letter" of handwriting at [x] on the line whose baseline is [y]. */
    private fun letter(x: Float, y: Float, h: Float = 24f) = Box(x, y - h, x + 18f, y)

    @Test
    fun groupsTwoLines() {
        val boxes = listOf(
            letter(100f, 200f), letter(125f, 202f), letter(150f, 198f, 34f), // first line, with a tall letter
            letter(100f, 260f), letter(125f, 262f, 14f), // second line
            letter(175f, 200f), // written later, still the first line
        )
        assertEquals(listOf(listOf(0, 1, 2, 5), listOf(3, 4)), TextLines.group(boxes))
    }

    @Test
    fun dotsJoinTheirLine() {
        val boxes = listOf(letter(100f, 200f), letter(125f, 200f), Box(130f, 168f, 133f, 171f))
        assertEquals(listOf(listOf(0, 1, 2)), TextLines.group(boxes))
    }

    @Test
    fun drawingsAndColumnsAreSeparated() {
        val boxes = listOf(
            letter(100f, 200f), letter(125f, 200f),
            letter(700f, 200f), letter(725f, 200f), // far right: a separate note
            Box(100f, 300f, 600f, 700f), // a big drawing
            letter(100f, 260f), letter(125f, 260f),
        )
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(5, 6)), TextLines.group(boxes))
    }

    @Test
    fun matching() {
        assertTrue(TextLines.matches("Ёлка", listOf("новогодняя елка")))
        assertTrue(TextLines.matches("интеграл", listOf("интеграп по частям")))
        assertTrue(TextLines.matches("Функция", listOf("функцuя")))
        assertFalse(TextLines.matches("кот", listOf("кит")))
        assertFalse(TextLines.matches("", listOf("что угодно")))
        assertTrue(TextLines.matches("по част", listOf("интеграл по частям")))
    }
}
