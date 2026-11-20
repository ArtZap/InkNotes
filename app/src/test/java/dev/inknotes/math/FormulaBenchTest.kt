package dev.inknotes.math

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The formula bench (see [FormulaBench]) as a unit test: runs only when CROHME_DIR points at
 * the unpacked CROHME data (tools/formula-bench.sh sets it; CI fetches it), on every set of
 * [FormulaBench.SETS]. Compared formula by formula with resources/formula-outcomes.tsv: fails
 * when more formulas became wrong than stopped being wrong, or right answers were lost.
 *
 * Writes build/formula-report.txt (figures, risk–coverage, changed formulas),
 * build/formula-report.json and build/formula-outcomes.tsv: after an intended change,
 * `tools/formula-bench.sh --update` makes the last one the new baseline.
 */
class FormulaBenchTest {
    @Test
    fun noRegressionOnCrohme() {
        val root = System.getenv("CROHME_DIR")?.let(::File)?.takeIf { it.isDirectory }
        // Without the data the test is reported as skipped, not as passed.
        org.junit.Assume.assumeTrue("CROHME_DIR is not set: the formula bench is skipped (tools/formula-bench.sh)", root != null)
        checkNotNull(root)
        val numbers = Printer.numbers
        Printer.numbers = Printer.Numbers.DECIMAL
        // CROHME is written the English way: 8.5 with a decimal point.
        val point = Tune.decimalPoint
        Tune.decimalPoint = 1f
        try {
            val report = StringBuilder()
            val runs = FormulaBench.SETS.map { set -> FormulaBench.run(File(root, set), log = { report.append(it).append('\n') }, set = set) }
            File("build").mkdirs()
            File("build/formula-outcomes.tsv").writeText(FormulaBench.outcomes(runs))
            File("build/formula-report.json").writeText(
                runs.joinToString(",\n", "{\n", "\n}") { r -> "\"${r.set}\": " + FormulaBench.json(r.levels.mapValues { FormulaBench.figures(it.value) }) },
            )
            val baseline = FormulaBenchTest::class.java.classLoader!!.getResource("formula-outcomes.tsv")?.readText()
            val comparison = baseline?.let { FormulaBench.compare(runs, it) }
            report.append(comparison?.toString() ?: "нет базы formula-outcomes.tsv: сравнивать не с чем\n")
            File("build/formula-report.txt").writeText(report.toString())
            if (comparison != null) assertTrue(comparison.regressions.joinToString("\n") + "\n" + comparison, comparison.regressions.isEmpty())
        } finally {
            Printer.numbers = numbers
            Tune.decimalPoint = point
        }
    }
}
