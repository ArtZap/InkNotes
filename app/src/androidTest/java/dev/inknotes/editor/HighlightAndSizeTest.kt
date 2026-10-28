package dev.inknotes.editor

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.InkBrushes
import dev.inknotes.data.InkKind
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PageSize
import dev.inknotes.data.PaperFormat
import dev.inknotes.data.PaperKind
import dev.inknotes.data.PageStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/** Highlighter passes fitted to text, stretching to a size, paper sizes of pages. */
@RunWith(AndroidJUnit4::class)
class HighlightAndSizeTest {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    /** A wobbly pass from [x0] to [x1] around [y]. */
    private fun pass(x0: Float, x1: Float, y: Float, wobble: Float): Pair<FloatArray, FloatArray> {
        val n = 60
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y + wobble * sin(it * 0.7).toFloat() + 0.03f * (xs[it] - x0) }
        return xs to ys
    }

    @Test
    fun handwritingLikeLetters() {
        // "Letters" 30 tall from y=100, a word from x=100 to 380; the pass wobbles a bit low.
        val (xs, ys) = pass(90f, 395f, 122f, 7f)
        val band = HighlightAligner.align(xs, ys, 20f) { c, _ ->
            var x = 100f
            while (x < 380f) {
                c.drawRect(x, 100f, x + 14f, 130f, paint)
                x += 22f
            }
        }
        assertNotNull(band)
        band!!
        assertEquals(115f, band.y, 2f)
        assertEquals("keeps its own thickness", 20f, band.thickness, 0.01f)
        assertTrue("starts at the first letter: ${band.x0}", band.x0 in 92f..100f)
        assertTrue("ends at the last letter: ${band.x1}", band.x1 in 375f..390f)
    }

    @Test
    fun printedTextLikePdf() {
        paint.textSize = 24f
        val (xs, ys) = pass(50f, 330f, 205f, 5f)
        val band = HighlightAligner.align(xs, ys, 18f) { c, _ ->
            c.drawText("Highlighted words here", 50f, 210f, paint)
            // the line above and below must not count
            c.drawText("Another line of text above", 50f, 170f, paint)
            c.drawText("And one more line below it", 50f, 250f, paint)
        }!!
        // The x-height of a 24 px font sits around y 193..210.
        assertTrue("level with the letters: ${band.y}", band.y in 195f..206f)
        assertEquals(18f, band.thickness, 0.01f)
    }

    @Test
    fun nothingUnderneathKeepsSizeAndBecomesLevel() {
        val (xs, ys) = pass(100f, 300f, 400f, 6f)
        val band = HighlightAligner.align(xs, ys, 20f) { _, _ -> }!!
        assertEquals(20f, band.thickness, 0.01f)
        assertEquals(100f, band.x0, 0.01f)
        assertEquals(300f, band.x1, 0.01f)
    }

    @Test
    fun verticalOrRoundMarksStayAsDrawn() {
        val xs = FloatArray(30) { 100f + it * 0.3f }
        val ys = FloatArray(30) { 100f + it * 8f }
        assertNull(HighlightAligner.align(xs, ys, 20f) { _, _ -> })
        val cx = FloatArray(40) { 200f + 60f * kotlin.math.cos(it * 0.16).toFloat() }
        val cy = FloatArray(40) { 200f + 60f * sin(it * 0.16).toFloat() }
        assertNull(HighlightAligner.align(cx, cy, 20f) { _, _ -> })
    }

    @Test
    fun stretchToSize() {
        val batch = MutableStrokeInputBatch()
        batch.add(InputToolType.STYLUS, 100f, 100f, 0)
        batch.add(InputToolType.STYLUS, 200f, 150f, 10)
        val s = PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 2f), batch))
        val t = PageEdits.Transform.stretch(2f, 1f, 100f, 100f)
        val r: RectF = PageEdits.bounds(PageEdits.transform(s, t))!!
        assertEquals(100f, r.left, 2f)
        assertEquals(300f, r.right, 2f)
        assertEquals(150f, r.bottom, 2f)
    }

    @Test
    fun pageSizesAreSaved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "size-test").apply { deleteRecursively(); mkdirs() }
        try {
            val repo = NotebookRepository(dir)
            var nb = repo.create("Размеры", PaperKind.BLANK)
            assertEquals(PageSize.DEFAULT, nb.pageSize(nb.pageIds[0]))
            assertEquals(1414.3f, nb.pageHeightUnits(nb.pageIds[0]), 0.1f)
            assertEquals(1000f, nb.pageWidthUnits(nb.pageIds[0]), 0.1f)
            nb = repo.setPageSize(nb, listOf(nb.pageIds[0]), PageSize(PaperFormat.A5, landscape = true))
            nb = repo.insertPage(nb, 1, PageSize(PaperFormat.LETTER))
            Thread.sleep(300)
            val back = NotebookRepository(dir).list().single { it.id == nb.id }
            assertEquals(PageSize(PaperFormat.A5, true), back.pageSize(back.pageIds[0]))
            // A unit is the same 0,21 mm on every format: 148 mm is 704,8 units on any page.
            assertEquals(148f / 0.21f, back.pageHeightUnits(back.pageIds[0]), 0.1f)
            assertEquals(1000f, back.pageWidthUnits(back.pageIds[0]), 0.1f)
            assertEquals(215.9f / 0.21f, back.pageWidthUnits(back.pageIds[1]), 0.1f)
            assertEquals(210f, back.pageWidthMm(back.pageIds[0]), 0.01f)
            assertEquals(PaperFormat.LETTER, back.pageSize(back.pageIds[1]).format)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun stamp(title: String) = Stamps.CATEGORIES.flatMap { it.second }.first { it.title == title }

    private fun boundsOf(strokes: List<PageStroke>) = strokes.mapNotNull { PageEdits.bounds(it) }.reduce { a, b -> RectF(a).apply { union(b) } }

    private fun points(s: PageStroke): List<Pair<Float, Float>> {
        val p = androidx.ink.strokes.StrokeInput()
        return (0 until s.stroke.inputs.size).map { s.stroke.inputs.populate(it, p); p.x to p.y }
    }

    @Test
    fun arrowGetsLongerNotWider() {
        val arrow = Stamps.place(stamp("Стрелка"), 300f, 300f, 130f, Color.BLACK, 3f)
        val r = boundsOf(arrow.strokes).apply { union(arrow.fills[0].bounds) }
        val res = Elongate.resize(arrow.strokes, arrow.fills, emptyList(), r, r.width() * 2, r.height())
        val r2 = boundsOf(res.strokes).apply { union(res.fills[0].bounds) }
        assertEquals(r.width() * 2, r2.width(), 1f)
        assertEquals(r.height(), r2.height(), 0.5f)
        // The solid head keeps its size and shape: the same offsets from its tip.
        val head = arrow.fills[0].outline
        val head2 = res.fills[0].outline
        for (i in head.indices step 2) {
            assertEquals(head[i] - head[0], head2[i] - head2[0], 0.5f)
            assertEquals(head[i + 1], head2[i + 1], 0.01f)
        }
    }

    @Test
    fun forceArrowGetsTallerAndShorter() {
        val force = Stamps.place(stamp("Сила"), 300f, 300f, 130f, Color.BLACK, 3f)
        val r = boundsOf(force.strokes).apply { union(force.fills[0].bounds) }
        for (target in listOf(r.height() * 1.8f, r.height() * 0.6f)) {
            val res = Elongate.resize(force.strokes, force.fills, emptyList(), r, r.width(), target)
            val r2 = boundsOf(res.strokes).apply { union(res.fills[0].bounds) }
            assertEquals(target, r2.height(), 1f)
            assertEquals(r.width(), r2.width(), 0.5f)
        }
    }

    @Test
    fun aCircleWithNothingToLengthenIsStretched() {
        val hinge = Stamps.place(stamp("Шарнир"), 300f, 300f, 130f, Color.BLACK, 3f).strokes
        val r = boundsOf(hinge)
        val res = Elongate.resize(hinge, emptyList(), emptyList(), r, r.width() * 1.5f, r.height())
        assertEquals(r.width() * 1.5f, boundsOf(res.strokes).width(), 1.5f)
    }

    @Test
    fun axisLabelsStayByTheirArrows() {
        val placed = Stamps.place(stamp("Оси координат"), 300f, 300f, 130f, Color.BLACK, 3f)
        val axes = placed.strokes
        val heads = placed.fills
        val r = boundsOf(axes).apply { for (f in heads) union(f.bounds) }
        // Strokes: x shaft, y shaft, x letter (2), y letter (2); heads are solids.
        val yAxis = boundsOf(axes.subList(1, 2))
        val yLetter = boundsOf(axes.subList(4, 6))
        val xAxis = boundsOf(axes.subList(0, 1))
        val xLetter = boundsOf(axes.subList(2, 4))
        val wide = Elongate.resize(axes, heads, emptyList(), r, r.width() * 2, r.height()).strokes
        assertEquals("y stays by its axis", yLetter.left - yAxis.right, boundsOf(wide.subList(4, 6)).left - boundsOf(wide.subList(1, 2)).right, 1f)
        assertEquals("x moves with the end of its axis", xLetter.right - xAxis.right, boundsOf(wide.subList(2, 4)).right - boundsOf(wide.subList(0, 1)).right, 1.5f)
        val tall = Elongate.resize(axes, heads, emptyList(), r, r.width(), r.height() * 2).strokes
        assertEquals("y moves with the top of its axis", yLetter.top - yAxis.top, boundsOf(tall.subList(4, 6)).top - boundsOf(tall.subList(1, 2)).top, 1.5f)
    }

    @Test
    fun highlightInATable() {
        paint.textSize = 22f
        val line = Paint().apply { color = Color.BLACK; strokeWidth = 1.5f }
        val (xs, ys) = pass(60f, 300f, 216f, 6f)
        val band = HighlightAligner.align(xs, ys, 18f) { c, _ ->
            // A table: rows 40 tall, the pass is over the text of the middle row.
            for (y in listOf(180f, 220f, 260f)) c.drawLine(20f, y, 600f, y, line)
            for (x in listOf(20f, 330f, 600f)) c.drawLine(x, 180f, x, 260f, line)
            c.drawText("Text in a cell", 60f, 212f, paint)
            c.drawText("Next row text", 60f, 252f, paint)
        }!!
        assertTrue("on the letters, not on a line of the table: ${band.y}", band.y in 199f..210f)
        assertTrue("ends at the text, not at the cell border: ${band.x1}", band.x1 < 320f)
    }
}
