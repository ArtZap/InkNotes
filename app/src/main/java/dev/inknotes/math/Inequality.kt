package dev.inknotes.math

import dev.inknotes.data.tr
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.max
import kotlin.math.sinh

/**
 * Inequalities in one unknown by the interval method: the roots and breaks of `left − right`
 * cut the line into intervals, the sign on each decides. Written as in school:
 * x ∈ (−∞; −2) ∪ [3; +∞).
 */
object Inequality {

    enum class Rel(val sign: String, val strict: Boolean) {
        LT("<", true),
        GT(">", true),
        LE("≤", false),
        GE("≥", false),
        ;

        fun holds(v: Double) = when (this) {
            LT -> v < 0
            GT -> v > 0
            LE -> v <= 0
            GE -> v >= 0
        }
    }

    private const val GRID = 20_000
    private const val MAX_POINTS = 40
    private const val RANGE = 1e6

    /** Where `[residual] rel 0` holds, as "x ∈ …"; a [MathError] when it can't be told. */
    fun solve(residual: Expr, x: String, rel: Rel): String {
        val name = Printer.name(x)
        fun f(t: Double) = runCatching { Numeric.scalar(residual, mapOf(x to t)) }.getOrDefault(Double.NaN)
        val umax = asinh(RANGE)
        val xs = DoubleArray(GRID + 1) { sinh(-umax + 2 * umax * it / GRID) }
        val ys = DoubleArray(xs.size) { f(xs[it]) }
        if (ys.none { it.isFinite() }) throw MathError(tr("не определено", "undefined"))
        // A wave (sin x > 0) changes sign thousands of times: say so before looking at each.
        var changes = 0
        for (k in 0 until GRID) {
            val a = ys[k]
            val b = ys[k + 1]
            if (a.isFinite() != b.isFinite() || (a.isFinite() && b.isFinite() && (a < 0) != (b < 0))) changes++
        }
        if (changes > MAX_POINTS) throw MathError(tr("слишком много интервалов", "too many intervals"))
        val roots = Solver.roots(::f, xs).map(Solver::clean)
        // Breaks: the edge of the domain (√x at 0) and poles (1/x), where the sign may change.
        val breaks = ArrayList<Double>()
        for (k in 0 until GRID) {
            val a = ys[k]
            val b = ys[k + 1]
            val edge = a.isFinite() != b.isFinite()
            val pole = a.isFinite() && b.isFinite() && (a < 0) != (b < 0) && roots.none { it in xs[k]..xs[k + 1] }
            if (!edge && !pole) continue
            var lo = xs[k]
            var hi = xs[k + 1]
            repeat(80) {
                val mid = (lo + hi) / 2
                val fm = f(mid)
                val sameAsLo = if (edge) fm.isFinite() == a.isFinite() else fm.isFinite() && (fm < 0) == (a < 0)
                if (sameAsLo) lo = mid else hi = mid
            }
            breaks += Solver.clean((lo + hi) / 2)
        }
        val points = (roots + breaks).sorted().fold(ArrayList<Double>()) { acc, p ->
            if (acc.isEmpty() || abs(acc.last() - p) > 1e-9 * max(1.0, abs(p))) acc += p
            acc
        }
        if (points.size > MAX_POINTS) throw MathError(tr("слишком много интервалов", "too many intervals"))
        val exact = Exact.roots(::f)
        fun text(v: Double) = exact?.firstOrNull { abs(it.value - v) < 1e-9 * max(1.0, abs(v)) }?.text ?: Printer.value(v)

        // Sign inside every interval between the points (and beyond the outer ones).
        fun sample(i: Int): Double = when {
            points.isEmpty() -> 0.0
            i == 0 -> points[0] - max(1.0, abs(points[0]))
            i == points.size -> points.last() + max(1.0, abs(points.last()))
            else -> (points[i - 1] + points[i]) / 2
        }
        val inside = BooleanArray(points.size + 1) { i -> f(sample(i)).let { it.isFinite() && rel.holds(it) } }
        // A point belongs when the relation holds there: a root of ≤ or ≥, the edge of the
        // domain where the value fits (√x < 2 at 0); never a pole.
        val atPoint = BooleanArray(points.size) { i ->
            f(points[i]).let { v -> v.isFinite() && rel.holds(if (abs(v) < 1e-9) 0.0 else v) }
        }

        // Runs of intervals glued through the points that belong too.
        val parts = ArrayList<String>()
        var i = 0
        while (i <= points.size) {
            if (!inside[i]) {
                if (i < points.size && atPoint[i] && (i + 1 > points.size || !inside[i + 1])) parts += "{${text(points[i])}}"
                i++
                continue
            }
            val start = i
            while (i < points.size && atPoint[i] && inside[i + 1]) i++
            val left = if (start == 0) "(${Printer.MINUS}∞" else (if (atPoint[start - 1]) "[" else "(") + text(points[start - 1])
            val right = if (i == points.size) "+∞)" else text(points[i]) + if (atPoint[i]) "]" else ")"
            parts += "$left; $right"
            // A point that closes this run is taken; don't list it again.
            i++
        }
        return when {
            parts.isEmpty() -> tr("нет решений", "no solutions")
            parts == listOf("(${Printer.MINUS}∞; +∞)") -> tr("$name — любое число", "$name is any number")
            parts.size == 1 && parts[0].startsWith("{") -> "$name = ${parts[0].removeSurrounding("{", "}")}"
            else -> "$name ∈ " + parts.joinToString(" ∪ ")
        }
    }
}
