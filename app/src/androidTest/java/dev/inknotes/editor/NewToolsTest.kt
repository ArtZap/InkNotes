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
import dev.inknotes.data.PageStroke
import dev.inknotes.data.PaperKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.hypot

/**
 * The tools added together: tap selection, hatching a closed shape by a tap, scratching
 * out, the hint marker, the ruler, dragging corners, lines sticking together, mirroring,
 * the dashed grid and faded ruling. Rendered pages go to the app's external files dir.
 */
@RunWith(AndroidJUnit4::class)
class NewToolsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var view: PageEditorView
    private lateinit var doc: PageDocument
    private var selectionCount = 0
    private var hint: String? = null

    @Before
    fun setUp() = onMain {
        view = PageEditorView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1600, 2200)
        doc = PageDocument("test", PageContent.EMPTY)
        view.source = object : PageEditorView.PageSource {
            override val pageCount = 1
            override fun pageHeight(index: Int) = PageEditorView.PAGE_H
            override fun document(index: Int) = doc
            override fun pdfBitmap(index: Int): Bitmap? = null
        }
        view.listener = object : PageEditorView.Listener {
            override fun onDocumentChanged() = Unit
            override fun onUndoGesture() = Unit
            override fun onRedoGesture() = Unit
            override fun onSelectionChanged(count: Int) {
                selectionCount = count
            }
            override fun onPendingAreaChanged(pending: Boolean) = Unit
            override fun onVisiblePageChanged(index: Int) = Unit
            override fun onHint(text: String) {
                hint = text
            }
        }
    }

    // ------------------------------------------------------------ selection

    @Test
    fun fingerTapSelectsTheWholeStamp() = onMain {
        val force = Stamps.place(stamp("Сила"), 300f, 400f, Stamps.SIZE, Color.BLACK, 2.8f)
        val hinge = Stamps.place(stamp("Шарнир"), 700f, 400f, Stamps.SIZE, Color.BLACK, 2.8f)
        doc.edit(strokes = force.strokes + hinge.strokes, fills = force.fills + hinge.fills)
        view.tool = Tool.PEN
        // On the shaft of the force: first the whole stamp (not the hinge), then just the shaft.
        tap(300f, 380f)
        assertEquals(force.strokes.size + force.fills.size, selectionCount)
        tap(300f, 380f)
        assertEquals(1, selectionCount)
        // Once more: the whole stamp again.
        tap(300f, 380f)
        assertEquals(force.strokes.size + force.fills.size, selectionCount)
        // Empty paper lets go.
        tap(500f, 900f)
        assertEquals(0, selectionCount)
    }

    @Test
    fun fingerTapSelectsATable() = onMain {
        val table = Stamps.table(3, 3, 500f, 600f, Color.BLACK, 2.8f)
        doc.edit(strokes = table + line(100f, 1100f, 400f))
        view.tool = Tool.PEN
        val b = PageEdits.bounds(table[0])!!
        // The whole table, then one line of it on a second tap.
        tap(b.centerX(), b.centerY())
        assertEquals(table.size, selectionCount)
        save("tap-table.png")
        tap(b.centerX(), b.centerY())
        assertEquals(1, selectionCount)
        // Another line of the table, not selected: the table again.
        val other = PageEdits.bounds(table[table.size - 1])!!
        tap(other.centerX(), other.centerY())
        assertEquals(table.size, selectionCount)
        // Let go, then a line far off: just it.
        tap(500f, 900f)
        assertEquals(0, selectionCount)
        tap(250f, 1100f)
        assertEquals(1, selectionCount)
    }

    @Test
    fun aFingerHeldStillDrawsALasso() {
        onMain {
            doc.edit(strokes = listOf(line(200f, 400f, 400f), line(200f, 450f, 400f), line(600f, 900f, 800f)))
            view.tool = Tool.PEN
        }
        val pts = listOf(150f to 350f, 450f to 350f, 450f to 500f, 150f to 500f, 150f to 352f)
        var view0 = FloatArray(2)
        val t0 = SystemClock.uptimeMillis()
        onMain {
            view0 = view.pageToViewPoint(0, pts[0].first, pts[0].second)
            send(MotionEvent.ACTION_DOWN, view0[0], view0[1], t0, t0, MotionEvent.TOOL_TYPE_FINGER)
        }
        Thread.sleep(700)
        onMain {
            var t = t0 + 700
            for (k in 1 until pts.size) {
                val a = view.pageToViewPoint(0, pts[k - 1].first, pts[k - 1].second)
                val b = view.pageToViewPoint(0, pts[k].first, pts[k].second)
                for (st in 1..10) {
                    t += 8
                    send(MotionEvent.ACTION_MOVE, a[0] + (b[0] - a[0]) * st / 10, a[1] + (b[1] - a[1]) * st / 10, t0, t, MotionEvent.TOOL_TYPE_FINGER)
                }
            }
            val end = view.pageToViewPoint(0, pts.last().first, pts.last().second)
            send(MotionEvent.ACTION_UP, end[0], end[1], t0, t + 8, MotionEvent.TOOL_TYPE_FINGER)
            assertEquals(2, selectionCount)
        }
    }

    // ---------------------------------------------------------- hatching

    @Test
    fun tapHatchesAClosedShape() {
        val table = Stamps.table(1, 1, 500f, 600f, Color.BLACK, 2.8f)
        val cell = PageEdits.bounds(table[0])!!.apply { union(PageEdits.bounds(table[1])!!) }
        onMain {
            doc.edit(strokes = table)
            view.tool = Tool.AREA
            tap(cell.centerX(), cell.centerY())
        }
        settle()
        onMain {
            assertEquals(1, doc.fills.size)
            val f = doc.fills[0].bounds
            // Reaches under the lines, not beyond them.
            assertEquals(cell.top, f.top, 3f)
            assertEquals(cell.bottom, f.bottom, 3f)
            hint = null
            // Outside: not closed, nothing filled, a hint shown.
            tap(100f, 1200f)
        }
        settle()
        onMain {
            assertEquals(1, doc.fills.size)
            assertNotNull(hint)
        }
    }

    @Test
    fun aRingIsHatchedAroundItsHole() {
        onMain {
            val outer = Stamps.place(stamp("Квадрат"), 500f, 600f, 400f, Color.BLACK, 2.8f)
            val inner = Stamps.place(stamp("Окружность"), 500f, 600f, 160f, Color.BLACK, 2.8f)
            doc.edit(strokes = outer.strokes + inner.strokes.take(1))
            view.tool = Tool.AREA
            // Between the circle and the square.
            tap(500f, 500f)
        }
        settle()
        onMain {
            assertEquals(1, doc.fills.size)
            val outline = doc.fills[0].outline
            assertTrue("ring filled", PageEdits.inside(500f, 495f, outline))
            assertFalse("hole left", PageEdits.inside(500f, 600f, outline))
        }
        waitForPicture()
        onMain { save("tap-ring.png") }
    }

    /** Lets work handed to other threads finish and post back. */
    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            instrumentation.waitForIdleSync()
        }
    }

    // ---------------------------------------------------------- scratch out

    @Test
    fun aZigzagScratchesOutWriting() {
        val word = (0 until 6).map { i -> wavy(300f + i * 30f, 500f) }
        val other = wavy(700f, 900f)
        val scribble = zigzag(290f, 470f, 490f, 540f, passes = 8)
        val gone = Scribble.covered(scribble, word + other)
        assertNotNull(gone)
        assertEquals(word.size, gone!!.size)
        // A word written normally is no scribble.
        assertNull(Scribble.covered(wavy(300f, 500f).stroke, word + other))
        // A zigzag over empty paper erases nothing.
        assertNull(Scribble.covered(zigzag(290f, 1100f, 490f, 1170f, passes = 8), word + other))
    }

    // ---------------------------------------------------------- hint marker

    @Test
    fun aHintBandHidesUntilTapped() {
        onMain {
            val text = line(300f, 500f, 600f)
            val band = PageStroke(InkKind.SPOILER, Stroke(InkBrushes.create(InkKind.SPOILER, 0xFF7E8A9A.toInt(), 40f), batch(280f, 500f, 620f, 500f)))
            doc.edit(strokes = listOf(text, band))
        }
        fun pixel(): Int {
            var px = 0
            onMain {
                val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(b))
                val c = view.pageToViewPoint(0, 450f, 500f)
                px = b.getPixel(c[0].toInt(), c[1].toInt())
            }
            return px
        }
        // The page picture (with the text under the band) is drawn on a worker thread.
        waitForPicture()
        val hidden = pixel()
        assertTrue("hidden by the band: ${Integer.toHexString(hidden)}", Color.red(hidden) > 80)
        onMain { save("spoiler-hidden.png") }
        onMain { tap(450f, 500f) }
        val shown = pixel()
        assertTrue("text shows: ${Integer.toHexString(shown)}", Color.red(shown) < 80)
        onMain { save("spoiler-open.png") }
        onMain { tap(450f, 500f) }
        assertTrue(Color.red(pixel()) > 80)
    }

    // ---------------------------------------------------------- ruler

    @Test
    fun aPenAlongTheRulerDrawsAStraightLine() = onMain {
        view.tool = Tool.PEN
        view.penSize = 3f
        view.toggleRuler()
        val world = view.ruler
        world.cx = 500f
        world.cy = 700f
        world.angle = 0f
        val top = 700f - world.width / 2
        // A wobbly pen run just above the top edge.
        trace(listOf(300f to top - 4f, 400f to top - 9f, 500f to top + 3f, 650f to top - 6f))
        assertEquals(1, doc.strokes.size)
        val s = doc.strokes[0]
        assertEquals(InkKind.LINE, s.kind)
        val b = PageEdits.bounds(s)!!
        assertEquals(300f, b.left + 1.5f, 2f)
        assertEquals(650f, b.right - 1.5f, 2f)
        assertEquals(3f, b.height(), 0.6f)
        assertEquals(top - 1.5f, b.centerY(), 0.6f)
        save("ruler.png")
    }

    // ---------------------------------------------------------- corners

    @Test
    fun aShapeIsReshapedByItsCorner() = onMain {
        val tri = Magnet.restroke(template(), floatArrayOf(300f, 700f, 500f, 400f, 700f, 700f, 300f, 700f))
        doc.edit(strokes = listOf(tri))
        view.tool = Tool.PEN
        tap(400f, 700f)
        assertEquals(1, selectionCount)
        // The pen moves a selection with the lasso (with a pen tool it writes).
        view.tool = Tool.LASSO
        // The top corner dragged up and right (to no angle near a multiple of 15°).
        trace(listOf(500f to 400f, 555f to 335f))
        val pts = Magnet.polyline(doc.strokes[0])!!
        assertEquals(8, pts.size)
        assertEquals(555f, pts[2], 1f)
        assertEquals(335f, pts[3], 1f)
        assertEquals(300f, pts[0], 0.5f)
        // The closed corner moves both its ends.
        trace(listOf(300f to 700f, 250f to 750f))
        val p2 = Magnet.polyline(doc.strokes[0])!!
        assertEquals(250f, p2[0], 1f)
        assertEquals(250f, p2[6], 1f)
        save("corner.png")
    }

    @Test
    fun aDraggedCornerSticksToAMultipleOf15Degrees() = onMain {
        val line = Magnet.restroke(template(), floatArrayOf(200f, 600f, 500f, 610f))
        doc.edit(strokes = listOf(line))
        view.tool = Tool.LASSO
        tap(350f, 605f)
        // The end dragged to 3° off level: it goes level.
        trace(listOf(500f to 610f, 520f to 660f, 500f to 615.7f))
        val pts = Magnet.polyline(doc.strokes[0])!!
        assertEquals(600f, pts[3], 0.5f)
        // 3° off 45° up: onto 45°.
        trace(listOf(500f to 600f, 420f to 391f))
        val q = Magnet.polyline(doc.strokes[0])!!
        assertEquals(q[2] - 200f, 600f - q[3], 1f)
    }

    @Test
    fun anArcHasItsEndsAndOneBendPoint() = onMain {
        // A half circle as the recogniser draws it.
        val pts = FloatArray(65 * 2) { i ->
            val a = Math.PI * (i / 2) / 64
            if (i % 2 == 0) (500 - 200 * kotlin.math.cos(a)).toFloat() else (600 - 200 * kotlin.math.sin(a)).toFloat()
        }
        val arc = Magnet.restroke(template(), pts)
        doc.edit(strokes = listOf(arc))
        val h = Magnet.arc(arc)!!
        assertEquals(6, h.size)
        assertEquals(500f, h[2], 2f)
        assertEquals(400f, h[3], 2f)
        view.tool = Tool.LASSO
        tap(300f, 600f)
        assertEquals(1, selectionCount)
        // The bend point pulled down: a flatter arc through it, the ends where they were.
        trace(listOf(h[2] to h[3], 500f to 500f))
        val bent = doc.strokes[0]
        val h2 = Magnet.arc(bent)!!
        assertEquals(300f, h2[0], 1f)
        assertEquals(700f, h2[4], 1f)
        assertEquals(500f, h2[3], 2f)
        save("arc-bent.png")
    }

    @Test
    fun aCurveHasPointsAtItsEndsAndBends() = onMain {
        // An S: two bends the other way round each.
        val pts = FloatArray(81 * 2) { i ->
            val t = (i / 2) / 80f
            if (i % 2 == 0) 200f + 600f * t else 600f + 120f * kotlin.math.sin(t * 2 * Math.PI).toFloat()
        }
        val s = Magnet.restroke(template(), pts)
        val keys = Magnet.curve(s)!!
        assertEquals(8, keys.size)
        assertEquals(350f, keys[2], 15f)
        assertEquals(720f, keys[3], 5f)
        assertEquals(650f, keys[4], 15f)
        assertEquals(480f, keys[5], 5f)
        // Drawn again through its points, it stays near where it was.
        val again = Magnet.curveThrough(keys)
        for (i in again.indices step 2) {
            val x = again[i]
            val y = 600f + 120f * kotlin.math.sin((x - 200f) / 600f * 2 * Math.PI).toFloat()
            assertEquals(y, again[i + 1], 30f)
        }
    }

    @Test
    fun aSideHandleStretchesOneWay() = onMain {
        val box = Magnet.restroke(template(), floatArrayOf(300f, 500f, 500f, 500f, 500f, 600f, 300f, 600f, 300f, 500f))
        doc.edit(strokes = listOf(box))
        view.tool = Tool.LASSO
        tap(400f, 500f)
        val r = PageEdits.bounds(doc.strokes[0])!!
        // The right side's handle pulled right: twice as wide, as tall as before.
        val pad = r.right + 6f
        trace(listOf(pad to r.centerY(), pad + (r.right - r.left) to r.centerY()))
        val after = PageEdits.bounds(doc.strokes[0])!!
        assertEquals(r.height(), after.height(), 1f)
        assertEquals(r.width() * 2, after.width(), 8f)
        // An even line keeps the pen's width however it is resized.
        assertEquals(2.8f, doc.strokes[0].stroke.brush.size, 0.01f)
    }

    @Test
    fun aNeonLineBecomesAPenLineAndBack() = onMain {
        val neon = PageStroke(InkKind.NEON, Stroke(InkBrushes.create(InkKind.NEON, 0xFFFF2BD6.toInt(), 6.5f), batch(200f, 500f, 600f, 500f)))
        doc.edit(strokes = listOf(neon))
        view.tool = Tool.PEN
        tap(400f, 500f)
        assertEquals(setOf(InkKind.NEON), view.selectedLineKinds())
        view.restyleSelection(InkKind.PEN)
        val pen = doc.strokes[0]
        assertEquals(InkKind.PEN, pen.kind)
        assertEquals(0xFFFF2BD6.toInt(), pen.stroke.brush.colorIntArgb)
        assertEquals(6.5f, pen.stroke.brush.size, 0.01f)
        // Still selected, so it can be changed again.
        assertEquals(1, selectionCount)
        view.restyleSelection(InkKind.NEON)
        assertEquals(InkKind.NEON, doc.strokes[0].kind)
        // Markers are left as they are.
        view.restyleSelection(InkKind.HIGHLIGHTER)
        assertEquals(InkKind.NEON, doc.strokes[0].kind)
    }

    // ---------------------------------------------------------- magnet

    @Test
    fun aMovedLineSticksToAnEnd() = onMain {
        val a = Magnet.restroke(template(), floatArrayOf(200f, 500f, 500f, 500f))
        val b = Magnet.restroke(template(), floatArrayOf(507f, 520f, 507f, 800f))
        doc.edit(strokes = listOf(a, b))
        view.tool = Tool.PEN
        tap(507f, 700f)
        assertEquals(1, selectionCount)
        view.tool = Tool.LASSO
        // Dragged almost onto the end of the first line: it sticks there.
        trace(listOf(507f to 700f, 503f to 684f))
        val moved = Magnet.polyline(doc.strokes[1])!!
        assertEquals(500f, moved[0], 0.5f)
        assertEquals(500f, moved[1], 0.5f)
    }

    @Test
    fun aForceSticksToABeam() = onMain {
        val beam = Magnet.restroke(template(), floatArrayOf(100f, 600f, 900f, 600f))
        doc.edit(strokes = listOf(beam))
        val force = Stamps.place(stamp("Сила"), 400f, 470f, Stamps.SIZE, Color.BLACK, 2.8f)
        view.tool = Tool.LASSO
        view.insert(0, strokes = force.strokes, fills = force.fills)
        // The tip is at y ≈ 470 + 52; dragged to a few units above the beam.
        val tipBefore = force.fills[0].outline[1]
        trace(listOf(400f to 450f, 420f to 450f + (596f - tipBefore)))
        val tip = doc.fills[0].outline
        assertEquals(600f, tip[1], 0.5f)
        save("force-beam.png")
    }

    // ---------------------------------------------------------- mirror

    @Test
    fun theMomentMirrors() = onMain {
        val cw = Stamps.place(stamp("Момент по часовой"), 500f, 500f, Stamps.SIZE, Color.BLACK, 2.8f)
        val ccw = Stamps.place(stamp("Момент против часовой"), 500f, 500f, Stamps.SIZE, Color.BLACK, 2.8f)
        view.tool = Tool.LASSO
        view.insert(0, strokes = cw.strokes, fills = cw.fills)
        view.flipSelection(horizontal = true)
        val head = doc.fills[0].outline
        val expected = ccw.fills[0].outline
        // The same head, tip first, mirrored about the selection's middle.
        assertEquals(expected[0], head[0], 1.5f)
        assertEquals(expected[1], head[1], 0.5f)
        save("moment-flip.png")
    }

    @Test
    fun aPenWritesOverATappedLine() = onMain {
        val line = Magnet.restroke(template(), floatArrayOf(200f, 500f, 600f, 500f))
        doc.edit(strokes = listOf(line))
        view.tool = Tool.PEN
        tap(400f, 500f)
        assertEquals(1, selectionCount)
        // A pen stroke across the selected line writes; it does not drag the line.
        trace(listOf(380f to 480f, 420f to 520f))
        assertEquals(0, selectionCount)
        assertEquals(500f, Magnet.polyline(doc.strokes[0])!![1], 0.5f)
    }

    // ---------------------------------------------------------- tables

    @Test
    fun tableGrowsAndShrinks() = onMain {
        val table = Stamps.table(3, 3, 500f, 600f, Color.BLACK, 2.8f)
        doc.edit(strokes = table)
        view.tool = Tool.PEN
        val b = PageEdits.bounds(table[0])!!
        tap(b.centerX(), b.centerY())
        assertTrue(view.selectionIsTable())
        val before = Tables.grid(doc.strokes)!!
        view.addToTable(row = true)
        view.addToTable(row = false)
        val grown = Tables.grid(doc.strokes)!!
        assertEquals(before.rows.size + 1, grown.rows.size)
        assertEquals(before.cols.size + 1, grown.cols.size)
        // The new row is as tall as the last one, the column as wide.
        assertEquals(before.bottom + (before.bottom - before.rows[before.rows.size - 2]), grown.bottom, 1f)
        assertEquals(before.right + (before.right - before.cols[before.cols.size - 2]), grown.right, 1f)
        assertEquals(doc.strokes.size, selectionCount)
        save("table-grown.png")
        assertTrue(view.removeFromTable(row = true))
        assertTrue(view.removeFromTable(row = false))
        val back = Tables.grid(doc.strokes)!!
        assertEquals(before.rows.size, back.rows.size)
        assertEquals(before.cols.size, back.cols.size)
        assertEquals(before.bottom, back.bottom, 1f)
        assertEquals(before.right, back.right, 1f)
        // Down to one row, no further.
        assertTrue(view.removeFromTable(row = true))
        assertTrue(view.removeFromTable(row = true))
        assertFalse(view.removeFromTable(row = true))
    }

    @Test
    fun aHandDrawnGridIsATable() {
        // Slightly wavy lines, upright ones drawn bottom to top, like by hand.
        fun hand(x0: Float, y0: Float, x1: Float, y1: Float): PageStroke {
            val batch = MutableStrokeInputBatch()
            for (i in 0..30) {
                val t = i / 30f
                val wob = kotlin.math.sin(i * 0.9f) * 1.2f
                val dx = if (y0 == y1) 0f else wob
                val dy = if (y0 == y1) wob else 0f
                batch.add(InputToolType.STYLUS, x0 + (x1 - x0) * t + dx, y0 + (y1 - y0) * t + dy, i * 8L, pressure = 0.5f)
            }
            return PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 3f), batch))
        }
        val lines = listOf(
            hand(100f, 100f, 400f, 102f), hand(100f, 160f, 401f, 159f), hand(99f, 220f, 400f, 221f),
            hand(100f, 222f, 101f, 98f), hand(250f, 221f, 251f, 100f), hand(400f, 223f, 399f, 99f),
        )
        val grid = Tables.grid(lines)
        assertNotNull(grid)
        assertEquals(3, grid!!.rows.size)
        assertEquals(3, grid.cols.size)
        val (drop, add) = Tables.removeLast(grid, row = true)!!
        val left = lines.filterNot { it in drop } + add
        val smaller = Tables.grid(left)!!
        assertEquals(2, smaller.rows.size)
        assertEquals(160f, smaller.bottom, 3f)
        // Text and a circle are not a table.
        assertNull(Tables.grid(lines.take(3) + wavy(200f, 300f)))
        // Writing in the cells, a dash and a "1" among it, is content, not lines.
        val dash = hand(130f, 200f, 170f, 200f)
        val one = hand(300f, 170f, 300f, 210f)
        val written = Tables.grid(lines + listOf(wavy(120f, 130f), dash, one))
        assertNotNull(written)
        assertEquals(3, written!!.rows.size)
        assertEquals(3, written.cols.size)
        // Taking the last row away takes what is written in it too.
        val (gone, _) = Tables.removeLast(written, row = true)!!
        assertTrue(dash in gone && one in gone)
        // Writing outside the table: not a table selection.
        assertNull(Tables.grid(lines + wavy(600f, 130f)))
    }

    // ---------------------------------------------------------- neon

    @Test
    fun neonLinesRunTogether() {
        fun neon(x0: Float, y0: Float, x1: Float, y1: Float, size: Float): PageStroke {
            val batch = MutableStrokeInputBatch()
            for (i in 0..40) batch.add(InputToolType.STYLUS, x0 + (x1 - x0) * i / 40, y0 + (y1 - y0) * i / 40, i * 8L, pressure = 0.5f)
            return PageStroke(InkKind.NEON, Stroke(InkBrushes.create(InkKind.NEON, 0xFF00E5FF.toInt(), size), batch))
        }
        val r = PageRenderer()
        r.paper = PageBackground(PaperKind.BLANK)
        r.dark = true
        r.pageWidth = 400f
        r.pageHeight = 400f
        fun render(strokes: List<PageStroke>): Bitmap {
            val b = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            r.drawBackground(c, edgeWidth = 0f)
            r.drawContent(c, strokes, emptyList(), android.graphics.Matrix())
            return b
        }
        val old = neon(50f, 200f, 350f, 200f, 10f)
        val new = neon(200f, 50f, 200f, 350f, 10f)
        val alone = render(listOf(old))
        val both = render(listOf(old, new))
        // On the old line's core next to the new line, outside its tube: the new glow went under it.
        val x = 200 + 9
        assertEquals(alone.getPixel(x, 200), both.getPixel(x, 200))
        // At the crossing the new line's light core is on top.
        val crossing = both.getPixel(200, 200)
        assertTrue("light core at the crossing: ${Integer.toHexString(crossing)}", Color.red(crossing) > 180 && Color.green(crossing) > 230)
        // The brush size is the tube: a thicker line has a wider light core.
        fun coreWidth(b: Bitmap): Int = (0 until 400).count { y -> Color.red(b.getPixel(300, y)) > 170 }
        val thin = coreWidth(render(listOf(neon(50f, 200f, 350f, 200f, 6f))))
        val thick = coreWidth(render(listOf(neon(50f, 200f, 350f, 200f, 16f))))
        assertTrue("core $thin -> $thick", thick >= thin * 2)
        File(context.getExternalFilesDir(null), "test-neon.png").outputStream().use { both.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------------------------------------------------- paper

    @Test
    fun dashedGridAndFadedRuling() {
        val r = PageRenderer()
        fun render(bg: PageBackground): Bitmap {
            r.paper = bg
            r.pageWidth = 1000f
            r.pageHeight = 1414f
            val b = Bitmap.createBitmap(500, 707, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(0.5f, 0.5f)
            r.drawBackground(c, edgeWidth = 0f)
            return b
        }
        fun ink(b: Bitmap): Long {
            var sum = 0L
            for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) sum += 255 - Color.red(b.getPixel(x, y))
            return sum
        }
        val grid = ink(render(PageBackground(PaperKind.GRID)))
        val dashed = render(PageBackground(PaperKind.DASH_GRID))
        val faded = ink(render(PageBackground(PaperKind.GRID, lines = 0.3f)))
        assertTrue("dashed grid drawn", ink(dashed) > 0)
        assertTrue("faded: $faded vs $grid", faded < grid * 0.45f && faded > grid * 0.15f)
        File(context.getExternalFilesDir(null), "test-dash-grid.png").outputStream().use { dashed.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun allStampsRender() {
        onMain {
            Stamps.ALL.forEachIndexed { i, s ->
                val p = Stamps.place(s, 55f + (i % 10) * 98f, 60f + (i / 10) * 98f, 80f, Color.BLACK, 2f)
                doc.edit(strokes = doc.strokes + p.strokes, fills = doc.fills + p.fills)
            }
        }
        waitForPicture()
        onMain { save("stamps-new.png") }
    }

    // ------------------------------------------------------------ helpers

    private fun stamp(title: String) = Stamps.ALL.first { it.title == title }

    private fun template() = PageStroke(InkKind.LINE, Stroke(InkBrushes.create(InkKind.LINE, Color.BLACK, 2.8f), batch(0f, 0f, 1f, 0f)))

    private fun batch(x0: Float, y0: Float, x1: Float, y1: Float): MutableStrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val n = 40
        for (i in 0..n) batch.add(InputToolType.STYLUS, x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n, i * 4L, pressure = 0.6f)
        return batch
    }

    private fun line(x0: Float, y: Float, x1: Float): PageStroke {
        val batch = MutableStrokeInputBatch()
        val n = 40
        // Uneven pressure, like a hand.
        for (i in 0..n) batch.add(InputToolType.STYLUS, x0 + (x1 - x0) * i / n, y, i * 8L, pressure = 0.4f + (i % 5) * 0.05f)
        return PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 3f), batch))
    }

    /** A handwritten-looking letter: a small wave. */
    private fun wavy(x: Float, y: Float): PageStroke {
        val batch = MutableStrokeInputBatch()
        for (i in 0..30) {
            val t = i / 30f
            batch.add(InputToolType.STYLUS, x + t * 22f, y + 12f * kotlin.math.sin(t * 6.28f), i * 9L, pressure = 0.5f + (i % 3) * 0.05f)
        }
        return PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 3f), batch))
    }

    private fun zigzag(x0: Float, y0: Float, x1: Float, y1: Float, passes: Int): Stroke {
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (k in 0..passes) {
            val x = if (k % 2 == 0) x0 else x1
            val y = y0 + (y1 - y0) * k / passes
            batch.add(InputToolType.STYLUS, x, y, t, pressure = 0.6f)
            t += 40
        }
        // Densify a little, as a pen would sample it.
        val dense = MutableStrokeInputBatch()
        val p = androidx.ink.strokes.StrokeInput()
        val q = androidx.ink.strokes.StrokeInput()
        var time = 0L
        for (i in 1 until batch.size) {
            batch.populate(i - 1, p)
            batch.populate(i, q)
            val n = (hypot(q.x - p.x, q.y - p.y) / 4f).toInt().coerceAtLeast(1)
            for (s in 0 until n) {
                dense.add(InputToolType.STYLUS, p.x + (q.x - p.x) * s / n, p.y + (q.y - p.y) * s / n, time, pressure = 0.6f)
                time += 5
            }
        }
        return Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 3f), dense)
    }

    /** Called off the main thread: lets the page picture render and arrive. */
    private fun waitForPicture() {
        val b = Bitmap.createBitmap(1600, 2200, Bitmap.Config.ARGB_8888)
        repeat(30) {
            onMain { view.draw(Canvas(b)) }
            Thread.sleep(60)
        }
    }

    private fun tap(x: Float, y: Float) {
        val c = view.pageToViewPoint(0, x, y)
        val t0 = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, c[0], c[1], t0, t0, MotionEvent.TOOL_TYPE_FINGER)
        send(MotionEvent.ACTION_UP, c[0], c[1], t0, t0 + 60, MotionEvent.TOOL_TYPE_FINGER)
    }

    private fun trace(pagePoints: List<Pair<Float, Float>>) {
        val pts = pagePoints.map { (x, y) -> view.pageToViewPoint(0, x, y) }
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

    private fun send(action: Int, x: Float, y: Float, downTime: Long, time: Long, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = tool
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.6f
        }
        val source = if (tool == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS
        val e = MotionEvent.obtain(downTime, time, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
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
