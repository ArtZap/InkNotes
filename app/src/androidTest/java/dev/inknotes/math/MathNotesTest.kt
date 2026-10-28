package dev.inknotes.math

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.InkBrushes
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageContent
import dev.inknotes.data.PageStroke
import dev.inknotes.editor.PageDocument
import dev.inknotes.editor.PageEditorView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Math notes with the real handwriting reader: formulas "written" from simple glyph outlines,
 * read by ML Kit, laid out and computed. Downloads the English model on first run (network).
 */
@RunWith(AndroidJUnit4::class)
class MathNotesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Single-stroke-ish shapes of symbols in a 10×20 box (y down); each list is one stroke. */
    private val glyphs: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        '0' to listOf(ellipse(5f, 10f, 4.5f, 9.5f)),
        '1' to listOf(listOf(2f to 5f, 6f to 0f, 6f to 20f)),
        '2' to listOf(listOf(1f to 5f, 2f to 1.5f, 5f to 0f, 8.5f to 1.5f, 9f to 5f, 7f to 10f, 1f to 20f, 10f to 20f)),
        '3' to listOf(listOf(1f to 2f, 4f to 0f, 8f to 1f, 9f to 4.5f, 5f to 9f, 9f to 12f, 9.5f to 16f, 6f to 20f, 1.5f to 19f)),
        '4' to listOf(listOf(7f to 20f, 7f to 0f, 0f to 14f, 10f to 14f)),
        '5' to listOf(listOf(9f to 0f, 2f to 0f, 1.5f to 9f, 5f to 7.5f, 8.5f to 10f, 9f to 15f, 6.5f to 19.5f, 3f to 20f, 0.5f to 17.5f)),
        '6' to listOf(listOf(8.5f to 1f, 5f to 0f, 1.5f to 5f, 0.5f to 13f, 2f to 19f, 5f to 20f, 8.5f to 17.5f, 9f to 13f, 6f to 10f, 2.5f to 11f, 0.8f to 14f)),
        '7' to listOf(listOf(0f to 0f, 10f to 0f, 4f to 20f)),
        '+' to listOf(listOf(5f to 5f, 5f to 17f), listOf(-1f to 11f, 11f to 11f)),
        '-' to listOf(listOf(0f to 11f, 10f to 11f)),
        'x' to listOf(listOf(1f to 8f, 9f to 20f), listOf(9f to 8f, 1f to 20f)),
        '°' to listOf(ellipse(2.5f, 2.5f, 2.2f, 2.2f)),
        's' to listOf(listOf(8f to 9f, 5f to 7.5f, 1.5f to 9f, 2f to 12f, 8f to 15f, 8.5f to 18f, 5f to 20f, 1f to 18.5f)),
        'i' to listOf(listOf(4f to 8f, 4f to 20f), listOf(4f to 3f, 4.6f to 3.6f)),
        'n' to listOf(listOf(1f to 8f, 1f to 20f, 1f to 12f, 4f to 8f, 8f to 8.5f, 9f to 12f, 9f to 20f)),
    )

    /** Drawn like a written 0: from the top, counter-clockwise. */
    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) = (0..32).map { k ->
        val a = -Math.PI / 2 - Math.PI * 2 * k / 32
        (cx + rx * Math.cos(a).toFloat()) to (cy + ry * Math.sin(a).toFloat())
    }

    private class Writer {
        val strokes = ArrayList<PageStroke>()
        private var t = 0L

        fun stroke(points: List<Pair<Float, Float>>) {
            val batch = MutableStrokeInputBatch()
            // Interpolate so the reader sees a dense, timed trace.
            var prev: Pair<Float, Float>? = null
            for (p in points) {
                val q = prev
                if (q != null) {
                    val steps = 6
                    for (s in 1..steps) {
                        val x = q.first + (p.first - q.first) * s / steps
                        val y = q.second + (p.second - q.second) * s / steps
                        t += 8
                        batch.add(InputToolType.STYLUS, x, y, t)
                    }
                } else {
                    t += 8
                    batch.add(InputToolType.STYLUS, p.first, p.second, t)
                }
                prev = p
            }
            strokes += PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 2.5f), batch))
            t += 150
        }

        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = stroke(listOf(x0 to y0, x1 to y1))
    }

    /** Writes [text] at ([x], [top]) with symbols [h] tall; returns the x after it. */
    private fun Writer.write(text: String, x: Float, top: Float, h: Float = 40f): Float {
        var cx = x
        val k = h / 20f
        for (c in text) {
            when (c) {
                ' ' -> cx += 0.5f * h
                '=' -> {
                    line(cx, top + 0.42f * h, cx + 0.5f * h, top + 0.42f * h)
                    line(cx, top + 0.62f * h, cx + 0.5f * h, top + 0.62f * h)
                    cx += 0.75f * h
                }
                else -> {
                    for (s in glyphs.getValue(c)) stroke(s.map { (px, py) -> cx + px * k to top + py * k })
                    cx += if (c == '°') 0.4f * h else 0.72f * h
                }
            }
        }
        return cx
    }

    private fun analyze(strokes: List<PageStroke>): List<PageAnswer> {
        val math = shared ?: run {
            lateinit var m: MathNotes
            instrumentation.runOnMainSync { m = MathNotes({ true }, {}) }
            m.also { shared = it }
        }
        val deadline = System.currentTimeMillis() + 180_000
        while (math.state != MathNotes.State.Ready) {
            assertTrue("model download: ${math.state}", math.state !is MathNotes.State.Failed && System.currentTimeMillis() < deadline)
            Thread.sleep(200)
        }
        val done = CountDownLatch(1)
        var result: List<PageAnswer> = emptyList()
        instrumentation.runOnMainSync {
            math.analyze("test", strokes, 1000f) {
                result = it
                done.countDown()
            }
        }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        return result
    }

    private companion object {
        /** One instance for all tests, as in the app. */
        var shared: MathNotes? = null
    }

    private fun text(a: PageAnswer) = (a.answer as Answer.Text).lines.joinToString("\n")

    @Test
    fun sumOfNumbers() {
        val w = Writer()
        w.write("12+30=", 100f, 100f)
        val answers = analyze(w.strokes)
        assertEquals(listOf("42"), answers.map(::text))
        render(w.strokes, answers, "math-sum.png")
    }

    @Test
    fun variableAcrossLines() {
        val w = Writer()
        w.write("x=5", 100f, 100f)
        w.write("2x+1=", 100f, 200f)
        w.write("x-3=", 100f, 300f)
        assertEquals(listOf("11", "2"), analyze(w.strokes).map(::text))
    }

    @Test
    fun fractionAndPower() {
        val w = Writer()
        // 6 over 3, then + 2²
        w.write("6", 112f, 40f)
        w.line(100f, 88f, 150f, 88f)
        w.write("3", 112f, 96f)
        w.write("+2", 165f, 68f)
        w.write("2", 225f, 52f, 22f)
        w.write("=", 255f, 68f)
        val answers = analyze(w.strokes)
        assertEquals(listOf("6"), answers.map(::text))
        render(w.strokes, answers, "math-fraction.png")
    }

    @Test
    fun degrees() {
        val w = Writer()
        w.write("30°+60°=", 100f, 100f)
        w.write("sin30°=", 100f, 200f)
        val answers = analyze(w.strokes)
        assertEquals(listOf("90°", "0,5"), answers.map(::text))
        render(w.strokes, answers, "math-degrees.png")
    }

    /** Draws the page with its answers for a look (app's external files dir). */
    private fun render(strokes: List<PageStroke>, answers: List<PageAnswer>, name: String) {
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val view = PageEditorView(context)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 1000, 700)
            val doc = PageDocument("m", PageContent(strokes, emptyList()))
            view.source = object : PageEditorView.PageSource {
                override val pageCount = 1
                override fun pageHeight(index: Int) = PageEditorView.PAGE_H
                override fun document(index: Int) = doc
                override fun pdfBitmap(index: Int): Bitmap? = null
                override fun answers(index: Int) = answers
            }
            val bmp = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val dir = context.getExternalFilesDir(null) ?: return@runOnMainSync
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
