package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypesetTest {
    private val measure = Typeset.Measure { text, size, _ -> text.length * size * 0.55f }

    @Test
    fun latexOfHandwritingForms() {
        assertEquals("\\frac{a+1}{2}", Typeset.latex("\\frac{a+1}{2}"))
        assertEquals("x^{2}+y^{2}", Typeset.latex("x^{2}+y^{2}"))
        assertEquals("\\sqrt{x-1}", Typeset.latex("\\sqrt{x-1}"))
        assertEquals("2 \\cdot 3", Typeset.latex("2*3"))
        assertEquals("\\sin x", Typeset.latex("sinx"))
        assertEquals("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}", Typeset.latex("\\mat{1&2\\\\3&4}"))
        assertEquals("\\begin{vmatrix} a & b \\\\ c & d \\end{vmatrix}", Typeset.latex("\\det{\\mat{a&b\\\\c&d}}"))
        assertEquals("\\int_{0}^{1} xdx", Typeset.latex("\\int_{0}^{1} xdx"))
        assertEquals("\\left( a+b \\right)^{2}", Typeset.latex("(a+b)^{2}"))
        assertEquals("30^{\\circ}", Typeset.latex("30°"))
        assertEquals("\\pi r^{2}", Typeset.latex("πr^{2}"))
    }

    @Test
    fun latexReadsLatex() {
        // What the latex function writes, read again, gives the same.
        for (src in listOf("\\frac{\\alpha}{2} \\le \\sqrt[3]{x}", "\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}", "\\sum_{k=1}^{n} k^{2}")) {
            val once = Typeset.latex(src)
            assertEquals(once, Typeset.latex(once))
        }
        assertEquals("\\frac{\\alpha}{2} \\le \\sqrt[3]{x}", Typeset.latex("\\frac{\\alpha}{2} \\le \\sqrt[3]{x}"))
    }

    @Test
    fun fractionStacksAndIsCentredOnTheAxis() {
        val l = Typeset.layout("\\frac{1}{x+1}", 20f, measure)
        val texts = l.ops.filterIsInstance<Typeset.Op.Text>()
        val one = texts.first { it.text == "1" }
        val x = texts.first { it.text == "x" }
        assertTrue("numerator above the denominator", one.y < x.y)
        val bar = l.ops.filterIsInstance<Typeset.Op.Line>().single()
        assertEquals(-Typeset.AXIS * 20f, bar.y1, 0.01f)
        assertTrue(one.y < bar.y1 && x.y - x.size * Typeset.ASCENT > bar.y1)
    }

    @Test
    fun exponentRaisedAndSmaller() {
        val l = Typeset.layout("x^{2}", 20f, measure)
        val texts = l.ops.filterIsInstance<Typeset.Op.Text>()
        val two = texts.first { it.text == "2" }
        val x = texts.first { it.text == "x" }
        assertTrue(two.y < x.y)
        assertTrue(two.size < x.size)
        assertTrue(two.x > x.x)
    }

    @Test
    fun bracketsAsTallAsWhatTheyHold() {
        val low = Typeset.layout("(x)", 20f, measure)
        val tall = Typeset.layout("(\\frac{1}{2})", 20f, measure)
        fun bracketHeight(l: Typeset.Laid): Float {
            val p = l.ops.filterIsInstance<Typeset.Op.Poly>().first().points
            val ys = p.filterIndexed { k, _ -> k % 2 == 1 }
            return ys.max() - ys.min()
        }
        assertTrue(bracketHeight(tall) > bracketHeight(low) * 1.4f)
    }

    @Test
    fun matrixCellsInGrid() {
        val l = Typeset.layout("\\mat{1&2\\\\3&4}", 20f, measure)
        val t = l.ops.filterIsInstance<Typeset.Op.Text>().associateBy { it.text }
        assertEquals(t.getValue("1").y, t.getValue("2").y, 0.01f)
        assertTrue(t.getValue("3").y > t.getValue("1").y)
        assertTrue(t.getValue("2").x > t.getValue("1").x)
    }

    @Test
    fun nothingUnderstoodNeverThrows() {
        for (src in listOf("", "}", "\\frac{", "x^", "((", "\\mat{", "\\begin{pmatrix} 1 &", "\\unknown x", "|x|")) {
            Typeset.layout(src, 20f, measure)
            Typeset.latex(src)
        }
    }
}
