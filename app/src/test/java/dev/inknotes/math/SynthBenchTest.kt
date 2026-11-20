package dev.inknotes.math

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The synthetic formulas ([SynthFormulas]: printed layout, CROHME handwriting) read by
 * [PerfectReader]: measures the layout and the page, including equations, which CROHME does not
 * have. Needs CROHME (tools/formula-bench.sh --fetch); skipped without it. Writes
 * build/synth-report.txt and build/synth-outcomes.tsv, and fails when more formulas turned wrong
 * than stopped being wrong against resources/synth-outcomes.tsv.
 */
class SynthBenchTest {

    @Test
    fun synthetic() {
        val root = System.getenv("CROHME_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val bank = SynthFormulas.bank(root) ?: return
        val numbers = Printer.numbers
        val point = Tune.decimalPoint
        Printer.numbers = Printer.Numbers.DECIMAL
        Tune.decimalPoint = 1f
        val layout = sortedMapOf<String, ReplayBenchTest.Tally>()
        val page = sortedMapOf<String, ReplayBenchTest.Tally>()
        val outcomes = LinkedHashMap<String, Pair<String, String?>>()
        val wrong = ArrayList<String>()
        try {
            val synth = SynthFormulas.generate(bank)
            for (sy in synth) {
                val f = sy.formula
                // What the device bench gets (FormulaExportTest) reads back the same.
                val back = FormulaJudge.parse(SynthFormulas.exportLine(f))
                assertTrue("${f.id}: export", back != null && back.strokes.size == f.strokes.size && back.pageWant == f.pageWant && back.kind == f.kind)
                val reader = PerfectReader(sy.sample)
                val o = if (FormulaJudge.hasSelected(f)) FormulaJudge.selected(f, reader).first else "SKIP"
                if (o != "SKIP") for (k in listOf("all", f.kind)) layout.getOrPut(k) { ReplayBenchTest.Tally() }.add(o)
                val po = FormulaJudge.page(f, reader)
                if (po != null) for (k in listOf("all", f.kind)) page.getOrPut(k) { ReplayBenchTest.Tally() }.add(po)
                outcomes["${f.set}/${f.id}"] = o to po
                if (o == "WRONG" || po == "WRONG") wrong += "${f.id}: ${sy.source} → $o / $po"
            }
        } finally {
            Printer.numbers = numbers
            Tune.decimalPoint = point
        }
        val text = buildString {
            append("== синтетические формулы, идеальное чтение символов (${outcomes.size})\n")
            append("== формула выделена\n")
            for ((k, t) in layout) append("  %-30s %s\n".format(k, t))
            append("== страница (дописан «=»; уравнения — корни)\n")
            for ((k, t) in page) append("  %-30s %s\n".format(k, t))
            append("== неверные\n")
            for (w in wrong.take(40)) append("  $w\n")
        }
        println(text)
        File("build").mkdirs()
        File("build/synth-report.txt").writeText(text)
        File("build/synth-outcomes.tsv").writeText(outcomes.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k\t${v.first}\t${v.second ?: ""}" })
        assertTrue("синтетических формул слишком мало: ${outcomes.size}", outcomes.size >= SynthFormulas.PER_KIND * 6)
        val base = SynthBenchTest::class.java.classLoader!!.getResourceAsStream("synth-outcomes.tsv")?.bufferedReader()?.readLines() ?: return
        var worse = 0
        var better = 0
        val turned = ArrayList<String>()
        for (line in base) {
            val p = line.split('\t')
            if (p.size < 2) continue
            val now = outcomes[p[0]] ?: continue
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
