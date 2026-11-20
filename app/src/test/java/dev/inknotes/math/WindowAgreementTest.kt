package dev.inknotes.math

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The correction window ("Как прочитано") must show what the page read: for formulas of the
 * device bench with "=" written after them, read with what ML Kit read on the emulator, the
 * window's starting reading (Correct.side towards the page's) must be the page's. Needs CROHME
 * and the recorded reads, as ReplayBenchTest; writes build/window-report.txt.
 */
class WindowAgreementTest {
    @Test
    fun windowShowsWhatThePageRead() {
        val formulas = ReplayBenchTest.formulas() ?: return
        val reads = ReplayBenchTest.reads() ?: return
        val numbers = Printer.numbers
        val point = Tune.decimalPoint
        Printer.numbers = Printer.Numbers.DECIMAL
        Tune.decimalPoint = 1f
        var n = 0
        var agree = 0
        val differ = ArrayList<String>()
        try {
            for (f in formulas) {
                val r = reads["${f.set}/${f.id}"] ?: reads[f.id] ?: continue
                val reader = RecordedReader(r)
                val (all, x0) = FormulaJudge.onPage(f)
                val a = runCatching { MathPage.analyze(all, reader, 4000f) }.getOrDefault(emptyList()).firstOrNull { it.x > x0 } ?: continue
                val fix = a.fix ?: continue
                val target = fix.leftRead ?: continue
                n++
                val side = Correct.side(fix.left, fix.h, fix.axis, reader, target)
                val shown = side?.let { MathPage.parseText(Correct.text(it, it.initial)) }
                if (shown == target) agree++ else if (differ.size < 40) differ += "${f.id}: page ${runCatching { Printer.expr(target) }.getOrDefault("?")}, window ${side?.let { Correct.text(it, it.initial) }}"
            }
        } finally {
            Printer.numbers = numbers
            Tune.decimalPoint = point
        }
        val text = "== окно «Как прочитано» показывает прочтение страницы: $agree из $n\n" + differ.joinToString("\n") { "  $it" } + "\n"
        println(text)
        File("build").mkdirs()
        File("build/window-report.txt").writeText(text)
        assertTrue(text, n == 0 || agree >= n * MIN_AGREE)
    }

    companion object {
        const val MIN_AGREE = 0.97
    }
}
