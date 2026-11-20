package dev.inknotes.math

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The formula bench (see [FormulaBench]) as a unit test: runs only when CROHME_DIR points at
 * the unpacked CROHME data (tools/formula-bench.sh sets it), and fails on a regression against
 * resources/formula-baseline.json. The report goes to build/formula-report.json.
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
        try {
            val res = FormulaBench.run(File(root, "CROHME2013_data/TestINKMLGT"))
            File("build").mkdirs()
            File("build/formula-report.json").writeText(FormulaBench.json(res))
            val baseline = FormulaBenchTest::class.java.classLoader!!.getResource("formula-baseline.json")!!.readText()
            val reg = FormulaBench.regressions(res, baseline)
            assertTrue(reg.joinToString("\n"), reg.isEmpty())
        } finally {
            Printer.numbers = numbers
        }
    }
}
