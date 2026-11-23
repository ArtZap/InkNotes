package dev.inknotes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimTest {
    private fun line(vararg p: Float) = Trim.Line(FloatArray(p.size / 2) { p[2 * it] }, FloatArray(p.size / 2) { p[2 * it + 1] })

    // A level line from 0 to 100 drawn as 10 segments, crossed by uprights at 30 and 70.
    private val target = line(*FloatArray(22) { if (it % 2 == 0) it / 2 * 10f else 50f })
    private val at30 = line(30f, 0f, 30f, 100f)
    private val at70 = line(70f, 0f, 70f, 100f)

    @Test
    fun theMiddleBetweenTwoCrossingsGoes() {
        val keep = Trim.keep(target, listOf(at30, at70), 50f, 51f, reach = 5f, touch = 2f)!!
        assertEquals(2, keep.size)
        assertEquals(3f, keep[0].second, 1e-4f) // up to x = 30
        assertEquals(7f, keep[1].first, 1e-4f) // from x = 70
    }

    @Test
    fun aFreeEndGoesUpToTheCrossing() {
        val keep = Trim.keep(target, listOf(at30, at70), 90f, 50f, reach = 5f, touch = 2f)!!
        assertEquals(1, keep.size)
        assertEquals(0f, keep[0].first, 1e-4f)
        assertEquals(7f, keep[0].second, 1e-4f)
    }

    @Test
    fun aLineThatMeetsNothingGoesWhole() {
        assertTrue(Trim.keep(target, emptyList(), 50f, 50f, reach = 5f, touch = 2f)!!.isEmpty())
    }

    @Test
    fun anUprightStoppingShortOfTheLineMeetsIt() {
        // A T drawn by hand: the upright ends 1.5 above the line.
        val t = line(40f, 0f, 40f, 48.5f)
        val keep = Trim.keep(target, listOf(t), 80f, 50f, reach = 5f, touch = 2f)!!
        assertEquals(listOf(0f to 4f), keep.map { (a, b) -> Math.round(a * 1000) / 1000f to Math.round(b * 1000) / 1000f })
    }

    @Test
    fun aTouchFarFromTheLineTrimsNothing() = assertNull(Trim.keep(target, listOf(at30), 50f, 70f, reach = 5f, touch = 2f))
}
