package dev.inknotes.math

import dev.inknotes.math.Expr.Add
import dev.inknotes.math.Expr.Fn
import dev.inknotes.math.Expr.Mul
import dev.inknotes.math.Expr.Num
import dev.inknotes.math.Expr.Pow
import dev.inknotes.math.Expr.Sym
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.round

/** Results as they are written in a Russian school notebook: decimal comma, x², √, tg. */
object Printer {

    const val MINUS = "−"

    /** How rational answers are written: 1/3 and 5/2, or 0,333333 and 2,5. */
    enum class Numbers { MIXED, DECIMAL, FRACTION }

    /** Set from the settings; read on the analysis thread. MIXED keeps 2,5 and writes 1/3. */
    @Volatile
    var numbers = Numbers.MIXED
    private const val SIGNIFICANT = 6

    fun number(v: Double): String {
        if (v.isNaN()) throw MathError("не определено")
        if (v.isInfinite()) return if (v > 0) "∞" else "$MINUS∞"
        val x = Solver.clean(v)
        if (x == 0.0) return "0"
        val sign = if (x < 0) MINUS else ""
        val a = abs(x)
        if (a == floor(a) && a < 1e15) return sign + a.toLong().toString()
        if (a >= 1e-4 && a < 1e15) {
            val s = BigDecimal(a).round(MathContext(SIGNIFICANT)).stripTrailingZeros().toPlainString()
            return sign + s.replace('.', ',')
        }
        val exp = floor(log10(a)).toInt()
        val mant = BigDecimal(a / Math.pow(10.0, exp.toDouble())).round(MathContext(4)).stripTrailingZeros().toPlainString()
        return sign + mant.replace('.', ',') + "·10" + superscript(exp.toString())
    }

    /** A result: 1/3 rather than 0,333333 when the decimal would not end; 2,5 stays decimal. */
    fun value(v: Double): String {
        if (numbers == Numbers.DECIMAL) return number(v)
        val f = fraction(v)
        if (numbers == Numbers.FRACTION && f != null && f.second in 2..1000) {
            return (if (f.first < 0) MINUS else "") + "${abs(f.first)}/${f.second}"
        }
        if (f != null && f.second in 2..100) {
            var q = f.second
            while (q % 2 == 0L) q /= 2
            while (q % 5 == 0L) q /= 5
            if (q != 1L) return (if (f.first < 0) MINUS else "") + "${abs(f.first)}/${f.second}"
        }
        return number(v)
    }

    /** p/q with q ≤ 1000 if [v] is (numerically) that fraction. */
    fun fraction(v: Double): Pair<Long, Long>? {
        if (!v.isFinite() || abs(v) > 1e9) return null
        for (q in 1L..1000L) {
            val p = round(v * q)
            if (abs(v * q - p) < 1e-9 * q) return p.toLong() to q
        }
        return null
    }

    fun superscript(s: String): String = buildString {
        for (c in s) {
            append(
                when (c) {
                    '0' -> '⁰'; '1' -> '¹'; '2' -> '²'; '3' -> '³'; '4' -> '⁴'
                    '5' -> '⁵'; '6' -> '⁶'; '7' -> '⁷'; '8' -> '⁸'; '9' -> '⁹'
                    '-', '−' -> '⁻'; 'x' -> 'ˣ'; 'n' -> 'ⁿ'; 'y' -> 'ʸ'
                    else -> return@superscript ""
                },
            )
        }
    }

    fun subscript(s: String): String = buildString {
        for (c in s) {
            if (c !in '0'..'9') return@subscript ""
            append('₀' + (c - '0'))
        }
    }

    /** Variable name as written: x1 → x₁. */
    fun name(n: String): String {
        if (n == "pi") return "π"
        val letters = n.takeWhile { !it.isDigit() }
        val digits = n.drop(letters.length)
        return letters + (subscript(digits).ifEmpty { digits })
    }

    fun expr(e: Expr): String = str(e, 0)

    private const val P_ADD = 1
    private const val P_MUL = 2
    private const val P_POW = 3

    private fun paren(s: String, need: Boolean) = if (need) "($s)" else s

    private fun str(e: Expr, prec: Int): String = when (e) {
        is Num -> {
            val s = numberOrFraction(e.v)
            paren(s, (e.v < 0 && prec > P_ADD) || (s.contains('/') && prec >= P_MUL))
        }
        is Sym -> name(e.name)
        is Add -> paren(sum(e), prec > P_ADD)
        is Mul -> product(e, prec)
        is Pow -> power(e, prec)
        is Fn -> function(e)
        is Expr.Mat, is Expr.Integral, is Expr.Apply, is Expr.Deriv -> throw MathError("не записать")
    }

    private fun numberOrFraction(v: Double): String {
        if (numbers == Numbers.DECIMAL) return number(v)
        val f = fraction(v)
        if (f != null && f.second in 2..100) {
            return (if (f.first < 0) MINUS else "") + "${abs(f.first)}/${f.second}"
        }
        return number(v)
    }

    private fun isNegative(t: Expr) = (t is Num && t.v < 0) || (t is Mul && t.factors.firstOrNull().let { it is Num && it.v < 0 })

    private fun negate(t: Expr): Expr = Algebra.mul(listOf(Expr.MINUS_ONE, t))

    private fun sum(e: Add): String = buildString {
        e.terms.forEachIndexed { i, t ->
            when {
                i == 0 -> append(str(t, P_ADD))
                isNegative(t) -> append(" $MINUS ").append(str(negate(t), P_ADD))
                else -> append(" + ").append(str(t, P_ADD))
            }
        }
    }

    private fun product(e: Mul, prec: Int): String {
        var coef = 1.0
        val num = ArrayList<Expr>()
        val den = ArrayList<Expr>()
        for (f in e.factors) {
            when {
                f is Num -> coef *= f.v
                f is Pow && f.exp is Num && (f.exp as Num).v < 0 -> den += Algebra.pow(f.base, Num(-(f.exp as Num).v))
                else -> num += f
            }
        }
        // Letters before functions and brackets: 2x·cos(x²), not 2cos(x²)·x.
        num.sortBy { if (it is Sym || (it is Pow && it.base is Sym)) 0 else 1 }
        val negative = coef < 0
        coef = abs(coef)
        val frac = fraction(coef)
        val (p, q) = if (frac != null && frac.second <= 1000) frac.first.toDouble() to frac.second.toDouble() else coef to 1.0
        val top = buildString {
            var afterNumber = false
            var prevSimple = false
            if (p != 1.0 || num.isEmpty()) {
                append(number(p))
                afterNumber = true
            }
            for (f in num) {
                val s = str(f, P_MUL)
                val simple = f is Sym || (f is Pow && f.base is Sym)
                // 3x, 2(x+1), 2√x, 2sin(x), xy, x²y; a dot otherwise.
                val juxtapose = (afterNumber && (s.first().isLetter() || s.first() == '(' || s.first() == '√')) || (prevSimple && simple)
                if (isNotEmpty() && !juxtapose) append('·')
                append(s)
                afterNumber = false
                prevSimple = simple
            }
        }
        val bottomParts = ArrayList<String>()
        if (q != 1.0) bottomParts += number(q)
        for (f in den) bottomParts += str(f, P_MUL)
        var s = top
        if (bottomParts.isNotEmpty()) {
            val bottom = bottomParts.joinToString("·")
            val needParen = bottomParts.size > 1 || den.any { it is Add || it is Mul }
            s = "${paren(top, num.size + (if (p != 1.0) 1 else 0) > 1 && top.contains(' '))}/${paren(bottom, needParen && !bottom.startsWith("("))}"
        }
        if (negative) s = MINUS + s
        return paren(s, prec > P_MUL || (negative && prec > P_ADD) || (bottomParts.isNotEmpty() && prec >= P_POW))
    }

    private fun power(e: Pow, prec: Int): String {
        val exp = e.exp
        if (exp is Num) {
            if (exp.v == 0.5) return "√" + paren(str(e.base, P_POW), e.base !is Sym && e.base !is Num)
            if (exp.v < 0) return paren("1/" + str(Algebra.pow(e.base, Num(-exp.v)), P_POW), prec >= P_MUL)
            if (exp.v == round(exp.v) && exp.v < 1000) {
                return str(e.base, P_POW + 1) + superscript(exp.v.toLong().toString())
            }
        }
        val base = if (e.base == Sym("e")) "e" else str(e.base, P_POW + 1)
        val sup = superscript(if (exp is Sym) exp.name else "")
        if (sup.isNotEmpty()) return base + sup
        return paren("$base^" + paren(str(exp, P_POW), exp !is Sym && !(exp is Num && exp.v >= 0)), prec > P_POW)
    }

    private val NAMES = mapOf(
        "tan" to "tg", "cot" to "ctg", "asin" to "arcsin", "acos" to "arccos", "atan" to "arctg", "acot" to "arcctg",
        "sinh" to "sh", "cosh" to "ch", "tanh" to "th",
    )

    private fun function(e: Fn): String {
        val a = e.args.last()
        return when (e.name) {
            "abs" -> "|${str(a, 0)}|"
            "sqrt" -> "√" + paren(str(a, P_POW), a !is Sym && a !is Num)
            "exp" -> str(Pow(Sym("e"), a), 0)
            "fact" -> str(a, P_POW + 1) + "!"
            "log" -> "log" + subscript(number(e.args[0].let { (it as? Num)?.v ?: throw MathError("log") })).ifEmpty { "_" + str(e.args[0], P_POW + 1) } + "(${str(a, 0)})"
            "ln" -> if (a is Fn && a.name == "abs") "ln${str(a, 0)}" else "ln(${str(a, 0)})"
            else -> (NAMES[e.name] ?: e.name) + "(" + e.args.joinToString("; ") { str(it, 0) } + ")"
        }
    }
}
