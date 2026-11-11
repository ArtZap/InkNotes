package dev.inknotes.math

import kotlin.math.max
import kotlin.math.min

/**
 * A formula set in print: read from the app's formula text (what handwriting is read into,
 * `\frac{a}{b}`, `x^{2}`, `\sqrt{…}`, `\mat{…}`, `\int_{a}^{b}`) or from ordinary LaTeX, laid
 * out like a textbook (fractions stacked, exponents raised, radicals over their contents,
 * brackets as tall as what they hold) and turned back into LaTeX for other programs.
 * Plain arithmetic, no Android: the picture is a list of [Op]s a canvas draws.
 */
object Typeset {

    // ------------------------------------------------------------------ tree

    sealed class Node

    /** A letter (italic), number, operator or function name (upright). */
    data class Sym(val text: String, val italic: Boolean = false, val space: Float = 0f) : Node()
    data class Row(val items: List<Node>) : Node()
    data class Frac(val num: Row, val den: Row) : Node()
    data class Sqrt(val inner: Row, val index: Row? = null) : Node()
    data class Scripts(val base: Node, val sup: Row?, val sub: Row?) : Node()
    data class Fenced(val open: String, val close: String, val inner: Row) : Node()
    /** A matrix in round brackets, square ones ([square]) or bars (a determinant, [det]). */
    data class Matrix(val rows: List<List<Row>>, val det: Boolean, val square: Boolean = false) : Node()

    /** ∫, ∑ or ∏ with limits. */
    data class BigOp(val sign: String, val lo: Row?, val hi: Row?) : Node()

    private val FUNCTIONS = listOf(
        "arcsin", "arccos", "arctan", "arctg", "arcctg", "sinh", "cosh", "tanh",
        "sin", "cos", "tan", "tg", "ctg", "cot", "ln", "lg", "log", "exp", "det", "max", "min", "lim",
    ).sortedByDescending { it.length }

    private val GREEK = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ",
        "eta" to "η", "theta" to "θ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Omega" to "Ω",
    )

    private val SYMBOLS = mapOf(
        "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "infty" to "∞", "circ" to "°", "to" to "→", "rightarrow" to "→", "partial" to "∂",
        "degree" to "°", "cdots" to "⋯", "ldots" to "…",
    )

    /** Operators set with room on both sides (a relation a little more). */
    private val BINARY = setOf("+", "−", "·", "×", "÷", "±", "∓", "/", ":")
    private val RELATIONS = setOf("=", "<", ">", "≤", "≥", "≠", "≈", "→")

    // ----------------------------------------------------------------- parse

    /** The tree of [source]; never throws: what it does not understand it shows as it is. */
    fun parse(source: String): Row = Reader(normalize(source)).row(null)

    private fun normalize(s: String): String = s
        .replace("\\left", "").replace("\\right", "")
        .replace("\\,", " ").replace("\\;", " ").replace("\\!", "").replace("\\quad", " ")

    private class Reader(val s: String) {
        var i = 0

        fun row(stop: Set<Char>?, stopCommand: Set<String> = emptySet()): Row {
            val out = ArrayList<Node>()
            while (i < s.length) {
                val c = s[i]
                if (stop != null && c in stop) break
                if (c == '\\' && stopCommand.any { s.startsWith("\\$it", i) }) break
                when {
                    c == ' ' -> i++
                    c == '}' -> i++ // stray closing brace
                    c == '^' || c == '_' -> {
                        i++
                        val arg = argument()
                        val base = out.removeLastOrNull() ?: Sym("")
                        out += attach(base, arg, sup = c == '^')
                    }
                    c == '\\' -> out += command()
                    c == '{' -> {
                        i++
                        out += row(setOf('}')).also { if (i < s.length) i++ }
                    }
                    c in "([|" -> {
                        val close = when (c) {
                            '(' -> ')'
                            '[' -> ']'
                            else -> '|'
                        }
                        val save = i
                        i++
                        val inner = row(setOf(close, ')', ']').let { if (close == '|') it + '|' else it })
                        if (i < s.length && s[i] == close) {
                            i++
                            out += Fenced(c.toString(), close.toString(), inner)
                        } else {
                            // Unbalanced: the bracket as a plain sign.
                            i = save + 1
                            out += Sym(c.toString())
                        }
                    }
                    c.isDigit() || (c == ',' || c == '.') && i + 1 < s.length && s[i + 1].isDigit() && out.lastOrNull().isNumber() -> {
                        val start = i
                        while (i < s.length && (s[i].isDigit() || (s[i] == ',' || s[i] == '.') && i + 1 < s.length && s[i + 1].isDigit())) i++
                        out += Sym(s.substring(start, i))
                    }
                    c.isLetter() -> {
                        val fn = FUNCTIONS.firstOrNull { s.startsWith(it, i) }
                        if (fn != null) {
                            out += Sym(fn, space = 0.1f)
                            i += fn.length
                        } else {
                            out += Sym(c.toString(), italic = c.code < 0x370 || c in GREEK.values.joinToString(""))
                            i++
                        }
                    }
                    else -> {
                        i++
                        out += sign(c)
                    }
                }
            }
            return Row(out)
        }

        private fun Node?.isNumber() = this is Sym && text.isNotEmpty() && text.all { it.isDigit() || it == ',' || it == '.' }

        private fun sign(c: Char): Sym {
            val t = when (c) {
                '*' -> "·"
                '-', '–', '—' -> "−"
                '\'' -> "′"
                else -> c.toString()
            }
            return Sym(t)
        }

        private fun attach(base: Node, arg: Row, sup: Boolean): Node = when {
            base is Scripts && sup && base.sup == null -> base.copy(sup = arg)
            base is Scripts && !sup && base.sub == null -> base.copy(sub = arg)
            base is BigOp && sup && base.hi == null -> base.copy(hi = arg)
            base is BigOp && !sup && base.lo == null -> base.copy(lo = arg)
            sup -> Scripts(base, arg, null)
            else -> Scripts(base, null, arg)
        }

        /** `{…}` or one character (`x^2`), or a command (`x^\circ`). */
        fun argument(): Row {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length) return Row(emptyList())
            return when (s[i]) {
                '{' -> {
                    i++
                    row(setOf('}')).also { if (i < s.length) i++ }
                }
                '\\' -> Row(listOf(command()))
                else -> {
                    val c = s[i++]
                    if (c.isDigit()) Row(listOf(Sym(c.toString()))) else Row(listOf(if (c.isLetter()) Sym(c.toString(), italic = true) else sign(c)))
                }
            }
        }

        private fun name(): String {
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            if (i == start && i < s.length) i++ // \\ or \{ …
            return s.substring(start, i)
        }

        fun command(): Node {
            i++ // backslash
            val n = name()
            return when (n) {
                "frac", "dfrac", "tfrac" -> Frac(argument(), argument())
                "sqrt" -> {
                    var index: Row? = null
                    if (i < s.length && s[i] == '[') {
                        i++
                        index = row(setOf(']')).also { if (i < s.length) i++ }
                    }
                    Sqrt(argument(), index)
                }
                "mat" -> Matrix(matrixArgument(), det = false)
                "det" -> {
                    // \det{\mat{…}}: a determinant; \det A: the function name.
                    val save = i
                    while (i < s.length && s[i] == ' ') i++
                    if (s.startsWith("{\\mat", i)) {
                        i += 1
                        val m = command()
                        while (i < s.length && s[i] != '}') i++
                        if (i < s.length) i++
                        if (m is Matrix) m.copy(det = true) else m
                    } else {
                        i = save
                        Sym("det", space = 0.1f)
                    }
                }
                "begin" -> {
                    val env = argument().items.joinToString("") { (it as? Sym)?.text ?: "" }
                    val m = gridUntilEnd()
                    when (env) {
                        "vmatrix" -> Matrix(m, det = true)
                        "bmatrix" -> Matrix(m, det = false, square = true)
                        else -> Matrix(m, det = false)
                    }
                }
                "int" -> BigOp("∫", null, null)
                "sum" -> BigOp("∑", null, null)
                "prod" -> BigOp("∏", null, null)
                "operatorname" -> Sym(argument().items.joinToString("") { (it as? Sym)?.text ?: "" }, space = 0.1f)
                "text", "mathrm" -> Sym(argument().items.joinToString("") { (it as? Sym)?.text ?: "" })
                "{" -> Sym("{")
                "}" -> Sym("}")
                "\\" -> Sym(" ")
                else -> when {
                    n in GREEK -> Sym(GREEK.getValue(n), italic = n[0].isLowerCase())
                    n in SYMBOLS -> Sym(SYMBOLS.getValue(n))
                    n in FUNCTIONS -> Sym(n, space = 0.1f)
                    else -> Sym(n)
                }
            }
        }

        /** Rows and cells up to `\end{…}`. */
        private fun gridUntilEnd(): List<List<Row>> {
            val rows = ArrayList<List<Row>>()
            var cells = ArrayList<Row>()
            while (i < s.length) {
                val cell = row(setOf('&'), setOf("\\", "end"))
                cells += cell
                when {
                    i >= s.length -> break
                    s[i] == '&' -> i++
                    s.startsWith("\\\\", i) -> {
                        i += 2
                        rows += cells
                        cells = ArrayList()
                    }
                    s.startsWith("\\end", i) -> {
                        i += 4
                        argument()
                        break
                    }
                    else -> i++
                }
            }
            if (cells.isNotEmpty() && !(cells.size == 1 && cells[0].items.isEmpty())) rows += cells
            return rows
        }

        /** `{a&b\\c&d}` after `\mat`. */
        fun matrixArgument(): List<List<Row>> {
            while (i < s.length && s[i] == ' ') i++
            if (i >= s.length || s[i] != '{') return emptyList()
            i++
            val rows = ArrayList<List<Row>>()
            var cells = ArrayList<Row>()
            while (i < s.length) {
                val cell = row(setOf('&', '}'), setOf("\\"))
                cells += cell
                when {
                    i >= s.length -> break
                    s[i] == '&' -> i++
                    s[i] == '}' -> {
                        i++
                        break
                    }
                    s.startsWith("\\\\", i) -> {
                        i += 2
                        rows += cells
                        cells = ArrayList()
                    }
                    else -> i++
                }
            }
            if (cells.isNotEmpty()) rows += cells
            return rows
        }
    }

    // ---------------------------------------------------------------- latex

    /** Ordinary LaTeX for [source] (fractions, roots, matrices, Greek letters, `\cdot`…). */
    fun latex(source: String): String = latex(parse(source)).trim()
        .replace(Regex(" {2,}"), " ").replace(Regex(" +}"), "}").replace(Regex("\\{ +"), "{").replace(Regex(" +\\]"), "]")

    fun latex(n: Node): String = when (n) {
        is Row -> buildString {
            for (item in n.items) {
                val t = latex(item)
                // A command name must not run into the letter after it (\cdot x, not \cdotx).
                if (isNotEmpty() && last().isLetter() && t.firstOrNull()?.isLetter() == true && Regex("\\\\[a-zA-Z]+$").containsMatchIn(this)) append(' ')
                append(t)
            }
        }
        is Sym -> symLatex(n)
        is Frac -> "\\frac{${latex(n.num)}}{${latex(n.den)}}"
        is Sqrt -> if (n.index != null) "\\sqrt[${latex(n.index)}]{${latex(n.inner)}}" else "\\sqrt{${latex(n.inner)}}"
        is Scripts -> buildString {
            val b = latex(n.base)
            append(if (n.base is Scripts || n.base is Frac) "{$b}" else b)
            n.sub?.let { append("_{").append(latex(it)).append("}") }
            n.sup?.let { append("^{").append(latex(it)).append("}") }
        }
        is Fenced -> {
            val o = when (n.open) { "" -> "."; "{" -> "\\{"; else -> n.open }
            val c = when (n.close) { "" -> "."; "}" -> "\\}"; else -> n.close }
            if (o == "." && c == ".") latex(n.inner) else "\\left$o ${latex(n.inner)} \\right$c"
        }
        is Matrix -> {
            val env = if (n.det) "vmatrix" else if (n.square) "bmatrix" else "pmatrix"
            "\\begin{$env} " + n.rows.joinToString(" \\\\ ") { r -> r.joinToString(" & ") { latex(it) } } + " \\end{$env}"
        }
        is BigOp -> buildString {
            append(when (n.sign) { "∑" -> "\\sum"; "∏" -> "\\prod"; else -> "\\int" })
            n.lo?.let { append("_{").append(latex(it)).append("}") }
            n.hi?.let { append("^{").append(latex(it)).append("}") }
            append(' ')
        }
    }

    private fun symLatex(s: Sym): String {
        val t = s.text
        if (t in FUNCTIONS) return if (t in setOf("arctg", "arcctg", "tg", "ctg")) "\\operatorname{$t} " else "\\$t "
        GREEK.entries.firstOrNull { it.value == t }?.let { return "\\${it.key} " }
        return when (t) {
            "·" -> " \\cdot "
            "×" -> " \\times "
            "÷" -> " \\div "
            "±" -> " \\pm "
            "∓" -> " \\mp "
            "≤" -> " \\le "
            "≥" -> " \\ge "
            "≠" -> " \\ne "
            "≈" -> " \\approx "
            "∞" -> "\\infty "
            "°" -> "^{\\circ}"
            "→" -> " \\to "
            "−" -> "-"
            "′" -> "'"
            "{" -> "\\{"
            "}" -> "\\}"
            "√" -> "\\surd "
            "∂" -> "\\partial "
            else -> if (t.length > 1 && t.all { it.isLetter() }) "\\mathrm{$t}" else t
        }
    }

    // ---------------------------------------------------------------- layout

    /** Width of [text] at [size] (italic or upright), in the units the caller draws in. */
    fun interface Measure {
        fun width(text: String, size: Float, italic: Boolean): Float
    }

    sealed class Op {
        /** [text] with its baseline at [x], [y]. */
        data class Text(val x: Float, val y: Float, val text: String, val size: Float, val italic: Boolean) : Op()
        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float) : Op()

        /** A polyline (a radical sign, a bracket), x, y pairs. */
        data class Poly(val points: FloatArray, val width: Float) : Op()
    }

    /** Laid out: [width], [ascent] above and [descent] below the baseline, ops relative to (0, baseline). */
    class Laid(val width: Float, val ascent: Float, val descent: Float, val ops: List<Op>) {
        val height get() = ascent + descent

        fun moved(dx: Float, dy: Float): List<Op> = ops.map {
            when (it) {
                is Op.Text -> it.copy(x = it.x + dx, y = it.y + dy)
                is Op.Line -> it.copy(x1 = it.x1 + dx, y1 = it.y1 + dy, x2 = it.x2 + dx, y2 = it.y2 + dy)
                is Op.Poly -> Op.Poly(FloatArray(it.points.size) { k -> it.points[k] + if (k % 2 == 0) dx else dy }, it.width)
            }
        }
    }

    /** [source] laid out at letter [size] with [measure]. */
    fun layout(source: String, size: Float, measure: Measure): Laid = Layout(measure).node(parse(source), size)

    private class Layout(val m: Measure) {
        fun node(n: Node, s: Float): Laid = when (n) {
            is Row -> row(n, s)
            is Sym -> sym(n, s)
            is Frac -> frac(n, s)
            is Sqrt -> sqrt(n, s)
            is Scripts -> scripts(n, s)
            is Fenced -> fenced(n.open, n.close, row(n.inner, s), s)
            is Matrix -> matrix(n, s)
            is BigOp -> bigOp(n, s)
        }

        private fun sym(n: Sym, s: Float): Laid {
            if (n.text.isEmpty()) return Laid(0f, 0f, 0f, emptyList())
            val pad = when (n.text) {
                in RELATIONS -> 0.28f * s
                in BINARY -> 0.2f * s
                "," -> 0f
                else -> n.space * s
            }
            val after = if (n.text == ",") 0.2f * s else 0f
            val w = m.width(n.text, s, n.italic)
            return Laid(pad + w + pad + after, ASCENT * s, DESCENT * s, listOf(Op.Text(pad, 0f, n.text, s, n.italic)))
        }

        fun row(r: Row, s: Float): Laid {
            if (r.items.isEmpty()) return Laid(0f, ASCENT * s * 0.5f, 0f, emptyList())
            var x = 0f
            var asc = 0f
            var desc = 0f
            val ops = ArrayList<Op>()
            var prev: Node? = null
            for (item in r.items) {
                // An operator at the start of a row (a sign: −1) gets no room before it.
                val l = if (prev == null && item is Sym && item.text in BINARY) sym(item.copy(), s).let { Laid(it.width - 0.2f * s, it.ascent, it.descent, it.moved(-0.2f * s, 0f)) } else node(item, s)
                ops += l.moved(x, 0f)
                x += l.width
                asc = max(asc, l.ascent)
                desc = max(desc, l.descent)
                prev = item
            }
            return Laid(x, asc, desc, ops)
        }

        private fun frac(n: Frac, s: Float): Laid {
            val k = if (s > MIN_SIZE) 0.82f else 1f
            val num = row(n.num, s * k)
            val den = row(n.den, s * k)
            val pad = 0.12f * s
            val w = max(num.width, den.width) + 2 * pad
            val axis = AXIS * s
            val gap = 0.14f * s
            val t = RULE * s
            val ops = ArrayList<Op>()
            ops += num.moved((w - num.width) / 2, -axis - gap - num.descent)
            ops += den.moved((w - den.width) / 2, -axis + gap + den.ascent)
            ops += Op.Line(pad * 0.5f, -axis, w - pad * 0.5f, -axis, t)
            return Laid(w + 0.08f * s, axis + gap + num.height, max(0f, -axis + gap + den.height), ops)
        }

        private fun sqrt(n: Sqrt, s: Float): Laid {
            val inner = row(n.inner, s)
            val t = RULE * s
            val top = inner.ascent + 0.18f * s
            val bottom = inner.descent
            val hook = 0.5f * s
            val ops = ArrayList<Op>()
            var x0 = 0f
            val index = n.index?.let { row(it, s * 0.55f) }
            if (index != null) x0 = max(0f, index.width - hook * 0.4f)
            ops += Op.Poly(
                floatArrayOf(
                    x0, -0.35f * s,
                    x0 + 0.15f * hook, -0.45f * s,
                    x0 + 0.5f * hook, bottom,
                    x0 + hook, -top,
                    x0 + hook + inner.width + 0.15f * s, -top,
                ),
                t,
            )
            if (index != null) ops += index.moved(0f, -0.5f * s - index.descent)
            ops += inner.moved(x0 + hook + 0.05f * s, 0f)
            val w = x0 + hook + inner.width + 0.25f * s
            return Laid(w, top + t + max(0f, (index?.height ?: 0f) - top + 0.5f * s).coerceAtLeast(0f), bottom, ops)
        }

        private fun scripts(n: Scripts, s: Float): Laid {
            val base = node(n.base, s)
            val ks = max(MIN_SIZE, s * 0.68f)
            val sup = n.sup?.let { row(it, ks) }
            val sub = n.sub?.let { row(it, ks) }
            // A tall base (a fraction, a bracket) carries its scripts at its corners.
            val supShift = max(0.42f * s, base.ascent - 0.42f * (sup?.height ?: 0f))
            val subShift = max(0.22f * s, base.descent - 0.3f * (sub?.height ?: 0f))
            val ops = ArrayList(base.ops)
            var w = 0f
            sup?.let {
                ops += it.moved(base.width + 0.03f * s, -supShift)
                w = max(w, it.width)
            }
            sub?.let {
                ops += it.moved(base.width + 0.02f * s, subShift)
                w = max(w, it.width)
            }
            val asc = max(base.ascent, sup?.let { supShift + it.ascent } ?: 0f)
            val desc = max(base.descent, sub?.let { subShift + it.descent } ?: 0f)
            return Laid(base.width + w + 0.06f * s, asc, desc, ops)
        }

        /** [inner] between brackets as tall as it is. */
        fun fenced(open: String, close: String, inner: Laid, s: Float): Laid {
            val h = max(inner.height, (ASCENT + DESCENT) * s)
            val top = -max(inner.ascent, ASCENT * s)
            val bottom = top + h
            val bw = 0.3f * s + 0.04f * h
            val ops = ArrayList<Op>()
            var x = 0f
            if (open.isNotEmpty()) {
                ops += bracket(open, x, top, bottom, bw, s, left = true)
                x += bw
            }
            ops += inner.moved(x + 0.04f * s, 0f)
            x += inner.width + 0.08f * s
            if (close.isNotEmpty()) {
                ops += bracket(close, x, top, bottom, bw, s, left = false)
                x += bw
            }
            return Laid(x, -top + 0.04f * s, bottom + 0.04f * s, ops)
        }

        private fun bracket(b: String, x: Float, top: Float, bottom: Float, w: Float, s: Float, left: Boolean): Op {
            val t = RULE * s * 1.1f
            val inner = if (left) x + w * 0.75f else x + w * 0.25f
            val outer = if (left) x + w * 0.25f else x + w * 0.75f
            val mid = (top + bottom) / 2
            return when (b) {
                "|" -> Op.Line(x + w / 2, top, x + w / 2, bottom, t)
                "[", "]" -> Op.Poly(floatArrayOf(inner, top, outer, top, outer, bottom, inner, bottom), t)
                "{", "}" -> {
                    val c = (inner + outer) / 2
                    Op.Poly(floatArrayOf(inner, top, c, top + 0.1f * (bottom - top), c, mid - 0.06f * (bottom - top), outer, mid, c, mid + 0.06f * (bottom - top), c, bottom - 0.1f * (bottom - top), inner, bottom), t)
                }
                else -> {
                    // A round bracket: a bow from top to bottom through the outer side.
                    val pts = FloatArray(2 * 13)
                    for (k in 0..12) {
                        val u = k / 12f
                        val bow = 4 * u * (1 - u)
                        pts[2 * k] = inner + (outer - inner) * bow
                        pts[2 * k + 1] = top + (bottom - top) * u
                    }
                    Op.Poly(pts, t)
                }
            }
        }

        private fun matrix(n: Matrix, s: Float): Laid {
            if (n.rows.isEmpty()) return fenced("(", ")", Laid(0.5f * s, ASCENT * s, DESCENT * s, emptyList()), s)
            val cells = n.rows.map { r -> r.map { row(it, s) } }
            val cols = cells.maxOf { it.size }
            val colW = FloatArray(cols) { c -> cells.maxOf { r -> r.getOrNull(c)?.width ?: 0f } }
            val gapX = 0.7f * s
            val gapY = 0.3f * s
            val rowAsc = cells.map { r -> r.maxOf { it.ascent } }
            val rowDesc = cells.map { r -> r.maxOf { it.descent } }
            val total = rowAsc.indices.sumOf { (rowAsc[it] + rowDesc[it]).toDouble() }.toFloat() + gapY * (cells.size - 1)
            // Centred on the maths axis.
            var y = -AXIS * s - total / 2
            val ops = ArrayList<Op>()
            for ((r, rowCells) in cells.withIndex()) {
                y += rowAsc[r]
                var x = 0f
                for (c in 0 until cols) {
                    rowCells.getOrNull(c)?.let { ops += it.moved(x + (colW[c] - it.width) / 2, y) }
                    x += colW[c] + gapX
                }
                y += rowDesc[r] + gapY
            }
            val width = colW.sum() + gapX * (cols - 1)
            val inner = Laid(width, AXIS * s + total / 2, total / 2 - AXIS * s, ops)
            return when {
                n.det -> fenced("|", "|", inner, s)
                n.square -> fenced("[", "]", inner, s)
                else -> fenced("(", ")", inner, s)
            }
        }

        private fun bigOp(n: BigOp, s: Float): Laid {
            val big = if (n.sign == "∫") 1.6f * s else 1.4f * s
            val w = m.width(n.sign, big, n.sign == "∫")
            // The sign centred on the maths axis.
            val baseline = 0.25f * big - AXIS * s
            val top = 0.5f * big + AXIS * s
            val foot = 0.5f * big - AXIS * s
            val ks = max(MIN_SIZE, s * 0.62f)
            val lo = n.lo?.let { row(it, ks) }
            val hi = n.hi?.let { row(it, ks) }
            val ops = ArrayList<Op>()
            var width: Float
            var asc = top
            var desc = foot
            if (n.sign == "∫") {
                ops += Op.Text(0f, baseline, n.sign, big, true)
                width = w
                hi?.let {
                    ops += it.moved(w * 0.85f, -top + it.ascent)
                    width = max(width, w * 0.85f + it.width)
                }
                lo?.let {
                    ops += it.moved(w * 0.45f, foot - it.descent)
                    width = max(width, w * 0.45f + it.width)
                }
            } else {
                val wide = max(w, max(lo?.width ?: 0f, hi?.width ?: 0f))
                ops += Op.Text((wide - w) / 2, baseline, n.sign, big, false)
                hi?.let {
                    ops += it.moved((wide - it.width) / 2, -top - 0.08f * s - it.descent)
                    asc = top + 0.08f * s + it.height
                }
                lo?.let {
                    ops += it.moved((wide - it.width) / 2, foot + 0.08f * s + it.ascent)
                    desc = foot + 0.08f * s + it.height
                }
                width = wide
            }
            return Laid(width + 0.15f * s, asc, desc, ops)
        }
    }

    /** Height of capitals and depth below the baseline, as shares of the letter size. */
    const val ASCENT = 0.75f
    const val DESCENT = 0.25f

    /** The maths axis (fraction bars, the middle of + and =) above the baseline. */
    const val AXIS = 0.27f

    /** Thickness of bars and radicals. */
    const val RULE = 0.055f
    const val MIN_SIZE = 6f
}
