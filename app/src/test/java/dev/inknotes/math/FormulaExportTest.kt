package dev.inknotes.math

import org.junit.Test
import java.io.File

/**
 * Writes the formulas of the check sets for the device run (FormulaReadTest) when
 * FORMULA_EXPORT names the file and CROHME_DIR the data; does nothing otherwise. The values
 * are computed here, on the JVM, the device only reads and compares.
 */
class FormulaExportTest {
    @Test
    fun export() {
        val out = System.getenv("FORMULA_EXPORT")?.let(::File) ?: return
        val root = System.getenv("CROHME_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val numbers = Printer.numbers
        Printer.numbers = Printer.Numbers.DECIMAL
        try {
            val lines = FormulaBench.SETS.drop(1).flatMap { set ->
                InkMl.loadDir(File(root, set)).mapNotNull { s -> FormulaBench.covered(s)?.let { FormulaBench.exportLine(set, s, it) } }
            }
            out.parentFile?.mkdirs()
            out.writeText(lines.joinToString("\n", postfix = "\n"))
            println("formulas for the device: ${lines.size}")
        } finally {
            Printer.numbers = numbers
        }
    }
}
