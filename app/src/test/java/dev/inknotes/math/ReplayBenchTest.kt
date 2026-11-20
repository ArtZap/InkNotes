package dev.inknotes.math

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

        fun reads(): Map<String, Map<List<Int>, Pair<List<String>, List<String>>>>? {
            System.getenv("REPLAY_READS")?.let(::File)?.takeIf { it.isFile }?.let { f ->
                val lines = if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()).bufferedReader().readLines() else f.readLines()
                return RecordedReader.parse(lines.asSequence())
            }
            val res = ReplayBenchTest::class.java.classLoader!!.getResourceAsStream("replay-reads.tsv.gz") ?: return null
            return RecordedReader.parse(GZIPInputStream(res).bufferedReader().readLines().asSequence())
        }

        /** One pass, everything as the app is now set (ReadOptions, MathPage). */
        fun measure(formulas: List<FormulaJudge.Formula>, reads: Map<String, Map<List<Int>, Pair<List<String>, List<String>>>>): Result {
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
                    val r = reads[f.id]
                    if (r == null) {
                        missing++
                        continue
                    }
                    val reader = RecordedReader(r)
                    val o = FormulaJudge.selected(f, reader).first
                    for (k in listOf("all", f.kind, f.set)) layout.getOrPut(k) { Tally() }.add(o)
                    val po = FormulaJudge.page(f, reader)
                    if (po != null) for (k in listOf("all", f.kind, f.set)) page.getOrPut(k) { Tally() }.add(po)
                    outcomes["${f.set}/${f.id}"] = o to po
                }
            } finally {
                Printer.numbers = numbers
                Tune.decimalPoint = point
            }
            return Result(outcomes, layout, page, missing)
        }

        fun report(r: Result): String = buildString {
            append("== повтор чтений с эмулятора (формул без записи: ${r.missing})\n")
            append("== формула выделена\n")
            for ((k, t) in r.layout) append("  %-30s %s\n".format(k, t))
            append("== страница (дописан «=»)\n")
            for ((k, t) in r.page) append("  %-30s %s\n".format(k, t))
        }
    }

    @Test
    fun replay() {
        val formulas = formulas() ?: return
        val reads = reads() ?: return
        val r = measure(formulas, reads)
        val text = report(r)
        println(text)
        File("build").mkdirs()
        File("build/replay-report.txt").writeText(text)
        File("build/replay-outcomes.tsv").writeText(r.outcomes.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k\t${v.first}\t${v.second ?: ""}" })
    }
}
