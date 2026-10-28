package dev.inknotes.math

import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.max
import kotlin.math.sinh
import kotlin.math.sqrt

/** Real solutions of equations `residual = 0` in the given unknowns. */
object Solver {

    sealed class Result {
        /** Distinct solutions, each a value per unknown; [more] when some were left out. */
        class Points(val solutions: List<DoubleArray>, val more: Boolean) : Result()
        data object NoSolution : Result()
        data object Infinite : Result()
    }

    fun solve(residuals: List<Expr>, unknowns: List<String>): Result {
        if (unknowns.isEmpty()) throw MathError("нет неизвестных")
        val f = { x: DoubleArray ->
            val vars = HashMap<String, Double>()
            unknowns.forEachIndexed { i, n -> vars[n] = x[i] }
            DoubleArray(residuals.size) { k -> runCatching { Numeric.scalar(residuals[k], vars) }.getOrDefault(Double.NaN) }
        }
        return solve(f, residuals.size, unknowns.size)
    }

    private fun solve(f: (DoubleArray) -> DoubleArray, m: Int, n: Int): Result {
        linear(f, m, n)?.let { return it }
        if (m < n) return Result.Infinite
        if (n == 1 && m == 1) return oneVariable { x -> f(doubleArrayOf(x))[0] }
        eliminate(f, m, n)?.let { return it }
        return newton(f, n)
    }

    /**
     * An equation linear in one unknown (x + y = 1488) gives that unknown from the others;
     * the rest is solved in one unknown fewer, on the wide grid when one is left. Newton from a
     * few starting points would miss roots in the hundreds.
     */
    private fun eliminate(f: (DoubleArray) -> DoubleArray, m: Int, n: Int): Result? {
        val probes = listOf(DoubleArray(n) { 0.37 + it }, DoubleArray(n) { -1.9 + 0.6 * it }, DoubleArray(n) { 3.1 - it })
        for (i in 0 until m) for (j in 0 until n) {
            fun at(x: DoubleArray, t: Double) = f(x.copyOf().also { it[j] = t })[i]
            val linear = probes.all { x ->
                val g0 = at(x, 0.0)
                val g1 = at(x, 1.0)
                val slope = g1 - g0
                g0.isFinite() && g1.isFinite() && abs(slope) > 1e-12 &&
                    doubleArrayOf(2.7, -1.9, 13.0).all { t -> abs(at(x, t) - (g0 + slope * t)) <= 1e-9 * (1 + abs(g0) + abs(slope * t)) }
            }
            if (!linear) continue
            // x_j from equation i, given the other unknowns.
            fun full(rest: DoubleArray): DoubleArray {
                val x = DoubleArray(n)
                var k = 0
                for (q in 0 until n) if (q != j) x[q] = rest[k++]
                val g0 = at(x, 0.0)
                val slope = at(x, 1.0) - g0
                x[j] = -g0 / slope
                return x
            }
            val reduced = { rest: DoubleArray ->
                val y = f(full(rest))
                DoubleArray(m - 1) { k -> y[if (k < i) k else k + 1] }
            }
            return when (val res = solve(reduced, m - 1, n - 1)) {
                is Result.Points -> Result.Points(res.solutions.map { p -> full(p).map(::clean).toDoubleArray() }, res.more)
                else -> res
            }
        }
        return null
    }

    // ------------------------------------------------------------ linear systems

    /** Solves the system when it is linear (checked at sample points), otherwise null. */
    private fun linear(f: (DoubleArray) -> DoubleArray, m: Int, n: Int): Result? {
        val c = f(DoubleArray(n))
        if (c.any { !it.isFinite() }) return null
        val a = Array(m) { DoubleArray(n) }
        for (j in 0 until n) {
            val col = f(DoubleArray(n) { if (it == j) 1.0 else 0.0 })
            for (i in 0 until m) a[i][j] = col[i] - c[i]
        }
        for (probe in PROBES) {
            val x = DoubleArray(n) { probe[it % probe.size] * (1 + it * 0.37) }
            val y = f(x)
            for (i in 0 until m) {
                var lin = c[i]
                for (j in 0 until n) lin += a[i][j] * x[j]
                if (!y[i].isFinite() || abs(y[i] - lin) > 1e-8 * (1 + abs(y[i]))) return null
            }
        }
        // Gauss–Jordan on [A | -c].
        val aug = Array(m) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else -c[i] } }
        val scale = max(aug.maxOf { r -> r.maxOf { abs(it) } }, 1e-300)
        var row = 0
        val pivotCol = IntArray(m) { -1 }
        for (col in 0 until n) {
            if (row == m) break
            var p = row
            for (r in row + 1 until m) if (abs(aug[r][col]) > abs(aug[p][col])) p = r
            if (abs(aug[p][col]) < 1e-10 * scale) continue
            aug[p] = aug[row].also { aug[row] = aug[p] }
            val piv = aug[row][col]
            for (k in 0..n) aug[row][k] /= piv
            for (r in 0 until m) {
                if (r == row) continue
                val fct = aug[r][col]
                if (fct != 0.0) for (k in 0..n) aug[r][k] -= fct * aug[row][k]
            }
            pivotCol[row] = col
            row++
        }
        for (r in row until m) if (abs(aug[r][n]) > 1e-9 * scale) return Result.NoSolution
        if (row < n) return Result.Infinite
        val x = DoubleArray(n)
        for (r in 0 until row) x[pivotCol[r]] = aug[r][n]
        return Result.Points(listOf(x.map(::clean).toDoubleArray()), false)
    }

    // ------------------------------------------------------------ one unknown

    /** All real roots found on a wide grid (dense near zero), sign changes and touching roots. */
    private fun oneVariable(f: (Double) -> Double): Result {
        val umax = asinh(RANGE)
        val roots = roots(f, DoubleArray(GRID + 1) { sinh(-umax + 2 * umax * it / GRID) })
        if (roots.isEmpty()) return Result.NoSolution
        roots.sortBy { abs(it) }
        val more = roots.size > MAX_ROOTS
        val kept = roots.take(MAX_ROOTS).sorted()
        return Result.Points(kept.map { doubleArrayOf(clean(it)) }, more)
    }

    /** Roots of [f] between the points [xs]: sign changes, and touching roots (sin x = 1). */
    fun roots(f: (Double) -> Double, xs: DoubleArray): MutableList<Double> {
        val n = xs.size - 1
        val ys = DoubleArray(n + 1) { f(xs[it]) }
        val roots = ArrayList<Double>()
        fun addRoot(r: Double) {
            if (roots.none { abs(it - r) <= 1e-7 * max(1.0, abs(r)) }) roots += r
        }
        for (k in 0 until n) {
            val ya = ys[k]
            val yb = ys[k + 1]
            if (!ya.isFinite() || !yb.isFinite()) continue
            if (ya == 0.0) {
                addRoot(xs[k])
                continue
            }
            if (ya * yb < 0) {
                var lo = xs[k]
                var hi = xs[k + 1]
                var flo = ya
                repeat(200) {
                    val mid = (lo + hi) / 2
                    if (mid == lo || mid == hi) return@repeat
                    val fm = f(mid)
                    if (!fm.isFinite()) return@repeat
                    if (fm == 0.0) { lo = mid; hi = mid; return@repeat }
                    if ((fm < 0) == (flo < 0)) { lo = mid; flo = fm } else hi = mid
                }
                val r = (lo + hi) / 2
                val fr = f(r)
                // A pole changes sign too, but grows instead of vanishing.
                if (fr.isFinite() && abs(fr) <= 1e-6 * (abs(ya) + abs(yb))) addRoot(r)
            } else if (k > 0 && ys[k - 1].isFinite() && abs(ya) < abs(ys[k - 1]) && abs(ya) <= abs(yb)) {
                // Local minimum of |f| without a sign change: maybe a double root.
                val r = goldenMin({ abs(f(it)) }, xs[k - 1], xs[k + 1])
                val fr = abs(f(r))
                if (fr.isFinite() && fr < 1e-10 * (1 + abs(ys[k - 1]) + abs(yb))) addRoot(r)
            }
        }
        if (ys[n].isFinite() && ys[n] == 0.0) addRoot(xs[n])
        return roots
    }

    private fun goldenMin(g: (Double) -> Double, a0: Double, b0: Double): Double {
        var a = a0
        var b = b0
        val r = (sqrt(5.0) - 1) / 2
        var c = b - r * (b - a)
        var d = a + r * (b - a)
        repeat(120) {
            if (g(c) < g(d)) b = d else a = c
            c = b - r * (b - a)
            d = a + r * (b - a)
        }
        return (a + b) / 2
    }

    // ------------------------------------------------------------ nonlinear systems

    /** Damped Newton (Gauss–Newton for extra equations) from a grid of starting points. */
    private fun newton(f: (DoubleArray) -> DoubleArray, n: Int): Result {
        val starts = ArrayList<DoubleArray>()
        if (n <= 3) {
            val grid = doubleArrayOf(-7.0, -2.0, -0.5, 0.7, 3.0, 9.0)
            var count = 1
            repeat(n) { count *= grid.size }
            for (k in 0 until count) {
                var rest = k
                starts += DoubleArray(n) { (grid[rest % grid.size]).also { rest /= grid.size } }
            }
        } else {
            val rnd = java.util.Random(7)
            repeat(300) { starts += DoubleArray(n) { rnd.nextGaussian() * 5 } }
        }
        val found = ArrayList<DoubleArray>()
        for (s in starts) {
            val x = refine(f, s, n) ?: continue
            if (found.none { p -> p.indices.all { abs(p[it] - x[it]) <= 1e-6 * max(1.0, abs(x[it])) } }) found += x
            if (found.size > MAX_ROOTS) break
        }
        if (found.isEmpty()) return Result.NoSolution
        found.sortWith(compareBy<DoubleArray> { it[0] }.thenBy { it.getOrElse(1) { 0.0 } })
        return Result.Points(found.take(MAX_ROOTS).map { p -> p.map(::clean).toDoubleArray() }, found.size > MAX_ROOTS)
    }

    private fun norm(v: DoubleArray): Double {
        var s = 0.0
        for (x in v) s += x * x
        return sqrt(s)
    }

    private fun refine(f: (DoubleArray) -> DoubleArray, start: DoubleArray, n: Int): DoubleArray? {
        var x = start.copyOf()
        var fx = f(x)
        if (fx.any { !it.isFinite() }) return null
        val m = fx.size
        for (step in 0 until 80) {
            if (norm(fx) < 1e-13) break
            // Jacobian by central differences.
            val j = Array(m) { DoubleArray(n) }
            for (k in 0 until n) {
                val h = 1e-6 * max(1.0, abs(x[k]))
                val xp = x.copyOf().also { it[k] += h }
                val xm = x.copyOf().also { it[k] -= h }
                val fp = f(xp)
                val fm = f(xm)
                for (i in 0 until m) j[i][k] = (fp[i] - fm[i]) / (2 * h)
            }
            // Normal equations JᵀJ·dx = −Jᵀf (the same as J·dx = −f for a square system).
            val a = Array(n) { r -> DoubleArray(n) { c -> (0 until m).sumOf { j[it][r] * j[it][c] } } }
            val b = DoubleArray(n) { r -> -(0 until m).sumOf { j[it][r] * fx[it] } }
            val dx = solveSquare(a, b) ?: return null
            var t = 1.0
            val before = norm(fx)
            while (true) {
                val xn = DoubleArray(n) { x[it] + t * dx[it] }
                val fn = f(xn)
                if (fn.all { it.isFinite() } && norm(fn) < before) {
                    x = xn
                    fx = fn
                    break
                }
                t /= 2
                // No step helps: further steps from the same point would fail the same way.
                if (t < 1e-6) break
            }
            if (t < 1e-6) break
        }
        val scale = 1 + x.maxOf { abs(it) }
        return if (norm(fx) < 1e-9 * scale && x.all { it.isFinite() && abs(it) < 1e9 }) x else null
    }

    private fun solveSquare(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var p = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[p][col])) p = r
            if (abs(m[p][col]) < 1e-300) return null
            m[p] = m[col].also { m[col] = m[p] }
            for (r in 0 until n) {
                if (r == col) continue
                val fct = m[r][col] / m[col][col]
                for (k in col..n) m[r][k] -= fct * m[col][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** Rounds away the last bits of numeric noise: 2.9999999999 → 3. */
    fun clean(v: Double): Double {
        if (abs(v) < 1e-11) return 0.0
        val r = Math.round(v).toDouble()
        if (abs(v - r) < 1e-9 * max(1.0, abs(v))) return r
        return v
    }

    private val PROBES = listOf(doubleArrayOf(1.3, -0.7, 2.1), doubleArrayOf(-2.9, 0.45, 5.3))
    private const val GRID = 6000
    private const val RANGE = 1e5
    private const val MAX_ROOTS = 8
}
