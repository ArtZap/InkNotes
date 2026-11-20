package dev.inknotes.math

import dev.inknotes.data.tr

/** Something in a handwritten formula could not be read or computed; the message is for the user. */
class MathError(message: String) : Exception(message)

/** Expression tree. Subtraction and division are stored as `a + (-1)·b` and `a·b^(-1)`. */
sealed class Expr {
    data class Num(val v: Double) : Expr()

    /** A variable, or the constants `pi` and `e` while no variable of that name is defined. */
    data class Sym(val name: String) : Expr()
    data class Add(val terms: List<Expr>) : Expr()

    /** Factors keep their written order: matrix products do not commute. */
    data class Mul(val factors: List<Expr>) : Expr()
    data class Pow(val base: Expr, val exp: Expr) : Expr()

    /** Built-in function ([Parser.FUNCTIONS]). */
    data class Fn(val name: String, val args: List<Expr>) : Expr()

    /**
     * `f(args)` as written: a call of a user function `f` (differentiated [primes] times), or
     * just a product when `f` is a variable. Decided when the page's definitions are known.
     */
    data class Apply(val name: String, val primes: Int, val args: List<Expr>) : Expr()
    data class Mat(val rows: List<List<Expr>>) : Expr()

    /** Integral of [body] over [v]; indefinite when the limits are null. */
    data class Integral(val body: Expr, val v: String, val lo: Expr?, val hi: Expr?) : Expr()

    /** Derivative of [body]; [v] is null for a prime `(...)'`, where the variable is guessed. */
    data class Deriv(val body: Expr, val v: String?, val order: Int) : Expr()

    companion object {
        val ZERO = Num(0.0)
        val ONE = Num(1.0)
        val MINUS_ONE = Num(-1.0)

        fun neg(e: Expr): Expr = if (e is Num) Num(-e.v) else Mul(listOf(MINUS_ONE, e))
        fun div(a: Expr, b: Expr): Expr = Mul(listOf(a, Pow(b, MINUS_ONE)))
        fun sub(a: Expr, b: Expr): Expr = Add(listOf(a, neg(b)))
    }
}

/**
 * Parses math as read from handwriting: ordinary infix text (`2x+sin x`, `3:4`, `5!`) plus the
 * structures found in the 2-D layout, written in a small LaTeX-like form:
 * `\frac{a}{b}`, `x^{2}`, `x_{1}`, `\sqrt{a}`, `\int_{a}^{b} … dx`, `\mat{1&2\\3&4}`, `\det{…}`.
 * Multiplication may be implicit (`2x`, `3(x+1)`, `sin x cos x`).
 */
class Parser private constructor(private val toks: List<Tok>) {

    private sealed class Tok {
        data class N(val v: Double) : Tok()

        /** Function name or a one-letter variable. */
        data class Id(val name: String) : Tok()
        data class Op(val s: String) : Tok()
        data class Cmd(val name: String) : Tok()

        /** Inline `d/dx`. */
        data class D(val v: String) : Tok()
    }

    private var pos = 0
    private var absDepth = 0

    /** Inside the argument of sin, cos, tg, ctg, where a small raised 0 is a degree sign. */
    private var trigDepth = 0

    /** The formula has a degree sign somewhere: 30° + 15⁰ adds angles. */
    private val degreeContext = toks.any { it is Tok.Op && it.s == "°" }

    companion object {
        /** Built-in functions; the parser splits runs of letters into these and single letters. */
        val FUNCTIONS = listOf(
            "arcsin", "arccos", "arctan", "arctg", "arcctg", "arccot", "sinh", "cosh", "tanh",
            "sqrt", "sin", "cos", "tan", "tg", "ctg", "cot", "ln", "lg", "log", "exp", "abs", "det",
            "max", "min", "pi", "inf",
        )
        private val ALIASES = mapOf(
            "tg" to "tan", "ctg" to "cot", "arctg" to "atan", "arctan" to "atan", "arcsin" to "asin",
            "arccos" to "acos", "arcctg" to "acot", "arccot" to "acot",
        )

        /** Functions of an angle. */
        val TRIG = setOf("sin", "cos", "tan", "cot")

        /** Units of measurement after numbers (`9,8 м/с² · 3 с`), and physical constants; a setting. */
        @Volatile
        var units = false

        fun parse(text: String): Expr {
            val p = Parser(lex(text))
            if (p.toks.isEmpty()) throw MathError(tr("пусто", "empty"))
            val e = p.expr()
            if (p.pos != p.toks.size) throw MathError(tr("лишнее: ${p.toks[p.pos]}", "extra: ${p.toks[p.pos]}"))
            return e
        }

        private fun lex(src: String): List<Tok> {
            val text = src.replace(Regex("d/d([a-zA-Z])"), "\\\\dd{$1}")
            val out = ArrayList<Tok>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                        val start = i
                        while (i < text.length && text[i].isDigit()) i++
                        // A decimal point or comma between digits.
                        if (i + 1 < text.length && (text[i] == '.' || text[i] == ',') && text[i + 1].isDigit()) {
                            i++
                            while (i < text.length && text[i].isDigit()) i++
                        }
                        val v = text.substring(start, i).replace(',', '.').toDoubleOrNull() ?: throw MathError(tr("число", "number"))
                        out += Tok.N(v)
                    }
                    c == '\\' -> {
                        if (i + 1 < text.length && text[i + 1] == '\\') {
                            out += Tok.Op("\\\\")
                            i += 2
                        } else {
                            val start = ++i
                            while (i < text.length && text[i].isLetter()) i++
                            val name = text.substring(start, i)
                            if (name == "dd") {
                                // \dd{x} from inline d/dx
                                val close = text.indexOf('}', i)
                                if (close < 0) throw MathError("d/d")
                                out += Tok.D(text.substring(i + 1, close))
                                i = close + 1
                            } else {
                                out += Tok.Cmd(name)
                            }
                        }
                    }
                    c.isLetter() && c != 'º' -> {
                        val start = i
                        while (i < text.length && text[i].isLetter() && text[i] != 'π') i++
                        if (i == start) {
                            out += Tok.Id("pi")
                            i++
                        } else {
                            val word = text.substring(start, i)
                            // After "·" a constant is the constant: 2 кг · g.
                            val afterTimes = (out.lastOrNull() as? Tok.Op)?.s == "*"
                            val units = if (Parser.units && unitPlace(out) && !(afterTimes && Units.isConstant(word))) Units.words(word) else null
                            if (units != null) for (u in units) out += Tok.Id(Units.PREFIX + u) else splitLetters(word, out)
                        }
                    }
                    c == 'π' -> { out += Tok.Id("pi"); i++ }
                    c == '∞' -> { out += Tok.Id("inf"); i++ }
                    c == '√' -> { out += Tok.Cmd("sqrt"); i++ }
                    c == '∫' -> { out += Tok.Cmd("int"); i++ }
                    else -> {
                        val op = when (c) {
                            '×', '·', '•', '∙', '*', '⋅' -> "*"
                            '÷', ':', '/' -> "/"
                            '−', '–', '—', '-' -> "-"
                            '′', '\'', '’', '`' -> "'"
                            '"', '″', '”' -> "\""
                            '°', 'º', '˚' -> "°"
                            '+', '^', '(', ')', '[', ']', '{', '}', '|', '!', ',', ';', '_', '&', '%', '°' -> c.toString()
                            else -> throw MathError(tr("символ «$c»", "symbol “$c”"))
                        }
                        out += Tok.Op(op)
                        i++
                    }
                }
            }
            return out
        }

        /**
         * Units are read where they are written: right after a number (9,8 m, 3 c), after
         * another unit (kg m), or after "/" or "·" that follows a number or unit (m/s², Н·м).
         */
        private fun unitPlace(out: List<Tok>): Boolean {
            fun unit(t: Tok?) = t is Tok.Id && t.name.startsWith(Units.PREFIX)
            val last = out.lastOrNull()
            val before = out.getOrNull(out.size - 2)
            return when {
                last is Tok.N || unit(last) -> true
                last is Tok.Op && last.s == "/" -> before is Tok.N || unit(before)
                last is Tok.Op && last.s == "*" -> unit(before)
                // 9,8 м/с² written as a fraction: its numerator right after the number…
                last is Tok.Op && last.s == "{" && before is Tok.Cmd && before.name == "frac" -> out.getOrNull(out.size - 3) is Tok.N
                // …and its denominator after a numerator of units.
                last is Tok.Op && last.s == "{" && before is Tok.Op && before.s == "}" -> numeratorHasUnits(out)
                else -> false
            }
        }

        /** [out] ends with `\frac{…}{`: the part between the braces holds a unit. */
        private fun numeratorHasUnits(out: List<Tok>): Boolean {
            var depth = 0
            for (k in out.size - 2 downTo 0) {
                val t = out[k]
                if (t is Tok.Op && t.s == "}") depth++
                if (t is Tok.Op && t.s == "{") {
                    depth--
                    if (depth == 0) return (out.getOrNull(k - 1) as? Tok.Cmd)?.name == "frac" &&
                        out.subList(k, out.size - 2).any { it is Tok.Id && it.name.startsWith(Units.PREFIX) }
                }
            }
            return false
        }

        /** `xsinx` → x, sin, x; unknown letters are one-letter variables. */
        private fun splitLetters(word: String, out: MutableList<Tok>) {
            var i = 0
            while (i < word.length) {
                val rest = word.substring(i).lowercase()
                val f = FUNCTIONS.firstOrNull { rest.startsWith(it) }
                if (f != null && word.length > 1) {
                    out += Tok.Id(ALIASES[f] ?: f)
                    i += f.length
                } else {
                    out += Tok.Id(word[i].toString())
                    i++
                }
            }
        }
    }

    private fun peek(): Tok? = toks.getOrNull(pos)
    private fun isOp(s: String, at: Int = pos) = (toks.getOrNull(at) as? Tok.Op)?.s == s
    private fun expect(s: String) {
        if (!isOp(s)) throw MathError(tr("ожидалось «$s»", "expected “$s”"))
        pos++
    }

    private fun isFunction(t: Tok?) = t is Tok.Id && t.name.length > 1 && t.name != "pi" && t.name != "inf"

    fun expr(): Expr {
        val terms = mutableListOf(term())
        while (true) {
            when {
                isOp("+") -> { pos++; terms += term() }
                isOp("-") -> { pos++; terms += Expr.neg(term()) }
                else -> break
            }
        }
        return if (terms.size == 1) terms[0] else Expr.Add(terms)
    }

    private fun term(): Expr {
        val factors = mutableListOf(unary())
        while (true) {
            when {
                isOp("*") -> { pos++; factors += unary() }
                isOp("/") -> { pos++; factors += Expr.Pow(divisor(), Expr.MINUS_ONE) }
                startsFactor() -> factors += power()
                else -> break
            }
        }
        return if (factors.size == 1) factors[0] else Expr.Mul(factors)
    }

    /** What follows "/": a quantity is divided by whole (100 Дж / 2 с is per two seconds). */
    private fun divisor(): Expr {
        val d = unary()
        val units = ArrayList<Expr>()
        while ((peek() as? Tok.Id)?.name?.startsWith(Units.PREFIX) == true) units += power()
        return if (units.isEmpty()) d else Expr.Mul(listOf(d) + units)
    }

    private fun unary(): Expr = when {
        isOp("-") -> { pos++; Expr.neg(unary()) }
        isOp("+") -> { pos++; unary() }
        else -> power()
    }

    private fun startsFactor(): Boolean = when (val t = peek()) {
        is Tok.N, is Tok.Id, is Tok.Cmd, is Tok.D -> true
        is Tok.Op -> t.s == "(" || t.s == "[" || t.s == "{" || (t.s == "|" && absDepth == 0)
        null -> false
    }

    private fun power(): Expr {
        val base = postfix()
        if (!isOp("^")) return base
        pos++
        // 30^{o}: a small circle written as an exponent is a degree sign; a raised 0 is one too
        // where angles are meant (sin 30⁰), elsewhere it stays a power (2⁰ = 1).
        if (isOp("{") && isOp("}", pos + 2)) {
            val t = toks[pos + 1]
            val circle = (t is Tok.Id && (t.name == "o" || t.name == "O")) || (t is Tok.Op && t.s == "°") ||
                (t is Tok.N && t.v == 0.0 && (trigDepth > 0 || degreeContext))
            if (circle) {
                pos += 3
                return degrees(base)
            }
        }
        val exp = if (isOp("-")) { pos++; Expr.neg(power()) } else power()
        return Expr.Pow(base, exp)
    }

    /**
     * An angle in degrees, with minutes and seconds if written after it (30°15′20″). Kept as
     * `deg(x)`, or `dms(x)` with minutes, so that an answer about angles is given in degrees.
     */
    private fun degrees(e: Expr): Expr {
        var value = e
        var dms = false
        if (peek() is Tok.N && isOp("'", pos + 1) && !isOp("'", pos + 2)) {
            value = Expr.Add(listOf(value, Expr.Num((toks[pos] as Tok.N).v / 60)))
            pos += 2
            dms = true
            if (peek() is Tok.N && (isOp("\"", pos + 1) || (isOp("'", pos + 1) && isOp("'", pos + 2)))) {
                value = Expr.Add(listOf(value, Expr.Num((toks[pos] as Tok.N).v / 3600)))
                pos += if (isOp("\"", pos + 1)) 2 else 3
            }
        }
        return Expr.Fn(if (dms) "dms" else "deg", listOf(value))
    }

    private fun postfix(): Expr {
        var e = primary()
        while (true) {
            e = when {
                isOp("!") -> { pos++; Expr.Fn("fact", listOf(e)) }
                isOp("%") -> { pos++; Expr.Mul(listOf(e, Expr.Num(0.01))) }
                isOp("°") -> { pos++; degrees(e) }
                isOp("'") -> Expr.Deriv(e, null, primes())
                else -> return e
            }
        }
    }

    private fun primes(): Int {
        var k = 0
        while (isOp("'")) { pos++; k++ }
        // Each order multiplies the expression: as far as d⁵/dx⁵ goes.
        if (k > 5) throw MathError(tr("слишком высокий порядок производной", "derivative order too high"))
        return k
    }

    /** `{…}` group, or a single primary (as in `x^2`, `log_2`). */
    private fun group(): Expr = if (isOp("{")) {
        pos++
        val e = expr()
        expect("}")
        e
    } else {
        primary()
    }

    /** Factors written one after another, as the argument of `sin 2x`; stops at another function. */
    private fun implicitChain(): Expr {
        val factors = mutableListOf(power())
        while (startsFactor() && !isFunction(peek())) factors += power()
        return if (factors.size == 1) factors[0] else Expr.Mul(factors)
    }

    private fun args(): List<Expr> {
        expect("(")
        val out = mutableListOf(expr())
        while (isOp(",") || isOp(";")) { pos++; out += expr() }
        expect(")")
        return out
    }

    private fun primary(): Expr {
        val t = peek() ?: throw MathError(tr("незаконченное выражение", "unfinished expression"))
        pos++
        return when (t) {
            is Tok.N -> Expr.Num(t.v)
            is Tok.D -> Expr.Deriv(implicitChain(), t.v, 1)
            is Tok.Op -> when (t.s) {
                "(" -> expr().also { expect(")") }
                "[" -> expr().also { expect("]") }
                "{" -> expr().also { expect("}") }
                "|" -> {
                    absDepth++
                    val e = expr()
                    absDepth--
                    expect("|")
                    Expr.Fn("abs", listOf(e))
                }
                else -> throw MathError(tr("неожиданное «${t.s}»", "unexpected “${t.s}”"))
            }
            is Tok.Cmd -> command(t.name)
            is Tok.Id -> identifier(t.name)
        }
    }

    private fun command(name: String): Expr = when (name) {
        "frac" -> {
            val num = group()
            val den = group()
            derivativeOrder(num, den)?.let { (v, n) -> Expr.Deriv(implicitChain(), v, n) } ?: Expr.div(num, den)
        }
        "sqrt" -> if (isOp("{") || isOp("(")) Expr.Fn("sqrt", listOf(if (isOp("(")) primary() else group())) else Expr.Fn("sqrt", listOf(postfix()))
        "det" -> Expr.Fn("det", listOf(group()))
        "mat" -> matrix()
        "int" -> integral()
        else -> throw MathError("\\$name")
    }

    /** `d/dx` or `d²/dx²` written as a fraction: the variable and the order. */
    private fun derivativeOrder(num: Expr, den: Expr): Pair<String, Int>? {
        val d = Expr.Sym("d")
        val order = when {
            num == d -> 1
            num is Expr.Pow && num.base == d && num.exp is Expr.Num -> (num.exp as Expr.Num).v.toInt()
            else -> return null
        }
        if (den !is Expr.Mul || den.factors.size != 2 || den.factors[0] != d) return null
        return when (val v = den.factors[1]) {
            is Expr.Sym -> if (order == 1) v.name to 1 else null
            is Expr.Pow -> if (v.base is Expr.Sym && v.exp == Expr.Num(order.toDouble())) (v.base as Expr.Sym).name to order else null
            else -> null
        }?.takeIf { it.second in 1..5 }
    }

    private fun matrix(): Expr {
        expect("{")
        val rows = ArrayList<List<Expr>>()
        var row = ArrayList<Expr>()
        while (true) {
            row += expr()
            when {
                isOp("&") -> pos++
                isOp("\\\\") -> { pos++; rows += row; row = ArrayList() }
                isOp("}") -> { pos++; rows += row; break }
                else -> throw MathError(tr("матрица", "matrix"))
            }
        }
        if (rows.any { it.size != rows[0].size }) throw MathError(tr("строки матрицы разной длины", "matrix rows of different lengths"))
        return Expr.Mat(rows)
    }

    private fun integral(): Expr {
        var lo: Expr? = null
        var hi: Expr? = null
        while (true) {
            when {
                isOp("_") && lo == null -> { pos++; lo = limit() }
                isOp("^") && hi == null -> { pos++; hi = limit() }
                else -> break
            }
        }
        if ((lo == null) != (hi == null)) throw MathError(tr("у интеграла один предел", "the integral has only one limit"))
        // The integrand runs up to "d<variable>".
        var depth = 0
        var end = -1
        for (k in pos until toks.size - 1) {
            val t = toks[k]
            if (t is Tok.Op) {
                if (t.s == "(" || t.s == "[" || t.s == "{") depth++
                if (t.s == ")" || t.s == "]" || t.s == "}") depth--
                if (depth < 0) break
            }
            val next = toks[k + 1]
            if (depth == 0 && t == Tok.Id("d") && next is Tok.Id && next.name.length == 1) {
                end = k
                break
            }
        }
        if (end < 0) throw MathError(tr("нет dx", "no dx"))
        val sub = Parser(toks.subList(pos, end))
        val body = sub.expr()
        if (sub.pos != sub.toks.size) throw MathError(tr("подынтегральное выражение", "integrand"))
        val v = (toks[end + 1] as Tok.Id).name
        pos = end + 2
        return Expr.Integral(body, v, lo, hi)
    }

    private fun limit(): Expr = if (isOp("-")) { pos++; Expr.neg(group()) } else group()

    private fun identifier(name: String): Expr {
        if (name.startsWith(Units.PREFIX)) return Expr.Sym(name)
        when (name) {
            "pi" -> return Expr.Sym("pi")
            "inf" -> return Expr.Num(Double.POSITIVE_INFINITY)
        }
        if (name.length > 1) {
            if (name == "log") {
                val base = if (isOp("_")) { pos++; group() } else null
                val arg = if (isOp("(")) args().single() else implicitChain()
                return Expr.Fn("log", listOfNotNull(base ?: Expr.Num(10.0), arg))
            }
            // sin^2 x
            val exp = if (isOp("^")) { pos++; group() } else null
            val trig = name in TRIG
            if (trig) trigDepth++
            val a = try {
                if (isOp("(")) args() else listOf(implicitChain())
            } finally {
                if (trig) trigDepth--
            }
            val f = Expr.Fn(name, a)
            return if (exp != null) Expr.Pow(f, exp) else f
        }
        var full = name
        if (isOp("_")) {
            pos++
            val sub = if (isOp("{")) {
                pos++
                val s = StringBuilder()
                while (!isOp("}")) {
                    when (val t = peek() ?: throw MathError(tr("индекс", "index"))) {
                        is Tok.N -> s.append(formatIndex(t.v))
                        is Tok.Id -> s.append(t.name)
                        else -> throw MathError(tr("индекс", "index"))
                    }
                    pos++
                }
                pos++
                s.toString()
            } else {
                when (val t = peek()) {
                    is Tok.N -> formatIndex(t.v)
                    is Tok.Id -> t.name
                    else -> throw MathError(tr("индекс", "index"))
                }.also { pos++ }
            }
            full += sub
        }
        val primes = primes()
        if (isOp("(")) return Expr.Apply(full, primes, args())
        if (primes > 0) return Expr.Deriv(Expr.Sym(full), null, primes)
        return Expr.Sym(full)
    }

    private fun formatIndex(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
}
