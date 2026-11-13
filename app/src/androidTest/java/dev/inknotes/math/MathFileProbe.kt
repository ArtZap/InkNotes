package dev.inknotes.math

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.PageCodec
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: the math analysis of page files (the `.ink` files in `<external files>/render`) with the real
 * reader: a step-by-step report per page.
 */
@RunWith(AndroidJUnit4::class)
class MathFileProbe {
    @Test
    fun probe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dir = File(context.getExternalFilesDir(null), "render")
        val only = InstrumentationRegistry.getArguments().getString("page")
        val files = dir.listFiles { f -> f.name.endsWith(".ink") && (only == null || f.name == only) }.orEmpty().sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        lateinit var math: MathNotes
        instrumentation.runOnMainSync { math = MathNotes({ true }, {}) }
        while (math.state != MathNotes.State.Ready) Thread.sleep(100)
        for (f in files) {
            val content = PageCodec.read(f)
            val ink = math.toInk(content.strokes)
            val json = org.json.JSONArray()
            for (st in ink) json.put(org.json.JSONObject().put("o", st.order).put("x", org.json.JSONArray(st.xs.map { it.toDouble() })).put("y", org.json.JSONArray(st.ys.map { it.toDouble() })))
            File(dir, f.name + ".ink.json").writeText(json.toString())
            val report = StringBuilder()
            MathPage.trace = { report.appendLine(it) }
            val reader = RunReader { run, h -> math.reader.read(run, h).also { report.appendLine("    read ${run.size} strokes -> $it") } }
            val answers = MathPage.analyze(ink, reader)
            MathPage.trace = null
            report.appendLine("ANSWERS:")
            for (a in answers) report.appendLine("  (${a.x.toInt()},${a.y.toInt()}) err=${a.error} ${describe(a.answer)}")
            File(dir, f.name + ".math.txt").writeText(report.toString())
        }
    }

    private fun describe(a: Answer): String = when (a) {
        is Answer.Text -> a.lines.joinToString(" | ")
        is Answer.Graph -> "graph ${a.variable} x=${a.x0}..${a.x1}"
        is Answer.Surface -> "surface ${a.xv},${a.yv}"
        is Answer.Grid -> a.rows.joinToString(" ; ") { it.joinToString(",") }
    }
}
