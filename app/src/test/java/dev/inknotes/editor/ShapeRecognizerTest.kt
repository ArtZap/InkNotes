package dev.inknotes.editor

import dev.inknotes.editor.ShapeRecognizer.Kind
import dev.inknotes.editor.ShapeRecognizer.P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Hand-drawing is simulated with jittered, unevenly sampled points. */
class ShapeRecognizerTest {
    private val rnd = Random(42)

    private fun jitter(p: P, amount: Float = 2.5f) =
        P(p.x + (rnd.nextFloat() - 0.5f) * 2 * amount, p.y + (rnd.nextFloat() - 0.5f) * 2 * amount)

    /** Points along the polyline with random spacing and jitter. */
    private fun scribble(vararg corners: P, jitter: Float = 2.5f): List<P> {
        val out = ArrayList<P>()
        for (i in 0 until corners.size - 1) {
            val a = corners[i]
            val b = corners[i + 1]
            val n = (hypot(b.x - a.x, b.y - a.y) / (3 + rnd.nextFloat() * 6)).toInt().coerceAtLeast(2)
            for (k in 0 until n) {
                val t = k.toFloat() / n
                out += jitter(P(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t), jitter)
            }
        }
        out += corners.last()
        return out
    }

    private fun kind(pts: List<P>) = ShapeRecognizer.recognize(pts)?.kind

    @Test
    fun aSlightlyTiltedShapeIsLevelled() {
        // A rectangle drawn 3° off level comes out level; one 10° off keeps its tilt.
        fun rect(deg: Double): ShapeRecognizer.Shape {
            val a = Math.toRadians(deg)
            val corners = listOf(0f to 0f, 300f to 0f, 300f to 150f, 0f to 150f, 0f to 0f).map { (x, y) ->
                P((x * cos(a) - y * kotlin.math.sin(a)).toFloat() + 200f, (x * kotlin.math.sin(a) + y * cos(a)).toFloat() + 200f)
            }
            return ShapeRecognizer.Shape(Kind.RECTANGLE, listOf(corners))
        }
        val level = ShapeRecognizer.level(rect(3.0)).paths[0]
        assertEquals(level[0].y, level[1].y, 0.01f)
        val tilted = ShapeRecognizer.level(rect(10.0)).paths[0]
        assertTrue(abs(tilted[0].y - tilted[1].y) > 40f)
        // A circle is left alone.
        val circle = ShapeRecognizer.Shape(Kind.CIRCLE, listOf(listOf(P(0f, 0f), P(10f, 1f))))
        assertTrue(ShapeRecognizer.level(circle) === circle)
    }

    @Test
    fun straightLine() = assertEquals(Kind.LINE, kind(scribble(P(100f, 100f), P(500f, 160f))))

    @Test
    fun wobblyLineStillLine() = assertEquals(Kind.LINE, kind(scribble(P(100f, 100f), P(300f, 112f), P(500f, 100f), jitter = 4f)))

    @Test
    fun tooShortIgnored() = assertNull(ShapeRecognizer.recognize(listOf(P(0f, 0f), P(5f, 3f))))

    @Test
    fun arrowDrawnInOneStroke() {
        // Shaft to the right, then the head: down-left wing, back to the tip, up-left wing.
        val pts = scribble(P(100f, 300f), P(500f, 300f), P(440f, 260f), P(500f, 300f), P(440f, 340f))
        val shape = ShapeRecognizer.recognize(pts)!!
        assertEquals(Kind.ARROW, shape.kind)
        val shaft = shape.paths[0]
        assertEquals(500f, shaft[1].x, 8f)
        assertEquals(300f, shaft[1].y, 8f)
    }

    @Test
    fun zigzagIsPolyline() =
        assertEquals(Kind.POLYLINE, kind(scribble(P(100f, 100f), P(200f, 300f), P(300f, 100f), P(400f, 300f))))

    @Test
    fun circle() {
        val pts = (0..80).map { k ->
            val t = 2 * PI * k / 80
            jitter(P((300 + 120 * cos(t)).toFloat(), (300 + 120 * sin(t)).toFloat()), 4f)
        }
        val shape = ShapeRecognizer.recognize(pts)!!
        assertEquals(Kind.CIRCLE, shape.kind)
        val ring = shape.paths[0]
        val r = hypot(ring[0].x - 300f, ring[0].y - 300f)
        assertEquals(120f, r, 10f)
    }

    @Test
    fun ellipse() {
        val pts = (0..90).map { k ->
            val t = 2 * PI * k / 90
            jitter(P((400 + 200 * cos(t)).toFloat(), (300 + 90 * sin(t)).toFloat()), 3f)
        }
        assertEquals(Kind.ELLIPSE, kind(pts))
    }

    @Test
    fun triangle() =
        assertEquals(Kind.TRIANGLE, kind(scribble(P(300f, 100f), P(560f, 420f), P(100f, 380f), P(298f, 104f))))

    @Test
    fun equilateralTriangle() {
        val shape = ShapeRecognizer.recognize(scribble(P(300f, 100f), P(470f, 395f), P(130f, 392f), P(299f, 103f)))!!
        assertEquals(Kind.EQUILATERAL, shape.kind)
        val r = shape.paths[0]
        val sides = (0 until 3).map { ShapeRecognizer.dist(r[it], r[it + 1]) }
        assertTrue(sides.max() - sides.min() < 1f)
    }

    @Test
    fun rightTriangleGetsExactRightAngle() {
        // Corner at (100, 400), legs slightly off 90°.
        val shape = ShapeRecognizer.recognize(scribble(P(100f, 400f), P(500f, 410f), P(110f, 120f), P(101f, 397f)))!!
        assertEquals(Kind.RIGHT_TRIANGLE, shape.kind)
        val r = shape.paths[0]
        val v = r.minBy { hypot(it.x - 100f, it.y - 400f) }
        val others = r.take(3).filter { it !== v }
        val dot = (others[0].x - v.x) * (others[1].x - v.x) + (others[0].y - v.y) * (others[1].y - v.y)
        assertEquals(0f, dot / (ShapeRecognizer.dist(v, others[0]) * ShapeRecognizer.dist(v, others[1])), 0.01f)
    }

    @Test
    fun isoscelesTriangle() {
        val shape = ShapeRecognizer.recognize(scribble(P(310f, 80f), P(400f, 420f), P(200f, 415f), P(309f, 84f)))!!
        assertEquals(Kind.ISOSCELES, shape.kind)
        val r = shape.paths[0]
        assertEquals(ShapeRecognizer.dist(r[0], r[1]), ShapeRecognizer.dist(r[0], r[2]), 1f)
    }

    @Test
    fun rhombus() {
        val shape = ShapeRecognizer.recognize(scribble(P(300f, 100f), P(410f, 300f), P(302f, 500f), P(190f, 298f), P(299f, 104f)))!!
        assertEquals(Kind.RHOMBUS, shape.kind)
        val r = shape.paths[0]
        val sides = (0 until 4).map { ShapeRecognizer.dist(r[it], r[it + 1]) }
        assertTrue(sides.max() - sides.min() < 1f)
    }

    @Test
    fun parallelogram() {
        val shape = ShapeRecognizer.recognize(scribble(P(200f, 100f), P(600f, 104f), P(500f, 300f), P(98f, 296f), P(198f, 103f)))!!
        assertEquals(Kind.PARALLELOGRAM, shape.kind)
        val r = shape.paths[0]
        assertEquals(ShapeRecognizer.dist(r[0], r[1]), ShapeRecognizer.dist(r[2], r[3]), 0.5f)
    }

    @Test
    fun isoscelesTrapezoid() {
        val shape = ShapeRecognizer.recognize(scribble(P(220f, 100f), P(480f, 106f), P(600f, 300f), P(98f, 294f), P(219f, 103f)))!!
        assertEquals(Kind.TRAPEZOID, shape.kind)
        val r = shape.paths[0]
        // Whichever corner the ring starts at: one pair of opposite sides parallel, the other equal.
        fun angle(i: Int) = Math.atan2((r[i + 1].y - r[i].y).toDouble(), (r[i + 1].x - r[i].x).toDouble())
        fun parallel(i: Int) = abs(Math.sin(angle(i) - angle(i + 2))) < 0.01
        val base = if (parallel(0)) 0 else 1
        assertTrue(parallel(base))
        assertEquals(ShapeRecognizer.dist(r[base + 1], r[base + 2]), ShapeRecognizer.dist(r[(base + 3) % 4], r[(base + 4) % 4]), 1f)
    }

    @Test
    fun starOutline() {
        val corners = (0..10).map { k ->
            val t = -PI / 2 + PI * k / 5
            val r = if (k % 2 == 0) 160 else 65
            P((300 + r * cos(t)).toFloat(), (300 + r * sin(t)).toFloat())
        }
        assertEquals(Kind.STAR, kind(scribble(*corners.toTypedArray(), jitter = 2f)))
    }

    @Test
    fun pentagram() {
        val corners = (0..5).map { k ->
            val t = -PI / 2 + 4 * PI * k / 5
            P((300 + 160 * cos(t)).toFloat(), (300 + 160 * sin(t)).toFloat())
        }
        assertEquals(Kind.STAR, kind(scribble(*corners.toTypedArray(), jitter = 2f)))
    }

    @Test
    fun arc() {
        val pts = (0..60).map { k ->
            val t = PI + PI * k / 60 // upper half circle, left to right
            jitter(P((300 + 150 * cos(t)).toFloat(), (300 + 150 * sin(t)).toFloat()), 2.5f)
        }
        val shape = ShapeRecognizer.recognize(pts)!!
        assertEquals(Kind.ARC, shape.kind)
        val path = shape.paths[0]
        assertTrue(path.all { abs(hypot(it.x - 300f, it.y - 300f) - 150f) < 8f })
        assertEquals(450f, path.last().x, 10f) // drawn left to right
    }

    @Test
    fun rectangleAndSquare() {
        assertEquals(
            Kind.RECTANGLE,
            kind(scribble(P(100f, 100f), P(500f, 104f), P(498f, 300f), P(102f, 296f), P(100f, 104f))),
        )
        val square = ShapeRecognizer.recognize(
            scribble(P(100f, 100f), P(300f, 102f), P(302f, 300f), P(98f, 298f), P(101f, 103f)),
        )!!
        assertEquals(Kind.SQUARE, square.kind)
        val ring = square.paths[0]
        val side1 = ShapeRecognizer.dist(ring[0], ring[1])
        val side2 = ShapeRecognizer.dist(ring[1], ring[2])
        assertEquals(side1, side2, 0.5f)
    }

    @Test
    fun pentagonBecomesRegular() {
        val corners = (0..5).map { k ->
            val t = -PI / 2 + 2 * PI * k / 5
            P((300 + 150 * cos(t)).toFloat(), (300 + 150 * sin(t)).toFloat())
        }
        val shape = ShapeRecognizer.recognize(scribble(*corners.toTypedArray()))!!
        assertEquals(Kind.POLYGON, shape.kind)
        assertEquals(6, shape.paths[0].size) // 5 corners + closing point
        val sides = (0 until 5).map { ShapeRecognizer.dist(shape.paths[0][it], shape.paths[0][it + 1]) }
        assertTrue(sides.max() - sides.min() < 1f)
    }

    /** A heart built from two circular lobes and two straight sides, not from the template curve. */
    private fun sketchedHeart(): List<P> {
        val r = 62f
        val path = ArrayList<P>()
        fun arc(cx: Float, from: Float, to: Float) {
            for (k in 0..20) {
                val t = (from + (to - from) * k / 20) * PI.toFloat() / 180
                path += P(cx + r * cos(t), 250f + r * sin(t))
            }
        }
        // From the notch over the left lobe, down to the tip, up and over the right lobe.
        arc(240f, -14.6f, -225f)
        path += P(300f, 420f)
        arc(360f, 45f, -165.4f)
        path += path.first()
        return scribble(*path.toTypedArray(), jitter = 2f)
    }

    @Test
    fun heart() {
        val shape = ShapeRecognizer.recognize(sketchedHeart())!!
        assertEquals(Kind.HEART, shape.kind)
        val xs = shape.paths[0].map { it.x }
        val ys = shape.paths[0].map { it.y }
        assertEquals(420f, ys.max(), 10f)
        assertEquals(300f, (xs.min() + xs.max()) / 2, 10f)
    }
}
