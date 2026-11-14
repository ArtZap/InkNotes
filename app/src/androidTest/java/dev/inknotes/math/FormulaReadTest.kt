package dev.inknotes.math

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

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
        val layout = sortedMapOf<String, Tally>()
        val page = sortedMapOf<String, Tally>()
        val wrongs = ArrayList<String>()
        val started = System.currentTimeMillis()
        try {
            for (line in file.readLines()) {
                val p = line.split('\t')
                if (p.size < 9) continue
                val (set, id, kind) = Triple(p[0], p[1], p[2])
                val h = p[3].toFloat()
                val ax = p[4].toFloat()
                val want = p[5]
                val pageWant = p[6]
                val pageAx = p[7].toFloat()
                val strokes = p[8].split(';').mapIndexed { i, s ->
                    val pts = s.split(',').map { q -> q.trim().split(' ').map(String::toFloat) }
                    Ink(FloatArray(pts.size) { pts[it][0] }, FloatArray(pts.size) { pts[it][1] }, i)
                }
                // Read as the app reads a selected formula, with the same check for "unsure".
                val got = runCatching { MathPage.read(strokes, h, ax, math.reader, MathPage.Prefer.FIRST) }.getOrNull()
                val o = when {
                    got == null -> "NONE"
                    MathLayout.Shift.CHECKS.any { shift ->
                        val again = runCatching { MathPage.read(strokes, h, ax, math.reader, MathPage.Prefer.FIRST, shift = shift) }.getOrNull()
                        again != null && again != got && !FormulaValues.same(again, got)
                    } -> "UNSURE"
                    FormulaValues.fingerprint(got)?.let { FormulaValues.sameFingerprint(it, want) } == true -> "RIGHT"
                    else -> "WRONG"
                }
                for (k in listOf("all", kind, set)) layout.getOrPut(k) { Tally() }.add(o)
                if (o == "WRONG" && wrongs.size < 60) wrongs += "$set/$id  →  ${runCatching { Printer.expr(got!!) }.getOrDefault("?")}"
                if (pageWant.isEmpty()) continue
                // As written on a page: "=" drawn after it, the whole page analysed.
                val right = strokes.maxOf { it.right }
                val x0 = right + 0.5f * h
                val w = 0.7f * h
                fun dash(y: Float, order: Int) = Ink(FloatArray(8) { x0 + w * it / 7 }, FloatArray(8) { y + (if (it % 3 == 0) 0.3f else 0f) }, order)
                val all = strokes + dash(pageAx - 0.16f * h, strokes.size) + dash(pageAx + 0.16f * h, strokes.size + 1)
                val a = runCatching { MathPage.analyze(all, math.reader, 4000f) }.getOrDefault(emptyList()).firstOrNull { it.x > x0 && abs(it.y - pageAx) < h }
                val text = (a?.answer as? Answer.Text)?.lines?.joinToString(";")
                val po = when {
                    a == null || text == null || a.error -> "NONE"
                    a.unsure -> "UNSURE"
                    FormulaValues.close(text, pageWant) -> "RIGHT"
                    else -> "WRONG"
                }
                for (k in listOf("all", kind, set)) page.getOrPut(k) { Tally() }.add(po)
            }
        } finally {
            Printer.numbers = numbers
        }
        val report = buildString {
            append("ML Kit, формулы целиком, %.0f с\n".format((System.currentTimeMillis() - started) / 1000.0))
            append("== раскладка и чтение (формула выделена)\n")
            for ((k, t) in layout) append("  %-30s %s\n".format(k, t))
            append("== страница (дописан «=»)\n")
            for ((k, t) in page) append("  %-30s %s\n".format(k, t))
            append("== неверные ответы (первые ${wrongs.size})\n")
            for (x in wrongs) append("  $x\n")
        }
        File(dir, "report.txt").writeText(report)
        Log.i("FormulaReadTest", report)
    }
}
