package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {
    private fun eval(text: String, env: Env = Env()): String {
        Parser.units = true
        try {
            val a = Calc.evaluate(Parser.parse(text), env) as Answer.Text
            return a.lines.joinToString("\n")
        } finally {
            Parser.units = false
        }
    }

    private fun error(text: String): String = try {
        eval(text)
        "no error"
    } catch (e: MathError) {
        e.message ?: ""
    }

    @Test
    fun speedTimesTime() = assertEquals("29,4 м/с", eval("9,8m/s^{2}*3s"))

    @Test
    fun cyrillicLookalikes() {
        // м/с² written in Cyrillic and read as Latin m/c.
        assertEquals("29,4 м/с", eval("9,8m/c^{2}*3c"))
        assertEquals("20 Н", eval("2kr*10m/c^{2}"))
    }

    @Test
    fun keepsWrittenUnits() {
        assertEquals("120 км", eval("60km/h*2h"))
        assertEquals("30 км/ч", eval("60km/(2h)"))
    }

    @Test
    fun namedUnit() {
        assertEquals("100 Дж", eval("10N*10m"))
        assertEquals("50 Вт", eval("100J/2s"))
    }

    @Test
    fun addingMetresToSecondsIsAnError() = assertTrue(error("2m+3s").startsWith("нельзя сложить м и с"))

    @Test
    fun constantG() = assertEquals("19,6133 Н", eval("2kg*g"))

    @Test
    fun lettersAfterSignsStayVariables() {
        // 2·m is twice m, not two metres: with m = 3 it is 6.
        val env = Env()
        Parser.units = true
        try {
            Calc.define(Expr.Sym("m"), Expr.Num(3.0), env)
        } finally {
            Parser.units = false
        }
        assertEquals("6", eval("2*m", env))
    }

    @Test
    fun variableWithUnits() {
        val env = Env()
        Parser.units = true
        try {
            Calc.define(Expr.Sym("v"), Parser.parse("20m/s"), env)
        } finally {
            Parser.units = false
        }
        assertEquals("60 м", eval("v*3s", env))
    }

    @Test
    fun rootOfArea() = assertEquals("3 м", eval("\\sqrt{9m^{2}}"))

    @Test
    fun offByDefault() {
        // Without the setting 2m is 2·m as before.
        val e = Parser.parse("2m")
        assertEquals(Expr.Mul(listOf(Expr.Num(2.0), Expr.Sym("m"))), e)
    }

    @Test
    fun unitsInAFraction() {
        // 9,8 м/с² written as a fraction after the number.
        assertEquals("29,4 м/с", eval("9,8\\frac{m}{c^{2}}*3c"))
        assertEquals("29,4 м/с", eval("9,8\\frac{u}{c^{2}}*3c"))
    }

    @Test
    fun kilometresPerHourAsRead() {
        // 848 км : 3 ч, the reader taking к for K and ч for r.
        assertEquals("282,667 км/ч", eval("848Km:3r"))
    }

    @Test
    fun lettersStayLettersInEquations() {
        // 3y is three times y even with units on.
        Parser.units = true
        try {
            assertEquals(Expr.Mul(listOf(Expr.Num(3.0), Expr.Sym("y"))), Parser.parse("3y"))
            assertEquals(Expr.Mul(listOf(Expr.Num(2.0), Expr.Sym("t"))), Parser.parse("2t"))
        } finally {
            Parser.units = false
        }
    }
}
