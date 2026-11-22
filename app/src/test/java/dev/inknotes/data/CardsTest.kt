package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsTest {

    private fun days(s: Cards.State, today: Long) = s.due - today

    @Test
    fun cardsOfUnreadPagesAreKept() {
        val s = Cards.State(3, 10)
        val states = mapOf("p1:1:2:3:4" to s, "p2:1:2:3:4" to s, "p3:5:6:7:8" to s)
        // p1's card is still there, p2 could not be read, p3's band was erased.
        assertEquals(setOf("p1:1:2:3:4", "p2:1:2:3:4"), Cards.kept(states, setOf("p1:1:2:3:4"), setOf("p2")).keys)
    }

    @Test
    fun aLongRunIsReadBack() {
        var s: Cards.State? = Cards.State(Cards.MAX_BOX, 10)
        s = Cards.grade(s, Cards.Grade.GOOD, 20)
        assertEquals(Cards.MAX_BOX, s.box)
        assertEquals(s.box, Cards.decode(Cards.encode(mapOf("k" to s)))["k"]?.box)
    }

    @Test
    fun newCardIsDue() = assertTrue(Cards.isDue(null, 100))

    @Test
    fun firstAnswerSetsTheFirstInterval() {
        // FSRS-6 initial stabilities: Hard 1.29, Good 2.31, Easy 8.30 days.
        assertEquals(0L, days(Cards.grade(null, Cards.Grade.AGAIN, 100), 100))
        assertEquals(1L, days(Cards.grade(null, Cards.Grade.HARD, 100), 100))
        assertEquals(2L, days(Cards.grade(null, Cards.Grade.GOOD, 100), 100))
        assertEquals(8L, days(Cards.grade(null, Cards.Grade.EASY, 100), 100))
    }

    @Test
    fun rememberedCardsComeBackLaterAndLater() {
        var today = 100L
        var s = Cards.grade(null, Cards.Grade.GOOD, today)
        assertFalse(Cards.isDue(s, today))
        var last = days(s, today)
        repeat(6) {
            today = s.due
            assertTrue(Cards.isDue(s, today))
            s = Cards.grade(s, Cards.Grade.GOOD, today)
            val next = days(s, today)
            assertTrue("$next after $last", next > last)
            last = next
        }
        assertEquals(7, s.box)
        // 2, 12, 50, 174, 526, 1412, 3431 days: an easy card always remembered.
        assertTrue("years after 7 answers: $last", last in 365..36500)
    }

    @Test
    fun forgottenCardStartsOverToday() {
        val learned = Cards.grade(Cards.grade(null, Cards.Grade.GOOD, 100), Cards.Grade.GOOD, 102)
        val s = Cards.grade(learned, Cards.Grade.AGAIN, learned.due)
        assertEquals(0, s.box)
        assertTrue(Cards.isDue(s, learned.due))
        assertTrue(s.stability < learned.stability)
        assertTrue(s.difficulty > learned.difficulty)
    }

    @Test
    fun harderGradesGiveShorterIntervals() {
        val s = Cards.grade(Cards.grade(null, Cards.Grade.GOOD, 100), Cards.Grade.GOOD, 102)
        val today = s.due
        val hard = days(Cards.grade(s, Cards.Grade.HARD, today), today)
        val good = days(Cards.grade(s, Cards.Grade.GOOD, today), today)
        val easy = days(Cards.grade(s, Cards.Grade.EASY, today), today)
        assertTrue("$hard $good $easy", hard < good && good < easy)
    }

    @Test
    fun reviewedLateCountsTheTimeThatPassed() {
        val s = Cards.grade(Cards.grade(null, Cards.Grade.GOOD, 100), Cards.Grade.GOOD, 102)
        val onTime = Cards.grade(s, Cards.Grade.GOOD, s.due)
        val late = Cards.grade(s, Cards.Grade.GOOD, s.due + 20)
        // Remembered after a longer gap: the memory is stronger than thought.
        assertTrue(late.stability > onTime.stability)
    }

    @Test
    fun statesFromBeforeFsrsKeepTheirProgress() {
        // Box 5 was a 60-day interval: the card is not learned anew.
        val s = Cards.grade(Cards.State(5, 1000), Cards.Grade.GOOD, 1000)
        assertTrue(days(s, 1000) > 60)
        assertEquals(6, s.box)
    }

    @Test
    fun stateRoundTrip() {
        val fsrs = Cards.grade(null, Cards.Grade.GOOD, 20000)
        val states = mapOf("a:1:2:3:4" to Cards.State(2, 20000), "b:0:0:1:1" to Cards.State(0, 19999), "c:1:1:1:1" to fsrs)
        val back = Cards.decode(Cards.encode(states))
        assertEquals(states.keys, back.keys)
        assertEquals(states["a:1:2:3:4"], back["a:1:2:3:4"])
        assertEquals(fsrs.due, back.getValue("c:1:1:1:1").due)
        assertEquals(fsrs.stability, back.getValue("c:1:1:1:1").stability, 1e-3)
        assertEquals(fsrs.difficulty, back.getValue("c:1:1:1:1").difficulty, 1e-3)
        assertEquals(emptyMap<String, Cards.State>(), Cards.decode("garbage\nx\t1\ny\t1\t2\tNaN\t1\t1"))
    }

    @Test
    fun intervalsReadable() {
        assertEquals("через 2 дн.", Cards.interval(null, Cards.Grade.GOOD, 0))
        assertEquals("сегодня", Cards.interval(null, Cards.Grade.AGAIN, 0))
        assertTrue(Cards.interval(Cards.State(5, 0), Cards.Grade.GOOD, 0).endsWith("мес."))
    }
}
