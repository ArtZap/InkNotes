package dev.inknotes.math

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.PageCodec
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The owner's own pages of worked solutions ([NotesJudge]), read with the real reader. They
 * are not in this repository: a private one pushes them to files/notes/ on the device and
 * keeps the report (report.txt) and what ML Kit read (reads.tsv, for NotesReplayTest).
 * Skipped without them; measures only, never fails.
 */
@RunWith(AndroidJUnit4::class)
class RealNotesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun readNotes() {
        val dir = instrumentation.targetContext.getExternalFilesDir("notes")
        val pages = dir?.listFiles { f -> f.name.endsWith(".ink") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no notes/*.ink on the device", pages.isNotEmpty())
        checkNotNull(dir)
        lateinit var math: MathNotes
        instrumentation.runOnMainSync { math = MathNotes({ true }, {}) }
        val deadline = System.currentTimeMillis() + 300_000
        while (math.state != MathNotes.State.Ready) {
            check(math.state !is MathNotes.State.Failed && System.currentTimeMillis() < deadline) { "model: ${math.state}" }
            Thread.sleep(200)
        }
        val recorded = ArrayList<String>()
        val report = StringBuilder()
        var id = ""
        math.onRecognized = { run: List<Ink>, plain: List<String>, guesses: List<String> -> recorded.add(RecordedReader.line(id, run, plain, guesses)); Unit }
        try {
            for (f in pages) {
                id = "notes/" + f.name.removeSuffix(".ink")
                val ink = math.toInk(PageCodec.read(f).strokes)
                val r = NotesJudge.measure(ink, math.reader)
                report.append("== ${f.name}: $r\n")
                r.lines.forEach { report.append("  $it\n") }
                report.append("  -- ответы на странице\n")
                r.answers.forEach { report.append("  $it\n") }
            }
        } finally {
            math.onRecognized = null
        }
        File(dir, "report.txt").writeText(report.toString())
        File(dir, "reads.tsv").writeText(recorded.joinToString("\n", postfix = "\n"))
    }
}
