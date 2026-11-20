package dev.inknotes.math

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The formula bench end to end: CROHME formulas read by the real handwriting reader (ML Kit,
 * as [MathNotes] asks it), not by a reader that knows every symbol. The number that matters:
 * how many answers the app shows are wrong. Needs files/level1/formulas.tsv on the device
 * (FormulaExportTest writes it, the "Formulas on a device" workflow pushes it); skipped
 * without it. Writes files/level1/report.txt. Measures only, never fails.
 */
@RunWith(AndroidJUnit4::class)
class FormulaReadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Tally(var n: Int = 0, var right: Int = 0, var wrong: Int = 0, var unsure: Int = 0) {
        fun add(o: String) {
            n++
            when (o) {
                "RIGHT" -> right++
                "WRONG" -> wrong++
                "UNSURE" -> unsure++
            }
        }

        override fun toString(): String {
            fun p(k: Int) = if (n == 0) 0.0 else 100.0 * k / n
            val shown = right + wrong
            return "%5d  верно %5.1f%%  неверно %5.1f%%  не уверен %5.1f%%  нет ответа %5.1f%%  | неверных среди показанных %5.1f%%"
                .format(n, p(right), p(wrong), p(unsure), p(n - right - wrong - unsure), if (shown == 0) 0.0 else 100.0 * wrong / shown)
        }
    }

    @Test
    fun readFormulas() {
        val dir = instrumentation.targetContext.getExternalFilesDir("level1")
        val file = dir?.let { File(it, "formulas.tsv") }
        assumeTrue("no level1/formulas.tsv on the device", file != null && file.isFile)
        checkNotNull(file)
        lateinit var math: MathNotes
        instrumentation.runOnMainSync { math = MathNotes({ true }, {}) }
        val deadline = System.currentTimeMillis() + 300_000
        while (math.state != MathNotes.State.Ready) {
            check(math.state !is MathNotes.State.Failed && System.currentTimeMillis() < deadline) { "model: ${math.state}" }
            Thread.sleep(200)
        }
        val numbers = Printer.numbers
        Printer.numbers = Printer.Numbers.DECIMAL
        Tune.decimalPoint = 1f // CROHME is written the English way (8.5)
        val lines = file.readLines()
        val shipped = ReadOptions.get()
        // How the reading switches (ReadOptions) change the figures: every setting on the same
        // formulas, as the app ships first (its full report below), then the others.
        val compared = ArrayList<String>()
        lateinit var full: String
        try {
            for ((name, setting) in listOf("as shipped" to shipped) + ReadOptions.BENCH) {
                ReadOptions.set(setting)
                // The shipped pass records what ML Kit read, to replay on a computer (ReplayBenchTest).
                val recorded = if (name == "as shipped") ArrayList<String>() else null
                math.onRecognized = if (recorded == null) {
                    null
                } else {
                    { run: List<Ink>, plain: List<String>, guesses: List<String> -> recorded.add(RecordedReader.line(recordingId, run, plain, guesses)); Unit }
                }
                val (summary, report) = measure(lines, math)
                math.onRecognized = null
                if (recorded != null) File(dir, "reads.tsv").writeText(recorded.joinToString("\n", postfix = "\n"))
                compared += "  %-18s %s".format(name, summary)
                if (name == "as shipped") full = report
            }
        } finally {
            ReadOptions.set(shipped)
            Printer.numbers = numbers
        }
        val report = buildString {
            append("== переключатели чтения (ReadOptions: знаки, по глифам, от угла, паузы = ${shipped.joinToString("") { if (it) "1" else "0" }}): формула выделена | страница\n")
            for (c in compared) append(c).append('\n')
            append(full)
        }
        File(dir, "report.txt").writeText(report)
        Log.i("FormulaReadTest", report)
    }

    /** The formula being read, for the recorded readings. */
    private var recordingId = ""

    /** One pass over the formulas with the reader as now set: a one-line summary and the full report. */
    private fun measure(lines: List<String>, math: MathNotes): Pair<String, String> {
        val layout = sortedMapOf<String, Tally>()
        val page = sortedMapOf<String, Tally>()
        val wrongs = ArrayList<String>()
        class Signal(val right: Boolean, val shifts: Boolean, val top2: Boolean, val top3: Boolean, val agree: Boolean, val margin: Float?)
        val signals = ArrayList<Signal>()
        val started = System.currentTimeMillis()
        run {
            for (line in lines) {
                val f = FormulaJudge.parse(line) ?: continue
                val (set, id, kind) = Triple(f.set, f.id, f.kind)
                val strokes = f.strokes
                recordingId = id
                val (o, got) = FormulaJudge.selected(f, math.reader)
                for (k in listOf("all", kind, set)) layout.getOrPut(k) { Tally() }.add(o)
                // Signals for "unsure", measured before one is chosen: the moved layouts disagree;
                // the first readings (the reader's first guesses and the next ones) give other values.
                if (got != null) {
                    val right = FormulaJudge.right(got, f)
                    val readings = runCatching { MathPage.readings(strokes, f.h, f.ax, math.reader, MathPage.Prefer.FIRST) }.getOrDefault(emptyList())
                    val values = readings.take(3).mapNotNull { FormulaValues.fingerprint(it) }
                    val top2 = values.size >= 2 && !FormulaValues.sameFingerprint(values[0], values[1])
                    val top3 = values.drop(1).any { !FormulaValues.sameFingerprint(values[0], it) }
                    // How sure the reader was of each run it read for this formula.
                    val holes = runCatching { MathLayout.formula(strokes, f.h, f.ax).filterIsInstance<Piece.Hole>() }.getOrDefault(emptyList())
                    val ds = holes.mapNotNull { math.doubt(it.strokes) }
                    signals += Signal(right, o == "UNSURE", top2, top3, ds.all { it.agree }, ds.mapNotNull { it.margin }.minOrNull())
                }
                if (o == "WRONG" && wrongs.size < 60) wrongs += "$set/$id  →  ${runCatching { Printer.expr(got!!) }.getOrDefault("?")}"
                val po = FormulaJudge.page(f, math.reader) ?: continue
                for (k in listOf("all", kind, set)) page.getOrPut(k) { Tally() }.add(po)
            }
        }
        val report = buildString {
            append("ML Kit, формулы целиком, %.0f с\n".format((System.currentTimeMillis() - started) / 1000.0))
            append("== раскладка и чтение (формула выделена)\n")
            for ((k, t) in layout) append("  %-30s %s\n".format(k, t))
            append("== страница (дописан «=»)\n")
            for ((k, t) in page) append("  %-30s %s\n".format(k, t))
            append("== сигналы «не уверен» (раскладка и чтение): показано, неверных среди показанных\n")
            fun line(name: String, doubt: (Signal) -> Boolean) {
                val shown = signals.filter { !doubt(it) }
                val wrong = shown.count { !it.right }
                append("  %-28s показано %5.1f%%  неверных %5.1f%%  (%d из %d)\n".format(name, 100.0 * shown.size / maxOf(1, signals.size), 100.0 * wrong / maxOf(1, shown.size), wrong, shown.size))
            }
            line("без проверки", { false })
            line("сдвиги порогов", { it.shifts })
            line("первые два прочтения", { it.top2 })
            line("первые три прочтения", { it.top3 })
            line("сдвиги или два прочтения", { it.shifts || it.top2 })
            line("сдвиги или три прочтения", { it.shifts || it.top3 })
            line("оба прочтения ML Kit согласны", { !it.agree })
            line("сдвиги или ML Kit не согласен", { it.shifts || !it.agree })
            append("  с оценками ML Kit: ${signals.count { it.margin != null }} из ${signals.size}\n")
            for (t in listOf(0.5f, 1f, 2f, 3f, 5f, 8f)) {
                line("отрыв первого ≥ $t", { (it.margin ?: 0f) < t })
                line("отрыв ≥ $t и согласны", { (it.margin ?: 0f) < t || !it.agree })
            }
            append("== неверные ответы (первые ${wrongs.size})\n")
            for (x in wrongs) append("  $x\n")
        }
        return "${layout["all"] ?: Tally()} | ${page["all"] ?: Tally()}" to report
    }
}
