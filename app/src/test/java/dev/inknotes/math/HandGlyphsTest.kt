package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HandGlyphsTest {
    /** A symbol written in a box 100 tall: guides at 50 (top) and 150 (base). */
    private fun written(width: Float, low: Float = 150f) = HandGlyphs.glyph(listOf(floatArrayOf(200f, 50f, 200f + width, low)), 50f, 150f)!!

    private fun hand(chars: String) = HandGlyphs().apply { for (c in chars) add(c, written(50f)) }

    @Test
    fun aGlyphIsMeasuredInTheWritingHeight() {
        val g = written(60f)
        assertEquals(0.6f, g.width, 1e-4f)
        assertEquals(0f, g.strokes[0][0], 1e-4f)
        assertEquals(-1f, g.strokes[0][1], 1e-4f)
        assertEquals(0f, g.strokes[0][3], 1e-4f)
    }

    @Test
    fun aLineIsLaidOutOnItsBaseLine() {
        val h = hand("12−")
        val line = h.layout("−12", 10f, 300f, 20f)!!
        assertEquals(3, line.glyphs.size)
        // Each glyph 0.5 H wide, 0.14 H between: 3·10 + 2·2.8.
        assertEquals(35.6f, line.width, 1e-3f)
        val first = line.glyphs[0].strokes[0]
        assertEquals(10f, first[0], 1e-3f)
        assertEquals(280f, first[1], 1e-3f)
        assertEquals(300f, first[3], 1e-3f)
    }

    @Test
    fun aMissingSymbolMeansTheFont() {
        val h = hand("12")
        assertNull(h.layout("1,2", 0f, 0f, 10f))
        assertTrue(!h.covers(""))
        assertTrue(h.covers("12 21"))
    }

    @Test
    fun raisedDigitsAndHyphensUseThePlainOnes() {
        val h = hand("2x−")
        val line = h.layout("x² -2", 0f, 100f, 10f)!!
        assertEquals(4, line.glyphs.size)
        // ² is smaller and above the line.
        val sq = line.glyphs[1].strokes[0]
        assertTrue(sq[3] < 100f - 5f)
        assertTrue(abs((sq[3] - sq[1]) - 6.2f) < 1e-3f)
    }

    @Test
    fun variantsAreTakenInTurnAndKept() {
        val h = HandGlyphs()
        h.add('1', written(30f))
        h.add('1', written(50f))
        val line = h.layout("11", 0f, 0f, 10f)!!
        val w0 = line.glyphs[0].strokes[0][2] - line.glyphs[0].strokes[0][0]
        val w1 = line.glyphs[1].strokes[0][2] - line.glyphs[1].strokes[0][0]
        assertEquals(3f, w0, 1e-3f)
        assertEquals(5f, w1, 1e-3f)
        val back = HandGlyphs.parse(h.serialize())
        assertEquals(1, back.count())
        assertEquals(line.width, back.layout("11", 0f, 0f, 10f)!!.width, 1e-2f)
        repeat(5) { h.add('1', written(40f)) }
        assertEquals(HandGlyphs.VARIANTS, HandGlyphs.parse(h.serialize()).serialize().lines().count { it.isNotEmpty() })
    }
}
