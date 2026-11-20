package dev.inknotes.math

import java.io.File
import kotlin.math.abs
import kotlin.math.max

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

    /** UNSURE: an answer the app shows as unsure (grey, with others to choose from), not as right or wrong. */
    enum class Outcome { RIGHT, WRONG, NONE, UNSURE }

    /**
     * [doubts]: how many of the moved layouts ([MathLayout.Shift.CHECKS]) give another value, the
     * signal for "unsure"; [correct]: whether the first answer is right, unsure or not (null: no
     * answer). Both for the risk–coverage table.
     */
    class Result(
        val sample: InkSample,
        val kind: String,
        val outcome: Outcome,
        val got: String?,
        val cut: Int,
        val doubts: Int = 0,
        val correct: Boolean? = null,
    )

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

    fun values(e: Expr, t: Int): String? = FormulaValues.values(e, t)

    fun same(a: Expr, b: Expr): Boolean = FormulaValues.same(a, b)

    private fun close(x: String, y: String): Boolean = FormulaValues.close(x, y)

    /**
     * Line height and middle as the app takes them for a lasso selection
     * ([MathPage.formulaSource]): the height three quarters up the stroke heights, the middle of
     * the strokes about that tall. A median over all symbols would be the index's height in T₈₅₀.
     */
    fun axis(s: InkSample): Pair<Float, Float> {
        val heights = s.strokes.filter { !it.isDash }.map { it.height }.sorted()
        val h = max(heights.getOrNull(heights.size * 3 / 4) ?: s.strokes.maxOf { it.height }, 8f)
        val mids = s.strokes.filter { it.height in 0.4f * h..1.6f * h }.map { it.cy }
        return h to (MathLayout.median(mids) ?: Box.of(s.strokes).cy)
    }

    /**
     * One formula for the device run (FormulaReadTest): set, id, kind, line height and middle,
     * the value of the formula, the value a "=" after it must give ("" when the page level does
     * not apply, as in [page]), the middle the "=" is drawn at, and the strokes ("x y,x y;…" in
     * stroke order).
     */
    fun exportLine(set: String, s: InkSample, expected: Expr): String? {
        val fp = FormulaValues.fingerprint(expected) ?: return null
        val (h, ax) = axis(s)
        val pageWant = if (Algebra.freeVars(expected).any { it !in Algebra.CONSTANTS } || expected is Expr.Num ||
            (expected is Expr.Mul && expected.factors.all { it is Expr.Num })
        ) {
            ""
        } else {
            values(expected, 0).orEmpty()
        }
        val strokes = s.strokes.sortedBy { it.order }.joinToString(";") { k -> k.xs.indices.joinToString(",") { "%.1f %.1f".format(java.util.Locale.ROOT, k.xs[it], k.ys[it]) } }
        val pageAx = if ("\\frac" in s.truth) Box.of(s.strokes).cy else ax
        return listOf(
            set, s.id, kind(s.truth), "%.2f".format(java.util.Locale.ROOT, h), "%.2f".format(java.util.Locale.ROOT, ax), fp, pageWant,
            "%.2f".format(java.util.Locale.ROOT, pageAx), strokes,
        ).joinToString("\t")
    }

    /** The answer read at once, without the checks: as printed, and whether right (null: none). */
    fun answer(s: InkSample, expected: Expr): Pair<String?, Boolean?> {
        val (h, ax) = axis(s)
        val got = runCatching { MathPage.read(s.strokes, h, ax, PerfectReader(s), MathPage.Prefer.FIRST) }.getOrNull() ?: return null to null
        return runCatching { Printer.expr(got) }.getOrDefault(got.toString()) to same(got, expected)
    }

    fun layout(s: InkSample, expected: Expr): Result {
        val reader = PerfectReader(s)
        val (h, ax) = axis(s)
        val got = runCatching { MathPage.read(s.strokes, h, ax, reader, MathPage.Prefer.FIRST) }.getOrNull()
        // Read again with the layout's thresholds moved: another value means the app shows
        // the answer as unsure, not as an answer.
        val doubts = if (got == null) 0 else MathLayout.Shift.CHECKS.count { shift ->
            val again = runCatching { MathPage.read(s.strokes, h, ax, reader, MathPage.Prefer.FIRST, shift = shift) }.getOrNull()
            again != null && again != got && !same(again, got)
        }
        val correct = got?.let { same(it, expected) }
        val outcome = when {
            got == null -> Outcome.NONE
            doubts > 0 -> Outcome.UNSURE
            correct == true -> Outcome.RIGHT
            else -> Outcome.WRONG
        }
        return Result(s, kind(s.truth), outcome, got?.let { runCatching { Printer.expr(it) }.getOrDefault(it.toString()) }, reader.cut, doubts, correct)
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
        val answers = runCatching { MathPage.analyze(strokes, reader, 4000f) }.getOrDefault(emptyList()).filter { !it.missing }
        val a = answers.firstOrNull { it.x > x0 && abs(it.y - ax) < h }
        val text = (a?.answer as? Answer.Text)?.lines?.joinToString(";")
        val correct = if (a == null || text == null || a.error) null else close(text, want)
        val outcome = when {
            correct == null -> Outcome.NONE
            a?.unsure == true -> Outcome.UNSURE
            correct -> Outcome.RIGHT
            else -> Outcome.WRONG
        }
        return Result(s, kind(s.truth), outcome, text, reader.cut, a?.others?.size ?: 0, correct)
    }

    class Figures(var n: Int = 0, var right: Int = 0, var wrong: Int = 0, var unsure: Int = 0) {
        fun pct(k: Int) = if (n == 0) 0.0 else 100.0 * k / n
        override fun toString() = "%5d  верно %5.1f%%  неверно %5.1f%%  не уверен %5.1f%%  нет ответа %5.1f%%".format(n, pct(right), pct(wrong), pct(unsure), pct(n - right - wrong - unsure))
    }

    fun figures(results: List<Result>): Map<String, Figures> {
        val out = sortedMapOf<String, Figures>()
        for (r in results) {
            for (k in listOf("all", r.kind)) {
                val f = out.getOrPut(k) { Figures() }
                f.n++
                if (r.outcome == Outcome.RIGHT) f.right++
                if (r.outcome == Outcome.WRONG) f.wrong++
                if (r.outcome == Outcome.UNSURE) f.unsure++
            }
        }
        return out
    }

    fun json(level: Map<String, Map<String, Figures>>): String = level.entries.joinToString(",\n", "{\n", "\n}") { (name, m) ->
        "  \"$name\": {" + m.entries.joinToString(", ") { (k, f) -> "\"$k\": [${f.n}, ${f.right}, ${f.wrong}, ${f.unsure}]" } + "}"
    }

    /** One run on one set of formulas: the results of both levels. */
    class Run(val set: String, val layout: List<Result>, val page: List<Result>) {
        val levels get() = linkedMapOf("layout" to layout, "page" to page)
    }

    /**
     * The outcome of every formula, one per line (set, level, formula id, outcome): kept as
     * resources/formula-outcomes.tsv, compared formula by formula with the next run.
     */
    fun outcomes(runs: List<Run>): String = buildString {
        for (r in runs) for ((level, results) in r.levels) for (x in results.sortedBy { it.sample.id }) {
            append(r.set).append('\t').append(level).append('\t').append(x.sample.id).append('\t').append(x.outcome).append('\n')
        }
    }

    /**
     * Formula by formula against [baseline] (an [outcomes] text). A regression: more formulas
     * became wrong than stopped being wrong, or more right answers were lost than gained, by
     * more than half a percent of the formulas. Changed formulas are listed either way.
     */
    class Comparison(val newlyWrong: List<String>, val noLongerWrong: List<String>, val lostRight: List<String>, val gainedRight: List<String>, val n: Int) {
        val regressions: List<String>
            get() = buildList {
                if (newlyWrong.size > noLongerWrong.size) add("неверными стали ${newlyWrong.size} формул, перестали быть неверными ${noLongerWrong.size}")
                if (lostRight.size - gainedRight.size > n / 200) add("верных потеряно ${lostRight.size}, найдено ${gainedRight.size} (из $n)")
            }

        override fun toString() = buildString {
            append("по формулам: стали неверными ${newlyWrong.size}, перестали ${noLongerWrong.size}; верных потеряно ${lostRight.size}, найдено ${gainedRight.size}\n")
            for (k in newlyWrong.take(30)) append("  стала неверной: $k\n")
            for (k in lostRight.take(30)) append("  перестала быть верной: $k\n")
        }
    }

    fun compare(runs: List<Run>, baseline: String): Comparison {
        val before = baseline.lineSequence().filter { it.isNotBlank() }.associate { line ->
            val p = line.split('\t')
            "${p[0]}/${p[1]}/${p[2]}" to p[3]
        }
        val newlyWrong = ArrayList<String>()
        val noLongerWrong = ArrayList<String>()
        val lostRight = ArrayList<String>()
        val gainedRight = ArrayList<String>()
        var n = 0
        for (r in runs) for ((level, results) in r.levels) for (x in results) {
            n++
            val key = "${r.set}/$level/${x.sample.id}"
            val was = before[key] ?: continue
            val now = x.outcome.name
            val label = "$key  ${x.sample.truth}  →  ${x.got}"
            if (now == "WRONG" && was != "WRONG") newlyWrong += label
            if (was == "WRONG" && now != "WRONG") noLongerWrong += label
            if (was == "RIGHT" && now != "RIGHT") lostRight += label
            if (now == "RIGHT" && was != "RIGHT") gainedRight += label
        }
        return Comparison(newlyWrong, noLongerWrong, lostRight, gainedRight, n)
    }

    /**
     * Risk against coverage: answers shown when at most t of the moved layouts disagree, for
     * t = 0…8. Coverage: shown of all; risk: wrong of shown. The app shows at t = 0.
     */
    fun riskCoverage(results: List<Result>): String = buildString {
        val n = results.size
        val answered = results.filter { it.correct != null }
        append("  порог  показано  неверных среди показанных\n")
        for (t in 0..MathLayout.Shift.CHECKS.size) {
            val shown = answered.filter { it.doubts <= t }
            val wrong = shown.count { it.correct == false }
            append("  %5d  %6.1f%%  %6.1f%%  (%d из %d)\n".format(t, 100.0 * shown.size / max(1, n), 100.0 * wrong / max(1, shown.size), wrong, shown.size))
        }
    }

    fun run(dir: File, limit: Int = Int.MAX_VALUE, log: (String) -> Unit = ::println, set: String = dir.parentFile?.name + "/" + dir.name): Run {
        val samples = InkMl.loadDir(dir, limit)
        val coveredSamples = samples.mapNotNull { s -> covered(s)?.let { s to it } }
        log("$set — формул: ${samples.size}, InkNotes умеет представить: ${coveredSamples.size} (покрытие %.1f%%)".format(100.0 * coveredSamples.size / max(1, samples.size)))
        val layout = coveredSamples.map { (s, e) -> layout(s, e) }
        val page = coveredSamples.mapNotNull { (s, e) -> page(s, e) }
        val fails = File(System.getProperty("bench.fails") ?: "/dev/null")
        fails.printWriter().use { w ->
            for (r in layout) if (r.outcome != Outcome.RIGHT) w.println("layout\t${r.outcome}\t${r.kind}\t${r.sample.id}\t${r.sample.truth}\t${r.got}\tcut=${r.cut}")
            for (r in page) if (r.outcome != Outcome.RIGHT) w.println("page\t${r.outcome}\t${r.kind}\t${r.sample.id}\t${r.sample.truth}\t${r.got}\tcut=${r.cut}")
        }
        val run = Run(set, layout, page)
        for ((level, results) in run.levels) {
            log("== $level")
            for ((k, f) in figures(results)) log("  %-9s %s".format(k, f))
        }
        log("== риск — покрытие (раскладка)")
        log(riskCoverage(layout))
        return run
    }

    /**
     * Sets of the bench under a CROHME root: tuning is done on the 2013 training part; the 2013
     * test part and 2012 are the check.
     */
    val SETS = listOf("CROHME2013_data/TrainINKML", "CROHME2013_data/TestINKMLGT", "CROHME2012_data/testDataGT")

    @JvmStatic
    fun main(args: Array<String>) {
        Printer.numbers = Printer.Numbers.DECIMAL
        val run = run(File(args[0]), System.getProperty("bench.limit")?.toInt() ?: Int.MAX_VALUE)
        args.getOrNull(1)?.let { File(it).writeText(json(run.levels.mapValues { figures(it.value) })) }
    }
}
