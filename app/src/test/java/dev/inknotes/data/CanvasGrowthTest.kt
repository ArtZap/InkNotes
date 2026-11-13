package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasGrowthTest {
    @Test
    fun farFromTheEdgesItStays() {
        assertNull(CanvasGrowth.grow(420f, 297f, 300f, 200f))
        assertNull(CanvasGrowth.grow(420f, 297f, 420f - CanvasGrowth.EDGE, 297f - CanvasGrowth.EDGE))
    }

    @Test
    fun nearTheRightEdgeOnlyTheWidthGrows() {
        val (w, h) = CanvasGrowth.grow(420f, 297f, 400f, 100f)!!
        assertEquals(550f, w)
        assertEquals(297f, h)
    }

    @Test
    fun nearTheBottomOnlyTheHeightGrows() {
        val (w, h) = CanvasGrowth.grow(420f, 297f, 10f, 281.5f)!!
        assertEquals(420f, w)
        assertEquals(440f, h)
    }

    @Test
    fun itStopsAtTheLargestSize() {
        assertEquals(CanvasGrowth.MAX to 297f, CanvasGrowth.grow(1950f, 297f, 1990f, 10f))
        assertNull(CanvasGrowth.grow(CanvasGrowth.MAX, 297f, 2100f, 10f))
    }

    @Test
    fun cutBackToTheWritingButNotBelowTheStart() {
        assertEquals(CanvasGrowth.START_W to CanvasGrowth.START_H, CanvasGrowth.fit(100f, 50f))
        assertEquals(850f to 610f, CanvasGrowth.fit(805f, 563f))
    }

    @Test
    fun aCanvasIsSizedByItsOwnSides() {
        val c = PageSize.CANVAS
        assertEquals(true, c.isCanvas)
        assertEquals(CanvasGrowth.START_W, c.widthMm)
        assertEquals(CanvasGrowth.START_H, c.heightMm)
        assertEquals(false, PageSize.DEFAULT.isCanvas)
        assertEquals(210f, PageSize.DEFAULT.widthMm)
    }
}
