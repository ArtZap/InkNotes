package dev.inknotes.math

import dev.inknotes.math.Expr.Add
import dev.inknotes.math.Expr.Fn
import dev.inknotes.math.Expr.Mul
import dev.inknotes.math.Expr.Num
import dev.inknotes.math.Expr.Pow
import dev.inknotes.math.Expr.Sym
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Angles in degrees. The parser keeps `30°` as `deg(30)` (`dms` with minutes) so that what is
 * written in degrees is answered in degrees: 30° + 45° = 75°, 2·30°15′ = 60°30′, while
 * sin 30° = 0,5 is a plain number. Inverse functions give radians and degrees: arcsin 0,5 = π/6 = 30°.
 */
object Angles {
    val UNITS = setOf("deg", "dms")
    private val INVERSE = setOf("asin", "acos", "atan", "acot")
    private val SAME_UNIT = setOf("abs", "max", "min")

    fun radians(x: Expr): Expr = Mul(listOf(x, Sym("pi"), Num(1.0 / 180.0)))

    /** [e] is an angle in degrees: written with °, or made of such angles (2·30°, 90° − a). */
    fun isAngle(e: Expr, env: Env): Boolean = power(e, env) == 1

    /** How many times degrees multiply in [e]: 0 a plain number, 1 an angle, null mixed up. */
    private fun power(e: Expr, env: Env): Int? = when (e) {
        is Num -> 0
        is Sym -> if (env.isAngle(e.name)) 1 else 0
        is Fn -> when (e.name) {
            in UNITS -> 1
            in SAME_UNIT -> e.args.map { power(it, env) }.distinct().singleOrNull()
            else -> 0
        }
        is Add -> {
            val p = e.terms.map { power(it, env) ?: return null }.toSet()
            // 180 − 30°: a plain number among angles is in degrees too.
            when {
                p == setOf(0) -> 0
                p == setOf(1) || p == setOf(0, 1) -> 1
                else -> null
            }
        }
        is Mul -> e.factors.sumOf { power(it, env) ?: return null }
        is Pow -> {
            val b = power(e.base, env) ?: return null
            val n = (e.exp as? Num)?.v
            when {
                b == 0 -> 0
                n != null && n == round(n) && abs(n) <= 4 -> b * n.toInt()
                else -> null
            }
        }
        else -> 0
    }

    /** Terms of a sum of angles, its plain numbers made degrees (180 − 30° is 150°). */
    fun degreeTerms(e: Add, env: Env): List<Expr> {
        if (power(e, env) != 1) return e.terms
        return e.terms.map { if (plainNumber(it)) Fn("deg", listOf(it)) else it }
    }

    private fun plainNumber(e: Expr): Boolean = when (e) {
        is Num -> true
        is Mul -> e.factors.all(::plainNumber)
        is Pow -> plainNumber(e.base) && plainNumber(e.exp)
        else -> false
    }

    /** sin 60 without "°": a whole number of at least 10, no π in it, is degrees. */
    fun looksLikeDegrees(arg: Expr): Boolean {
        if (!numeric(arg)) return false
        val v = runCatching { Numeric.scalar(arg, emptyMap()) }.getOrNull() ?: return false
        return v.isFinite() && v == round(v) && abs(v) >= 10 && abs(v) <= 100_000
    }

    /** Numbers and arithmetic only: no letters, constants or functions. */
    private fun numeric(e: Expr): Boolean = when (e) {
        is Num -> true
        is Add -> e.terms.all(::numeric)
        is Mul -> e.factors.all(::numeric)
        is Pow -> numeric(e.base) && numeric(e.exp)
        else -> false
    }

    /**
     * The answer to [e] whose value is [v] (radians for angles), when angles change how it is
     * written; null for an ordinary number.
     */
    fun answer(e: Expr, v: Double, env: Env): String? {
        if (isAngle(e, env)) {
            // "30° =" alone asks for radians.
            if (literal(e)) return (piForm(v) ?: Printer.number(v)) + " рад"
            return degrees(v, dms = hasMinutes(e))
        }
        if (v != 0.0 && inverse(e)) {
            val deg = degrees(v)
            return (piForm(v) ?: Printer.number(v)) + " = " + deg
        }
        return null
    }

    private fun literal(e: Expr): Boolean = when (e) {
        is Fn -> e.name in UNITS && plainNumber(e.args[0])
        is Mul -> e.factors.size == 2 && e.factors[0] == Expr.MINUS_ONE && literal(e.factors[1])
        else -> false
    }

    private fun hasMinutes(e: Expr): Boolean = when (e) {
        is Fn -> e.name == "dms" || e.args.any(::hasMinutes)
        is Add -> e.terms.any(::hasMinutes)
        is Mul -> e.factors.any(::hasMinutes)
        is Pow -> hasMinutes(e.base)
        else -> false
    }

    /** Made of inverse trigonometric functions: arcsin 0,5, 2·arctg 1, arcsin x + arccos x. */
    private fun inverse(e: Expr): Boolean = when (e) {
        is Fn -> e.name in INVERSE
        is Add -> e.terms.all(::inverse)
        is Mul -> e.factors.count(::inverse) == 1 && e.factors.all { inverse(it) || plainNumber(it) }
        else -> false
    }

    /** [rad] in degrees: 75°, 22,5°, or 60°30′ when minutes were written. */
    fun degrees(rad: Double, dms: Boolean = false): String {
        if (!rad.isFinite()) throw MathError("не определено")
        val d = Solver.clean(rad * 180 / PI)
        if (!dms || d == floor(d)) return Printer.number(d) + "°"
        val sign = if (d < 0) Printer.MINUS else ""
        var total = Math.round(abs(d) * 3600)
        val s = total % 60
        total /= 60
        val m = total % 60
        val deg = total / 60
        return sign + "$deg°" + (if (m != 0L || s != 0L) "$m′" else "") + (if (s != 0L) "$s″" else "")
    }

    /** A multiple of π with a small denominator: π/6, −3π/4, 2π. */
    fun piForm(v: Double): String? {
        if (v == 0.0) return "0"
        val f = Printer.fraction(v / PI) ?: return null
        val (p, q) = f
        if (q > 12 || abs(p) > 100) return null
        val sign = if (p < 0) Printer.MINUS else ""
        val num = if (abs(p) == 1L) "π" else "${abs(p)}π"
        return sign + num + (if (q == 1L) "" else "/$q")
    }

    /**
     * All solutions of a trigonometric equation in [x] as x = a + Tk, or null when [residual]
     * is not periodic in x (or has too many roots in a period).
     */
    fun periodic(residual: Expr, x: String, inDegrees: Boolean): List<String>? {
        if (!trigOf(residual, x)) return null
        val f = { t: Double -> runCatching { Numeric.scalar(residual, mapOf(x to t)) }.getOrDefault(Double.NaN) }
        val full = 2 * PI
        var finite = 0
        for (t in SAMPLES) {
            val a = f(t)
            val b = f(t + full)
            if (!a.isFinite() || !b.isFinite()) continue
            if (abs(a - b) > 1e-9 * (1 + abs(a))) return null
            finite++
        }
        if (finite < 4) return null
        val xs = DoubleArray(PERIOD_GRID + 1) { -0.001 + (full + 0.002) * it / PERIOD_GRID }
        val found = ArrayList<Double>()
        for (r0 in Solver.roots(f, xs)) {
            val r = snap(r0, f)
            val m = ((r % full) + full) % full
            val v = if (full - m < 1e-7) 0.0 else m
            if (found.none { abs(it - v) < 1e-7 }) found += v
        }
        if (found.isEmpty()) return listOf("нет решений")
        // The smallest period the roots repeat with: tg x = 1 has π, sin 2x = 0 has π/2.
        var n = 1
        for (k in MAX_DIVISOR downTo 2) {
            val t = full / k
            if (found.size % k == 0 && found.all { r -> found.any { near(it, (r + t) % full, full) } }) {
                n = k
                break
            }
        }
        val period = full / n
        val base = found.map { r ->
            var m = r % period
            if (m > period / 2 + 1e-9) m -= period
            Solver.clean(m)
        }.distinctBy { Math.round(it * 1e7) }.sorted()
        if (base.size > MAX_SERIES) return null
        val name = Printer.name(x)
        fun angle(v: Double) = if (inDegrees) degrees(v) else piForm(v) ?: Printer.number(v)
        val step = if (inDegrees) degrees(period) + "k" else stepForm(n)
        val lines = ArrayList<String>()
        val used = HashSet<Double>()
        for (r in base) {
            if (r in used) continue
            used += r
            val pair = r != 0.0 && base.any { abs(it + r) < 1e-9 }
            if (pair) used += base.first { abs(it + r) < 1e-9 }
            val head = when {
                r == 0.0 -> ""
                pair -> "±" + angle(abs(r)) + " + "
                r < 0 -> Printer.MINUS + angle(-r) + " + "
                else -> angle(r) + " + "
            }
            lines += "$name = $head$step"
        }
        lines[lines.size - 1] = lines.last() + ", k ∈ ℤ"
        return lines
    }

    /** A double root (sin x = 1) is found only to about 1e-8: put it on π/2 if it is there. */
    private fun snap(r: Double, f: (Double) -> Double): Double {
        for (q in 1..12) {
            val c = Math.round(r * q / PI) * PI / q
            if (abs(c - r) < 1e-5 && abs(f(c)) < 1e-12) return c
        }
        return r
    }

    private fun near(a: Double, b: Double, full: Double): Boolean {
        val d = abs(a - b)
        return d < 1e-6 || abs(d - full) < 1e-6
    }

    /** 2π/n times k: 2πk, πk, πk/2. */
    private fun stepForm(n: Int): String = when {
        n == 1 -> "2πk"
        n == 2 -> "πk"
        n % 2 == 0 -> "πk/${n / 2}"
        else -> "2πk/$n"
    }

    /** A trigonometric function of [x] somewhere in [e]. */
    private fun trigOf(e: Expr, x: String): Boolean = when (e) {
        is Fn -> (e.name in Parser.TRIG && e.args.any { Algebra.has(it, x) }) || e.args.any { trigOf(it, x) }
        is Add -> e.terms.any { trigOf(it, x) }
        is Mul -> e.factors.any { trigOf(it, x) }
        is Pow -> trigOf(e.base, x) || trigOf(e.exp, x)
        else -> false
    }

    private val SAMPLES = listOf(0.37, 1.1, 2.3, 3.7, 4.4, 5.9, -0.8)
    private const val PERIOD_GRID = 4000
    private const val MAX_DIVISOR = 12
    private const val MAX_SERIES = 6
}
