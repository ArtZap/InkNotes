package dev.inknotes.data

import dev.inknotes.data.PdfGeometry.Margins
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfGeometryTest {
    private fun near(e: Float, a: Float) = assertEquals(e, a, 1e-3f)

    @Test
    fun turnedPageSwapsSides() {
        assertEquals(842f to 595f, PdfGeometry.turned(595f, 842f, 90))
        assertEquals(595f to 842f, PdfGeometry.turned(595f, 842f, -180))
        assertEquals(842f to 595f, PdfGeometry.outer(595f, 842f, 270, Margins.NONE))
    }

    @Test
    fun cornersLandOnTheTurnedPage() {
        // A quarter turn clockwise: the top left corner goes to the top right.
        val m = PdfGeometry.pdfToOuter(100f, 200f, 90, Margins.NONE)
        val (x, y) = PdfGeometry.map(m, 0f, 0f)
        near(200f, x); near(0f, y)
        val (x2, y2) = PdfGeometry.map(m, 100f, 200f)
        near(0f, x2); near(100f, y2)
        val r = PdfGeometry.map(PdfGeometry.pdfToOuter(100f, 200f, 270, Margins.NONE), 0f, 0f)
        near(0f, r.first); near(100f, r.second)
    }

    @Test
    fun marginsMoveThePdfInAndGrowThePage() {
        val m = Margins(left = 0.1f, top = 0.2f, right = 0.5f)
        val (w, h) = PdfGeometry.outer(100f, 200f, 0, m)
        near(160f, w); near(220f, h)
        val (x, y) = PdfGeometry.map(PdfGeometry.pdfToOuter(100f, 200f, 0, m), 0f, 0f)
        near(10f, x); near(20f, y)
        // In page units the page is 1000 wide.
        val (ux, _) = PdfGeometry.map(PdfGeometry.pdfToUnits(100f, 200f, 0, m, 1000f), 100f, 0f)
        near(1000f * 110f / 160f, ux)
    }

    @Test
    fun writingStaysOnItsPlaceOfThePdf() {
        val a = Margins.NONE
        val b = Margins(right = 0.5f)
        val move = PdfGeometry.moveContent(100f, 200f, 0, a, 90, b, 1000f)
        // The PDF's centre: in units of the old page, then of the new one.
        val (ox, oy) = PdfGeometry.map(PdfGeometry.pdfToUnits(100f, 200f, 0, a, 1000f), 50f, 100f)
        val (nx, ny) = PdfGeometry.map(move, ox, oy)
        val (ex, ey) = PdfGeometry.map(PdfGeometry.pdfToUnits(100f, 200f, 90, b, 1000f), 50f, 100f)
        near(ex, nx); near(ey, ny)
        near(90f, PdfGeometry.degreesOf(move))
    }

    @Test
    fun inverseUndoes() {
        val m = PdfGeometry.pdfToUnits(612f, 792f, 270, Margins(0.1f, 0.1f, 0.3f, 0.2f), 1000f)
        val id = PdfGeometry.then(m, PdfGeometry.invert(m))
        near(1f, id[0]); near(0f, id[1]); near(0f, id[2]); near(1f, id[3]); near(0f, id[4]); near(0f, id[5])
    }

    @Test
    fun turnUnitMatchesTheTurnOfPoints() {
        for (rot in listOf(0, 90, 180, 270)) {
            val m = PdfGeometry.pdfToOuter(100f, 200f, rot, Margins.NONE)
            val (w, h) = PdfGeometry.turned(100f, 200f, rot)
            val (x, y) = PdfGeometry.map(m, 30f, 50f)
            val (u, v) = PdfGeometry.turnUnit(0.3f, 0.25f, rot)
            near(x / w, u); near(y / h, v)
        }
    }

    @Test
    fun veryTallPagesAreNarrowedNotCut() {
        // A strip 20 times as tall as wide: the height would pass the side limit at full width.
        val (w, h) = PdfGeometry.fitPixels(2000, 20f, 16_000_000L, 8192)
        assertEquals(20f, h.toFloat() / w, 0.05f)
        assert(h <= 8192 && w.toLong() * h <= 16_000_000L)
        // An ordinary page keeps its width.
        assertEquals(1000 to 1414, PdfGeometry.fitPixels(1000, 1.4142f, 16_000_000L, 8192))
        // A huge page keeps its shape within the area.
        val (w2, h2) = PdfGeometry.fitPixels(10_000, 1.5f, 4_000_000L, 8192)
        assert(w2.toLong() * h2 <= 4_000_000L)
        assertEquals(1.5f, h2.toFloat() / w2, 0.01f)
    }
}
