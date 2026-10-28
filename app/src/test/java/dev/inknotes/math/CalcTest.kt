package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTest {
    private fun text(a: Answer) = (a as Answer.Text).lines.joinToString("\n")
    private fun eval(s: String, env: Env = Env()) = text(Calc.evaluate(Parser.parse(s), env)!!)
    private fun define(l: String, r: String, env: Env) = assertTrue(Calc.define(Parser.parse(l), Parser.parse(r), env))
    private fun solve(env: Env = Env(), vararg eqs: Pair<String, String>) =
        text(Calc.solve(eqs.map { Parser.parse(it.first) to Parser.parse(it.second) }, env))

    @Test
    fun arithmetic() {
        assertEquals("14", eval("2+3*4"))
        assertEquals("14", eval("2+3·4"))
        assertEquals("20", eval("(2+3)4"))
        assertEquals("2,5", eval("5:2"))
        assertEquals("1/3", eval("1/3"))
        assertEquals("−7", eval("3-10"))
        assertEquals("120", eval("5!"))
        assertEquals("1024", eval("2^{10}"))
        assertEquals("512", eval("2^3^2"))
        assertEquals("3", eval("\\sqrt{9}"))
        assertEquals("1,5", eval("\\frac{3}{2}"))
        assertEquals("1,75", eval("\\frac{1}{2}+\\frac{5}{4}"))
        assertEquals("5", eval("|2-7|"))
        assertEquals("15", eval("30%·50"))
        assertEquals("−2", eval("(-8)^{\\frac{1}{3}}"))
        assertEquals("1,23·10⁻⁵", eval("0.0000123"))
        assertEquals("2,5", eval("2,5"))
    }

    @Test
    fun functions() {
        assertEquals("1", eval("sin(\\frac{\\pi}{2})".replace("\\pi", "π")))
        assertEquals("0,5", eval("sin 30^{o}"))
        assertEquals("0,5", eval("cos 60°"))
        assertEquals("1", eval("tg 45°"))
        assertEquals("3", eval("log_{2}8"))
        assertEquals("2", eval("lg100"))
        assertEquals("1", eval("ln e"))
        assertEquals("3", eval("ln(e^{3})"))
        assertEquals("1", eval("sin^{2}x+cos^{2}x".replace("x", "1.7")))
        assertEquals("4", eval("log_{3}81"))
    }

    @Test
    fun variablesAndFunctions() {
        val env = Env()
        define("a", "5", env)
        define("b", "a+1", env)
        assertEquals("36", eval("ab+a+1", env))
        define("f(x)", "x^{2}+1", env)
        assertEquals("10", eval("f(3)", env))
        assertEquals("6", eval("f'(3)", env))
        assertEquals("26", eval("f(a)", env))
        // A variable followed by brackets is a product.
        assertEquals("15", eval("a(2+1)", env))
    }

    @Test
    fun symbolic() {
        assertEquals("5x", eval("2x+3x"))
        assertEquals("3x²", eval("(x^{3})'"))
        assertEquals("3x² + 2", eval("\\frac{d}{dx}(x^{3}+2x)"))
        assertEquals("6x", eval("\\frac{d^{2}}{dx^{2}}x^{3}"))
        assertEquals("cos(x)", eval("(sin x)'"))
        assertEquals("1/x", eval("(ln x)'"))
        assertEquals("2x·cos(x²)", eval("(sin(x^{2}))'"))
        assertEquals("x³/3 + C", eval("\\int x^{2}dx"))
        assertEquals("−cos(x) + C", eval("\\int sin x dx"))
        assertEquals("ln|x| + C", eval("\\int \\frac{1}{x}dx"))
        assertEquals("xeˣ − eˣ + C", eval("\\int x e^{x} dx"))
        assertEquals("(x + 1)³/3 + C", eval("\\int (x+1)^{2} dx"))
        assertEquals("x³/3 + x² + x + C", eval("\\int (x^{2}+2x+1) dx"))
        assertEquals("arctg(x) + C", eval("\\int \\frac{1}{1+x^{2}} dx"))
    }

    @Test
    fun nothingToSay() {
        // Words before "=" in ordinary notes, or a formula that is already as simple as it gets.
        assertEquals(null, Calc.evaluate(Parser.parse("Utak"), Env()))
        assertEquals(null, Calc.evaluate(Parser.parse("x+1"), Env()))
        assertEquals("5x", eval("2x+3x"))
    }

    @Test
    fun derivativeAtDefinedPoint() {
        val env = Env()
        define("x", "2", env)
        assertEquals("12", eval("(x^{3})'", env))
    }

    @Test
    fun definiteIntegrals() {
        assertEquals("1/3", eval("\\int_{0}^{1}x^{2}dx"))
        assertEquals("2", eval("\\int_{0}^{π}sin x dx"))
        assertEquals("1", eval("\\int_{0}^{∞}e^{-x}dx"))
        assertEquals("1,77245", eval("\\int_{-∞}^{∞}e^{-x^{2}}dx"))
        assertEquals("2", eval("\\int_{0}^{1}\\frac{1}{\\sqrt{x}}dx"))
    }

    @Test
    fun matrices() {
        val env = Env()
        define("A", "\\mat{1&2\\\\3&4}", env)
        assertEquals("−2", eval("\\det{A}", env))
        assertEquals("−2", eval("|A|", env))
        val inv = Calc.evaluate(Parser.parse("A^{-1}"), env) as Answer.Grid
        assertEquals(listOf(listOf("−2", "1"), listOf("1,5", "−0,5")), inv.rows)
        val sq = Calc.evaluate(Parser.parse("A·A"), env) as Answer.Grid
        assertEquals(listOf(listOf("7", "10"), listOf("15", "22")), sq.rows)
        val t = Calc.evaluate(Parser.parse("A^{T}"), env) as Answer.Grid
        assertEquals(listOf(listOf("1", "3"), listOf("2", "4")), t.rows)
        val v = Calc.evaluate(Parser.parse("A\\mat{1\\\\1}"), env) as Answer.Grid
        assertEquals(listOf(listOf("3"), listOf("7")), v.rows)
    }

    @Test
    fun systems() {
        assertEquals("x = 2; y = 1", solve(Env(), "x+y" to "3", "x-y" to "1"))
        assertEquals("x = 3", solve(Env(), "2x+1" to "7"))
        assertEquals("x₁ = 2\nx₂ = 3", solve(Env(), "x^{2}-5x+6" to "0"))
        assertEquals("x = 1", solve(Env(), "(x-1)^{2}" to "0"))
        assertEquals("нет решений", solve(Env(), "x+y" to "1", "x+y" to "2"))
        assertEquals("∞ решений", solve(Env(), "x+y" to "1", "2x+2y" to "2"))
        assertEquals("нет решений", solve(Env(), "x^{2}" to "-1"))
        assertEquals("(x; y) =\n(−2; −1)\n(2; 1)", solve(Env(), "x^{2}+y^{2}" to "5", "x" to "2y"))
        val env = Env()
        define("a", "4", env)
        assertEquals("x = 2", solve(env, "ax" to "8"))
        assertEquals("x = 1; y = 2; z = 3", solve(Env(), "x+y+z" to "6", "x-y" to "-1", "2z" to "6"))
        // x defined above is still an unknown of the system that needs it.
        val withX = Env()
        define("x", "5", withX)
        assertEquals("x = 2; y = 1", solve(withX, "x+y" to "3", "x-y" to "1"))
    }

    @Test
    fun runawayDefinitionsStop() {
        val env = Env()
        define("f(x)", "x·x", env)
        val failed = runCatching {
            var prev = "f"
            for (k in 0 until 40) {
                val name = ('g' + k % 18).toString() + "_{$k}"
                Calc.define(Parser.parse("$name(x)"), Parser.parse("$prev(x)·$prev(x)"), env)
                prev = name
            }
        }.isFailure
        assertTrue(failed)
        assertTrue(runCatching { eval("\\int_{0}^{1}\\int_{0}^{1}xy dx dy") }.isFailure)
    }

    @Test
    fun errors() {
        for (bad in listOf("2+", "(1+2", "\\int x", "ln(-1)", "1/0", "\\frac{1}{0}")) {
            val failed = runCatching { eval(bad) }.isFailure
            assertTrue(bad, failed)
        }
    }

    @Test
    fun degrees() {
        assertEquals("75°", eval("30°+45°"))
        assertEquals("150°", eval("180-30°"))
        assertEquals("22,5°", eval("90°/4"))
        assertEquals("60°30′", eval("2·30°15'"))
        assertEquals("41°5′", eval("30°15'+10°50'"))
        assertEquals("1", eval("sin30°+cos60°"))
        assertEquals("0,5", eval("sin(30^{0})"))
        assertEquals("1", eval("2^{0}"))
        assertEquals("0,866025", eval("sin(90-30)"))
        assertEquals("π/6 рад", eval("30°"))
        assertEquals("π/6 = 30°", eval("arcsin(0,5)"))
        assertEquals("π/4 = 45°", eval("arctg1"))
        assertEquals("π/2 = 90°", eval("arcsin(0,5)+arccos(0,5)"))
        val env = Env()
        define("a", "30°", env)
        assertEquals("45°", eval("a+15", env))
        assertEquals("60°", eval("2a", env))
        assertEquals("0,5", eval("sin a", env))
        assertEquals("x = 60°", solve(env, "x+30°" to "90°"))
    }

    @Test
    fun trigonometricEquations() {
        assertEquals("x = π/6 + 2πk\nx = 5π/6 + 2πk, k ∈ ℤ", solve(Env(), "sin x" to "0,5"))
        assertEquals("x = ±π/3 + 2πk, k ∈ ℤ", solve(Env(), "cos x" to "\\frac{1}{2}"))
        assertEquals("x = π/4 + πk, k ∈ ℤ", solve(Env(), "tg x" to "1"))
        assertEquals("x = πk, k ∈ ℤ", solve(Env(), "sin x" to "0"))
        assertEquals("x = π/2 + 2πk, k ∈ ℤ", solve(Env(), "sin x" to "1"))
        assertEquals("нет решений", solve(Env(), "sin x" to "2"))
    }

    @Test
    fun exactRoots() {
        // Closed form, without the " ≈ value" after it.
        fun exact(env: Env, vararg eqs: Pair<String, String>) = solve(env, *eqs).lines().joinToString("\n") { it.substringBefore(" ≈") }
        assertEquals("x = 2 + ∛3", exact(Env(), "x^3" to "6x^2-12x+11"))
        assertEquals("x₁ = −√2\nx₂ = √2", exact(Env(), "x^2" to "2"))
        assertEquals("x₁ = 1 − √3\nx₂ = 1 + √3", exact(Env(), "x^2-2x-2" to "0"))
        assertEquals("x₁ = (−1 − √5)/2\nx₂ = (−1 + √5)/2", exact(Env(), "x^2+x-1" to "0"))
        // A rational root split off: (x − 1)(x² − 2).
        assertEquals("x₁ = −√2\nx₂ = 1\nx₃ = √2", exact(Env(), "x^3-x^2-2x+2" to "0"))
        // Rational roots stay as before.
        assertEquals("x₁ = 2\nx₂ = 3", solve(Env(), "x^2-5x+6" to "0"))
        assertEquals("x = −∛2", exact(Env(), "x^3+2" to "0"))
    }

    @Test
    fun inequalities() {
        fun ineq(l: String, rel: Inequality.Rel, r: String) =
            Inequality.solve(Algebra.simplify(Expr.sub(Parser.parse(l), Parser.parse(r))), "x", rel)
        assertEquals("x ∈ (−2; 2)", ineq("x^2", Inequality.Rel.LT, "4"))
        assertEquals("x ∈ (−∞; −2] ∪ [2; +∞)", ineq("x^2", Inequality.Rel.GE, "4"))
        assertEquals("x ∈ (2; +∞)", ineq("2x-4", Inequality.Rel.GT, "0"))
        assertEquals("x ∈ (−∞; 0) ∪ (0; +∞)", ineq("1/x^2", Inequality.Rel.GT, "0"))
        assertEquals("x ∈ (0; +∞)", ineq("1/x", Inequality.Rel.GT, "0"))
        assertEquals("x ∈ (−√2; √2)", ineq("x^2", Inequality.Rel.LT, "2"))
        assertEquals("x — любое число", ineq("x^2+1", Inequality.Rel.GT, "0"))
        assertEquals("нет решений", ineq("x^2+1", Inequality.Rel.LT, "0"))
        assertEquals("x = 3", ineq("(x-3)^2", Inequality.Rel.LE, "0"))
        assertEquals("x ∈ [0; 4)", ineq("sqrt(x)", Inequality.Rel.LT, "2"))
    }

    @Test
    fun steps() {
        fun steps(vararg eqs: Pair<String, String>) = Steps.of(eqs.map { Parser.parse(it.first) to Parser.parse(it.second) }, Env())
        val q = steps("x^2-5x" to "-6")!!
        assertTrue(q.any { it.startsWith("D = b² − 4ac") && it.endsWith("= 1") })
        assertEquals("x₁ = 2,  x₂ = 3", q.last())
        assertEquals("x = 6 / 2 = 3", steps("2x+1" to "7")!!.last())
        assertTrue(steps("x^2+1" to "0")!!.last().startsWith("D < 0"))
        val sys = steps("x+y" to "3", "x-y" to "1")!!
        assertTrue(sys.contains("x + y = 3"))
        assertTrue(sys.any { it.endsWith("x = Δₓ / Δ = 2") || it.contains("/ Δ = 2") })
        // No school method for a cubic: no steps.
        assertEquals(null, steps("x^3" to "2"))
    }

    @Test
    fun graphs() {
        fun graph(src: String) = Calc.graph(Parser.parse(src), Env())
        // tan x breaks at its asymptotes instead of joining them with a vertical line.
        val tan = graph("tg x")!!
        assertTrue(tan.segments.size >= 2)
        // 1/x: two branches.
        assertEquals(2, graph("1/x")!!.segments.size)
        // √x starts at 0, the window shows it.
        val root = graph("sqrt(x)")!!
        assertTrue(root.x0 <= 0f && root.x1 > 2f)
        // Not a function of one letter: no graph.
        assertEquals(null, graph("a+b"))
        assertEquals(null, graph("5"))
        assertEquals(listOf(-2f, -1f, 0f, 1f, 2f), Plot.ticks(-2.5f, 2.5f))
    }

    @Test
    fun numberModes() {
        try {
            Printer.numbers = Printer.Numbers.FRACTION
            assertEquals("x = 5/2", solve(Env(), "2x" to "5"))
            assertEquals("x = 1/3", solve(Env(), "3x" to "1"))
            assertEquals("x = 2 + ∛3 ≈ 3,44225", solve(Env(), "x^3" to "6x^2-12x+11"))
            Printer.numbers = Printer.Numbers.DECIMAL
            assertEquals("x = 2,5", solve(Env(), "2x" to "5"))
            assertEquals("x = 0,333333", solve(Env(), "3x" to "1"))
            assertEquals("x = 3,44225", solve(Env(), "x^3" to "6x^2-12x+11"))
        } finally {
            Printer.numbers = Printer.Numbers.MIXED
        }
    }

    @Test
    fun systemWithLargeRoots() {
        val a = solve(Env(), "x+y" to "1488", "x^6-y^5+3x-2x" to "67")
        assertTrue(a, a.contains("351,746") && a.contains("1136,25") && a.contains("−579,183"))
    }
}
