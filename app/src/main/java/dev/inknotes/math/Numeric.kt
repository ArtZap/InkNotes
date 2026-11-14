package dev.inknotes.math

import dev.inknotes.data.tr
import dev.inknotes.math.Expr.Add
import dev.inknotes.math.Expr.Fn
import dev.inknotes.math.Expr.Integral
import dev.inknotes.math.Expr.Mat
import dev.inknotes.math.Expr.Mul
import dev.inknotes.math.Expr.Num
import dev.inknotes.math.Expr.Pow
import dev.inknotes.math.Expr.Sym
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

sealed interface Value

data class Scalar(val v: Double) : Value

class Matrix(val rows: Int, val cols: Int, val a: DoubleArray) : Value {
    operator fun get(i: Int, j: Int) = a[i * cols + j]

    companion object {
        fun identity(n: Int) = Matrix(n, n, DoubleArray(n * n) { if (it / n == it % n) 1.0 else 0.0 })
    }
}

/** Numeric evaluation of resolved expressions (no user functions or unknowns left). */
object Numeric {

    fun eval(e: Expr, vars: Map<String, Double> = emptyMap()): Value = when (e) {
        is Num -> Scalar(e.v)
        is Sym -> Scalar(
            vars[e.name] ?: when (e.name) {
                "pi" -> PI
                "e" -> Math.E
                else -> throw MathError(tr("неизвестно ${e.name}", "${e.name} is unknown"))
            },
        )
        is Add -> e.terms.map { eval(it, vars) }.reduce(::plus)
        is Mul -> e.factors.map { eval(it, vars) }.reduce(::times)
        is Pow -> power(e, vars)
        is Fn -> {
            val args = e.args.map { eval(it, vars) }
            val m = args.singleOrNull() as? Matrix
            when {
                m != null && (e.name == "det" || e.name == "abs") -> Scalar(det(m))
                m != null && e.name == "transpose" -> transpose(m)
                args.any { it is Matrix } -> throw MathError(tr("${e.name} от матрицы", "${e.name} of a matrix"))
                else -> Scalar(function(e.name, args.map { (it as Scalar).v }))
            }
        }
        is Mat -> {
            val r = e.rows.size
            val c = e.rows[0].size
            Matrix(r, c, DoubleArray(r * c) { scalar(e.rows[it / c][it % c], vars) })
        }
        is Integral -> {
            val lo = scalar(e.lo ?: throw MathError(tr("неопределённый интеграл", "indefinite integral")), vars)
            val hi = scalar(e.hi!!, vars)
            val inner = HashMap(vars)
            Scalar(integrate({ t -> inner[e.v] = t; scalar(e.body, inner) }, lo, hi))
        }
        is Expr.Apply, is Expr.Deriv -> throw MathError(tr("не вычисляется", "cannot be computed"))
    }

    fun scalar(e: Expr, vars: Map<String, Double>): Double =
        (eval(e, vars) as? Scalar)?.v ?: throw MathError(tr("ожидалось число, а не матрица", "expected a number, not a matrix"))

    private fun plus(a: Value, b: Value): Value = when {
        a is Scalar && b is Scalar -> Scalar(a.v + b.v)
        a is Matrix && b is Matrix -> {
            if (a.rows != b.rows || a.cols != b.cols) throw MathError(tr("размеры матриц не совпадают", "matrix sizes differ"))
            Matrix(a.rows, a.cols, DoubleArray(a.a.size) { a.a[it] + b.a[it] })
        }
        else -> throw MathError(tr("число + матрица", "number + matrix"))
    }

    private fun times(a: Value, b: Value): Value = when {
        a is Scalar && b is Scalar -> Scalar(a.v * b.v)
        a is Scalar && b is Matrix -> Matrix(b.rows, b.cols, DoubleArray(b.a.size) { a.v * b.a[it] })
        a is Matrix && b is Scalar -> Matrix(a.rows, a.cols, DoubleArray(a.a.size) { a.a[it] * b.v })
        else -> matmul(a as Matrix, b as Matrix)
    }

    private fun matmul(a: Matrix, b: Matrix): Matrix {
        if (a.cols != b.rows) throw MathError(tr("матрицы ${a.rows}×${a.cols} и ${b.rows}×${b.cols} не перемножаются", "${a.rows}×${a.cols} and ${b.rows}×${b.cols} matrices cannot be multiplied"))
        return Matrix(a.rows, b.cols, DoubleArray(a.rows * b.cols) { idx ->
            val i = idx / b.cols
            val j = idx % b.cols
            var s = 0.0
            for (k in 0 until a.cols) s += a[i, k] * b[k, j]
            s
        })
    }

    private fun power(e: Pow, vars: Map<String, Double>): Value {
        val base = eval(e.base, vars)
        if (base is Matrix) {
            if (e.exp == Sym("T") && "T" !in vars) return transpose(base)
            val n = scalar(e.exp, vars)
            if (n != round(n) || abs(n) > MAX_MATRIX_POWER) throw MathError(tr("степень матрицы", "matrix power"))
            if (base.rows != base.cols) throw MathError(tr("матрица не квадратная", "the matrix is not square"))
            val m = if (n < 0) inverse(base) else base
            var r = Matrix.identity(base.rows)
            repeat(abs(n).toInt()) { r = matmul(r, m) }
            return r
        }
        val b = (base as Scalar).v
        val x = scalar(e.exp, vars)
        return Scalar(pow(b, x))
    }

    /** Real power; an odd root of a negative number stays real: (−8)^(1/3) = −2. */
    fun pow(b: Double, x: Double): Double {
        if (b < 0 && x != round(x)) {
            val q = round(1 / x)
            if (abs(1 / x - q) < 1e-9 && q.toLong() % 2L != 0L) return -Math.pow(-b, x)
            val r3 = round(3 / x)
            if (abs(3 / x - r3) < 1e-9 && r3.toLong() % 2L != 0L) return -Math.pow(-b, x)
            throw MathError(tr("не определено", "undefined"))
        }
        if (b == 0.0 && x < 0) throw MathError(tr("деление на ноль", "division by zero"))
        return Math.pow(b, x)
    }

    fun function(name: String, a: List<Double>): Double {
        val x = a.last()
        fun domain(ok: Boolean) {
            if (!ok) throw MathError(tr("не определено", "undefined"))
        }
        if (a.size != 1 && name != "log" && name != "max" && name != "min") throw MathError(tr("$name: аргументы", "$name: arguments"))
        return when (name) {
            "sin" -> Math.sin(x)
            "cos" -> Math.cos(x)
            "tan" -> { domain(abs(Math.cos(x)) > 1e-15); Math.tan(x) }
            "cot" -> { domain(abs(Math.sin(x)) > 1e-15); 1 / Math.tan(x) }
            "asin" -> { domain(x in -1.0..1.0); Math.asin(x) }
            "acos" -> { domain(x in -1.0..1.0); Math.acos(x) }
            "atan" -> Math.atan(x)
            "acot" -> PI / 2 - Math.atan(x)
            "sinh" -> Math.sinh(x)
            "cosh" -> Math.cosh(x)
            "tanh" -> Math.tanh(x)
            "ln" -> { domain(x > 0); Math.log(x) }
            "lg" -> { domain(x > 0); Math.log10(x) }
            "log" -> {
                if (a.size != 2) throw MathError(tr("log: аргументы", "log: arguments"))
                domain(x > 0 && a[0] > 0 && a[0] != 1.0)
                Math.log(x) / Math.log(a[0])
            }
            "exp" -> Math.exp(x)
            "sqrt" -> { domain(x >= 0); Math.sqrt(x) }
            "abs" -> abs(x)
            "fact" -> {
                domain(x >= 0 && x == round(x) && x <= 170)
                var r = 1.0
                for (k in 2..x.toInt()) r *= k
                r
            }
            "max" -> a.max()
            "min" -> a.min()
            "det" -> x
            else -> throw MathError(tr("неизвестная функция $name", "unknown function $name"))
        }
    }

    // ------------------------------------------------------------ matrices

    fun transpose(m: Matrix) = Matrix(m.cols, m.rows, DoubleArray(m.a.size) { m[it % m.rows, it / m.rows] })

    fun det(m: Matrix): Double {
        if (m.rows != m.cols) throw MathError(tr("определитель неквадратной матрицы", "determinant of a non-square matrix"))
        val n = m.rows
        val a = m.a.copyOf()
        var d = 1.0
        for (col in 0 until n) {
            var p = col
            for (r in col + 1 until n) if (abs(a[r * n + col]) > abs(a[p * n + col])) p = r
            if (a[p * n + col] == 0.0) return 0.0
            if (p != col) {
                for (k in 0 until n) a[p * n + k] = a[col * n + k].also { a[col * n + k] = a[p * n + k] }
                d = -d
            }
            d *= a[col * n + col]
            for (r in col + 1 until n) {
                val f = a[r * n + col] / a[col * n + col]
                for (k in col until n) a[r * n + k] -= f * a[col * n + k]
            }
        }
        return d
    }

    fun inverse(m: Matrix): Matrix {
        if (m.rows != m.cols) throw MathError(tr("обратная к неквадратной матрице", "inverse of a non-square matrix"))
        val n = m.rows
        val a = m.a.copyOf()
        val inv = Matrix.identity(n).a
        val scale = max(m.a.maxOf { abs(it) }, 1e-300)
        for (col in 0 until n) {
            var p = col
            for (r in col + 1 until n) if (abs(a[r * n + col]) > abs(a[p * n + col])) p = r
            if (abs(a[p * n + col]) < 1e-12 * scale) throw MathError(tr("матрица вырожденная", "the matrix is singular"))
            for (k in 0 until n) {
                a[p * n + k] = a[col * n + k].also { a[col * n + k] = a[p * n + k] }
                inv[p * n + k] = inv[col * n + k].also { inv[col * n + k] = inv[p * n + k] }
            }
            val piv = a[col * n + col]
            for (k in 0 until n) {
                a[col * n + k] /= piv
                inv[col * n + k] /= piv
            }
            for (r in 0 until n) {
                if (r == col) continue
                val f = a[r * n + col]
                if (f == 0.0) continue
                for (k in 0 until n) {
                    a[r * n + k] -= f * a[col * n + k]
                    inv[r * n + k] -= f * inv[col * n + k]
                }
            }
        }
        return Matrix(n, n, inv)
    }

    // ------------------------------------------------------------ integrals

    /** Adaptive Gauss–Kronrod (7/15), infinite limits by a change of variable. */
    fun integrate(f: (Double) -> Double, a: Double, b: Double): Double {
        if (a == b) return 0.0
        if (a > b) return -integrate(f, b, a)
        val budget = intArrayOf(MAX_EVALUATIONS)
        val g: (Double) -> Double
        val lo: Double
        val hi: Double
        when {
            a.isInfinite() && b.isInfinite() -> {
                g = { t -> val d = 1 - t * t; f(t / d) * (1 + t * t) / (d * d) }
                lo = -1.0; hi = 1.0
            }
            b.isInfinite() -> {
                g = { t -> val d = 1 - t; f(a + t / d) / (d * d) }
                lo = 0.0; hi = 1.0
            }
            a.isInfinite() -> {
                g = { t -> f(b - (1 - t) / t) / (t * t) }
                lo = 0.0; hi = 1.0
            }
            else -> {
                g = f
                lo = a; hi = b
            }
        }
        val (whole, _) = kronrod(g, lo, hi, budget)
        val r = adaptive(g, lo, hi, whole, 0, budget)
        if (!r.isFinite()) throw MathError(tr("интеграл расходится", "the integral diverges"))
        return r
    }

    private fun adaptive(g: (Double) -> Double, a: Double, b: Double, whole: Double, depth: Int, budget: IntArray): Double {
        val m = (a + b) / 2
        val (left, el) = kronrod(g, a, m, budget)
        val (right, er) = kronrod(g, m, b, budget)
        val sum = left + right
        val err = abs(sum - whole) + el + er
        if (err <= max(1e-11, 1e-11 * abs(sum))) return sum
        if (depth >= MAX_DEPTH || budget[0] <= 0) {
            // Still far from converged: a divergent integral, not a precision problem.
            if (err > 1e-6 * max(1.0, abs(sum))) throw MathError(tr("интеграл расходится", "the integral diverges"))
            return sum
        }
        return adaptive(g, a, m, left, depth + 1, budget) + adaptive(g, m, b, right, depth + 1, budget)
    }

    private fun kronrod(g: (Double) -> Double, a: Double, b: Double, budget: IntArray): Pair<Double, Double> {
        budget[0] -= 15
        val c = (a + b) / 2
        val h = (b - a) / 2
        val fc = g(c)
        if (!fc.isFinite()) throw MathError(tr("интеграл расходится", "the integral diverges"))
        var k = fc * WGK[7]
        var gs = fc * WG[3]
        for (j in 0 until 7) {
            val dx = h * XGK[j]
            val f1 = g(c - dx)
            val f2 = g(c + dx)
            if (!f1.isFinite() || !f2.isFinite()) throw MathError(tr("интеграл расходится", "the integral diverges"))
            k += WGK[j] * (f1 + f2)
            if (j % 2 == 1) gs += WG[j / 2] * (f1 + f2)
        }
        return k * h to abs((k - gs) * h)
    }

    private val XGK = doubleArrayOf(
        0.991455371120812639206854697526329, 0.949107912342758524526189684047851,
        0.864864423359769072789712788640926, 0.741531185599394439863864773280788,
        0.586087235467691130294144845693013, 0.405845151377397166906606412076961,
        0.207784955007898467600689403773245, 0.0,
    )
    private val WGK = doubleArrayOf(
        0.022935322010529224963732008058970, 0.063092092629978553290700663189204,
        0.104790010322250183839876322541518, 0.140653259715525918745189590510238,
        0.169004726639267902826583426598550, 0.190350578064785409913256402421014,
        0.204432940075298892414161999234649, 0.209482141084727828012999174891714,
    )
    private val WG = doubleArrayOf(
        0.129484966168869693270611432679082, 0.279705391489276667901467771423780,
        0.381830050505118944950369775488975, 0.417959183673469387755102040816327,
    )

    private const val MAX_DEPTH = 40
    private const val MAX_EVALUATIONS = 400_000
    private const val MAX_MATRIX_POWER = 1000
}
