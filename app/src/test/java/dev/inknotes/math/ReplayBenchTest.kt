package dev.inknotes.math

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The device bench (FormulaReadTest) on a computer: the same CROHME formulas, read with what
 * ML Kit read for them on the emulator (resources/replay-reads.tsv.gz, or the file REPLAY_READS
 * names). Needs CROHME (tools/formula-bench.sh --fetch); skipped without it or the readings.
 * Seconds instead of a CI run, so that choosing among the reader's guesses can be measured
 * while it is changed. Writes build/replay-report.txt and build/replay-outcomes.tsv.
 */
class ReplayBenchTest {

    class Tally(var n: Int = 0, var right: Int = 0, var wrong: Int = 0, var unsure: Int = 0) {
        fun add(o: String) {
            n++
            when (o) {
                "RIGHT" -> right++
                "WRONG" -> wrong++
                "UNSURE" -> unsure++
            }
        }

        val wrongShown: Double get() = if (right + wrong == 0) 0.0 else 100.0 * wrong / (right + wrong)

        override fun toString(): String {
            fun p(k: Int) = if (n == 0) 0.0 else 100.0 * k / n
            return "%5d  верно %5.1f%%  неверно %5.1f%%  не уверен %5.1f%%  нет ответа %5.1f%%  | неверных среди показанных %5.1f%%"
                .format(n, p(right), p(wrong), p(unsure), p(n - right - wrong - unsure), wrongShown)
        }
    }

    /** Outcomes of one pass: by formula ("set/id" → selected, page) and tallied. */
    class Result(val outcomes: Map<String, Pair<String, String?>>, val layout: Map<String, Tally>, val page: Map<String, Tally>, val missing: Int)

    companion object {
        fun formulas(): List<FormulaJudge.Formula>? {
            val root = System.getenv("CROHME_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return null
            val numbers = Printer.numbers
            Printer.numbers = Printer.Numbers.DECIMAL
            try {
                return FormulaBench.SETS.drop(1).flatMap { set ->
                    InkMl.loadDir(File(root, set)).mapNotNull { s -> FormulaBench.covered(s)?.let { FormulaBench.exportLine(set, s, it) } }
                }.mapNotNull { FormulaJudge.parse(it) }
            } finally {
                Printer.numbers = numbers
            }
        }

        /** The synthetic formulas and equations (SynthFormulas), as the device reads them. */
        fun synthetic(): List<FormulaJudge.Formula> {
            val root = System.getenv("CROHME_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return emptyList()
            val bank = SynthFormulas.bank(root) ?: return emptyList()
            // Through the exported line, as on the device (strokes rounded to 0.1); the values as
            // FormulaExportTest computes them, in decimals.
            val numbers = Printer.numbers
            Printer.numbers = Printer.Numbers.DECIMAL
            try {
                return SynthFormulas.generate(bank).mapNotNull { FormulaJudge.parse(SynthFormulas.exportLine(it.formula)) }
            } finally {
                Printer.numbers = numbers
            }
        }

        fun reads(): Map<String, Map<List<Int>, Pair<List<String>, List<String>>>>? {
            System.getenv("REPLAY_READS")?.let(::File)?.takeIf { it.isFile }?.let { f ->
                val lines = if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()).bufferedReader().readLines() else f.readLines()
                return RecordedReader.parse(lines.asSequence())
            }
            val res = ReplayBenchTest::class.java.classLoader!!.getResourceAsStream("replay-reads.tsv.gz") ?: return null
            return RecordedReader.parse(GZIPInputStream(res).bufferedReader().readLines().asSequence())
        }

        /** One pass, everything as the app is now set (ReadOptions, MathPage). */
        /** [wrap]: a voice put over what ML Kit read (a classifier to measure), none by default. */
        fun measure(formulas: List<FormulaJudge.Formula>, reads: Map<String, Map<List<Int>, Pair<List<String>, List<String>>>>, wrap: (RunReader) -> RunReader = { it }): Result {
            val numbers = Printer.numbers
            val point = Tune.decimalPoint
            Printer.numbers = Printer.Numbers.DECIMAL
            Tune.decimalPoint = 1f // CROHME is written the English way (8.5), as on the device
            val layout = sortedMapOf<String, Tally>()
            val page = sortedMapOf<String, Tally>()
            val outcomes = LinkedHashMap<String, Pair<String, String?>>()
            var missing = 0
            try {
                for (f in formulas) {
                    // Nothing recorded: nothing was read on the device either (no "=" found, say).
                    val r = reads["${f.set}/${f.id}"] ?: reads[f.id] ?: emptyMap<List<Int>, Pair<List<String>, List<String>>>().also { missing++ }
                    val reader = wrap(RecordedReader(r))
                    val o = if (FormulaJudge.hasSelected(f)) FormulaJudge.selected(f, reader).first else "SKIP"
                    // "all" stays CROHME, as on the emulator.
                    val keys = if (f.set == "synthetic") listOf(f.set, "${f.set}/${f.kind}") else listOf("all", f.kind, f.set)
                    if (o != "SKIP") for (k in keys) layout.getOrPut(k) { Tally() }.add(o)
                    val po = FormulaJudge.page(f, reader)
                    if (po != null) for (k in keys) page.getOrPut(k) { Tally() }.add(po)
                    outcomes["${f.set}/${f.id}"] = o to po
                }
            } finally {
                Printer.numbers = numbers
                Tune.decimalPoint = point
            }
            return Result(outcomes, layout, page, missing)
        }

        fun report(r: Result): String = buildString {
            append("== повтор чтений с эмулятора (формул без записи — на устройстве ничего не читалось: ${r.missing})\n")
            append("== формула выделена\n")
            for ((k, t) in r.layout) append("  %-30s %s\n".format(k, t))
            append("== страница (дописан «=»)\n")
            for ((k, t) in r.page) append("  %-30s %s\n".format(k, t))
        }
    }

    /**
     * Fails when more formulas turned wrong than stopped being wrong against
     * resources/replay-outcomes.tsv (selected or on a page), as FormulaBenchTest does for the
     * layout. A change that is meant to trade some for more: copy build/replay-outcomes.tsv there.
     */
    @Test
    fun replay() {
        val formulas = formulas() ?: return
        val reads = reads() ?: return
        val r = measure(formulas + synthetic(), reads)
        val text = report(r)
        println(text)
        File("build").mkdirs()
        File("build/replay-report.txt").writeText(text)
        File("build/replay-outcomes.tsv").writeText(r.outcomes.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k\t${v.first}\t${v.second ?: ""}" })
        val base = ReplayBenchTest::class.java.classLoader!!.getResourceAsStream("replay-outcomes.tsv")?.bufferedReader()?.readLines() ?: return
        var worse = 0
        var better = 0
        val turned = ArrayList<String>()
        for (line in base) {
            val p = line.split('\t')
            if (p.size < 2) continue
            val now = r.outcomes[p[0]] ?: continue
            for ((was, got) in listOf(p[1] to now.first, p.getOrElse(2) { "" } to (now.second ?: ""))) {
                if (was != "WRONG" && got == "WRONG") {
                    worse++
                    turned += p[0]
                }
                if (was == "WRONG" && got != "WRONG") better++
            }
        }
        assertTrue("неверными стали $worse (${turned.take(10)}), перестали быть неверными $better", worse <= better)
    }
}
