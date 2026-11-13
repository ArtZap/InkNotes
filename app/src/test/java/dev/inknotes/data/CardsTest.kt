package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsTest {

    @Test
    fun newCardIsDue() = assertTrue(Cards.isDue(null, 100))

    @Test
    fun rememberedCardsComeBackLater() {
        var s = Cards.grade(null, Cards.Grade.GOOD, 100)
        assertEquals(Cards.State(1, 101), s)
        assertFalse(Cards.isDue(s, 100))
        s = Cards.grade(s, Cards.Grade.GOOD, 101)
        assertEquals(Cards.State(2, 104), s)
        s = Cards.grade(s, Cards.Grade.EASY, 104)
        assertEquals(4, s.box)
        assertEquals(104L + 14, s.due)
    }

    @Test
    fun forgottenCardStartsOverToday() {
        val s = Cards.grade(Cards.State(5, 90), Cards.Grade.AGAIN, 100)
        assertEquals(Cards.State(0, 100), s)
        assertTrue(Cards.isDue(s, 100))
    }

    @Test
    fun hardStepsBack() {
        val s = Cards.grade(Cards.State(3, 100), Cards.Grade.HARD, 100)
        assertEquals(2, s.box)
        assertEquals(103L, s.due)
    }

    @Test
    fun stateRoundTrip() {
        val states = mapOf("a:1:2:3:4" to Cards.State(2, 20000), "b:0:0:1:1" to Cards.State(0, 19999))
        assertEquals(states, Cards.decode(Cards.encode(states)))
        assertEquals(emptyMap<String, Cards.State>(), Cards.decode("garbage\nx\t1"))
    }

    @Test
    fun intervalsReadable() {
        assertEquals("через 1 дн.", Cards.interval(null, Cards.Grade.GOOD, 0))
        assertEquals("сегодня", Cards.interval(null, Cards.Grade.AGAIN, 0))
        assertEquals("через 2 мес.", Cards.interval(Cards.State(5, 0), Cards.Grade.GOOD, 0))
    }
}
