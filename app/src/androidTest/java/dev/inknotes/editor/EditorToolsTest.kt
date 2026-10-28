package dev.inknotes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.FillPattern
import dev.inknotes.data.InkBrushes
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageBackground
import dev.inknotes.data.PageContent
import dev.inknotes.data.PaperKind
import dev.inknotes.data.PageStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives [PageEditorView] with synthetic stylus events (lasso, drag, area fill, both eraser
 * modes) and checks the document. Also writes rendered pages to the app's external files
 * dir for a visual check.
 */
@RunWith(AndroidJUnit4::class)
class EditorToolsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var view: PageEditorView
    private lateinit var doc: PageDocument
    private lateinit var doc2: PageDocument
    private var selectionCount = 0
    private var pendingArea = false
    private var picked: Int? = null

    @Before
    fun setUp() = onMain {
        view = PageEditorView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1600, 2200)
        doc = PageDocument("test", PageContent(listOf(line(100f, 200f, 600f), line(100f, 260f, 600f), line(100f, 320f, 600f), line(700f, 200f, 900f)), emptyList()))
        doc2 = PageDocument("test2", PageContent.EMPTY)
        view.source = object : PageEditorView.PageSource {
            override val pageCount = 2
            override fun pageHeight(index: Int) = PageEditorView.PAGE_H
            override fun document(index: Int) = if (index == 0) doc else doc2
            override fun pdfBitmap(index: Int): Bitmap? = null
        }
        view.listener = object : PageEditorView.Listener {
            override fun onDocumentChanged() = Unit
            override fun onUndoGesture() = Unit
            override fun onRedoGesture() = Unit
            override fun onSelectionChanged(count: Int) {
                selectionCount = count
            }
            override fun onPendingAreaChanged(pending: Boolean) {
                pendingArea = pending
            }
            override fun onVisiblePageChanged(index: Int) = Unit
            override fun onColorPicked(color: Int) {
                picked = color
            }
        }
    }

    @Test
    fun lassoSelectsAndDragMovesStrokes() = onMain {
        view.tool = Tool.LASSO
        trace(listOf(60f to 150f, 650f to 150f, 650f to 360f, 60f to 360f, 60f to 152f))
        assertEquals(3, selectionCount)

        val before = PageEdits.bounds(doc.strokes[0])!!
        trace(listOf(300f to 260f, 300f to 560f))
        val after = PageEdits.bounds(doc.strokes[0])!!
        assertEquals(before.top + 300f, after.top, 2f)
        assertEquals(3, selectionCount)
        assertEquals(before.top, PageEdits.bounds(doc.strokes[3])!!.top + 0f, 50f) // unselected stays

        doc.undo()
        assertEquals(before.top, PageEdits.bounds(doc.strokes[0])!!.top, 0.5f)
    }

    /** A selection dropped on the next page moves over to it and stays visible. */
    @Test
    fun selectionMovesToAnotherPage() = onMain {
        // Short pages, so both are on screen.
        view.source = object : PageEditorView.PageSource {
            override val pageCount = 2
            override fun pageHeight(index: Int) = 500f
            override fun document(index: Int) = if (index == 0) doc else doc2
            override fun pdfBitmap(index: Int): Bitmap? = null
        }
        view.relayout()
        view.fitToWidth(0)
        view.tool = Tool.LASSO
        trace(listOf(60f to 150f, 650f to 150f, 650f to 360f, 60f to 360f, 60f to 152f))
        assertEquals(3, selectionCount)
        // From y 260 on page 0 to y 260 + 500 + 40 (gap) + 100, i.e. y ≈ 360 on page 1.
        trace(listOf(300f to 260f, 300f to 900f))
        assertEquals(1, doc.strokes.size)
        assertEquals(3, doc2.strokes.size)
        val moved = PageEdits.bounds(doc2.strokes[0])!!
        assertEquals(200f + 640f - 540f, moved.top + 1.5f, 3f)
        assertEquals(3, selectionCount)
    }

    @Test
    fun areaFillAllPatternsAndPartialEraser() = onMain {
        doc.edit(strokes = emptyList())
        view.tool = Tool.AREA
        val colors = listOf(Color.BLACK, 0xFF1E5BD8.toInt(), 0xFFD32F2F.toInt(), 0xFF2E7D32.toInt(), 0xFF7B1FA2.toInt())
        FillPattern.entries.forEachIndexed { i, pattern ->
            // Two rows of four; every pattern with its own width, spacing and angle.
            val x = 60f + (i % 4) * 230f
            val y = 500f + (i / 4) * 350f
            trace(listOf(x to y, x + 150f to y - 20f, x + 170f to y + 200f, x + 60f to y + 260f, x to y + 150f))
            assertTrue(pendingArea)
            val lineType = dev.inknotes.data.LineType.entries[i % dev.inknotes.data.LineType.entries.size]
            val style = dev.inknotes.data.HatchStyle(width = 1f + i * 0.5f, spacing = 6f + i * 2f, angle = i * 25f, scale = if (i % 2 == 0) 1f else 1.5f, lineType = lineType)
            view.applyFill(pattern, colors[i % colors.size], style)
        }
        assertEquals(FillPattern.entries.size, doc.fills.size)
        assertEquals(50f, doc.fills[2].style.angle, 0.01f)

        view.tool = Tool.ERASER
        view.eraserMode = EraserMode.PARTIAL
        trace(listOf(40f to 620f, 960f to 620f))
        trace(listOf(40f to 970f, 960f to 970f))
        // The ninth pattern (opaque fill) starts a third row.
        trace(listOf(40f to 1320f, 960f to 1320f))
        assertTrue(doc.fills.all { it.cuts.isNotEmpty() })
        save("fills.png")
    }

    /** A stamp appears selected; dragging moves it, the corner handle resizes it, undo works. */
    @Test
    fun stampInsertMoveAndResize() = onMain {
        doc.edit(strokes = emptyList())
        view.tool = Tool.LASSO
        val stamp = Stamps.ALL.first()
        Stamps.place(stamp, 500f, 700f, Stamps.SIZE, Color.BLACK, 2.8f).let { view.insert(0, strokes = it.strokes, fills = it.fills) }
        assertEquals(stamp.paths.size, doc.strokes.size)
        assertEquals(stamp.paths.size + stamp.solids.size, selectionCount)

        fun bounds() = (doc.strokes.mapNotNull { PageEdits.bounds(it) } + doc.fills.map { it.bounds }).reduce { a, b -> android.graphics.RectF(a).apply { union(b) } }
        val before = bounds()
        // Drag from the middle: moves by (100, 50).
        trace(listOf(before.centerX() to before.centerY(), before.centerX() + 100f to before.centerY() + 50f))
        val moved = bounds()
        assertEquals(before.left + 100f, moved.left, 2f)
        assertEquals(before.top + 50f, moved.top, 2f)

        // Drag the bottom-right handle outwards: twice the size, top-left corner stays.
        val cx = moved.right + 4f
        val cy = moved.bottom + 4f
        trace(listOf(cx to cy, cx + moved.width() to cy + moved.height()))
        val resized = bounds()
        assertEquals(moved.left, resized.left, 4f)
        assertEquals(moved.width() * 2, resized.width(), moved.width() * 0.08f)
        save("stamp.png")

        doc.undo()
        assertEquals(moved.width(), bounds().width(), 1f)
    }

    /** The handle above the frame turns the selection; it sticks to 90°. */
    @Test
    fun stampRotates() = onMain {
        doc.edit(strokes = emptyList())
        view.tool = Tool.LASSO
        // A horizontal arrow 130 wide: after a quarter turn it is tall and narrow.
        Stamps.place(Stamps.ALL.first { it.title == "Стрелка" }, 500f, 700f, Stamps.SIZE, Color.BLACK, 2.8f).let { view.insert(0, strokes = it.strokes, fills = it.fills) }
        fun bounds() = (doc.strokes.mapNotNull { PageEdits.bounds(it) } + doc.fills.map { it.bounds }).reduce { a, b -> android.graphics.RectF(a).apply { union(b) } }
        val before = bounds()
        assertTrue(before.width() > before.height() * 3)
        // Handle: above the frame's top centre. Drag it a quarter turn clockwise around the centre.
        val scale = view.pageToViewPoint(0, 1f, 0f)[0] - view.pageToViewPoint(0, 0f, 0f)[0]
        val density = context.resources.displayMetrics.density
        val pad = 8f * density / scale
        val hx = before.centerX()
        val hy = before.top - pad - 30f * density / scale
        val r = before.centerY() - hy
        trace(listOf(hx to hy, before.centerX() + r * 0.7f to before.centerY() - r * 0.7f, before.centerX() + r + 3f to before.centerY() + 2f))
        val after = bounds()
        assertTrue("rotated: $after", after.height() > after.width() * 3)
        assertEquals(before.centerX(), after.centerX(), 3f)
        assertEquals(before.centerY(), after.centerY(), 3f)
        save("rotated.png")
        doc.undo()
        assertEquals(before.width(), bounds().width(), 1f)
    }

    /** While the handle is being turned, the selection is drawn turning in place (not off the page). */
    @Test
    fun rotationPreviewStaysInPlace() = onMain {
        doc.edit(strokes = emptyList())
        view.tool = Tool.LASSO
        Stamps.place(Stamps.ALL.first { it.title == "Стрелка" }, 500f, 700f, Stamps.SIZE, Color.BLACK, 2.8f).let { view.insert(0, strokes = it.strokes, fills = it.fills) }
        val before = (doc.strokes.mapNotNull { PageEdits.bounds(it) } + doc.fills.map { it.bounds }).reduce { a, b -> android.graphics.RectF(a).apply { union(b) } }
        val scale = view.pageToViewPoint(0, 1f, 0f)[0] - view.pageToViewPoint(0, 0f, 0f)[0]
        val density = context.resources.displayMetrics.density
        val hx = before.centerX()
        val hy = before.top - 8f * density / scale - 30f * density / scale
        val r = before.centerY() - hy
        // Pen down on the handle, a quarter turn, and a look before lifting it.
        val pts = listOf(hx to hy, before.centerX() + r * 0.7f to before.centerY() - r * 0.7f, before.centerX() + r to before.centerY())
            .map { (x, y) -> view.pageToViewPoint(0, x, y) }
        val t0 = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, pts[0][0], pts[0][1], t0, t0)
        var t = t0
        for (k in 1 until pts.size) for (s in 1..12) {
            t += 4
            send(MotionEvent.ACTION_MOVE, pts[k - 1][0] + (pts[k][0] - pts[k - 1][0]) * s / 12, pts[k - 1][1] + (pts[k][1] - pts[k - 1][1]) * s / 12, t0, t)
        }
        val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(b))
        fun dark(px: Float, py: Float): Boolean {
            val c = view.pageToViewPoint(0, px, py)
            for (dy in -6..6) for (dx in -6..6) {
                val p = b.getPixel((c[0] + dx).toInt(), (c[1] + dy).toInt())
                if (Color.red(p) < 90 && Color.green(p) < 90 && Color.blue(p) < 90) return true
            }
            return false
        }
        // Turned upright around its centre: ink above and below the centre, none at the old ends.
        assertTrue("turned arrow drawn at its place", dark(before.centerX(), before.centerY() - before.width() / 3))
        assertTrue("turned arrow drawn at its place", dark(before.centerX(), before.centerY() + before.width() / 3))
        assertTrue("old position empty", !dark(before.left + 4f, before.centerY()))
        send(MotionEvent.ACTION_UP, pts.last()[0], pts.last()[1], t0, t + 4)
    }

    @Test
    fun allStampsAndTableRender() = onMain {
        doc.edit(strokes = emptyList())
        Stamps.ALL.forEachIndexed { i, stamp ->
            Stamps.place(stamp, 55f + (i % 10) * 98f, 60f + (i / 10) * 98f, 80f, Color.BLACK, 2f).let { view.insert(0, strokes = it.strokes, fills = it.fills) }
        }
        view.insert(0, strokes = Stamps.table(3, 3, 500f, 1300f, Color.BLACK, 2.8f))
        assertEquals(Stamps.ALL.sumOf { it.paths.size } + 4 + 4, doc.strokes.size)
        view.clearSelection()
        save("stamps.png")
    }

    @Test
    fun lassoRestylesFills() = onMain {
        doc.edit(strokes = emptyList())
        view.tool = Tool.AREA
        trace(listOf(100f to 500f, 400f to 500f, 400f to 800f, 100f to 800f, 100f to 504f))
        view.applyFill(FillPattern.LINES, Color.BLACK)
        view.tool = Tool.LASSO
        trace(listOf(50f to 450f, 450f to 450f, 450f to 850f, 50f to 850f, 50f to 452f))
        assertEquals(1, view.selectedFillCount())
        val style = dev.inknotes.data.HatchStyle(width = 4f, spacing = 20f, angle = 90f)
        view.restyleSelection(FillPattern.WAVES, style)
        assertEquals(FillPattern.WAVES, doc.fills.single().pattern)
        assertEquals(style, doc.fills.single().style)
        assertEquals(1, view.selectedFillCount()) // still selected after the change
        doc.undo()
        assertEquals(FillPattern.LINES, doc.fills.single().pattern)
    }

    @Test
    fun partialEraserSplitsStrokes() = onMain {
        view.tool = Tool.ERASER
        view.eraserMode = EraserMode.PARTIAL
        trace(listOf(350f to 150f, 350f to 380f))
        // Three long lines cut in two; the short one untouched.
        assertEquals(7, doc.strokes.size)
        save("partial-erase.png")
        doc.undo()
        assertEquals(4, doc.strokes.size)

        view.eraserMode = EraserMode.WHOLE
        trace(listOf(350f to 150f, 350f to 380f))
        assertEquals(1, doc.strokes.size)
    }

    @Test
    fun inkColorsStayVisibleOnBothPapers() {
        assertEquals(0xFFE8E8E8.toInt(), InkColors.forPaper(Color.BLACK, dark = true))
        assertEquals(0xFF1A1A1A.toInt(), InkColors.forPaper(Color.WHITE, dark = false))
        assertEquals(0xFF1E5BD8.toInt(), InkColors.forPaper(0xFF1E5BD8.toInt(), dark = true))
        assertEquals(0x80E8E8E8.toInt(), InkColors.forPaper(0x80000000.toInt(), dark = true))
        // Ink stored as light grey by v0.3 builds must still show up on light paper.
        assertEquals(0xFF1A1A1A.toInt(), InkColors.forPaper(0xFFE8E8E8.toInt(), dark = false))
        // Saturated light/dark colours keep their colour.
        assertEquals(0x80FFEB3B.toInt(), InkColors.forPaper(0x80FFEB3B.toInt(), dark = false))
        assertEquals(0xFF14254D.toInt(), InkColors.forPaper(0xFF14254D.toInt(), dark = true))
    }

    /** Renders every background in both themes (plus a generated photo) for a visual check. */
    @Test
    fun renderBackgrounds() = renderBackgrounds(dark = false)

    @Test
    fun renderBackgroundsDark() = renderBackgrounds(dark = true)

    private fun renderBackgrounds(dark: Boolean) {
        val photo = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            c.drawColor(0xFF8FB8DE.toInt())
            c.drawCircle(150f, 100f, 70f, android.graphics.Paint().apply { color = 0xFFF2C14E.toInt() })
        }
        onMain { doc.edit(fills = listOf(dev.inknotes.data.PageFill(floatArrayOf(700f, 300f, 950f, 300f, 950f, 420f, 700f, 420f), FillPattern.LINES, Color.BLACK))) }
        // One paper per main-thread turn: all of them in one would block the app long enough
        // for the system to call it frozen.
        for (kind in PaperKind.entries) {
            onMain {
                view.dark = dark
                view.paper = PageBackground(kind, if (kind == PaperKind.PHOTO) "x" else null)
                view.paperPhoto = if (kind == PaperKind.PHOTO) photo else null
                save("bg-${if (dark) "dark" else "light"}-${kind.name.lowercase()}.png")
            }
        }
    }

    @Test
    fun gesturesWorkOnTheSecondPage() = onMain {
        view.scrollToPage(1)
        view.tool = Tool.AREA
        trace(listOf(100f to 100f, 400f to 100f, 400f to 400f, 100f to 400f, 100f to 104f), page = 1)
        view.applyFill(FillPattern.DOTS, Color.BLACK)
        assertEquals(1, doc2.fills.size)
        assertEquals(0, doc.fills.size)
    }

    /** Pages drawn from their cached pictures show every change: added, erased, selected. */
    @Test
    fun cachedPagesShowEveryChange() {
        fun shot(): Bitmap {
            lateinit var b: Bitmap
            // The first frame starts the background render; the next ones use the picture.
            onMain { view.refresh() }
            Thread.sleep(1500)
            onMain {
                b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(b))
            }
            return b
        }
        fun inkAt(b: Bitmap, x: Float, y: Float): Boolean {
            val p = view.pageToViewPoint(0, x, y)
            val c = b.getPixel(p[0].toInt(), p[1].toInt())
            return Color.red(c) < 100 && Color.green(c) < 100 && Color.blue(c) < 100
        }
        shot()
        val b1 = shot()
        assertTrue("existing line drawn", inkAt(b1, 300f, 200f))
        assertTrue("paper elsewhere", !inkAt(b1, 300f, 700f))
        // A new stroke is patched into the picture.
        onMain { doc.add(line(100f, 700f, 600f)) }
        val b2 = shot()
        assertTrue("new line drawn", inkAt(b2, 300f, 700f))
        assertTrue("old line still there", inkAt(b2, 300f, 200f))
        // Erased: gone from the picture.
        onMain { doc.edit(strokes = doc.strokes.filterNot { PageEdits.bounds(it)!!.centerY() in 695f..705f }) }
        val b3 = shot()
        assertTrue("erased line gone", !inkAt(b3, 300f, 700f))
        // Selected: left out of the picture but drawn on top.
        onMain {
            view.tool = Tool.LASSO
            trace(listOf(60f to 150f, 650f to 150f, 650f to 240f, 60f to 240f, 60f to 152f))
        }
        assertEquals(1, selectionCount)
        val b4 = shot()
        assertTrue("selected line drawn", inkAt(b4, 300f, 200f))
        // Undo of everything: the picture follows.
        onMain { while (doc.undo()) Unit }
        val b5 = shot()
        assertTrue("back to the start", inkAt(b5, 300f, 260f) && !inkAt(b5, 300f, 700f))
    }

    /** A wobbly highlighter pass over a line of handwriting is laid level behind it. */
    @Test
    fun highlighterLiesLevelBehindText() {
        // A line of "handwriting": short uprights 30 tall from y = 500.
        onMain {
            val word = (0 until 12).map { k ->
                val batch = MutableStrokeInputBatch()
                val x = 200f + k * 25f
                for (i in 0..10) batch.add(InputToolType.STYLUS, x + i * 0.8f, 500f + i * 3f, i * 8L)
                PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 4f), batch))
            }
            doc.edit(strokes = doc.strokes + word)
        }
        // The stroke as the pen drew it (the live ink layer needs a window, so it is handed over directly).
        val batch = MutableStrokeInputBatch()
        for (k in 0..20) batch.add(InputToolType.STYLUS, 190f + k * 15f, 522f + (if (k % 2 == 0) 6f else -6f), k * 8L)
        val drawn = Stroke(InkBrushes.create(InkKind.HIGHLIGHTER, 0x80FFEB3B.toInt(), 20f), batch)
        lateinit var h: PageStroke
        onMain { h = PageStroke(InkKind.HIGHLIGHTER, view.alignHighlight(0, drawn, 0x80FFEB3B.toInt(), 20f)!!) }
        val inputs = h.stroke.inputs
        val p = androidx.ink.strokes.StrokeInput()
        val ys = (0 until inputs.size).map { inputs.populate(it, p); p.y }
        assertEquals(0f, ys.max() - ys.min(), 0.01f)
        assertEquals(515f, ys[0], 3f)
        assertEquals(200f, ys.size.let { (0 until inputs.size).minOf { inputs.populate(it, p); p.x } }, 8f)
        assertEquals(20f, h.stroke.brush.size, 0.01f)
    }

    /** Pen held still after drawing a rough circle: the stroke becomes a perfect circle. */
    @Test
    fun holdingThePenSnapsToCircle() {
        onMain { view.tool = Tool.PEN }
        val pts = (0..60).map { k ->
            val t = 2 * Math.PI * k / 60
            val wobble = if (k % 2 == 0) 4f else -4f
            (500f + (150f + wobble) * Math.cos(t).toFloat()) to (700f + (150f - wobble) * Math.sin(t).toFloat())
        }
        val view = pts.map { (x, y) -> this.view.pageToViewPoint(0, x, y) }
        val t0 = SystemClock.uptimeMillis()
        onMain {
            send(MotionEvent.ACTION_DOWN, view[0][0], view[0][1], t0, t0)
            view.drop(1).forEachIndexed { i, p -> send(MotionEvent.ACTION_MOVE, p[0], p[1], t0, t0 + 8L * (i + 1)) }
        }
        Thread.sleep(PageEditorView.HOLD_MS + 400)
        onMain { send(MotionEvent.ACTION_UP, view.last()[0], view.last()[1], t0, SystemClock.uptimeMillis()) }

        assertEquals(5, doc.strokes.size)
        val circle = PageEdits.bounds(doc.strokes.last())!!
        assertEquals(300f, circle.width(), 12f)
        assertEquals(300f, circle.height(), 12f)
        assertEquals(500f, circle.centerX(), 6f)
        assertEquals(700f, circle.centerY(), 6f)
        doc.undo()
        assertEquals(4, doc.strokes.size)
    }

    /** A neon line glows past its edge; the eyedropper takes its colour without drawing. */
    @Test
    fun neonGlowsAndEyedropperPicksIt() {
        val pink = 0xFFFF2BD6.toInt()
        onMain {
            view.dark = true
            val batch = MutableStrokeInputBatch()
            for (i in 0..40) batch.add(InputToolType.STYLUS, 100f + i * 15f, 900f, i * 8L)
            doc.add(PageStroke(InkKind.NEON, Stroke(InkBrushes.create(InkKind.NEON, pink, 4f), batch)))
            // The same tube with the widest halo.
            val far = MutableStrokeInputBatch()
            for (i in 0..40) far.add(InputToolType.STYLUS, 100f + i * 15f, 1300f, i * 8L)
            doc.add(PageStroke(InkKind.NEON, Stroke(InkBrushes.create(InkKind.NEON, pink, 16f), far)))
        }
        lateinit var b: Bitmap
        // The first frame starts the background render of the page; the second shows it.
        repeat(2) {
            onMain {
                b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(b))
            }
            Thread.sleep(1500)
        }
        onMain { save("neon-dark.png") }
        fun at(x: Float, y: Float) = view.pageToViewPoint(0, x, y).let { b.getPixel(it[0].toInt(), it[1].toInt()) }
        val core = at(400f, 900f)
        assertTrue("light core", Color.red(core) > 220 && Color.blue(core) > 200 && Color.green(core) > 150)
        val glow = at(400f, 908f) // just past the tube's edge (half width 3.25)
        assertTrue("pink glow ${Integer.toHexString(glow)}", Color.red(glow) > Color.green(glow) + 40 && Color.blue(glow) > Color.green(glow) + 20)
        val far = at(400f, 1100f)
        assertTrue("dark paper far away", Color.red(far) < 60)
        // Same tube whatever the halo; the wider halo reaches further.
        assertTrue("same light core", Color.red(at(400f, 1300f)) > 220)
        assertTrue("wide halo reaches further", Color.red(at(400f, 1325f)) > Color.red(at(400f, 925f)) + 15)

        onMain {
            view.pickingColor = true
            view.tool = Tool.PEN
            view.pickingColor = true
            trace(listOf(400f to 906f, 400f to 906f))
        }
        val c = picked
        assertTrue("picked a colour", c != null)
        assertTrue("pinkish ${Integer.toHexString(c!!)}", Color.red(c) > Color.green(c) + 40)
        assertTrue("eyedropper off again", !view.pickingColor)
        assertEquals("nothing drawn", 6, doc.strokes.size)
    }

    // ------------------------------------------------------------------

    private fun line(x0: Float, y: Float, x1: Float): PageStroke {
        val batch = MutableStrokeInputBatch()
        val n = 40
        for (i in 0..n) batch.add(InputToolType.STYLUS, x0 + (x1 - x0) * i / n, y, i * 8L)
        return PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 3f), batch))
    }

    /** Sends one stylus gesture through the page points, interpolated in view space. */
    private fun trace(pagePoints: List<Pair<Float, Float>>, page: Int = 0) {
        val pts = pagePoints.map { (x, y) -> view.pageToViewPoint(page, x, y) }
        val t0 = SystemClock.uptimeMillis()
        var t = t0
        send(MotionEvent.ACTION_DOWN, pts[0][0], pts[0][1], t0, t)
        for (k in 1 until pts.size) {
            val a = pts[k - 1]
            val b = pts[k]
            for (s in 1..12) {
                t += 4
                send(MotionEvent.ACTION_MOVE, a[0] + (b[0] - a[0]) * s / 12, a[1] + (b[1] - a[1]) * s / 12, t0, t)
            }
        }
        val last = pts.last()
        send(MotionEvent.ACTION_UP, last[0], last[1], t0, t + 4)
    }

    private fun send(action: Int, x: Float, y: Float, downTime: Long, time: Long) {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.6f
        }
        val e = MotionEvent.obtain(
            downTime, time, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
        )
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun save(name: String) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(context.getExternalFilesDir(null), "test-$name").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun onMain(block: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
    }
}
