package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Wrong answers reported from the tablet (see resources/math-reports/README.md), replayed with
 * the reader's saved readings: each must now give the answer the user said it should.
 */
class MathReportTest {

    class Report(val name: String, val pageWidth: Float, val x: Float, val y: Float, val expected: String, val strokes: List<Ink>, val reads: Map<List<Int>, List<String>>)

    companion object {
        private val NUM = "-?[0-9.]+(?:E-?[0-9]+)?"

        private fun unquote(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")

        fun parse(name: String, text: String): Report {
            fun num(key: String) = Regex("\"$key\":($NUM)").find(text)!!.groupValues[1].toFloat()
            val expected = unquote(Regex("\"expected\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)!!.groupValues[1])
            val strokes = Regex("\\{\"o\":(\\d+),\"x\":\\[([^\\]]*)\\],\"y\":\\[([^\\]]*)\\]\\}").findAll(text).map { m ->
                Ink(
                    m.groupValues[2].split(',').map { it.toFloat() }.toFloatArray(),
                    m.groupValues[3].split(',').map { it.toFloat() }.toFloatArray(),
                    m.groupValues[1].toInt(),
                )
            }.toList()
            val reads = Regex("\\{\"s\":\\[([^\\]]*)\\],\"t\":\\[((?:\"(?:[^\"\\\\]|\\\\.)*\",?)*)\\]\\}").findAll(text).associate { m ->
                val ids = m.groupValues[1].split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }
                val texts = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(m.groupValues[2]).map { unquote(it.groupValues[1]) }.toList()
                ids to texts
            }
            return Report(name, num("pageWidth"), num("x"), num("y"), expected, strokes, reads)
        }

        fun answer(r: Report): String? {
            val reader = RunReader { run, _ -> r.reads[run.map { it.order }.sorted()].orEmpty() }
            val answers = MathPage.analyze(r.strokes, reader, r.pageWidth)
            val a = answers.minByOrNull { hypot(it.x - r.x, it.y - r.y) } ?: return null
            return when (val v = a.answer) {
                is Answer.Text -> v.lines.joinToString("\n")
                is Answer.Grid -> v.rows.joinToString("\n") { it.joinToString(" ") }
                else -> null
            }
        }

        /** Same answer, written either way: spaces, minus signs, a decimal comma or point. */
        fun same(got: String, want: String): Boolean {
            fun norm(s: String) = s.replace(" ", "").replace('−', '-').replace('.', ',').lowercase()
            if (norm(got) == norm(want)) return true
            val a = norm(got).replace(',', '.').toDoubleOrNull() ?: return false
            val b = norm(want).replace(',', '.').toDoubleOrNull() ?: return false
            return abs(a - b) <= 1e-6 * maxOf(1.0, abs(b))
        }
    }

    @Test
    fun reportedPagesAnswerRight() {
        val dir = MathReportTest::class.java.classLoader!!.getResource("math-reports")?.let { File(it.toURI()) } ?: return
        val failures = ArrayList<String>()
        for (f in dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedBy { it.name }) {
            val r = parse(f.name, f.readText())
            val got = answer(r)
            if (got == null || !same(got, r.expected)) failures += "${f.name}: ожидалось «${r.expected}», получено «$got»"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun reportFormatRoundTrip() {
        // 2 + 3 = written by code, read back from the report format.
        val strokes = listOf(
            Ink(floatArrayOf(100f, 114f, 100f, 114f), floatArrayOf(100f, 108f, 116f, 124f), 0),
            Ink(floatArrayOf(126f, 126f), floatArrayOf(106f, 118f), 1),
            Ink(floatArrayOf(120f, 132f), floatArrayOf(112f, 112f), 2),
            Ink(floatArrayOf(140f, 154f, 140f, 154f), floatArrayOf(100f, 108f, 116f, 124f), 3),
            Ink(floatArrayOf(162f, 171f, 180f), floatArrayOf(108f, 108.6f, 108f), 4),
            Ink(floatArrayOf(162f, 171f, 180f), floatArrayOf(116f, 116.6f, 116f), 5),
        )
        fun floats(a: FloatArray) = a.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it) }
        val text = "{\"pageWidth\":1000.0,\"x\":190.0,\"y\":112.0,\"expected\":\"5\",\n\"strokes\":[" +
            strokes.joinToString(",\n") { "{\"o\":${it.order},\"x\":[${floats(it.xs)}],\"y\":[${floats(it.ys)}]}" } +
            "],\n\"reads\":[{\"s\":[0],\"t\":[\"2\"]},\n{\"s\":[3],\"t\":[\"3\"]}]}\n"
        val r = parse("synthetic", text)
        assertEquals(6, r.strokes.size)
        assertEquals("5", r.expected)
        assertEquals(listOf("2"), r.reads[listOf(0)])
        assertEquals("5", answer(r))
        assertTrue(same("2,5", "2.5"))
        assertTrue(same("−3", "-3"))
    }
}
