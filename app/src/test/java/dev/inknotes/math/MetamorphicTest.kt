package dev.inknotes.math

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.random.Random

/**
 * The CROHME formulas changed as by [Metamorphic.CHANGES] (scaled, turned, slanted, shaken,
 * written in another order…): how many answers each change turns. Runs with the formula bench
 * (CROHME_DIR); fails when a change turns clearly more answers than in
 * resources/metamorphic-baseline.tsv. Report: build/metamorphic-report.txt; the counts of this
 * run: build/metamorphic-baseline.tsv (tools/formula-bench.sh --update takes them).
 */
class MetamorphicTest {
    @Test
    fun answersSurviveHarmlessChanges() {
        val root = System.getenv("CROHME_DIR")?.let(::File)?.takeIf { it.isDirectory }
        org.junit.Assume.assumeTrue("CROHME_DIR is not set: skipped (tools/formula-bench.sh)", root != null)
        checkNotNull(root)
        val numbers = Printer.numbers
        Printer.numbers = Printer.Numbers.DECIMAL
        val point = Tune.decimalPoint
        Tune.decimalPoint = 1f // CROHME: the English way
        try {
            val samples = FormulaBench.SETS.flatMap { InkMl.loadDir(File(root, it)) }.mapNotNull { s -> FormulaBench.covered(s)?.let { s to it } }
            val base = samples.map { (s, e) -> FormulaBench.answer(s, e) }
            val answered = base.count { it.first != null }
            val report = StringBuilder("формул: ${samples.size}, с ответом: $answered\n")
            report.append("  изменение                 ответ изменился   верный стал не верным\n")
            val counts = StringBuilder()
            for (change in Metamorphic.CHANGES) {
                var turned = 0
                var broke = 0
                for ((i, pair) in samples.withIndex()) {
                    val (s, e) = pair
                    val b = base[i]
                    if (b.first == null) continue
                    val t = runCatching { FormulaBench.answer(change.apply(s, Random(s.id.hashCode())), e) }.getOrDefault(null to null)
                    if (t.first != b.first) turned++
                    if (b.second == true && t.second != true) broke++
                }
                report.append("  %-24s %6d (%4.1f%%)   %6d\n".format(change.name, turned, 100.0 * turned / max(1, answered), broke))
                counts.append(change.name).append('\t').append(turned).append('\n')
            }
            File("build").mkdirs()
            File("build/metamorphic-report.txt").writeText(report.toString())
            File("build/metamorphic-baseline.tsv").writeText(counts.toString())
            println(report)
            val baseline = MetamorphicTest::class.java.classLoader!!.getResource("metamorphic-baseline.tsv")?.readText() ?: return
            val before = baseline.lines().filter { it.isNotBlank() }.associate { it.substringBefore('\t') to it.substringAfter('\t').toInt() }
            val now = counts.lines().filter { it.isNotBlank() }.associate { it.substringBefore('\t') to it.substringAfter('\t').toInt() }
            val worse = now.filter { (k, v) -> before[k] != null && v > before[k]!! + max(2, answered / 200) }
            assertTrue("стало хрупче: " + worse.map { (k, v) -> "$k ${before[k]} → $v" } + "\n" + report, worse.isEmpty())
        } finally {
            Printer.numbers = numbers
            Tune.decimalPoint = point
        }
    }
}
