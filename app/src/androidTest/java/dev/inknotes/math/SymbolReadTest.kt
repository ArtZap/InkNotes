package dev.inknotes.math

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Level 0 of the formula bench: how the handwriting reader (ML Kit, as [MathNotes] asks it)
 * reads single handwritten symbols of CROHME. Needs files/level0/symbols.tsv on the device
 * (tools/crohme_symbols.py, pushed by the level-0 workflow); skipped without it. Writes
 * files/level0/report.txt: share read right first and among the guesses, by symbol, and the
 * commonest confusions. Measures only, never fails: the numbers decide what to fix.
 */
@RunWith(AndroidJUnit4::class)
class SymbolReadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun readSymbols() {
        val dir = instrumentation.targetContext.getExternalFilesDir("level0")
        val file = dir?.let { File(it, "symbols.tsv") }
        assumeTrue("no level0/symbols.tsv on the device", file != null && file.isFile)
        checkNotNull(file)
        lateinit var math: MathNotes
        instrumentation.runOnMainSync { math = MathNotes({ true }, {}) }
        val deadline = System.currentTimeMillis() + 300_000
        while (math.state != MathNotes.State.Ready) {
            check(math.state !is MathNotes.State.Failed && System.currentTimeMillis() < deadline) { "model: ${math.state}" }
            Thread.sleep(200)
        }

        val lines = file.readLines().filter { it.isNotBlank() }
        val shipped = ReadOptions.get()
        // How the strokes are handed to ML Kit (ReadOptions: from the corner of the area,
        // pauses by glyph): every way on the same symbols, as the app ships first.
        val compared = ArrayList<String>()
        lateinit var full: String
        try {
            val ways = listOf("as shipped" to shipped) + ReadOptions.BENCH
            for ((name, setting) in ways) {
                ReadOptions.set(setting)
                val (summary, report) = measure(lines, math)
                compared += "  %-18s %s".format(name, summary)
                if (name == "as shipped") full = report
            }
        } finally {
            ReadOptions.set(shipped)
        }
        val report = buildString {
            append("== чтение символа (ReadOptions = $shipped): верно первым, среди вариантов\n")
            for (c in compared) append(c).append('\n')
            append('\n').append(full)
        }
        File(dir, "report.txt").writeText(report)
        Log.i("SymbolReadTest", report)
    }

    /** One pass over the symbols with the reader as now set: a one-line summary and the full report. */
    private fun measure(lines: List<String>, math: MathNotes): Pair<String, String> {
        class Tally(var n: Int = 0, var first: Int = 0, var any: Int = 0)
        val byLabel = sortedMapOf<String, Tally>()
        val confusions = HashMap<String, Int>()
        val all = Tally()
        val started = System.currentTimeMillis()
        for ((order, line) in lines.withIndex()) {
            val parts = line.split('\t')
            if (parts.size < 5) continue
            val want = parts[3]
            val strokes = parts[4].split(';').mapIndexed { i, s ->
                val pts = s.split(',').map { p -> p.trim().split(' ').map(String::toFloat) }
                Ink(FloatArray(pts.size) { pts[it][0] }, FloatArray(pts.size) { pts[it][1] }, order * 10 + i)
            }
            val guesses = runCatching { math.reader.read(strokes, LINE_H) }.getOrDefault(emptyList()).map(::norm)
            val t = byLabel.getOrPut(want) { Tally() }
            val w = norm(want)
            for (x in listOf(t, all)) {
                x.n++
                if (guesses.firstOrNull() == w) x.first++
                if (w in guesses) x.any++
            }
            val got = guesses.firstOrNull() ?: "∅"
            if (got != w) confusions.merge("$want → $got", 1, Int::plus)
        }
        fun pct(k: Int, n: Int) = if (n == 0) 0.0 else 100.0 * k / n
        val report = buildString {
            append("символов: ${all.n}, %.0f с\n".format((System.currentTimeMillis() - started) / 1000.0))
            append("верно первым: %.1f%%, среди вариантов: %.1f%%\n\n".format(pct(all.first, all.n), pct(all.any, all.n)))
            append("символ     n   первым  среди вариантов\n")
            for ((k, t) in byLabel.entries.sortedByDescending { it.value.n }) {
                append("%-8s %4d  %5.1f%%  %5.1f%%\n".format(k, t.n, pct(t.first, t.n), pct(t.any, t.n)))
            }
            append("\nчастые путаницы (написано → прочитано первым):\n")
            for ((k, v) in confusions.entries.sortedByDescending { it.value }.take(30)) append("  %4d  %s\n".format(v, k))
        }
        return "%5.1f%%  %5.1f%%".format(pct(all.first, all.n), pct(all.any, all.n)) to report
    }

    /** As the formula reader compares readings: [MathText.normalize]. */
    private fun norm(s: String): String = MathText.normalize(s).trim()

    private companion object {
        /** The line height the symbols are scaled to (tools/crohme_symbols.py). */
        const val LINE_H = 30f
    }
}
