package dev.inknotes.math

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Formula recognition bench on handwritten datasets (CROHME InkML, see tools/fetch_crohme.sh).
 *
 * Level 1, layout: each formula is read by [MathPage.read] with a [PerfectReader] that knows
 * every symbol, so only the layout (fractions, exponents, roots, brackets…) and the parser can
 * go wrong. Level 1b, whole page: a handwritten "=" is drawn after formulas without letters and
 * the answer of [MathPage.analyze] is checked.
 *
 * Formulas are compared by value, not by text: letters get the same random values on both
 * sides (x²+2x+1 and (x+1)² are the same answer).
 *
 * Run: `java … dev.inknotes.math.FormulaBench <dir with .inkml> [report.json] [baseline.json]`.
 * With a baseline, exits 1 when a kind of formula lost more than 1 point of correct answers or
 * gained more than 0.5 point of wrong ones.
 */
object FormulaBench {

    enum class Outcome { RIGHT, WRONG, NONE }

    class Result(val sample: InkSample, val kind: String, val outcome: Outcome, val got: String?, val cut: Int)

    /** A kind of formula, for the per-kind figures: the most telling structure in it. */
    fun kind(truth: String): String = when {
        "\\int" in truth -> "integral"
        "\\sqrt" in truth -> "root"
        "\\frac" in truth -> "fraction"
        "^" in truth -> "power"
        "_" in truth -> "index"
        Regex("\\\\(sin|cos|tan|log|ln)").containsMatchIn(truth) -> "function"
        truth.any { it.isLetter() } -> "letters"
        else -> "numbers"
    }

    /** Whether InkNotes can represent the formula at all (coverage): every symbol known, the LaTeX parsed. */
    fun covered(s: InkSample): Expr? {
        if (s.symbols.isEmpty() || s.symbols.any { PerfectReader.labelText(it.label) == null }) return null
        // Every stroke is in some symbol.
        if (s.symbols.sumOf { it.strokes.size } != s.strokes.size) return null
        if (s.symbols.any { it.label == "=" }) return null
        val text = Latex.toParser(s.truth) ?: return null
        val e = runCatching { Parser.parse(text) }.getOrNull() ?: return null
        // Something must be computed both ways for the comparison to mean anything.
        return e.takeIf { values(it, 0) != null }
    }

    private val rnd = Random(7)
    private val trials = List(3) { List(26) { 1.1 + rnd.nextDouble() * 1.8 } }

    /** The value of [e] with letters a…z set as in trial [t]; null when it cannot be computed. */
    fun values(e: Expr, t: Int): String? {
        val env = Env()
        for (v in Algebra.freeVars(e)) {
            if (v in Algebra.CONSTANTS) continue
            val k = (v.lowercase().firstOrNull() ?: 'a') - 'a'
            // Letters that look the same in both cases are read in either (X and x): one value.
            val x = trials[t][k.coerceIn(0, 25)] + v.length * 0.01 + (if (v[0].isUpperCase() && v[0] !in "CKOPSUVWXYZ") 0.37 else 0.0)
            Calc.define(Expr.Sym(v), Expr.Num(x), env)
        }
        val a = runCatching { Calc.evaluate(e, env) }.getOrNull() ?: return null
        return when (a) {
            is Answer.Text -> a.lines.joinToString(";")
            is Answer.Grid -> a.rows.toString()
            is Answer.Graph, is Answer.Surface -> null
        }
    }

    fun same(a: Expr, b: Expr): Boolean = (0 until trials.size).all { t ->
        val x = values(a, t) ?: return false
        val y = values(b, t) ?: return false
        close(x, y)
    }

    private fun num(s: String): Double? = s.replace(Printer.MINUS, "-").replace(',', '.').toDoubleOrNull()

    private fun close(x: String, y: String): Boolean {
        if (x == y) return true
        val a = num(x) ?: return false
        val b = num(y) ?: return false
        return abs(a - b) <= 1e-5 * max(1.0, max(abs(a), abs(b)))
    }

    private fun axis(s: InkSample): Pair<Float, Float> {
        val boxes = s.symbols.filter { it.label.length == 1 && it.label[0].isLetterOrDigit() }.map { sym ->
            val ink = sym.strokes.map { s.strokes[it] }
            Box.of(ink)
        }.ifEmpty { listOf(Box.of(s.strokes)) }
        val h = MathLayout.median(boxes.map { it.height })!!.coerceAtLeast(8f)
        return h to MathLayout.median(boxes.map { it.cy })!!
    }

    fun layout(s: InkSample, expected: Expr): Result {
        val reader = PerfectReader(s)
        val (h, ax) = axis(s)
        val got = runCatching { MathPage.read(s.strokes, h, ax, reader, MathPage.Prefer.FIRST) }.getOrNull()
        val outcome = when {
            got == null -> Outcome.NONE
            same(got, expected) -> Outcome.RIGHT
            else -> Outcome.WRONG
        }
        return Result(s, kind(s.truth), outcome, got?.let { runCatching { Printer.expr(it) }.getOrDefault(it.toString()) }, reader.cut)
    }

    /** The formula with a handwritten "=" after it, through the whole page analysis. */
    fun page(s: InkSample, expected: Expr): Result? {
        if (Algebra.freeVars(expected).any { it !in Algebra.CONSTANTS }) return null
        // "3 =" has nothing to compute: no answer is right there.
        if (expected is Expr.Num || (expected is Expr.Mul && expected.factors.all { it is Expr.Num })) return null
        val want = values(expected, 0) ?: return null
        val (h, ax0) = axis(s)
        // A fraction's line is its bar, about the middle of the whole formula.
        val ax = if ("\\frac" in s.truth) Box.of(s.strokes).cy else ax0
        val right = s.strokes.maxOf { it.right }
        val x0 = right + 0.5f * h
        val w = 0.7f * h
        fun dash(y: Float, order: Int) = Ink(FloatArray(8) { x0 + w * it / 7 }, FloatArray(8) { y + (if (it % 3 == 0) 0.3f else 0f) }, order)
        val n = s.strokes.size
        val strokes = s.strokes + dash(ax - 0.16f * h, n) + dash(ax + 0.16f * h, n + 1)
        val reader = PerfectReader(InkSample(s.id, s.truth, strokes, s.symbols))
        val answers = runCatching { MathPage.analyze(strokes, reader, 4000f) }.getOrDefault(emptyList())
        val a = answers.firstOrNull { it.x > x0 && abs(it.y - ax) < h }
        val text = (a?.answer as? Answer.Text)?.lines?.joinToString(";")
        val outcome = when {
            text == null || a.error -> Outcome.NONE
            close(text, want) -> Outcome.RIGHT
            else -> Outcome.WRONG
        }
        return Result(s, kind(s.truth), outcome, text, reader.cut)
    }

    class Figures(var n: Int = 0, var right: Int = 0, var wrong: Int = 0) {
        fun pct(k: Int) = if (n == 0) 0.0 else 100.0 * k / n
        override fun toString() = "%5d  верно %5.1f%%  неверно %5.1f%%  нет ответа %5.1f%%".format(n, pct(right), pct(wrong), pct(n - right - wrong))
    }

    fun figures(results: List<Result>): Map<String, Figures> {
        val out = sortedMapOf<String, Figures>()
        for (r in results) {
            for (k in listOf("all", r.kind)) {
                val f = out.getOrPut(k) { Figures() }
                f.n++
                if (r.outcome == Outcome.RIGHT) f.right++
                if (r.outcome == Outcome.WRONG) f.wrong++
            }
        }
        return out
    }

    fun json(level: Map<String, Map<String, Figures>>): String = level.entries.joinToString(",\n", "{\n", "\n}") { (name, m) ->
        "  \"$name\": {" + m.entries.joinToString(", ") { (k, f) -> "\"$k\": [${f.n}, ${f.right}, ${f.wrong}]" } + "}"
    }

    private fun parseJson(text: String): Map<String, Map<String, IntArray>> {
        val out = HashMap<String, Map<String, IntArray>>()
        for (m in Regex("\"(\\w+)\": \\{([^}]*)\\}").findAll(text)) {
            out[m.groupValues[1]] = Regex("\"(\\w+)\": \\[(\\d+), (\\d+), (\\d+)\\]").findAll(m.groupValues[2]).associate {
                it.groupValues[1] to intArrayOf(it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toInt())
            }
        }
        return out
    }

    /** Regressions against [baseline]: lines describing each, empty when there are none. */
    fun regressions(now: Map<String, Map<String, Figures>>, baseline: String): List<String> {
        val base = parseJson(baseline)
        val out = ArrayList<String>()
        for ((level, kinds) in now) for ((k, f) in kinds) {
            val b = base[level]?.get(k) ?: continue
            if (b[0] < 20) continue
            val right0 = 100.0 * b[1] / b[0]
            val wrong0 = 100.0 * b[2] / b[0]
            if (f.pct(f.right) < right0 - 1.0) out += "$level/$k: верно %.1f%% → %.1f%%".format(right0, f.pct(f.right))
            if (f.pct(f.wrong) > wrong0 + 0.5) out += "$level/$k: неверно %.1f%% → %.1f%%".format(wrong0, f.pct(f.wrong))
        }
        return out
    }

    fun run(dir: File, limit: Int = Int.MAX_VALUE, log: (String) -> Unit = ::println): Map<String, Map<String, Figures>> {
        val samples = InkMl.loadDir(dir, limit)
        val coveredSamples = samples.mapNotNull { s -> covered(s)?.let { s to it } }
        log("формул: ${samples.size}, InkNotes умеет представить: ${coveredSamples.size} (покрытие %.1f%%)".format(100.0 * coveredSamples.size / max(1, samples.size)))
        val layout = coveredSamples.map { (s, e) -> layout(s, e) }
        val page = coveredSamples.mapNotNull { (s, e) -> page(s, e) }
        val fails = File(System.getProperty("bench.fails") ?: "/dev/null")
        fails.printWriter().use { w ->
            for (r in layout) if (r.outcome != Outcome.RIGHT) w.println("layout\t${r.outcome}\t${r.kind}\t${r.sample.id}\t${r.sample.truth}\t${r.got}\tcut=${r.cut}")
            for (r in page) if (r.outcome != Outcome.RIGHT) w.println("page\t${r.outcome}\t${r.kind}\t${r.sample.id}\t${r.sample.truth}\t${r.got}\tcut=${r.cut}")
        }
        val res = linkedMapOf("layout" to figures(layout), "page" to figures(page))
        for ((level, m) in res) {
            log("== $level")
            for ((k, f) in m) log("  %-9s %s".format(k, f))
        }
        return res
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Printer.numbers = Printer.Numbers.DECIMAL
        val res = run(File(args[0]), System.getProperty("bench.limit")?.toInt() ?: Int.MAX_VALUE)
        args.getOrNull(1)?.let { File(it).writeText(json(res)) }
        args.getOrNull(2)?.let { b ->
            val reg = regressions(res, File(b).readText())
            reg.forEach { println("РЕГРЕССИЯ $it") }
            if (reg.isNotEmpty()) kotlin.system.exitProcess(1)
        }
    }
}
