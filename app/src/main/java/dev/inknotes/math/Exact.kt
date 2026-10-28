package dev.inknotes.math

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.sqrt

/**
 * Closed forms for the real roots of quadratics and cubics with rational coefficients:
 * 1 ± √2, 2 + ∛3, and a rational root split off a cubic. Rational roots alone are left to
 * the numeric solver, which prints them exactly already.
 */
object Exact {

    /** A root as written and its value. */
    class Root(val text: String, val value: Double)

    /**
     * Roots of [f] when it is a quadratic or cubic polynomial with rational coefficients and
     * at least one root is irrational but expressible; null otherwise (and for no real root).
     */
    fun roots(f: (Double) -> Double): List<Root>? {
        val c = coefficients(f) ?: return null
        val degree = c.indexOfLast { it != 0L }
        return when (degree) {
            2 -> quadratic(c[2], c[1], c[0])
            3 -> cubic(c[3], c[2], c[1], c[0])
            else -> null
        }?.takeIf { roots -> roots.any { Printer.fraction(it.value)?.takeIf { f -> f.second <= 100 } == null } }
            ?.sortedBy { it.value }
    }

    /**
     * Integer coefficients c₀…c₃ of [f] (common factor taken out) when it is a polynomial of
     * degree ≤ 3 with rational coefficients; checked at points away from the fit.
     */
    private fun coefficients(f: (Double) -> Double): LongArray? {
        val ys = DoubleArray(4) { f(it.toDouble()) }
        if (ys.any { !it.isFinite() || abs(it) > 1e12 }) return null
        // Newton's forward differences at 0, 1, 2, 3 → power basis.
        val d1 = ys[1] - ys[0]
        val d2 = ys[2] - 2 * ys[1] + ys[0]
        val d3 = ys[3] - 3 * ys[2] + 3 * ys[1] - ys[0]
        val a3 = d3 / 6
        val a2 = d2 / 2 - 3 * a3
        val a1 = d1 - a2 - a3
        val a0 = ys[0]
        val coef = doubleArrayOf(a0, a1, a2, a3)
        for (x in doubleArrayOf(-2.3, 5.7, -11.1)) {
            val p = a0 + x * (a1 + x * (a2 + x * a3))
            val y = f(x)
            if (!y.isFinite() || abs(p - y) > 1e-7 * (1 + abs(y))) return null
        }
        val fr = coef.map { v -> if (abs(v) < 1e-9) 0L to 1L else Printer.fraction(v) ?: return null }
        val lcm = fr.fold(1L) { acc, (_, q) -> lcm(acc, q) }
        if (lcm > 10_000) return null
        val ints = LongArray(4) { fr[it].first * (lcm / fr[it].second) }
        val g = ints.fold(0L) { acc, v -> gcd(acc, abs(v)) }.coerceAtLeast(1)
        if (ints.any { abs(it) > 1_000_000 }) return null
        return LongArray(4) { ints[it] / g }
    }

    /** a·x² + b·x + c = 0 with integers: (−b ± k√m) / 2a. */
    private fun quadratic(a: Long, b: Long, c: Long): List<Root>? {
        val disc = b * b - 4 * a * c
        if (disc < 0) return null
        val (k, m) = surd(disc)
        if (m == 1L) return null // rational roots
        val den = 2 * a
        val s = sqrt(disc.toDouble())
        return listOf(-1, 1).map { sign ->
            Root(surdText(-b, sign * k, m, den), (-b + sign * s) / den)
        }
    }

    /** a·x³ + b·x² + c·x + d = 0: a rational root and the quadratic left, or (x − h)³ = k. */
    private fun cubic(a: Long, b: Long, c: Long, d: Long): List<Root>? {
        rationalRoot(a, b, c, d)?.let { (p, q) ->
            // Divide by (q·x − p): a·x³ + … = (q·x − p)(A·x² + B·x + C).
            if (a % q != 0L) return null
            val aa = a / q
            val bb = (b + aa * p).let { if (it % q != 0L) return null else it / q }
            val cc = (c + bb * p).let { if (it % q != 0L) return null else it / q }
            val rest = quadratic(aa, bb, cc) ?: return null
            return rest + Root(Printer.value(p.toDouble() / q), p.toDouble() / q)
        }
        // Depressed form t³ + P·t + Q with x = t − b/3a; closed only when P = 0.
        // Big coefficients would overflow a Long: then no exact form (the numbers still come).
        fun m(vararg v: Long) = v.reduce { x, y -> Math.multiplyExact(x, y) }
        val (kNum, kDen) = try {
            if (m(b, b) != m(3, a, c)) return null
            Math.subtractExact(m(b, b, b), m(27, a, a, d)) to m(27, a, a, a)
        } catch (e: ArithmeticException) {
            return null
        }
        // (x + b/3a)³ = (b³ − 27a²d) / 27a³
        val hNum = -b
        val hDen = 3 * a
        val value = hNum.toDouble() / hDen + cbrt(kNum.toDouble() / kDen)
        val h = fractionText(hNum, hDen)
        val k = "∛" + fractionText(abs(kNum), abs(kDen))
        val neg = (kNum < 0) != (kDen < 0)
        val root = when {
            h == "0" -> (if (neg) Printer.MINUS else "") + k
            else -> h + (if (neg) " ${Printer.MINUS} " else " + ") + k
        }
        return listOf(Root(root, value))
    }

    /** p/q with p | d and q | a that makes the cubic vanish. */
    private fun rationalRoot(a: Long, b: Long, c: Long, d: Long): Pair<Long, Long>? {
        if (d == 0L) return 0L to 1L
        val ps = divisors(abs(d)) ?: return null
        val qs = divisors(abs(a)) ?: return null
        for (q in qs) for (p0 in ps) for (p in longArrayOf(p0, -p0)) {
            if (gcd(abs(p), q) != 1L) continue
            // a·p³ + b·p²·q + c·p·q² + d·q³ = 0
            val v = a.toDouble() * p * p * p + b.toDouble() * p * p * q + c.toDouble() * p * q * q + d.toDouble() * q * q * q
            if (abs(v) < 0.5) return p to q
        }
        return null
    }

    private fun divisors(n: Long): List<Long>? {
        if (n == 0L || n > 1_000_000) return null
        return (1L..n).filter { n % it == 0L }
    }

    /** √n = k·√m with m square-free. */
    private fun surd(n: Long): Pair<Long, Long> {
        var k = 1L
        var m = n
        var f = 2L
        while (f * f <= m) {
            while (m % (f * f) == 0L) {
                m /= f * f
                k *= f
            }
            f++
        }
        return k to m
    }

    /** (p + q√m) / den, reduced and written the way it is read: 1 − √2, (3 + √5)/2. */
    private fun surdText(p0: Long, q0: Long, m: Long, den0: Long): String {
        var p = p0
        var q = q0
        var den = den0
        if (den < 0) {
            p = -p
            q = -q
            den = -den
        }
        val g = gcd(gcd(abs(p), abs(q)), den).coerceAtLeast(1)
        p /= g
        q /= g
        den /= g
        val root = (if (abs(q) == 1L) "" else abs(q).toString()) + "√$m"
        val sign = if (q < 0) Printer.MINUS else "+"
        val body = when {
            p == 0L -> (if (q < 0) Printer.MINUS else "") + root
            else -> "${Printer.value(p.toDouble())} $sign $root"
        }
        return when {
            den == 1L -> body
            p == 0L -> "$body/$den"
            else -> "($body)/$den"
        }
    }

    private fun fractionText(p: Long, q: Long): String {
        val g = gcd(abs(p), abs(q)).coerceAtLeast(1)
        val (pp, qq) = (p / g) to (q / g)
        val (np, nq) = if (qq < 0) -pp to -qq else pp to qq
        return if (nq == 1L) Printer.value(np.toDouble()) else (if (np < 0) Printer.MINUS else "") + "${abs(np)}/$nq"
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b
}
