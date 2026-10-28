package dev.inknotes.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How an answer was found, the way it is written in school: the equation brought to `… = 0`,
 * a linear one solved for x, a quadratic through its discriminant, a linear system by
 * Cramer's rule. Null when there is nothing to show beyond the answer (a numeric search).
 */
object Steps {

    fun of(equations: List<Pair<Expr, Expr>>, env: Env): List<String>? = runCatching {
        val (residuals, inDegrees) = Calc.residuals(equations, env)
        if (inDegrees) return null
        val unknowns = residuals.flatMap { Algebra.freeVars(it) }.distinct().sorted()
        when {
            unknowns.size == 1 && residuals.size == 1 -> single(residuals[0], unknowns[0])
            unknowns.size == residuals.size && unknowns.size in 2..3 -> cramer(residuals, unknowns)
            else -> null
        }
    }.getOrNull()

    private fun value(v: Double) = Printer.value(Solver.clean(v))

    /** a·x² + b·x + c = 0 (or linear) from values of [r] when it is such a polynomial. */
    private fun quadratic(r: Expr, x: String): DoubleArray? {
        fun f(t: Double) = Numeric.scalar(r, mapOf(x to t))
        val c = f(0.0)
        val p = f(1.0)
        val m = f(-1.0)
        val a = (p + m) / 2 - c
        val b = (p - m) / 2
        for (t in doubleArrayOf(2.5, -3.7, 11.0)) {
            val y = f(t)
            if (!y.isFinite() || abs(a * t * t + b * t + c - y) > 1e-7 * (1 + abs(y))) return null
        }
        return doubleArrayOf(a, b, c)
    }

    private fun single(r: Expr, x: String): List<String>? {
        val name = Printer.name(x)
        val (a, b, c) = quadratic(r, x)?.toList() ?: return null
        val out = ArrayList<String>()
        runCatching { Printer.expr(Algebra.simplify(r)) }.getOrNull()?.let { out += "Переносим всё в левую часть: $it = 0" }
        when {
            abs(a) > 1e-12 -> {
                out += "Квадратное уравнение: a = ${value(a)}, b = ${value(b)}, c = ${value(c)}"
                val d = b * b - 4 * a * c
                out += "D = b² − 4ac = ${paren(b)}² − 4·${paren(a)}·${paren(c)} = ${value(d)}"
                when {
                    d < -1e-12 -> out += "D < 0: действительных корней нет"
                    abs(d) <= 1e-12 -> out += "D = 0: один корень $name = −b / 2a = ${value(-b / (2 * a))}"
                    else -> {
                        val s = sqrt(d)
                        out += "$name₁,₂ = (−b ± √D) / 2a = (${value(-b)} ± ${value(s)}) / ${value(2 * a)}"
                        out += "$name₁ = ${value((-b - s) / (2 * a))},  $name₂ = ${value((-b + s) / (2 * a))}"
                    }
                }
            }
            abs(b) > 1e-12 -> {
                out += "Линейное уравнение: ${value(b)}·$name = ${value(-c)}"
                out += "$name = ${value(-c)} / ${paren(b)} = ${value(-c / b)}"
            }
            else -> out += if (abs(c) < 1e-12) "0 = 0: подходит любое $name" else "${value(c)} = 0 неверно: решений нет"
        }
        return out
    }

    /** Coefficients of a linear system, or null when it is not linear. */
    private fun linear(residuals: List<Expr>, unknowns: List<String>): Pair<Array<DoubleArray>, DoubleArray>? {
        val n = unknowns.size
        fun f(i: Int, x: DoubleArray) = Numeric.scalar(residuals[i], unknowns.indices.associate { unknowns[it] to x[it] })
        val a = Array(residuals.size) { DoubleArray(n) }
        val rhs = DoubleArray(residuals.size)
        for (i in residuals.indices) {
            val f0 = f(i, DoubleArray(n))
            rhs[i] = -f0
            for (j in 0 until n) a[i][j] = f(i, DoubleArray(n) { if (it == j) 1.0 else 0.0 }) - f0
            // Linear: the same at a point away from the axes.
            val probe = DoubleArray(n) { 1.3 + 0.7 * it }
            val predicted = f0 + (0 until n).sumOf { a[i][it] * probe[it] }
            val y = f(i, probe)
            if (!y.isFinite() || abs(predicted - y) > 1e-7 * (1 + abs(y))) return null
        }
        return a to rhs
    }

    private fun cramer(residuals: List<Expr>, unknowns: List<String>): List<String>? {
        val (a, rhs) = linear(residuals, unknowns) ?: return null
        val names = unknowns.map(Printer::name)
        val n = names.size
        val out = ArrayList<String>()
        out += "Линейная система, решаем по формулам Крамера:"
        for (i in 0 until n) out += row(a[i], names) + " = " + value(rhs[i])
        fun det(m: Array<DoubleArray>): Double = if (n == 2) {
            m[0][0] * m[1][1] - m[0][1] * m[1][0]
        } else {
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        }
        val d = det(a)
        out += "Δ = ${value(d)}" + if (n == 2) "  (${value(a[0][0])}·${paren(a[1][1])} − ${paren(a[0][1])}·${paren(a[1][0])})" else ""
        if (abs(d) < 1e-12) {
            out += "Δ = 0: решений нет или их бесконечно много"
            return out
        }
        for (j in 0 until n) {
            val dj = det(Array(n) { i -> DoubleArray(n) { k -> if (k == j) rhs[i] else a[i][k] } })
            out += "Δ${names[j]} = ${value(dj)},  ${names[j]} = Δ${names[j]} / Δ = ${value(dj / d)}"
        }
        return out
    }

    /** 2x − y, x + 3z: the left side of a linear equation. */
    private fun row(a: DoubleArray, names: List<String>): String = buildString {
        for ((j, c) in a.withIndex()) {
            if (abs(c) < 1e-12) continue
            val mag = abs(c)
            val coef = if (abs(mag - 1) < 1e-12) "" else value(mag)
            if (isEmpty()) append(if (c < 0) Printer.MINUS else "") else append(if (c < 0) " ${Printer.MINUS} " else " + ")
            append(coef).append(names[j])
        }
        if (isEmpty()) append("0")
    }

    private fun paren(v: Double) = value(v).let { if (v < 0) "($it)" else it }
}
