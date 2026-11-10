package dev.inknotes.math

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Units of measurement and physical constants in formulas (a setting): `9,8 м/с² · 3 с =`
 * answers `29,4 м/с`, and adding metres to seconds is an error, not a number.
 *
 * The handwriting reader reads Latin letters, so units are matched as written in either
 * alphabet: Cyrillic м, с, Н, В look like m, c, H, B. Units are read only right after a number
 * or another unit (see [Parser]), where letters are not variables: 2 m is two metres, 2·m is
 * twice m. Constants (g, c, G, R, N_A, k_B) are letters standing alone that the page does not
 * define.
 */
object Units {
    const val PREFIX = "u:"

    /** Powers of metre, kilogram, second, ampere, kelvin, mole. */
    class Dim(val p: IntArray) {
        val none get() = p.all { it == 0 }
        operator fun plus(o: Dim) = Dim(IntArray(N) { p[it] + o.p[it] })
        operator fun minus(o: Dim) = Dim(IntArray(N) { p[it] - o.p[it] })
        operator fun times(k: Int) = Dim(IntArray(N) { p[it] * k })
        override fun equals(other: Any?) = other is Dim && other.p.contentEquals(p)
        override fun hashCode() = p.contentHashCode()
        override fun toString() = text(this)
    }

    private const val N = 6
    private fun dim(m: Int = 0, kg: Int = 0, s: Int = 0, a: Int = 0, k: Int = 0, mol: Int = 0) = Dim(intArrayOf(m, kg, s, a, k, mol))
    val NONE = dim()

    /** A unit: how many SI units it is, its dimension and how it is written in the answer. */
    class Unit(val name: String, val factor: Double, val dim: Dim, val shown: String)

    private val UNITS = listOf(
        Unit("m", 1.0, dim(m = 1), "м"),
        Unit("km", 1000.0, dim(m = 1), "км"),
        Unit("cm", 0.01, dim(m = 1), "см"),
        Unit("mm", 0.001, dim(m = 1), "мм"),
        Unit("kg", 1.0, dim(kg = 1), "кг"),
        Unit("g", 0.001, dim(kg = 1), "г"),
        Unit("t", 1000.0, dim(kg = 1), "т"),
        Unit("s", 1.0, dim(s = 1), "с"),
        Unit("min", 60.0, dim(s = 1), "мин"),
        Unit("h", 3600.0, dim(s = 1), "ч"),
        Unit("N", 1.0, dim(m = 1, kg = 1, s = -2), "Н"),
        Unit("kN", 1000.0, dim(m = 1, kg = 1, s = -2), "кН"),
        Unit("J", 1.0, dim(m = 2, kg = 1, s = -2), "Дж"),
        Unit("kJ", 1000.0, dim(m = 2, kg = 1, s = -2), "кДж"),
        Unit("W", 1.0, dim(m = 2, kg = 1, s = -3), "Вт"),
        Unit("kW", 1000.0, dim(m = 2, kg = 1, s = -3), "кВт"),
        Unit("Pa", 1.0, dim(m = -1, kg = 1, s = -2), "Па"),
        Unit("kPa", 1000.0, dim(m = -1, kg = 1, s = -2), "кПа"),
        Unit("MPa", 1e6, dim(m = -1, kg = 1, s = -2), "МПа"),
        Unit("Hz", 1.0, dim(s = -1), "Гц"),
        Unit("A", 1.0, dim(a = 1), "А"),
        Unit("C", 1.0, dim(s = 1, a = 1), "Кл"),
        Unit("V", 1.0, dim(m = 2, kg = 1, s = -3, a = -1), "В"),
        Unit("Ohm", 1.0, dim(m = 2, kg = 1, s = -3, a = -2), "Ом"),
        Unit("K", 1.0, dim(k = 1), "К"),
        Unit("mol", 1.0, dim(mol = 1), "моль"),
        Unit("eV", 1.602176634e-19, dim(m = 2, kg = 1, s = -2), "эВ"),
    ).associateBy { it.name }

    /** Spellings, as the reader gives them, of each unit (Cyrillic handwriting read as Latin). */
    private val SPELLINGS: List<Pair<String, String>> = listOf(
        "m" to "m", "M" to "m", "km" to "km", "kM" to "km", "KM" to "km", "cm" to "cm", "CM" to "cm", "mm" to "mm", "MM" to "mm",
        "kg" to "kg", "Kg" to "kg", "kr" to "kg", "Kr" to "kg", "g" to "g", "r" to "g", "t" to "t", "T" to "t",
        "s" to "s", "c" to "s", "min" to "min", "MuH" to "min", "h" to "h", "y" to "h",
        "N" to "N", "H" to "N", "kN" to "kN", "kH" to "kN", "KH" to "kN",
        "J" to "J", "kJ" to "kJ", "W" to "W", "Bm" to "W", "BT" to "W", "Bt" to "W", "kW" to "kW", "kBm" to "kW", "kBT" to "kW",
        "Pa" to "Pa", "Na" to "Pa", "kPa" to "kPa", "kNa" to "kPa", "MPa" to "MPa", "MNa" to "MPa",
        "Hz" to "Hz", "Ty" to "Hz", "A" to "A", "C" to "C", "Kl" to "C", "Kn" to "C",
        "V" to "V", "B" to "V", "Ohm" to "Ohm", "Om" to "Ohm", "OM" to "Ohm", "Ω" to "Ohm", "K" to "K", "mol" to "mol", "Mol" to "mol",
        "eV" to "eV", "eB" to "eV", "aB" to "eV",
    ).sortedByDescending { it.first.length }

    /** The units [word] is made of (kgm → kg, m), or null when it is not units. */
    fun words(word: String): List<String>? {
        val out = ArrayList<String>()
        var i = 0
        while (i < word.length) {
            val (spelling, unit) = SPELLINGS.firstOrNull { word.startsWith(it.first, i) } ?: return null
            out += unit
            i += spelling.length
        }
        return out.takeIf { it.isNotEmpty() && it.size <= 3 }
    }

    /** A value in SI units with its dimension. */
    class Q(val v: Double, val dim: Dim)

    private val CONSTANTS = mapOf(
        "g" to Q(9.80665, dim(m = 1, s = -2)),
        "c" to Q(299_792_458.0, dim(m = 1, s = -1)),
        "G" to Q(6.674_30e-11, dim(m = 3, kg = -1, s = -2)),
        "R" to Q(8.314_462_618, dim(m = 2, kg = 1, s = -2, k = -1, mol = -1)),
        "NA" to Q(6.022_140_76e23, dim(mol = -1)),
        "kB" to Q(1.380_649e-23, dim(m = 2, kg = 1, s = -2, k = -1)),
    )

    fun isConstant(name: String) = name in CONSTANTS

    /** Units in [e], or constants not defined in [env]: the formula is about quantities. */
    fun involved(e: Expr, env: Env): Boolean = Algebra.freeVars(e).any { it.startsWith(PREFIX) || (it in CONSTANTS && env.variable(it) == null) }

    private class NotQuantity : Exception()

    /** The answer to [e] with units; null when it is not a formula of numbers and units. */
    fun answer(e: Expr, env: Env): Answer? {
        val used = Algebra.freeVars(e).filter { it.startsWith(PREFIX) }.mapNotNull { UNITS[it.removePrefix(PREFIX)] }
        val q = try {
            eval(e, env, 0)
        } catch (x: NotQuantity) {
            return null
        }
        if (!q.v.isFinite()) throw MathError("не определено")
        return Answer.Text(listOf(format(q, used)))
    }

    private fun eval(e: Expr, env: Env, depth: Int): Q {
        if (depth > 60) throw MathError("слишком глубоко")
        return when (e) {
            is Expr.Num -> Q(e.v, NONE)
            is Expr.Sym -> when {
                e.name.startsWith(PREFIX) -> UNITS[e.name.removePrefix(PREFIX)]?.let { Q(it.factor, it.dim) } ?: throw NotQuantity()
                e.name == "pi" && env.variable("pi") == null -> Q(Math.PI, NONE)
                e.name == "e" && env.variable("e") == null -> Q(Math.E, NONE)
                else -> env.variable(e.name)?.let { eval(it, env, depth + 1) } ?: CONSTANTS[e.name] ?: throw NotQuantity()
            }
            is Expr.Add -> {
                val terms = e.terms.map { eval(it, env, depth + 1) }
                val d = terms[0].dim
                terms.firstOrNull { it.dim != d }?.let { throw MathError("нельзя сложить ${text(d)} и ${text(it.dim)}") }
                Q(terms.sumOf { it.v }, d)
            }
            is Expr.Mul -> e.factors.map { eval(it, env, depth + 1) }.reduce { a, b -> Q(a.v * b.v, a.dim + b.dim) }
            is Expr.Pow -> {
                val b = eval(e.base, env, depth + 1)
                val x = eval(e.exp, env, depth + 1)
                if (!x.dim.none) throw MathError("показатель степени с единицами")
                power(b, x.v)
            }
            is Expr.Fn -> {
                val args = e.args.map { eval(it, env, depth + 1) }
                when {
                    e.name == "sqrt" && args.size == 1 -> power(args[0], 0.5)
                    e.name == "abs" && args.size == 1 -> Q(abs(args[0].v), args[0].dim)
                    args.any { !it.dim.none } -> throw MathError("${e.name} от величины с единицами")
                    e.name in Angles.UNITS -> Q(args[0].v * Math.PI / 180, NONE)
                    else -> Q(Numeric.function(e.name, args.map { it.v }), NONE)
                }
            }
            else -> throw NotQuantity()
        }
    }

    private fun power(b: Q, k: Double): Q {
        if (b.dim.none) return Q(Numeric.pow(b.v, k), NONE)
        val p = DoubleArray(N) { b.dim.p[it] * k }
        if (p.any { abs(it - it.roundToInt()) > 1e-9 }) throw MathError("дробная степень единиц")
        return Q(Numeric.pow(b.v, k), Dim(IntArray(N) { p[it].roundToInt() }))
    }

    private val BASE = listOf("м", "кг", "с", "А", "К", "моль")

    /** м/с², кг·м²/с², 1/с. */
    fun text(d: Dim): String {
        if (d.none) return "без единиц"
        fun part(signs: Int) = d.p.indices.filter { d.p[it] * signs > 0 }.joinToString("·") { i ->
            val k = abs(d.p[i])
            BASE[i] + if (k > 1) Printer.superscript(k.toString()) else ""
        }
        val num = part(1).ifEmpty { "1" }
        val den = part(-1)
        return if (den.isEmpty()) num else "$num/$den"
    }

    /**
     * The value in the unit it is best read in: one of the units written (km for a distance in
     * km), a quotient or product of two of them (km/ч), a named SI unit (Н, Дж), else SI base units.
     */
    fun format(q: Q, used: List<Unit>): String {
        if (q.dim.none) return Printer.value(q.v)
        val written = used.distinctBy { it.name }
        val candidates = buildList {
            for (u in written) if (u.dim == q.dim) add(u.factor to u.shown)
            for (a in written) for (b in written) {
                if (a !== b && a.dim - b.dim == q.dim) add(a.factor / b.factor to "${a.shown}/${b.shown}")
                if (a.name < b.name && a.dim + b.dim == q.dim) add(a.factor * b.factor to "${a.shown}·${b.shown}")
            }
            NAMED.firstOrNull { it.dim == q.dim }?.let { add(1.0 to it.shown) }
        }
        // SI units put together (Дж/с) read better by their own name (Вт); km/ч stays.
        val named = NAMED.firstOrNull { it.dim == q.dim }
        val first = candidates.firstOrNull()
        val (factor, name) = if (first != null && named != null && first.first == 1.0 && first.second != named.shown && first.second.any { it == '/' || it == '·' }) {
            1.0 to named.shown
        } else {
            first ?: (1.0 to text(q.dim))
        }
        return Printer.value(q.v / factor) + " " + name
    }

    private val NAMED = listOf("N", "J", "W", "Pa", "C", "V", "Ohm").mapNotNull { UNITS[it] }
}
