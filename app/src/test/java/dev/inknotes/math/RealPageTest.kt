package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Layout of formulas written by hand on the tablet (strokes saved from a real page): the
 * structure found for each "=", with # for a run left to the handwriting reader.
 */
class RealPageTest {
    private val ink = RealPage.load("page1.json")
    private val ink2 = RealPage.load("page2.json")
    private val ink6 = RealPage.load("page6.json")

    private fun template(x: Float, y: Float, ink: List<Ink> = this.ink, limit: Float? = null): String {
        val eqs = MathPage.findEquals(ink)
        val eqStrokes = eqs.flatMap { listOf(it.top, it.bottom) + it.extra }.toSet()
        val rest = ink.filter { it !in eqStrokes }
        val m = MathPage::class.java.getDeclaredMethod("lineHeight", MathPage.Equals::class.java, List::class.java).apply { isAccessible = true }
        for (e in eqs) e.h = m.invoke(MathPage, e, rest) as Float
        val e = eqs.minBy { Math.abs(it.left - x) + Math.abs(it.cy - y) }
        val side = MathPage.side(e, -1, eqs, rest, limit)
        return MathLayout.formula(side, e.h, e.cy).joinToString("") { if (it is Piece.Text) it.s else "#" }
    }

    /** The same for the right side of the "=" nearest to ([x], [y]). */
    private fun right(x: Float, y: Float, ink: List<Ink>): String {
        val eqs = MathPage.findEquals(ink)
        val eqStrokes = eqs.flatMap { listOf(it.top, it.bottom) + it.extra }.toSet()
        val rest = ink.filter { it !in eqStrokes }
        val m = MathPage::class.java.getDeclaredMethod("lineHeight", MathPage.Equals::class.java, List::class.java).apply { isAccessible = true }
        for (e in eqs) e.h = m.invoke(MathPage, e, rest) as Float
        val e = eqs.minBy { Math.abs(it.left - x) + Math.abs(it.cy - y) }
        return MathLayout.formula(MathPage.side(e, 1, eqs, rest, null), e.h, e.cy).joinToString("") { if (it is Piece.Text) it.s else "#" }
    }

    @Test
    fun equalsSignsFound() {
        // 28 signs, one of them traced twice.
        assertEquals(28, MathPage.findEquals(ink).size)
    }

    @Test
    fun crossedSevenInExponent() {
        assertEquals("#^{#}", template(816f, 61f)) // 2⁷
        assertEquals("#^{#}", template(630f, 105f)) // 7⁷
    }

    @Test
    fun plusWithAPenDot() = assertEquals("#+#", template(669f, 477f))

    @Test
    fun derivativeOfPower() = assertEquals("(#^{#})'", template(238f, 401f))

    @Test
    fun matrices() {
        assertEquals("\\mat{#&#\\\\#&#}^{-#}", template(655f, 408f))
        assertEquals("\\mat{#&#\\\\#&#}^{#}", template(854f, 410f))
        assertEquals("\\det{\\mat{#&#\\\\#&#}}", template(351f, 562f))
    }

    @Test
    fun integrals() {
        assertEquals("\\int_{#}^{#} #", template(623f, 558f))
        assertEquals("\\int #", template(641f, 656f))
    }

    @Test
    fun simpleLines() {
        assertEquals("#+#", template(166f, 39f))
        assertEquals("\\frac{#}{#}+#^{#}", template(171f, 187f))
    }

    @Test
    fun secondVisit() {
        assertEquals("#(#)", template(233f, 655f, ink2)) // sin(60)
        assertEquals("#+#", template(398f, 698f, ink2)) // V + V
        assertEquals("#+#", template(401f, 746f, ink2)) // V + r, + started from the V
        assertEquals("\\int #", template(641f, 656f, ink2))
    }

    /** A system whose y's have long tails reaching into the next line (Math_test, page 6). */
    @Test
    fun systemWithLongTailedY() {
        // The brace hooks reach x ≈ 225, over the start of the x written after it.
        assertEquals("#+#", template(402f, 470f, ink6, limit = 225f)) // x + y
        assertEquals("#^{#}-#^{#}+#-#", template(721f, 563f, ink6, limit = 225f)) // x⁶ − y⁵ + 3x − 2x
        assertEquals("#^{#}", template(265f, 726f, ink6)) // x³
    }

    /** A long, flat "=" with its lower line slanting (x³ = 6x² + 12x + 11, written big). */
    @Test
    fun longFlatEquals() {
        val ink7 = RealPage.load("page7.json")
        assertEquals(4, MathPage.findEquals(ink7).size)
        assertEquals("#^{#}", template(284f, 180f, ink7))
        assertEquals("#^{#}+#+#", right(284f, 180f, ink7)) // 6x² + 12x + 11, going downhill
    }

    /** Functions to graph, written on the tablet: the top bar of a "5" and a "−" in a power. */
    @Test
    fun graphFormulas() {
        val ink8 = RealPage.load("page8.json")
        assertEquals("\\frac{#^{#}-#}{#}", right(165f, 539f, ink8)) // f(x) = (5x³ − x)/2
        assertEquals("\\frac{#^{#}-#}{#}", right(134f, 656f, ink8)) // y = (5x³ − x)/2, the 5 in two strokes
        assertEquals("#^{-#}", right(134f, 775f, ink8)) // y = 3x⁻¹
    }
}
