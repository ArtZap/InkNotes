package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan

class GraphMathTest {
    private fun xs(m: List<GraphMath.Mark>, kind: GraphMath.Kind) = m.filter { it.kind == kind }.map { it.x }.sorted()

    @Test
    fun aParabolaAndALineCrossTwice() {
        val fs = listOf<(Double) -> Double>({ it * it }, { it + 2 })
        val m = GraphMath.marks(fs, -5.0, 5.0)
        assertEquals(listOf(-1.0, 2.0), xs(m, GraphMath.Kind.CROSS))
        assertEquals(4.0, m.first { it.kind == GraphMath.Kind.CROSS && it.x == 2.0 }.y, 1e-9)
        // x + 2 meets the x axis at −2; x² touches it at 0 (a sample there), both meet the y axis.
        assertEquals(listOf(-2.0, 0.0), xs(m, GraphMath.Kind.ROOT))
        assertEquals(listOf(0.0), xs(m, GraphMath.Kind.Y_AXIS))
    }

    @Test
    fun rootsOfSineAreRound() {
        val m = GraphMath.marks(listOf { x: Double -> sin(x) }, -7.0, 7.0)
        val roots = xs(m, GraphMath.Kind.ROOT)
        assertEquals(5, roots.size)
        assertTrue(roots.toString(), abs(roots[3] - Math.PI) < 1e-9)
    }

    @Test
    fun aJumpThroughInfinityIsNotACrossing() {
        val m = GraphMath.marks(listOf { x: Double -> tan(x) }, 1.0, 2.0)
        assertTrue(m.toString(), xs(m, GraphMath.Kind.ROOT).isEmpty())
    }

    @Test
    fun axisMarksAreRound() {
        assertEquals(2.0, GraphMath.step(10.0), 0.0)
        assertEquals(0.5, GraphMath.step(3.0), 0.0)
        assertEquals(listOf(-2.0, 0.0, 2.0, 4.0), GraphMath.ticks(-3.0, 5.0, 2.0))
        assertEquals(listOf(0.1, 0.2, 0.3), GraphMath.ticks(0.05, 0.35, 0.1))
    }

    @Test
    fun aFingerFindsTheNearestCurve() {
        val fs = listOf<(Double) -> Double>({ it * it }, { it + 2 })
        assertEquals(1, GraphMath.nearest(fs, 1.0, 2.8, 0.5)?.first)
        assertEquals(0, GraphMath.nearest(fs, 1.0, 1.1, 0.5)?.first)
        assertEquals(null, GraphMath.nearest(fs, 1.0, 10.0, 0.5))
    }

    @Test
    fun theGraphKeepsItsFunctions() {
        val g = Plot.ofMany(listOf({ x: Double -> x * x }, { x: Double -> x }), "x", false, listOf("y = x²", "y = x"))!!
        assertEquals(2, g.fns.size)
        assertEquals(listOf("y = x²", "y = x"), g.titles)
        assertEquals(1, Plot.of({ x: Double -> x * x }, "x", false)!!.fns.size)
    }
}
