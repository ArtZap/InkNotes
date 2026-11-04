package dev.inknotes.editor

import android.graphics.Color
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.inknotes.data.InkBrushes
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageStroke
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The hatch tool's areas: narrow corners filled, closed shapes inside left out. */
@RunWith(AndroidJUnit4::class)
class AreaFinderTest {
    private fun line(vararg xy: Float): PageStroke {
        val batch = MutableStrokeInputBatch()
        var t = 0L
        for (i in xy.indices step 2) {
            batch.add(InputToolType.STYLUS, xy[i], xy[i + 1], t)
            t += 8
        }
        return PageStroke(InkKind.LINE, Stroke(InkBrushes.create(InkKind.LINE, Color.BLACK, 2.5f), batch))
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = line(l, t, r, t, r, b, l, b, l, t)

    private fun inside(poly: FloatArray, x: Float, y: Float): Boolean {
        var c = false
        var j = poly.size - 2
        for (i in poly.indices step 2) {
            if ((poly[i + 1] > y) != (poly[j + 1] > y) && x < (poly[j] - poly[i]) * (y - poly[i + 1]) / (poly[j + 1] - poly[i + 1]) + poly[i]) c = !c
            j = i
        }
        return c
    }

    @Test
    fun aSmallClosedShapeInsideStaysEmpty() {
        val strokes = listOf(box(200f, 200f, 600f, 600f), box(400f, 400f, 414f, 414f))
        val area = AreaFinder.find(strokes, 1000f, 1414f, 300f, 300f)
        assertNotNull(area)
        assertTrue(inside(area!!, 300f, 300f))
        assertFalse(inside(area, 407f, 407f))
    }

    @Test
    fun aNarrowCornerIsFilled() {
        // A long thin wedge: near its tip the sides are closer than the gap closing.
        val strokes = listOf(line(200f, 300f, 800f, 300f, 200f, 330f, 200f, 300f))
        val area = AreaFinder.find(strokes, 1000f, 1414f, 250f, 314f)
        assertNotNull(area)
        assertTrue(inside(area!!, 650f, 304f))
    }

    @Test
    fun aTracedAreaLeavesOutTheShapesInside() {
        val strokes = listOf(box(400f, 400f, 460f, 460f))
        val traced = floatArrayOf(300f, 300f, 600f, 300f, 600f, 600f, 300f, 600f)
        val pieces = AreaFinder.carve(strokes, 1000f, 1414f, traced)
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.any { inside(it, 350f, 350f) })
        assertFalse(pieces.any { inside(it, 430f, 430f) })
    }

    private fun circle(cx: Float, cy: Float, r: Float): PageStroke {
        val xy = FloatArray(73 * 2)
        for (k in 0..72) {
            val a = Math.PI * 2 * k / 72
            xy[k * 2] = (cx + r * Math.cos(a)).toFloat()
            xy[k * 2 + 1] = (cy + r * Math.sin(a)).toFloat()
        }
        return line(*xy)
    }

    /** A circle almost touching both sides of a box: the narrow channels beside it are filled too. */
    @Test
    fun narrowChannelsBesideACircleAreFilled() {
        // Box 200..300 wide, circle radius 44 at x 250: 3.5 units of paper either side (lines
        // 2.5 wide), narrower than the 6 a loose end may stop short by.
        val strokes = listOf(box(200f, 200f, 300f, 600f), circle(250f, 400f, 44f))
        val area = AreaFinder.find(strokes, 1000f, 1414f, 250f, 250f)
        assertNotNull(area)
        assertTrue("beside the circle, left", inside(area!!, 203f, 400f))
        assertTrue("beside the circle, right", inside(area, 297f, 400f))
        assertTrue("below the circle", inside(area, 250f, 550f))
        assertFalse("inside the circle", inside(area, 250f, 400f))
    }

    /** A box whose last side stops short of the first: the gap is closed, the tap fills it. */
    @Test
    fun aGapAtALooseEndStillCloses() {
        val strokes = listOf(line(200f, 200f, 400f, 200f, 400f, 400f, 200f, 400f, 200f, 205f))
        val area = AreaFinder.find(strokes, 1000f, 1414f, 300f, 300f)
        assertNotNull(area)
        assertTrue(inside(area!!, 300f, 300f))
        assertFalse(inside(area, 150f, 300f))
    }
}
