package dev.inknotes.math

import dev.inknotes.math.Expr.Add
import dev.inknotes.math.Expr.Apply
import dev.inknotes.math.Expr.Deriv
import dev.inknotes.math.Expr.Fn
import dev.inknotes.math.Expr.Integral
import dev.inknotes.math.Expr.Mat
import dev.inknotes.math.Expr.Mul
import dev.inknotes.math.Expr.Num
import dev.inknotes.math.Expr.Pow
import dev.inknotes.math.Expr.Sym

/** What appears after "=": lines of text, or a matrix drawn in brackets. */
sealed class Answer {
    data class Text(val lines: List<String>) : Answer()
    data class Grid(val rows: List<List<String>>) : Answer()

    /** y = f(x) drawn in a box: the window and the curve in 0…1 box coordinates. */
    class Graph(val variable: String, val x0: Float, val x1: Float, val y0: Float, val y1: Float, val segments: List<FloatArray>) : Answer() {
        override fun toString() = "Graph($variable: ${Printer.number(x0.toDouble())}…${Printer.number(x1.toDouble())}, " +
            "${Printer.number(y0.toDouble())}…${Printer.number(y1.toDouble())}, ${segments.size} parts)"
    }
}

/** Variables and functions defined on a page so far (`a = 5`, `f(x) = x² + 1`), in writing order. */
class Env {
    val vars = LinkedHashMap<String, Expr>()
    val funcs = LinkedHashMap<String, Pair<String, Expr>>()

    /** Variables holding an angle written in degrees (a = 30°): sums with them answer in degrees. */
    val angles = HashSet<String>()

    fun isAngle(name: String): Boolean = name in angles || (vars[name] == null && angles.any { it.equals(name, ignoreCase = true) })

    fun variable(name: String): Expr? = vars[name] ?: vars.keys.singleOrNull { it.equals(name, ignoreCase = true) }?.let { vars[it] }
    fun function(name: String): Pair<String, Expr>? = funcs[name] ?: funcs.keys.singleOrNull { it.equals(name, ignoreCase = true) }?.let { funcs[it] }
}

/** Evaluation of what was written: `expr =`, definitions and systems of equations. */
object Calc {

    /**
     * Records `name = expr` or `f(x) = expr`; returns false when [left] is not a plain name
     * (the line is then an ordinary equation).
     */
    /** `a = …` or `f(x) = …` on the left of "=". */
    fun isDefinition(left: Expr) = definedName(left) != null

    fun definedName(left: Expr): String? = when {
        left is Sym && left.name !in Algebra.CONSTANTS -> left.name
        left is Apply && left.primes == 0 && left.args.size == 1 && left.args[0] is Sym -> left.name
        else -> null
    }

    fun define(left: Expr, right: Expr, env: Env): Boolean {
        when {
            left is Sym && left.name !in Algebra.CONSTANTS -> {
                env.vars[left.name] = bounded(constantFold(resolve(right, env, emptySet(), Flags())))
                if (Angles.isAngle(right, env)) env.angles += left.name else env.angles -= left.name
                return true
            }
            left is Apply && left.primes == 0 && left.args.size == 1 && left.args[0] is Sym -> {
                val param = (left.args[0] as Sym).name
                env.funcs[left.name] = param to bounded(resolve(right, env, setOf(param), Flags()))
                return true
            }
        }
        return false
    }

    /**
     * The value of [e] with the page's definitions; symbolic when unknowns remain. Null when
     * there is nothing to say: unknowns left and the formula reads the same simplified (a
     * word ending in "=" in ordinary notes, or `x + 1 =`).
     */
    fun evaluate(e: Expr, env: Env): Answer? {
        val flags = Flags()
        val r = resolve(e, env, emptySet(), flags)
        val free = Algebra.freeVars(r)
        if (free.isEmpty()) {
            return when (val v = Numeric.eval(r)) {
                is Scalar -> {
                    // ∞ only when it was written: 9^9^9 is just too big to show.
                    if (v.v.isInfinite() && runCatching { Printer.expr(e) }.getOrNull()?.contains("∞") != true) throw MathError("слишком большое число")
                    Answer.Text(listOf(Angles.answer(e, v.v, env) ?: Printer.value(v.v)))
                }
                is Matrix -> Answer.Grid(List(v.rows) { i -> List(v.cols) { j -> Printer.value(v[i, j]) } })
            }
        }
        if (containsMatrix(r)) throw MathError("матрица с неизвестными")
        val s = Algebra.simplify(r)
        val text = Printer.expr(s) + if (flags.indefinite) " + C" else ""
        if (!flags.calculus && runCatching { Printer.expr(r) }.getOrNull() == text) return null
        return Answer.Text(listOf(text))
    }

    /**
     * The graph of `y = [right]` (or `f([param]) = [right]`) when, with the page's definitions,
     * it is a formula in one letter (x²−3, sin x, a·x with a defined); null otherwise.
     */
    fun graph(right: Expr, env: Env, param: String? = null): Answer.Graph? {
        // f(x) = …: its own x, whatever x is defined as on the page.
        val r = runCatching { Algebra.simplify(resolve(right, env, setOfNotNull(param), Flags())) }.getOrNull() ?: return null
        if (containsMatrix(r)) return null
        val x = Algebra.freeVars(r).singleOrNull()?.takeIf { param == null || it == param } ?: return null
        val trig = TRIG.containsMatchIn(runCatching { Printer.expr(r) }.getOrDefault(""))
        return Plot.of({ v -> Numeric.scalar(r, mapOf(x to v)) }, x, trig)
    }

    private val TRIG = Regex("sin|cos|tg|ctg")

    /** Solves equations `left = right` in their unknowns (names not defined above). */
    fun solve(equations: List<Pair<Expr, Expr>>, env: Env): Answer {
        val (residuals, inDegrees) = residuals(equations, env)
        return solveFor(residuals, inDegrees)
    }

    /** `left − right` of each equation with the page's definitions in, and whether in degrees. */
    internal fun residuals(equations: List<Pair<Expr, Expr>>, env: Env): Pair<List<Expr>, Boolean> {
        // Letters defined above are parameters (a = 4; ax = 8), unless the system needs them as
        // unknowns: with x = 5 above, {x + y = 3; x − y = 1} is still solved for x and y.
        val raw = equations.map { (l, r) -> Expr.sub(l, r) }
        val written = raw.flatMap { Algebra.freeVars(it) }.distinct()
        val unknowns = written.filter { env.variable(it) == null }.toMutableList()
        val defined = written.filter { env.variable(it) != null }.sortedBy { if (it in PREFERRED_UNKNOWNS) PREFERRED_UNKNOWNS.indexOf(it) else 99 }.toMutableList()
        while (unknowns.size < equations.size && defined.isNotEmpty()) unknowns += defined.removeAt(0)
        val residuals = raw.map { Algebra.simplify(resolve(it, env, unknowns.toSet(), Flags())) }
        // x + 30° = 90°: the unknown is an angle in degrees too.
        val inDegrees = equations.any { (l, r) -> Angles.isAngle(l, env) || Angles.isAngle(r, env) }
        return residuals to inDegrees
    }

    private fun solveFor(residuals: List<Expr>, inDegrees: Boolean): Answer {
        val unknowns = residuals.flatMap { Algebra.freeVars(it) }.distinct().sorted()
        if (unknowns.isEmpty()) {
            val ok = residuals.all { kotlin.math.abs(Numeric.scalar(it, emptyMap())) < 1e-9 }
            return Answer.Text(listOf(if (ok) "верно" else "неверно"))
        }
        if (unknowns.size > MAX_UNKNOWNS) throw MathError("слишком много неизвестных")
        // sin x = 1/2: every solution, as x = π/6 + 2πk.
        if (unknowns.size == 1 && residuals.size == 1) {
            Angles.periodic(residuals[0], unknowns[0], inDegrees)?.let { return Answer.Text(it) }
        }
        // x³ = 6x² − 12x + 11: x = 2 + ∛3 rather than 3,4422 alone (unless decimals are asked for).
        if (unknowns.size == 1 && residuals.size == 1 && !inDegrees && Printer.numbers != Printer.Numbers.DECIMAL) {
            val v = unknowns[0]
            Exact.roots { x -> runCatching { Numeric.scalar(residuals[0], mapOf(v to x)) }.getOrDefault(Double.NaN) }?.let { roots ->
                val name = Printer.name(v)
                return Answer.Text(
                    roots.mapIndexed { k, r ->
                        val index = if (roots.size > 1) Printer.subscript((k + 1).toString()) else ""
                        val approx = Printer.value(r.value)
                        "$name$index = ${r.text}" + if (approx == r.text) "" else " ≈ $approx"
                    },
                )
            }
        }
        val show: (Double) -> String = if (inDegrees) Angles::degrees else Printer::value
        return when (val res = Solver.solve(residuals, unknowns)) {
            Solver.Result.NoSolution -> Answer.Text(listOf("нет решений"))
            Solver.Result.Infinite -> Answer.Text(listOf("∞ решений"))
            is Solver.Result.Points -> {
                val names = unknowns.map(Printer::name)
                val lines = ArrayList<String>()
                for ((k, p) in res.solutions.withIndex()) {
                    val index = if (res.solutions.size > 1 && unknowns.size == 1) Printer.subscript((k + 1).toString()) else ""
                    lines += names.indices.joinToString("; ") { "${names[it]}$index = ${show(p[it])}" }
                }
                if (res.more) lines += "…"
                if (unknowns.size > 1 && res.solutions.size > 1) {
                    // Several solutions of a system: one per line, as pairs.
                    lines.clear()
                    lines += res.solutions.map { p -> "(" + p.joinToString("; ") { show(it) } + ")" }
                    if (res.more) lines += "…"
                    lines.add(0, "(" + names.joinToString("; ") + ") =")
                }
                Answer.Text(lines)
            }
        }
    }

    /** Chains like f₂(x) = f₁(x)·f₁(x) double in size at every step; stop them early. */
    private fun bounded(e: Expr): Expr {
        if (Algebra.size(e, MAX_NODES) > MAX_NODES) throw MathError("слишком длинно")
        return e
    }

    /** A definition without unknowns is stored as its value, so chains of definitions stay small. */
    private fun constantFold(e: Expr): Expr {
        if (Algebra.freeVars(e).isNotEmpty()) return e
        return when (val v = runCatching { Numeric.eval(e) }.getOrNull()) {
            is Scalar -> Num(v.v)
            is Matrix -> Mat(List(v.rows) { i -> List(v.cols) { j -> Num(v[i, j]) } })
            null -> e
        }
    }

    private class Flags {
        var indefinite = false
        var calculus = false
        var depth = 0
        var visits = 0
    }

    private fun containsMatrix(e: Expr): Boolean = when (e) {
        is Mat -> true
        is Add -> e.terms.any(::containsMatrix)
        is Mul -> e.factors.any(::containsMatrix)
        is Pow -> containsMatrix(e.base) || containsMatrix(e.exp)
        is Fn -> e.args.any(::containsMatrix)
        else -> false
    }

    /**
     * Replaces defined names by their values and user function calls by their bodies,
     * computes derivatives and indefinite integrals symbolically. Integration and
     * differentiation variables are not replaced inside their own scope.
     */
    private fun resolve(e: Expr, env: Env, bound: Set<String>, flags: Flags): Expr {
        if (++flags.depth > MAX_RESOLVE) throw MathError("слишком глубоко")
        // Letters naming letters (a = bb, b = cc…) double the work at each step: a total bound.
        if (++flags.visits > MAX_VISITS) {
            flags.depth--
            throw MathError("слишком сложно")
        }
        try {
            return when (e) {
                is Num -> e
                // A stored value may name letters defined later on the page (a = b + 1, b = 2 below).
                is Sym -> if (e.name in bound) e else env.variable(e.name)?.let { resolve(it, env, bound, flags) } ?: e
                // 180 − 30°: plain numbers added to angles are degrees too.
                is Add -> Add(Angles.degreeTerms(e, env).map { resolve(it, env, bound, flags) })
                is Mul -> Mul(e.factors.map { resolve(it, env, bound, flags) })
                is Pow -> if (e.exp == Sym("T") && "T" !in bound && env.variable("T") == null) {
                    Fn("transpose", listOf(resolve(e.base, env, bound, flags)))
                } else {
                    Pow(resolve(e.base, env, bound, flags), resolve(e.exp, env, bound, flags))
                }
                is Fn -> when {
                    e.name in Angles.UNITS -> Angles.radians(resolve(e.args[0], env, bound, flags))
                    else -> {
                        val args = e.args.map { resolve(it, env, bound, flags) }
                        // sin 60 written without ° means degrees: nobody takes the sine of 60 radians.
                        if (e.name in Parser.TRIG && args.size == 1 && Angles.looksLikeDegrees(args[0])) {
                            Fn(e.name, listOf(Angles.radians(args[0])))
                        } else {
                            Fn(e.name, args)
                        }
                    }
                }
                is Mat -> Mat(e.rows.map { r -> r.map { resolve(it, env, bound, flags) } })
                is Apply -> {
                    val args = e.args.map { resolve(it, env, bound, flags) }
                    val f = if (e.name in bound) null else env.function(e.name)
                    when {
                        f != null -> {
                            if (args.size != 1) throw MathError("${e.name}: один аргумент")
                            var body = f.second
                            repeat(e.primes) { body = Algebra.derivative(body, f.first) }
                            bounded(Algebra.substitute(body, f.first, args[0]))
                        }
                        e.primes == 0 && args.size == 1 -> Mul(listOf(resolve(Sym(e.name), env, bound, flags), args[0]))
                        else -> throw MathError("функция ${e.name} не задана")
                    }
                }
                is Deriv -> {
                    flags.calculus = true
                    val v = e.v ?: guessVariable(e.body, env)
                    var d = resolve(e.body, env, bound + v, flags)
                    repeat(e.order) { d = Algebra.derivative(d, v) }
                    // The variable's value, if defined, is used after differentiating: x = 2, (x³)' = 12.
                    if (v !in bound) env.variable(v)?.let { d = Algebra.substitute(d, v, it) }
                    d
                }
                is Integral -> {
                    flags.calculus = true
                    val body = resolve(e.body, env, bound + e.v, flags)
                    if (e.lo == null || e.hi == null) {
                        flags.indefinite = true
                        Algebra.integrate(body, e.v) ?: throw MathError("не берётся в элементарных функциях")
                    } else {
                        val lo = resolve(e.lo, env, bound, flags)
                        val hi = resolve(e.hi, env, bound, flags)
                        // Each point of an outer integral would run a whole inner one.
                        if (Algebra.containsIntegral(body)) throw MathError("вложенный интеграл")
                        val params = Algebra.freeVars(body) - e.v
                        if (params.isEmpty()) {
                            Integral(body, e.v, lo, hi)
                        } else {
                            // Parameters left: F(b) − F(a) symbolically.
                            val f = Algebra.integrate(body, e.v) ?: throw MathError("не берётся в элементарных функциях")
                            Expr.sub(Algebra.substitute(f, e.v, hi), Algebra.substitute(f, e.v, lo))
                        }
                    }
                }
            }
        } finally {
            flags.depth--
        }
    }

    /** The variable of a prime: x if present, else the only unknown, else x. */
    private fun guessVariable(body: Expr, env: Env): String {
        val vars = Algebra.freeVars(body).filter { it.length == 1 || it.first().isLetter() }
        return when {
            "x" in vars -> "x"
            vars.size == 1 -> vars[0]
            else -> vars.firstOrNull { env.variable(it) == null } ?: "x"
        }
    }

    private const val MAX_UNKNOWNS = 6
    private const val PREFERRED_UNKNOWNS = "xyztuvw"
    private const val MAX_RESOLVE = 200
    private const val MAX_VISITS = 50_000
    private const val MAX_NODES = 5000
}
