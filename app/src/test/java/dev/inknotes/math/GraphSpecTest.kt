package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.abs

class GraphSpecTest {
    private fun same(a: (Double) -> Double, b: (Double) -> Double) =
        listOf(-1.3, 0.4, 2.2).all { x -> val u = a(x); val v = b(x); (u.isNaN() && v.isNaN()) || abs(u - v) < 1e-9 }

    @Test
    fun formulasReadBackExactly() {
        for (s in listOf("x^2", "sin(2x)", "x/2", "e^x", "\\sqrt{x}", "|x|", "2x^3-3x+1", "\\frac{1}{x}", "tg x", "x^{1/3}")) {
            val f = Calc.function(Parser.parse(s), Env())!!
            val back = ExprCodec.decode(ExprCodec.encode(f.expr))
            assertEquals(s, f.expr, back)
        }
        assertEquals(Expr.Sym("a b(%)_"), ExprCodec.decode(ExprCodec.encode(Expr.Sym("a b(%)_"))))
        assertEquals(Expr.Integral(Expr.Sym("t"), "t", null, Expr.Num(1.0)), ExprCodec.decode(ExprCodec.encode(Expr.Integral(Expr.Sym("t"), "t", null, Expr.Num(1.0)))))
        assertNull(ExprCodec.decode("(Q 1)"))
    }

    @Test
    fun aGraphKeepsWhatItShows() {
        val f = Calc.function(Parser.parse("sin(2x)"), Env())!!
        val g = Calc.function(Parser.parse("x/2"), Env())!!
        val spec = GraphSpec("x", listOf(f.expr, g.expr), listOf("y = sin(2x)", "y = x/2"), true, -3.0, 3.0, -1.5, 1.5, listOf(1.24 to 0.62), "k1")
        val back = GraphSpec.decode(spec.encode())!!
        assertEquals(listOf("y = sin(2x)", "y = x/2"), back.titles)
        assertEquals(-3.0, back.x0, 0.0)
        assertEquals(1.5, back.y1, 0.0)
        assertEquals(listOf(1.24 to 0.62), back.points)
        assertEquals("k1", back.key)
        assertTrue(back.trig)
        assertTrue(same(back.fns()[0], f.f) && same(back.fns()[1], g.f))
        assertNull(GraphSpec.decode("something else"))
    }

    /** A tiny PNG: signature, IHDR, IEND. */
    private fun png(): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val t = type.toByteArray()
            val crc = CRC32().apply { update(t); update(data) }.value.toInt()
            fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            return int(data.size) + t + data + int(crc)
        }
        val ihdr = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0)
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            write(chunk("IHDR", ihdr))
            write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
    }

    @Test
    fun thePictureCarriesTheGraph() {
        val text = "graph=1\nv=x\nтекст ²"
        val withText = PngText.write(png(), PngText.GRAPH, text)
        assertEquals(text, PngText.read(withText, PngText.GRAPH))
        assertNull(PngText.read(png(), PngText.GRAPH))
        assertNull(PngText.read(withText, "other"))
        // IEND stays last.
        assertEquals("IEND", String(withText, withText.size - 8, 4))
    }

    @Test
    fun aSurfaceKeepsItsTurn() {
        val s = Calc.surface(Parser.parse("x^2 + y^2"), Env())!!
        val spec = s.spec("k")!!.turned(60f, 40f, 5.0)
        val back = GraphSpec.decode(spec.encode())!!
        assertTrue(back.isSurface)
        assertEquals("y", back.second)
        assertEquals(60f, back.azimuth, 0f)
        assertEquals(40f, back.elevation, 0f)
        assertEquals(5.0, back.x1, 0.0)
        val again = back.surface()!!
        assertEquals(5f, again.x1, 0f)
        assertTrue(again.z1 > 40f)
        assertTrue(back.fns().isEmpty())
    }
}
