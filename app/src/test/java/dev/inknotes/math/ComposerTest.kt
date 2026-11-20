package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The formula keyboard: keys, templates with empty places, the cursor, the text out. */
class ComposerTest {

    @Test
    fun keysAndTemplates() {
        val c = Composer.empty()
        c.type("2")
        c.type("+")
        c.template("\\frac{#0}{#1}")
        assertEquals("2+\\frac{□}{□}", c.text())
        c.type("1")
        c.right() // to the denominator
        c.type("3")
        c.right() // out of the fraction
        c.type("=")
        assertEquals("2+\\frac{1}{3}=", c.text())
        assertTrue(!c.hasEmpty)
    }

    @Test
    fun anIntegralWithItsLimits() {
        val c = Composer.empty()
        c.template("\\int_{#0}^{#1}#2 d#3")
        c.type("0")
        c.right()
        c.type("1")
        c.right()
        c.type("x")
        c.template("^{#0}")
        c.type("2")
        c.right()
        c.right()
        c.type("x")
        assertEquals("\\int_{0}^{1}x^{2} dx", c.text())
        val v = Calc.evaluate(MathPage.parseText(c.text())!!, Env()) as Answer.Text
        assertTrue(v.lines.toString(), v.lines.any { it.startsWith("0,333") || it == "1/3" })
    }

    @Test
    fun eraseGoesIntoAndOutOfTemplates() {
        val c = Composer.of("x^{2}+1")
        c.erase()
        c.erase()
        assertEquals("x^{2}", c.text())
        c.erase() // into the exponent
        c.erase() // its 2
        assertEquals("x^{□}", c.text())
        c.erase() // out of it, and the empty exponent goes
        assertEquals("x", c.text())
    }

    @Test
    fun readsTextBack() {
        for (t in listOf("\\frac{1}{2}+\\sqrt{x}", "\\sqrt[3]{8}", "x_{1}^{2}", "\\int x dx", "2\\pi r")) {
            assertEquals(t, Composer.of(t).text())
        }
    }

    @Test
    fun nextEmptyPlace() {
        val c = Composer.empty()
        c.template("\\frac{#0}{#1}")
        c.nextEmpty()
        c.type("4")
        assertEquals("\\frac{□}{4}", c.text())
        c.nextEmpty()
        c.type("8")
        assertEquals("\\frac{8}{4}", c.text())
    }

    @Test
    fun theCursorIsShown() {
        val c = Composer.empty()
        c.type("x")
        c.template("^{#0}")
        assertEquals("x^{▏}", c.shown())
        c.right()
        assertEquals("x^{□}▏", c.shown())
    }
}

/** Every key of the formula keyboard gives text that the app reads or says it cannot compute. */
class FormulaKeysTest {
    @Test
    fun everyKeyReadsOrIsMarked() {
        val unread = ArrayList<String>()
        for (upper in listOf(false, true)) for ((tab, keys) in FormulaKeys.tabs(upper)) for (k in keys) {
            if (k.face == "⇧") continue
            val c = Composer.empty()
            c.type("2")
            k.press(c)
            // Every empty place filled with x.
            while (c.hasEmpty) {
                c.nextEmpty()
                c.type("x")
            }
            if (k.pattern == null) c.type("x")
            val text = c.text()
            if (MathPage.parseText(text) == null && !Correct.cannotCompute(text)) unread += "$tab ${k.face}: $text"
        }
        // Signs that only make sense between things (=, <) may not read alone; the rest must.
        // An index after a number (2_x), a comma or semicolon alone: only in context.
        val allowed = setOf("<", ">", "≤", "≥", "→", "=", "!", "'", ",", ";", "%", "d", "°", "Δ", "e", "□ₙ", "□ₙᵐ", "□,", "□;")
        val bad = unread.filter { line -> allowed.none { a -> line.contains(" $a:") || line.contains(" $a ") } }
        println("keys that neither read nor are marked: ${unread.size}")
        unread.forEach { println("  $it") }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun computedTemplates() {
        fun value(text: String) = (Calc.evaluate(MathPage.parseText(text)!!, Env()) as Answer.Text).lines.single()
        assertEquals("4", value("\\sqrt[3]{64}"))
        assertEquals("3", value("log_{2}(8)"))
        assertEquals("-2", value("\\det{\\mat{1&2\\\\3&4}}").replace("−", "-"))
    }

    @Test
    fun honestNotes() {
        assertTrue(Correct.unreadable("\\lim_{x\\to 0}x").note!!.isNotEmpty())
        assertTrue(Correct.cannotCompute("\\sum_{k}^{n}k"))
        assertTrue(!Correct.cannotCompute("\\frac{1}{2}"))
        assertTrue(Correct.unreadable("\\frac{□}{2}").note!!.contains("□"))
    }
}

class ComposerEraseTest {
    @Test
    fun erasingARootOfAnyDegreeGoesThroughBothPlacesThenTheRoot() {
        val c = Composer.empty()
        c.type("2")
        c.type("+")
        c.template("\\sqrt[#0]{#1}")
        c.type("3")
        c.right()
        c.type("2")
        c.type("7")
        c.right()
        assertEquals("2+\\sqrt[3]{27}", c.text())
        c.erase() // into the radicand
        c.erase()
        c.erase()
        assertEquals("2+\\sqrt[3]{□}", c.text())
        c.erase() // on to the degree
        c.erase()
        assertEquals("2+\\sqrt[□]{□}", c.text())
        c.erase() // the root itself
        assertEquals("2+", c.text())
        c.erase()
        assertEquals("2", c.text())
    }

    @Test
    fun aFractionToo() {
        val c = Composer.of("\\frac{1}{2}")
        c.erase()
        c.erase()
        c.erase()
        c.erase()
        assertEquals("\\frac{□}{□}", c.text())
        c.erase()
        assertEquals("", c.text())
    }

    @Test
    fun anIntegralWithALetterForALimitSaysWhich() {
        val e = runCatching { Calc.evaluate(MathPage.parseText("\\int_{o}^{1}x dx")!!, Env()) }.exceptionOrNull()
        assertTrue(e?.message.toString(), e?.message?.contains("o") == true)
    }
}
