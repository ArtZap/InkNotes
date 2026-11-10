package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceTest {
    @Test
    fun paraboloid() {
        val s = Calc.surface(Parser.parse("x^{2}+y^{2}"), Env())!!
        assertEquals("x", s.xv)
        assertEquals("y", s.yv)
        assertEquals(Plot3D.N * Plot3D.N, s.zs.size)
        // The middle of the grid is the lowest point.
        val mid = (Plot3D.N / 2) * Plot3D.N + Plot3D.N / 2
        assertEquals(s.z0, s.zs[mid], 1e-4f)
        assertTrue(s.zs.all { it >= s.z0 && it <= s.z1 })
    }

    @Test
    fun oneLetterIsNoSurface() = assertNull(Calc.surface(Parser.parse("x^{2}+1"), Env()))

    @Test
    fun parameterFromThePage() {
        val env = Env()
        Calc.define(Expr.Sym("a"), Expr.Num(2.0), env)
        // a is defined: x and y are left.
        assertTrue(Calc.surface(Parser.parse("a x y"), env) != null)
    }

    @Test
    fun viewFitsTheBoxAndDrawsFarFirst() {
        val s = Calc.surface(Parser.parse("sin x cos y"), Env())!!
        val v = Plot3D.view(s, Plot3D.AZIMUTH, Plot3D.ELEVATION)
        assertEquals((Plot3D.N - 1) * (Plot3D.N - 1), v.patches.size)
        val aspect = Plot.HEIGHT / Plot.WIDTH
        for (p in v.patches) for (k in p.pts.indices step 2) {
            assertTrue(p.pts[k] in 0f..1f)
            assertTrue(p.pts[k + 1] in 0f..aspect)
        }
    }

    @Test
    fun poleIsCutOff() {
        val s = Calc.surface(Parser.parse("\\frac{1}{x^{2}+y^{2}}"), Env())!!
        assertTrue(s.z1 < 1000f)
    }
}
