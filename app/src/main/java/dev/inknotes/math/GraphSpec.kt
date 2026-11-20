package dev.inknotes.math

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * A formula kept exactly, as a tree in prefix form: `(+ (^ (S x) (N 2)) (N 1))`. The printed
 * form (x², eˣ) does not read back, this does. Names are %-escaped.
 */
object ExprCodec {
    fun encode(e: Expr): String = buildString { put(e, this) }

    private fun name(s: String) = buildString {
        for (c in s) if (c == ' ' || c == '(' || c == ')' || c == '%' || c == '_') append('%').append("%04x".format(c.code)) else append(c)
    }

    private fun unname(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 5 <= s.length) {
                out.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                i += 5
            } else {
                out.append(s[i++])
            }
        }
        return out.toString()
    }

    private fun put(e: Expr?, b: StringBuilder) {
        when (e) {
            null -> b.append('_')
            is Expr.Num -> b.append("(N ").append(e.v).append(')')
            is Expr.Sym -> b.append("(S ").append(name(e.name)).append(')')
            is Expr.Add -> list("+", e.terms, b)
            is Expr.Mul -> list("*", e.factors, b)
            is Expr.Pow -> list("^", listOf(e.base, e.exp), b)
            is Expr.Fn -> {
                b.append("(F ").append(name(e.name))
                for (a in e.args) b.append(' ').also { put(a, b) }
                b.append(')')
            }
            is Expr.Apply -> {
                b.append("(A ").append(name(e.name)).append(' ').append(e.primes)
                for (a in e.args) b.append(' ').also { put(a, b) }
                b.append(')')
            }
            is Expr.Mat -> {
                b.append("(M")
                for (r in e.rows) {
                    b.append(" (R")
                    for (a in r) b.append(' ').also { put(a, b) }
                    b.append(')')
                }
                b.append(')')
            }
            is Expr.Integral -> {
                b.append("(I ").append(name(e.v)).append(' ')
                put(e.body, b)
                b.append(' ')
                put(e.lo, b)
                b.append(' ')
                put(e.hi, b)
                b.append(')')
            }
            is Expr.Deriv -> {
                b.append("(D ").append(e.v?.let(::name) ?: "_").append(' ').append(e.order).append(' ')
                put(e.body, b)
                b.append(')')
            }
        }
    }

    private fun list(op: String, xs: List<Expr>, b: StringBuilder) {
        b.append('(').append(op)
        for (x in xs) b.append(' ').also { put(x, b) }
        b.append(')')
    }

    /** The formula of [s]; null when it is not one this wrote. */
    fun decode(s: String): Expr? = runCatching {
        val toks = Regex("\\(|\\)|[^\\s()]+").findAll(s).map { it.value }.toList()
        var i = 0
        fun atom(): String = toks[i++]
        fun node(): Expr? {
            val t = atom()
            if (t == "_") return null
            require(t == "(")
            val head = atom()
            fun rest(): List<Expr> {
                val out = ArrayList<Expr>()
                while (toks[i] != ")") out += node()!!
                i++
                return out
            }
            return when (head) {
                "N" -> Expr.Num(atom().toDouble()).also { require(atom() == ")") }
                "S" -> Expr.Sym(unname(atom())).also { require(atom() == ")") }
                "+" -> Expr.Add(rest())
                "*" -> Expr.Mul(rest())
                "^" -> rest().let { Expr.Pow(it[0], it[1]) }
                "F" -> Expr.Fn(unname(atom()), rest())
                "A" -> {
                    val n = unname(atom())
                    val primes = atom().toInt()
                    Expr.Apply(n, primes, rest())
                }
                "M" -> {
                    val rows = ArrayList<List<Expr>>()
                    while (toks[i] != ")") {
                        require(atom() == "(" && atom() == "R")
                        rows += rest()
                    }
                    i++
                    Expr.Mat(rows)
                }
                "I" -> {
                    val v = unname(atom())
                    val body = node()!!
                    val lo = node()
                    val hi = node()
                    require(atom() == ")")
                    Expr.Integral(body, v, lo, hi)
                }
                "D" -> {
                    val v = atom().let { if (it == "_") null else unname(it) }
                    val order = atom().toInt()
                    val body = node()!!
                    require(atom() == ")")
                    Expr.Deriv(body, v, order)
                }
                else -> error("bad node $head")
            }
        }
        node().also { require(i == toks.size) }
    }.getOrNull()
}

/**
 * A graph put into a notebook as a picture: what it shows ([exprs] of [variable], the view,
 * the writer's points), so a tap on the picture opens it again to work on. Kept inside the
 * picture's own PNG file ([PngText]), so it travels wherever the picture goes.
 * [key]: the fingerprint of the formula it came from (its small graph is not drawn twice).
 */
class GraphSpec(
    val variable: String,
    val exprs: List<Expr>,
    val titles: List<String>,
    val trig: Boolean,
    val x0: Double,
    val x1: Double,
    val y0: Double,
    val y1: Double,
    val points: List<Pair<Double, Double>> = emptyList(),
    val key: String? = null,
    /** A surface z = f([variable], [second]) when not null: [x0]…[x1] its half-width both ways. */
    val second: String? = null,
    /** How a surface is turned and tilted, degrees. */
    val azimuth: Float = Plot3D.AZIMUTH,
    val elevation: Float = Plot3D.ELEVATION,
    /** One to one: a unit on x is as long as a unit on y, the grid's cells square. */
    val square: Boolean = false,
) {
    val isSurface get() = second != null

    fun fns(): List<(Double) -> Double> = if (isSurface) emptyList() else exprs.map { e -> { v: Double -> Numeric.scalar(e, mapOf(variable to v)) } }

    /** The surface's heights over a square of half-width [range] (null when not a surface). */
    fun surface(range: Double = x1): Answer.Surface? {
        val yv = second ?: return null
        val e = exprs.firstOrNull() ?: return null
        return Plot3D.of({ x, y -> Numeric.scalar(e, mapOf(variable to x, yv to y)) }, variable, yv, trig, range)?.withExpr(e, trig, titles.firstOrNull().orEmpty())
    }

    fun with(x0: Double, x1: Double, y0: Double, y1: Double, points: List<Pair<Double, Double>>, square: Boolean = this.square) =
        GraphSpec(variable, exprs, titles, trig, x0, x1, y0, y1, points, key, second, azimuth, elevation, square)

    /** The surface seen from ([azimuth], [elevation]) over half-width [range]. */
    fun turned(azimuth: Float, elevation: Float, range: Double) =
        GraphSpec(variable, exprs, titles, trig, -range, range, -range, range, emptyList(), key, second, azimuth, elevation)

    fun encode(): String = buildString {
        append("graph=1\n")
        append("v=").append(variable).append('\n')
        if (trig) append("trig=1\n")
        for ((i, e) in exprs.withIndex()) {
            append("e=").append(ExprCodec.encode(e)).append('\n')
            append("t=").append(titles.getOrElse(i) { "" }.replace('\n', ' ')).append('\n')
        }
        append("view=").append(x0).append(',').append(x1).append(',').append(y0).append(',').append(y1).append('\n')
        for ((x, y) in points) append("p=").append(x).append(',').append(y).append('\n')
        key?.let { append("key=").append(it).append('\n') }
        if (square) append("square=1\n")
        second?.let {
            append("second=").append(it).append('\n')
            append("turn=").append(azimuth).append(',').append(elevation).append('\n')
        }
    }

    companion object {
        /** The graph written by [encode]; null when [text] is not one. */
        fun decode(text: String): GraphSpec? = runCatching {
            val lines = text.lines().filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
            if (lines.firstOrNull() != ("graph" to "1")) return null
            val exprs = ArrayList<Expr>()
            val titles = ArrayList<String>()
            val points = ArrayList<Pair<Double, Double>>()
            var v = "x"
            var trig = false
            var view: List<Double>? = null
            var key: String? = null
            var second: String? = null
            var square = false
            var turn = listOf(Plot3D.AZIMUTH, Plot3D.ELEVATION)
            for ((k, value) in lines) when (k) {
                "second" -> second = value
                "square" -> square = value == "1"
                "turn" -> turn = value.split(',').map { it.toFloat() }
                "v" -> v = value
                "trig" -> trig = value == "1"
                "e" -> exprs += ExprCodec.decode(value) ?: return null
                "t" -> titles += value
                "view" -> view = value.split(',').map { it.toDouble() }
                "p" -> value.split(',').let { points += it[0].toDouble() to it[1].toDouble() }
                "key" -> key = value
            }
            val w = view ?: return null
            if (exprs.isEmpty() || w.size != 4 || !(w[1] > w[0]) || !(w[3] > w[2])) return null
            GraphSpec(v, exprs, titles, trig, w[0], w[1], w[2], w[3], points, key, second, turn[0], turn[1], square)
        }.getOrNull()
    }
}

/**
 * Text kept in a PNG file: an iTXt chunk, put just before the end. Viewers show the picture
 * as it is; [read] finds the text again.
 */
object PngText {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    fun write(png: ByteArray, keyword: String, text: String): ByteArray {
        require(png.size > 20 && png.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "not a PNG" }
        val end = iendAt(png) ?: throw IllegalArgumentException("no IEND")
        val data = ByteArrayOutputStream().apply {
            write(keyword.toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf(0, 0, 0, 0, 0)) // end of keyword, not compressed, method, no language, no translation
            write(text.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val type = "iTXt".toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }.value
        return ByteArrayOutputStream().apply {
            write(png, 0, end)
            write(int(data.size))
            write(type)
            write(data)
            write(int(crc.toInt()))
            write(png, end, png.size - end)
        }.toByteArray()
    }

    /** The text under [keyword], or null. */
    fun read(png: ByteArray, keyword: String): String? {
        if (png.size < 20 || !png.copyOfRange(0, 8).contentEquals(SIGNATURE)) return null
        var at = 8
        val key = keyword.toByteArray(Charsets.ISO_8859_1)
        while (at + 12 <= png.size) {
            val len = u32(png, at)
            if (len < 0 || at + 12 + len > png.size) return null
            val type = String(png, at + 4, 4, Charsets.ISO_8859_1)
            if (type == "iTXt" && len >= key.size + 5) {
                val body = png.copyOfRange(at + 8, at + 8 + len)
                if (body.copyOfRange(0, key.size).contentEquals(key) && body[key.size] == 0.toByte()) {
                    // keyword \0, flag, method, language \0, translated \0, text
                    var i = key.size + 3
                    while (i < body.size && body[i] != 0.toByte()) i++
                    i++
                    while (i < body.size && body[i] != 0.toByte()) i++
                    i++
                    if (i <= body.size) return String(body, i, body.size - i, Charsets.UTF_8)
                }
            }
            if (type == "IEND") return null
            at += 12 + len
        }
        return null
    }

    private fun iendAt(png: ByteArray): Int? {
        var at = 8
        while (at + 12 <= png.size) {
            val len = u32(png, at)
            if (len < 0) return null
            if (String(png, at + 4, 4, Charsets.ISO_8859_1) == "IEND") return at
            at += 12 + len
        }
        return null
    }

    private fun u32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    const val GRAPH = "InkNotes graph"
}
