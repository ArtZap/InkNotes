package dev.inknotes.math

import dev.inknotes.data.tr
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
import kotlin.math.abs
import kotlin.math.round

/** Symbolic work: simplification, derivatives and the integrals of a table plus a few rules. */
object Algebra {

    /** Constants used while no variable of that name is defined. */
    val CONSTANTS = setOf("pi", "e")

    fun freeVars(e: Expr, out: MutableSet<String> = LinkedHashSet()): Set<String> {
        when (e) {
            is Num -> {}
            is Sym -> if (e.name !in CONSTANTS) out += e.name
            is Add -> e.terms.forEach { freeVars(it, out) }
            is Mul -> e.factors.forEach { freeVars(it, out) }
            is Pow -> { freeVars(e.base, out); freeVars(e.exp, out) }
            is Fn -> e.args.forEach { freeVars(it, out) }
            is Apply -> e.args.forEach { freeVars(it, out) }
            is Mat -> e.rows.forEach { r -> r.forEach { freeVars(it, out) } }
            is Integral -> {
                out += freeVars(e.body).filter { it != e.v }
                e.lo?.let { freeVars(it, out) }
                e.hi?.let { freeVars(it, out) }
            }
            is Deriv -> freeVars(e.body, out)
        }
        return out
    }

    /** Node count of [e], stopping early once it passes [limit]. */
    fun size(e: Expr, limit: Int): Int {
        var n = 0
        fun walk(x: Expr) {
            if (++n > limit) return
            when (x) {
                is Num, is Sym -> {}
                is Add -> x.terms.forEach(::walk)
                is Mul -> x.factors.forEach(::walk)
                is Pow -> { walk(x.base); walk(x.exp) }
                is Fn -> x.args.forEach(::walk)
                is Apply -> x.args.forEach(::walk)
                is Mat -> x.rows.forEach { r -> r.forEach(::walk) }
                is Integral -> { walk(x.body); x.lo?.let(::walk); x.hi?.let(::walk) }
                is Deriv -> walk(x.body)
            }
        }
        walk(e)
        return n
    }

    fun containsIntegral(e: Expr): Boolean = when (e) {
        is Integral -> true
        is Add -> e.terms.any(::containsIntegral)
        is Mul -> e.factors.any(::containsIntegral)
        is Pow -> containsIntegral(e.base) || containsIntegral(e.exp)
        is Fn -> e.args.any(::containsIntegral)
        is Apply -> e.args.any(::containsIntegral)
        is Mat -> e.rows.any { r -> r.any(::containsIntegral) }
        is Deriv -> containsIntegral(e.body)
        else -> false
    }

    fun has(e: Expr, v: String): Boolean = when (e) {
        is Num -> false
        is Sym -> e.name == v
        is Add -> e.terms.any { has(it, v) }
        is Mul -> e.factors.any { has(it, v) }
        is Pow -> has(e.base, v) || has(e.exp, v)
        is Fn -> e.args.any { has(it, v) }
        is Apply -> e.args.any { has(it, v) }
        is Mat -> e.rows.any { r -> r.any { has(it, v) } }
        is Integral -> (e.v != v && has(e.body, v)) || e.lo?.let { has(it, v) } == true || e.hi?.let { has(it, v) } == true
        is Deriv -> has(e.body, v)
    }

    fun substitute(e: Expr, v: String, value: Expr): Expr = when (e) {
        is Num -> e
        is Sym -> if (e.name == v) value else e
        is Add -> Add(e.terms.map { substitute(it, v, value) })
        is Mul -> Mul(e.factors.map { substitute(it, v, value) })
        is Pow -> Pow(substitute(e.base, v, value), substitute(e.exp, v, value))
        is Fn -> Fn(e.name, e.args.map { substitute(it, v, value) })
        is Apply -> Apply(e.name, e.primes, e.args.map { substitute(it, v, value) })
        is Mat -> Mat(e.rows.map { r -> r.map { substitute(it, v, value) } })
        is Integral -> Integral(
            if (e.v == v) e.body else substitute(e.body, v, value), e.v,
            e.lo?.let { substitute(it, v, value) }, e.hi?.let { substitute(it, v, value) },
        )
        is Deriv -> Deriv(substitute(e.body, v, value), e.v, e.order)
    }

    // ------------------------------------------------------------ simplify

    fun simplify(e: Expr): Expr = when (e) {
        is Num, is Sym, is Apply, is Deriv -> e
        is Add -> add(e.terms.map(::simplify))
        is Mul -> mul(e.factors.map(::simplify))
        is Pow -> pow(simplify(e.base), simplify(e.exp))
        is Fn -> fn(e.name, e.args.map(::simplify))
        is Mat -> Mat(e.rows.map { r -> r.map(::simplify) })
        is Integral -> Integral(simplify(e.body), e.v, e.lo?.let(::simplify), e.hi?.let(::simplify))
    }

    private fun isNum(e: Expr, v: Double) = e is Num && e.v == v

    /** Sum with like terms collected (`2x + 3x` → `5x`), constants last. */
    fun add(terms: List<Expr>): Expr {
        val flat = ArrayList<Expr>()
        for (t in terms) if (t is Add) flat += t.terms else flat += t
        var constant = 0.0
        val collected = LinkedHashMap<String, Pair<Expr, Double>>()
        for (t in flat) {
            if (t is Num) {
                constant += t.v
                continue
            }
            val (c, rest) = splitCoefficient(t)
            val key = rest.toString()
            val prev = collected[key]
            collected[key] = rest to (prev?.second ?: 0.0) + c
        }
        val out = ArrayList<Expr>()
        for ((rest, c) in collected.values) {
            if (abs(c) < 1e-14) continue
            out += if (c == 1.0) rest else mul(listOf(Num(c), rest))
        }
        out.sortByDescending(::degree)
        if (constant != 0.0 || out.isEmpty()) out += Num(constant)
        return if (out.size == 1) out[0] else Add(out)
    }

    /** Total degree of a term, used to order a sum the usual way (x² + x + 1). */
    private fun degree(e: Expr): Double = when (e) {
        is Sym -> 1.0
        is Pow -> if (e.exp is Num) (e.exp as Num).v * degree(e.base).coerceAtLeast(0.5) else 1.0
        is Mul -> e.factors.sumOf(::degree)
        is Fn -> 0.5
        else -> 0.0
    }

    fun splitCoefficient(t: Expr): Pair<Double, Expr> {
        if (t is Mul) {
            var c = 1.0
            val rest = ArrayList<Expr>()
            for (f in t.factors) if (f is Num) c *= f.v else rest += f
            return c to when (rest.size) {
                0 -> Expr.ONE
                1 -> rest[0]
                else -> Mul(rest)
            }
        }
        return 1.0 to t
    }

    /** Product with numbers folded and powers of the same base merged (`x·x²` → `x³`). */
    fun mul(factors: List<Expr>): Expr {
        val flat = ArrayList<Expr>()
        for (f in factors) if (f is Mul) flat += f.factors else flat += f
        var c = 1.0
        val bases = LinkedHashMap<String, Pair<Expr, MutableList<Expr>>>()
        for (f in flat) {
            when {
                f is Num -> c *= f.v
                f is Pow -> bases.getOrPut(f.base.toString()) { f.base to ArrayList() }.second += f.exp
                else -> bases.getOrPut(f.toString()) { f to ArrayList() }.second += Expr.ONE
            }
        }
        if (c == 0.0) return Expr.ZERO
        val out = ArrayList<Expr>()
        for ((base, exps) in bases.values) {
            when (val p = pow(base, add(exps))) {
                is Num -> c *= p.v
                is Mul -> for (f in p.factors) if (f is Num) c *= f.v else out += f
                else -> out += p
            }
        }
        if (c != 1.0 || out.isEmpty()) out.add(0, Num(c))
        return if (out.size == 1) out[0] else Mul(out)
    }

    fun pow(base: Expr, exp: Expr): Expr {
        if (isNum(exp, 0.0)) return Expr.ONE
        if (isNum(exp, 1.0)) return base
        if (isNum(base, 1.0)) return Expr.ONE
        if (base is Num && exp is Num) {
            val r = Math.pow(base.v, exp.v)
            if (exp.v == round(exp.v) && abs(exp.v) <= 64 && r.isFinite() && (exp.v >= 0 || base.v != 0.0)) {
                // Integer powers fold; 2^-1 becomes 0.5 and prints as 1/2.
                return Num(r)
            }
            if (r.isFinite() && abs(r - round(r)) < 1e-12) return Num(round(r))
            return Pow(base, exp)
        }
        if (base is Pow && exp is Num && exp.v == round(exp.v)) return pow(base.base, mul(listOf(base.exp, exp)))
        if (base is Mul && exp is Num && exp.v == round(exp.v)) return mul(base.factors.map { pow(it, exp) })
        return Pow(base, exp)
    }

    fun fn(name: String, args: List<Expr>): Expr {
        if (args.all { it is Num }) {
            val r = runCatching { Numeric.function(name, args.map { (it as Num).v }) }.getOrNull()
            if (r != null && r.isFinite() && abs(r - round(r)) < 1e-12) return Num(round(r))
        }
        if (name == "ln" && args[0] == Sym("e")) return Expr.ONE
        return Fn(name, args)
    }

    /** Multiplies out products of sums and small integer powers of sums. */
    fun expand(e: Expr): Expr = when (e) {
        is Add -> add(e.terms.map(::expand))
        is Mul -> {
            var terms: List<Expr> = listOf(Expr.ONE)
            for (f in e.factors.map(::expand)) {
                val parts = if (f is Add) f.terms else listOf(f)
                if (terms.size * parts.size > MAX_EXPANDED_TERMS) throw MathError(tr("слишком длинно", "too long"))
                terms = terms.flatMap { t -> parts.map { p -> mul(listOf(t, p)) } }
            }
            add(terms)
        }
        is Pow -> {
            val n = (e.exp as? Num)?.v
            if (e.base is Add && n != null && n == round(n) && n in 2.0..12.0) {
                expand(Mul(List(n.toInt()) { e.base }))
            } else {
                e
            }
        }
        else -> e
    }

    // ------------------------------------------------------------ derivatives

    fun derivative(e: Expr, v: String): Expr = simplify(d(e, v))

    private fun d(e: Expr, v: String): Expr = when (e) {
        is Num -> Expr.ZERO
        is Sym -> if (e.name == v) Expr.ONE else Expr.ZERO
        is Add -> add(e.terms.map { d(it, v) })
        is Mul -> add(
            e.factors.indices.map { i ->
                mul(e.factors.mapIndexed { j, f -> if (i == j) d(f, v) else f })
            },
        )
        is Pow -> {
            val bv = has(e.base, v)
            val ev = has(e.exp, v)
            when {
                !bv && !ev -> Expr.ZERO
                !ev -> mul(listOf(e.exp, pow(e.base, add(listOf(e.exp, Expr.MINUS_ONE))), d(e.base, v)))
                !bv -> mul(listOf(e, Fn("ln", listOf(e.base)), d(e.exp, v)))
                else -> mul(
                    listOf(
                        e,
                        add(
                            listOf(
                                mul(listOf(d(e.exp, v), Fn("ln", listOf(e.base)))),
                                mul(listOf(e.exp, d(e.base, v), Pow(e.base, Expr.MINUS_ONE))),
                            ),
                        ),
                    ),
                )
            }
        }
        is Fn -> {
            val u = e.args.last()
            val du = d(u, v)
            if (isNum(simplify(du), 0.0)) {
                Expr.ZERO
            } else {
                mul(listOf(outer(e, u), du))
            }
        }
        is Mat -> Mat(e.rows.map { r -> r.map { d(it, v) } })
        is Integral, is Apply, is Deriv -> throw MathError(tr("производная", "derivative"))
    }

    /** f'(u) for a function f(u). */
    private fun outer(f: Fn, u: Expr): Expr {
        val inv = Expr.MINUS_ONE
        fun p(b: Expr, n: Double) = Pow(b, Num(n))
        val oneMinusU2 = add(listOf(Expr.ONE, Expr.neg(p(u, 2.0))))
        val onePlusU2 = add(listOf(Expr.ONE, p(u, 2.0)))
        return when (f.name) {
            "sin" -> Fn("cos", listOf(u))
            "cos" -> Expr.neg(Fn("sin", listOf(u)))
            "tan" -> p(Fn("cos", listOf(u)), -2.0)
            "cot" -> Expr.neg(p(Fn("sin", listOf(u)), -2.0))
            "asin" -> p(oneMinusU2, -0.5)
            "acos" -> Expr.neg(p(oneMinusU2, -0.5))
            "atan" -> Pow(onePlusU2, inv)
            "acot" -> Expr.neg(Pow(onePlusU2, inv))
            "sinh" -> Fn("cosh", listOf(u))
            "cosh" -> Fn("sinh", listOf(u))
            "tanh" -> p(Fn("cosh", listOf(u)), -2.0)
            "ln" -> Pow(u, inv)
            "lg" -> Pow(mul(listOf(u, Fn("ln", listOf(Num(10.0))))), inv)
            "log" -> Pow(mul(listOf(u, Fn("ln", listOf(f.args[0])))), inv)
            "exp" -> f
            "sqrt" -> Pow(mul(listOf(Num(2.0), f)), inv)
            "abs" -> mul(listOf(u, Pow(f, inv)))
            else -> throw MathError(tr("производная ${f.name}", "derivative of ${f.name}"))
        }
    }

    // ------------------------------------------------------------ integrals

    /** Antiderivative of [e] in [v] (without + C), or null when the rules do not cover it. */
    fun integrate(e: Expr, v: String): Expr? = integrate(simplify(e), v, 0)

    private fun integrate(e: Expr, v: String, depth: Int): Expr? {
        if (depth > MAX_DEPTH) return null
        if (!has(e, v)) return mul(listOf(e, Sym(v)))
        val x = Sym(v)
        when (e) {
            x -> return mul(listOf(Num(0.5), Pow(x, Num(2.0))))
            is Add -> {
                val parts = e.terms.map { integrate(it, v, depth + 1) ?: return null }
                return add(parts)
            }
            is Mul -> {
                val (consts, vars) = e.factors.partition { !has(it, v) }
                val c = mul(consts)
                val inner = if (vars.size == 1) {
                    integrate(vars[0], v, depth + 1)
                } else {
                    byParts(vars, v, depth) ?: run {
                        val expanded = expand(Mul(vars))
                        if (expanded is Add) integrate(expanded, v, depth + 1) else null
                    } ?: substitutionOfPower(vars, v)
                }
                return inner?.let { mul(listOf(c, it)) }
            }
            is Pow -> return integratePower(e, v, depth)
            is Fn -> {
                val u = e.args.last()
                val a = linearCoefficient(u, v) ?: return null
                val r = when (e.name) {
                    "sin" -> Expr.neg(Fn("cos", listOf(u)))
                    "cos" -> Fn("sin", listOf(u))
                    "exp" -> e
                    "tan" -> Expr.neg(Fn("ln", listOf(Fn("abs", listOf(Fn("cos", listOf(u)))))))
                    "cot" -> Fn("ln", listOf(Fn("abs", listOf(Fn("sin", listOf(u))))))
                    "sinh" -> Fn("cosh", listOf(u))
                    "cosh" -> Fn("sinh", listOf(u))
                    "sqrt" -> mul(listOf(Num(2.0 / 3.0), Pow(u, Num(1.5))))
                    "ln" -> add(listOf(mul(listOf(u, Fn("ln", listOf(u)))), Expr.neg(u)))
                    else -> return null
                }
                return mul(listOf(r, Pow(a, Expr.MINUS_ONE)))
            }
            else -> return null
        }
        @Suppress("UNREACHABLE_CODE")
        return null
    }

    private fun integratePower(e: Pow, v: String, depth: Int): Expr? {
        val base = e.base
        val exp = e.exp
        if (!has(exp, v)) {
            val n = (exp as? Num)?.v
            val a = linearCoefficient(base, v)
            if (a != null) {
                // (ax+b)^n
                return if (n == -1.0) {
                    mul(listOf(Fn("ln", listOf(Fn("abs", listOf(base)))), Pow(a, Expr.MINUS_ONE)))
                } else {
                    val n1 = add(listOf(exp, Expr.ONE))
                    mul(listOf(Pow(base, n1), Pow(mul(listOf(n1, a)), Expr.MINUS_ONE)))
                }
            }
            if (n != null && n == round(n) && n >= 2 && base is Add) {
                return integrate(expand(e), v, depth + 1)
            }
            val x = Sym(v)
            val x2 = pow(x, Num(2.0))
            // 1/(1+x²), 1/√(1-x²)
            if (n == -1.0 && simplify(base) == add(listOf(x2, Expr.ONE))) return Fn("atan", listOf(x))
            if (n == -0.5 && simplify(base) == add(listOf(Expr.neg(x2), Expr.ONE))) return Fn("asin", listOf(x))
            if (base is Fn && base.args.size == 1) {
                val u = base.args[0]
                val k = linearCoefficient(u, v) ?: return null
                val r = when {
                    base.name == "cos" && n == -2.0 -> Fn("tan", listOf(u))
                    base.name == "sin" && n == -2.0 -> Expr.neg(Fn("cot", listOf(u)))
                    base.name == "sin" && n == 2.0 -> add(listOf(mul(listOf(Num(0.5), u)), mul(listOf(Num(-0.25), Fn("sin", listOf(mul(listOf(Num(2.0), u))))))))
                    base.name == "cos" && n == 2.0 -> add(listOf(mul(listOf(Num(0.5), u)), mul(listOf(Num(0.25), Fn("sin", listOf(mul(listOf(Num(2.0), u))))))))
                    else -> return null
                }
                return mul(listOf(r, Pow(k, Expr.MINUS_ONE)))
            }
            return null
        }
        if (!has(base, v)) {
            // c^(ax+b)
            val a = linearCoefficient(exp, v) ?: return null
            val lnBase = if (base == Sym("e")) Expr.ONE else Fn("ln", listOf(base))
            return mul(listOf(e, Pow(mul(listOf(a, lnBase)), Expr.MINUS_ONE)))
        }
        return null
    }

    /** a in a·v + b, or null if [u] is not linear in [v]. */
    private fun linearCoefficient(u: Expr, v: String): Expr? {
        if (!has(u, v)) return null
        val a = runCatching { derivative(u, v) }.getOrNull() ?: return null
        return if (has(a, v)) null else a
    }

    private fun isPolynomial(e: Expr, v: String): Boolean = when (e) {
        is Num -> true
        is Sym -> true
        is Pow -> !has(e, v) || (e.base == Sym(v) && e.exp is Num && (e.exp as Num).v >= 0 && (e.exp as Num).v == round((e.exp as Num).v))
        is Mul -> e.factors.all { isPolynomial(it, v) }
        is Add -> e.terms.all { isPolynomial(it, v) }
        else -> !has(e, v)
    }

    /** ∫ P·f with P a polynomial: P·F − ∫ P'·F; and ∫ P·ln x = ln x·∫P − ∫(∫P)/x. */
    private fun byParts(factors: List<Expr>, v: String, depth: Int): Expr? {
        val (poly, other) = factors.partition { isPolynomial(it, v) }
        if (poly.isEmpty() || other.size != 1) return null
        val p = mul(poly)
        val f = other[0]
        val isLog = f is Fn && (f.name == "ln" || f.name == "lg" || f.name == "log" || f.name == "atan" || f.name == "asin")
        return if (isLog) {
            val pInt = integrate(p, v, depth + 1) ?: return null
            val rest = integrate(simplify(mul(listOf(pInt, d(f, v)))), v, depth + 1) ?: return null
            add(listOf(mul(listOf(f, pInt)), Expr.neg(rest)))
        } else {
            val fInt = integrate(f, v, depth + 1) ?: return null
            val rest = integrate(simplify(mul(listOf(d(p, v), fInt))), v, depth + 1) ?: return null
            add(listOf(mul(listOf(p, fInt)), Expr.neg(rest)))
        }
    }

    /** ∫ g'(x)·g(x)^n and ∫ g'(x)·f(g(x)) for simple g, by checking the quotient is constant. */
    private fun substitutionOfPower(factors: List<Expr>, v: String): Expr? {
        for (i in factors.indices) {
            val outer = factors[i]
            val (g, wrap) = when {
                outer is Pow && !has(outer.exp, v) -> outer.base to { u: Expr -> integrate(Pow(u, outer.exp), "\u0000u") }
                outer is Fn && outer.args.size == 1 -> outer.args[0] to { u: Expr -> integrate(Fn(outer.name, listOf(u)), "\u0000u") }
                else -> continue
            }
            val rest = mul(factors.filterIndexed { j, _ -> j != i })
            val dg = runCatching { derivative(g, v) }.getOrNull() ?: continue
            if (isNum(dg, 0.0)) continue
            val ratio = simplify(mul(listOf(rest, Pow(dg, Expr.MINUS_ONE))))
            if (has(ratio, v)) continue
            val inner = wrap(Sym("\u0000u")) ?: continue
            return mul(listOf(ratio, substitute(inner, "\u0000u", g)))
        }
        return null
    }

    private const val MAX_DEPTH = 12
    private const val MAX_EXPANDED_TERMS = 400
}
