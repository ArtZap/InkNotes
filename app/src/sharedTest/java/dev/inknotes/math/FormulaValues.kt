package dev.inknotes.math

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Formulas compared by value, not by text: letters get the same random values on both sides
 * (x²+2x+1 and (x+1)² are the same). Shared by the formula bench on the JVM and the formula
 * test on a device, so values computed in one are compared in the other.
 */
object FormulaValues {
    private val rnd = Random(7)
    private val trials = List(3) { List(26) { 1.1 + rnd.nextDouble() * 1.8 } }
    val TRIALS = trials.size

    /** The value of [e] with letters a…z set as in trial [t]; null when it cannot be computed. */
    fun values(e: Expr, t: Int): String? {
        val env = Env()
        for (v in Algebra.freeVars(e)) {
            if (v in Algebra.CONSTANTS) continue
            val k = (v.lowercase().firstOrNull() ?: 'a') - 'a'
            // Letters that look the same in both cases are read in either (X and x): one value.
            val x = trials[t][k.coerceIn(0, 25)] + v.length * 0.01 + (if (v[0].isUpperCase() && v[0] !in "CKOPSUVWXYZ") 0.37 else 0.0)
            Calc.define(Expr.Sym(v), Expr.Num(x), env)
        }
        val a = runCatching { Calc.evaluate(e, env) }.getOrNull() ?: return null
        return when (a) {
            is Answer.Text -> a.lines.joinToString(";")
            is Answer.Grid -> a.rows.toString()
            is Answer.Graph, is Answer.Surface -> null
        }
    }

    /** The values of every trial, "|" between: null when one cannot be computed. */
    fun fingerprint(e: Expr): String? = (0 until TRIALS).map { values(e, it) ?: return null }.joinToString("|")

    fun same(a: Expr, b: Expr): Boolean = (0 until TRIALS).all { t ->
        val x = values(a, t) ?: return false
        val y = values(b, t) ?: return false
        close(x, y)
    }

    /** Two fingerprints of the same value. */
    fun sameFingerprint(x: String, y: String): Boolean {
        val a = x.split('|')
        val b = y.split('|')
        return a.size == b.size && a.indices.all { close(a[it], b[it]) }
    }

    private fun num(s: String): Double? = s.replace(Printer.MINUS, "-").replace(',', '.').toDoubleOrNull()

    fun close(x: String, y: String): Boolean {
        if (x == y) return true
        val a = num(x) ?: return false
        val b = num(y) ?: return false
        return abs(a - b) <= 1e-5 * max(1.0, max(abs(a), abs(b)))
    }
}
