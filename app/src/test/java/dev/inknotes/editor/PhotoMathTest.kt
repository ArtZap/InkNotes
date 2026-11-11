package dev.inknotes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMathTest {

    @Test
    fun noTiltKeepsTheRectangle() {
        val c = PhotoMath.tiltCorners(400f, 300f, 0f, 0f)
        val want = floatArrayOf(0f, 0f, 400f, 0f, 400f, 300f, 0f, 300f)
        for (i in 0 until 8) assertEquals(want[i], c[i], 0.01f)
    }

    @Test
    fun topAwayMakesTheTopNarrower() {
        val c = PhotoMath.tiltCorners(400f, 300f, 30f, 0f)
        val top = c[2] - c[0]
        val bottom = c[4] - c[6]
        assertTrue("top $top bottom $bottom", top < bottom)
        // Seen at a slant the picture is lower than it was.
        assertTrue(PhotoMath.boundsSize(c).second < 300f)
    }

    @Test
    fun rightAwayMakesTheRightEdgeShorter() {
        val c = PhotoMath.tiltCorners(400f, 300f, 0f, 30f)
        val left = c[7] - c[1]
        val right = c[5] - c[3]
        assertTrue("left $left right $right", right < left)
    }

    @Test
    fun straightSizeIsTheAverageOfOppositeEdges() {
        val (w, h) = PhotoMath.straightSize(floatArrayOf(10f, 10f, 110f, 20f, 100f, 220f, 0f, 200f))
        assertEquals(100, w, 2)
        assertEquals(195, h, 3)
    }

    private fun assertEquals(want: Int, got: Int, slack: Int) = assertTrue("$got not near $want", kotlin.math.abs(want - got) <= slack)

    @Test
    fun convexOutlineOnly() {
        assertTrue(PhotoMath.isConvex(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)))
        // Two corners swapped: the outline crosses itself.
        assertFalse(PhotoMath.isConvex(floatArrayOf(0f, 0f, 10f, 10f, 10f, 0f, 0f, 10f)))
    }

    @Test
    fun backgroundJoinedToTheEdgeGoesTheDrawingStays() {
        val w = 20
        val h = 20
        val white = 0xFFF8F8F8.toInt()
        val ink = 0xFF101010.toInt()
        val px = IntArray(w * h) { white }
        // A dark ring with white inside: the inside is not joined to the edge and stays.
        for (y in 5..14) for (x in 5..14) if (x == 5 || x == 14 || y == 5 || y == 14) px[y * w + x] = ink
        val out = PhotoMath.clearBackground(px, w, h, 0.15f)
        assertEquals(0, out[0] ushr 24)
        assertEquals(0, out[3 * w + 3] ushr 24)
        assertEquals(255, out[5 * w + 5] ushr 24)
        assertEquals(255, out[10 * w + 10] ushr 24)
    }

    @Test
    fun documentPaperWhiteInkDark() {
        val paper = 0xFFB0A898.toInt()
        val ink = 0xFF403A30.toInt()
        val px = IntArray(1000) { if (it % 10 == 0) ink else paper }
        val out = PhotoMath.documentLevels(px)
        assertEquals(255, out[1] and 0xFF)
        assertTrue((out[0] and 0xFF) < 40)
        // Grey: the three channels equal.
        assertEquals(out[0] and 0xFF, (out[0] shr 16) and 0xFF)
    }
}
