package dev.inknotes.math

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.NotebookRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: runs the math analysis on a real page (`-e probe "<notebook title>,<page>"`) with
 * the real reader, writes a step-by-step report and the page's strokes (JSON) to the app's
 * external files dir. Read-only for notebooks.
 */
@RunWith(AndroidJUnit4::class)
class MathProbe {
    @Test
    fun probe() {
        val arg = InstrumentationRegistry.getArguments().getString("probe")
        assumeTrue(arg != null)
        val (title, page) = arg!!.split(",")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NotebookRepository(context.filesDir)
        val nb = repo.list().first { it.title == title }
        val content = repo.loadPage(nb, nb.pageIds[page.toInt()])
        lateinit var math: MathNotes
        InstrumentationRegistry.getInstrumentation().runOnMainSync { math = MathNotes({ true }, {}) }
        while (math.state != MathNotes.State.Ready) Thread.sleep(100)
        val ink = math.toInk(content.strokes)
        val dir = context.getExternalFilesDir(null)
        val json = JSONArray()
        for (s in ink) json.put(JSONObject().put("x", JSONArray(s.xs.map { it.toDouble() })).put("y", JSONArray(s.ys.map { it.toDouble() })))
        File(dir, "probe-strokes.json").writeText(json.toString())
        val report = StringBuilder()
        MathPage.trace = { report.appendLine(it) }
        val reader = RunReader { run, h -> math.reader.read(run, h).also { report.appendLine("    read ${run.size} strokes -> $it") } }
        val answers = MathPage.analyze(ink, reader)
        MathPage.trace = null
        report.appendLine("ANSWERS:")
        for (a in answers) report.appendLine("  (${a.x.toInt()},${a.y.toInt()}) ${a.answer}")
        File(dir, "probe.txt").writeText(report.toString())
    }
}
