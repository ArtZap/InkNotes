package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout tests on drawn-by-code strokes; the "reader" knows what each stroke was meant to be. */
class MathPageTest {

    private class Sheet {
        val strokes = ArrayList<Ink>()
        val labels = HashMap<Ink, String>()

        fun add(label: String?, xs: List<Float>, ys: List<Float>): Ink {
            val ink = Ink(xs.toFloatArray(), ys.toFloatArray(), strokes.size)
            strokes += ink
            if (label != null) labels[ink] = label
            return ink
        }

        /** A symbol drawn as a zigzag filling its box. */
        fun glyph(label: String, x: Float, top: Float, w: Float = 14f, h: Float = 24f) =
            add(label, listOf(x, x + w, x, x + w), listOf(top, top + h / 3, top + 2 * h / 3, top + h))

        fun dash(x: Float, y: Float, w: Float, label: String? = "-") = add(label, listOf(x, x + w / 2, x + w), listOf(y, y + 0.6f, y))

        fun equals(x: Float, cy: Float, w: Float = 18f) {
            dash(x, cy - 4, w, null)
            dash(x, cy + 4, w, null)
        }

        /** Curve x(t) over a tall box, t from top (0) to bottom (1). */
        fun tall(label: String?, x: Float, top: Float, h: Float, w: Float, shape: (Float) -> Float) {
            val ts = (0..40).map { it / 40f }
            add(label, ts.map { x + w * shape(it) }, ts.map { top + h * it })
        }

        fun reader(alt: Map<String, List<String>> = emptyMap()) = RunReader { run, _ ->
            val label = run.sortedBy { it.left }.mapNotNull { labels[it] }.joinToString("")
            alt[label] ?: listOf(label)
        }
        fun answers(alt: Map<String, List<String>> = emptyMap()) = MathPage.analyze(strokes, reader(alt))
    }

    private fun text(a: PageAnswer) = (a.answer as Answer.Text).lines.joinToString("\n")

    @Test
    fun simpleSum() {
        val s = Sheet()
        s.glyph("2", 100f, 100f)
        s.glyph("+", 120f, 106f, 12f, 12f)
        s.glyph("3", 140f, 100f)
        s.equals(162f, 112f)
        val a = s.answers().single()
        assertEquals("5", text(a))
        assertTrue(a.x > 180f && a.y in 108f..116f)
    }

    @Test
    fun plusOfTwoStraightStrokes() {
        val s = Sheet()
        s.glyph("2", 100f, 100f)
        s.glyph("3", 120f, 100f)
        s.add("+", listOf(146f, 146f), listOf(103f, 121f))
        s.add(null, listOf(137f, 155f), listOf(112f, 112f))
        s.glyph("1", 162f, 100f, 8f)
        s.equals(180f, 112f)
        val a = s.answers().single()
        assertEquals("24", text(a))
    }

    @Test
    fun handAnsweredLineGetsNothing() {
        val s = Sheet()
        s.glyph("2", 100f, 100f)
        s.glyph("+", 120f, 106f, 12f, 12f)
        s.glyph("3", 140f, 100f)
        s.equals(162f, 112f)
        s.glyph("5", 190f, 100f)
        assertTrue(s.answers().isEmpty())
    }

    @Test
    fun variableAndPower() {
        val s = Sheet()
        // x = 3
        s.glyph("x", 100f, 58f, 14f, 16f)
        s.equals(122f, 66f)
        s.glyph("3", 148f, 50f)
        // x² + 1 =
        s.glyph("x", 100f, 108f, 14f, 16f)
        s.glyph("2", 116f, 95f, 9f, 12f)
        s.glyph("+", 132f, 106f, 12f, 12f)
        s.glyph("1", 150f, 100f, 8f)
        s.equals(166f, 112f)
        assertEquals("10", text(s.answers().single()))
    }

    @Test
    fun fraction() {
        val s = Sheet()
        s.glyph("6", 104f, 84f, 14f, 22f)
        s.dash(100f, 112f, 24f)
        s.glyph("3", 104f, 117f, 14f, 22f)
        s.glyph("+", 132f, 106f, 12f, 12f)
        s.glyph("1", 152f, 100f, 8f)
        s.equals(170f, 112f)
        assertEquals("3", text(s.answers().single()))
    }

    @Test
    fun determinant() {
        val s = Sheet()
        s.tall(null, 100f, 80f, 64f, 2f) { 0f }
        s.glyph("1", 112f, 86f)
        s.glyph("2", 140f, 86f)
        s.glyph("3", 112f, 118f)
        s.glyph("4", 140f, 118f)
        s.tall(null, 164f, 80f, 64f, 2f) { 0f }
        s.equals(176f, 112f)
        assertEquals("−2", text(s.answers().single()))
    }

    @Test
    fun matrixProduct() {
        val s = Sheet()
        // (1 2; 3 4) · 2 =
        s.tall(null, 100f, 80f, 64f, 10f) { t -> (2 * t - 1) * (2 * t - 1) }
        s.glyph("1", 114f, 86f)
        s.glyph("2", 140f, 86f)
        s.glyph("3", 114f, 118f)
        s.glyph("4", 140f, 118f)
        s.tall(null, 160f, 80f, 64f, 10f) { t -> 1 - (2 * t - 1) * (2 * t - 1) }
        s.glyph("2", 180f, 100f)
        s.equals(202f, 112f)
        val a = s.answers().single().answer as Answer.Grid
        assertEquals(listOf(listOf("2", "4"), listOf("6", "8")), a.rows)
    }

    @Test
    fun definiteIntegral() {
        val s = Sheet()
        // ∫ from 0 to 1 of x² dx
        s.tall(null, 100f, 80f, 64f, 14f) { t -> if (t < 0.2f) 0.5f + 2.25f * (0.2f - t) else if (t > 0.8f) 0.5f - 2.25f * (t - 0.8f) else 0.5f }
        s.glyph("1", 118f, 70f, 7f, 12f)
        s.glyph("0", 118f, 146f, 8f, 12f)
        s.glyph("x", 134f, 108f, 14f, 16f)
        s.glyph("2", 150f, 95f, 9f, 12f)
        s.glyph("d", 166f, 100f)
        s.glyph("x", 184f, 108f, 14f, 16f)
        s.equals(206f, 112f)
        assertEquals("1/3", text(s.answers().single()))
    }

    @Test
    fun system() {
        val s = Sheet()
        // { x + y = 3
        //   x − y = 1
        s.tall(null, 90f, 66f, 100f, 12f) { t -> if (t < 0.5f) 1 - 2 * t * (1 - 0.0f) else 2 * t - 1 }
        s.glyph("x", 110f, 80f, 14f, 16f)
        s.glyph("+", 128f, 80f, 12f, 12f)
        s.glyph("y", 146f, 80f, 14f, 22f)
        s.equals(166f, 88f)
        s.glyph("3", 190f, 76f)
        s.glyph("x", 110f, 136f, 14f, 16f)
        s.dash(128f, 143f, 12f)
        s.glyph("y", 146f, 136f, 14f, 22f)
        s.equals(166f, 144f)
        s.glyph("1", 190f, 132f, 8f)
        val a = s.answers().single()
        assertEquals("x = 2; y = 1", text(a))
        // Under the system, from the brace, never over the equations.
        assertTrue(a.x < 110f)
        assertTrue(a.y > 158f)
    }

    /** A Russian б reads as 6 first: in a system whose unknowns are a and б, 3б is 3·b, not 36. */
    @Test
    fun systemWithCyrillicB() {
        val s = Sheet()
        // { a = 3б + 6
        //   б = 7a − 11
        s.tall(null, 90f, 66f, 100f, 12f) { t -> if (t < 0.5f) 1 - 2 * t else 2 * t - 1 }
        s.glyph("a", 110f, 84f, 14f, 16f)
        s.equals(130f, 92f)
        s.glyph("3", 156f, 80f)
        s.glyph("Б", 174f, 80f)
        s.glyph("+", 194f, 86f, 12f, 12f)
        s.glyph("6", 212f, 80f)
        s.glyph("Б", 110f, 132f)
        s.equals(130f, 144f)
        s.glyph("7", 156f, 132f)
        s.glyph("a", 174f, 140f, 14f, 16f)
        s.dash(194f, 147f, 12f)
        s.glyph("1", 212f, 132f, 8f)
        s.glyph("1", 224f, 132f, 8f)
        // How ML Kit reads them: digits first.
        val a = s.answers(mapOf("3Б+6" to listOf("36+6", "3b+6"), "Б" to listOf("6", "b"))).single()
        val answer = text(a)
        assertTrue(answer, answer.startsWith("a = ") && "b = " in answer && "42" !in answer)
    }

    @Test
    fun userFunction() {
        val s = Sheet()
        // f(x) = x²
        s.glyph("f(x)", 100f, 50f, 40f)
        s.equals(150f, 62f)
        s.glyph("x", 176f, 58f, 14f, 16f)
        s.glyph("2", 192f, 45f, 9f, 12f)
        // f(3) =
        s.glyph("f(3)", 100f, 100f, 40f)
        s.equals(150f, 112f)
        val answers = s.answers()
        assertEquals("9", text(answers.single { it.answer is Answer.Text }))
        // f(x) = x² gets its graph as well.
        assertEquals("x", (answers.single { it.answer is Answer.Graph }.answer as Answer.Graph).variable)
    }

    @Test
    fun inequality() {
        val s = Sheet()
        // x² < 4
        s.glyph("x", 100f, 108f, 14f, 16f)
        s.glyph("2", 116f, 95f, 9f, 12f)
        s.add(null, listOf(152f, 136f, 152f), listOf(102f, 112f, 122f))
        s.glyph("4", 162f, 100f)
        assertEquals("x ∈ (−2; 2)", text(s.answers().single()))
        // 2x − 4 ≥ 0, the ≥ with its dash under it, on a line of its own
        val t = Sheet()
        t.glyph("2", 100f, 200f)
        t.glyph("x", 116f, 208f, 14f, 16f)
        t.dash(134f, 214f, 12f)
        t.glyph("4", 150f, 200f)
        t.add(null, listOf(170f, 186f, 170f), listOf(202f, 210f, 218f))
        t.dash(170f, 224f, 16f, null)
        t.glyph("0", 196f, 200f)
        assertEquals("x ∈ [2; +∞)", text(t.answers().single()))
    }

    @Test
    fun graphOfY() {
        val s = Sheet()
        // y = x² beside free paper: a graph to the right of the line.
        s.glyph("y", 100f, 108f, 14f, 22f)
        s.equals(122f, 112f)
        s.glyph("x", 148f, 108f, 14f, 16f)
        s.glyph("2", 164f, 95f, 9f, 12f)
        val a = s.answers().single()
        val g = a.answer as Answer.Graph
        assertEquals("x", g.variable)
        assertTrue(a.x > 170f)
        // The vertex at 0 is in view, and the parabola is one piece.
        assertTrue(g.x0 < 0f && g.x1 > 0f && g.y0 <= 0f)
        assertEquals(1, g.segments.size)
    }

    @Test
    fun definitionBelowCounts() {
        val s = Sheet()
        // v + 1 = ; and v = 4 written later, lower on the page
        s.glyph("v", 100f, 108f, 14f, 16f)
        s.glyph("+", 120f, 106f, 12f, 12f)
        s.glyph("1", 140f, 100f, 8f)
        s.equals(156f, 112f)
        s.glyph("v", 100f, 208f, 14f, 16f)
        s.equals(122f, 216f)
        s.glyph("4", 148f, 200f)
        assertEquals("5", text(s.answers().single()))
    }

    @Test
    fun questionMarkAfterEquals() {
        val s = Sheet()
        s.glyph("2", 100f, 100f)
        s.glyph("+", 120f, 106f, 12f, 12f)
        s.glyph("3", 140f, 100f)
        s.equals(162f, 112f)
        s.glyph("?", 188f, 100f)
        val a = s.answers().single()
        assertEquals("5", text(a))
        assertTrue(a.x > 202f)
    }

    @Test
    fun oneStrokeEquals() {
        val s = Sheet()
        s.glyph("2", 100f, 100f)
        s.glyph("+", 120f, 106f, 12f, 12f)
        s.glyph("3", 140f, 100f)
        // top line, back diagonally, bottom line
        s.add(null, listOf(162f, 172f, 180f, 171f, 162f, 171f, 180f), listOf(108f, 108.5f, 108f, 112f, 116f, 116.3f, 116f))
        assertEquals("5", text(s.answers().single()))
    }

    @Test
    fun twoStrokeRoot() {
        val s = Sheet()
        // tick, then a bar over 9
        s.add(null, listOf(96f, 100f, 104f, 110f), listOf(112f, 110f, 124f, 96f))
        s.add(null, listOf(110f, 122f, 134f), listOf(96f, 96.5f, 96f))
        s.glyph("9", 114f, 100f)
        s.equals(142f, 112f)
        assertEquals("3", text(s.answers().single()))
    }

    @Test
    fun lettersThatAreDigits() {
        val s = Sheet()
        // the reader takes 60 for "Go" without context
        s.glyph("Go", 100f, 100f, 28f)
        s.glyph("+", 134f, 106f, 12f, 12f)
        s.glyph("1", 152f, 100f, 8f)
        s.equals(168f, 112f)
        assertEquals("61", text(s.answers().single()))
    }

    @Test
    fun wordsBeforeTheFormula() {
        val s = Sheet()
        // "Otvet:" then, after a space, 2+3=
        s.glyph("Otvet:", 20f, 100f, 60f)
        s.glyph("2", 100f, 100f)
        s.glyph("+", 120f, 106f, 12f, 12f)
        s.glyph("3", 140f, 100f)
        s.equals(162f, 112f)
        assertEquals("5", text(s.answers().single()))
    }

    @Test
    fun plusAddedLater() {
        val s = Sheet()
        s.glyph("2", 100f, 100f)
        s.glyph("3", 140f, 100f)
        s.equals(162f, 112f)
        s.glyph("+", 120f, 106f, 12f, 12f) // written last
        val reader = RunReader { run, _ -> listOf(run.mapNotNull { s.labels[it] }.joinToString("")) }
        assertEquals("5", text(MathPage.analyze(s.strokes, reader).single()))
    }

    @Test
    fun normalize() {
        assertEquals("10", MathText.normalize("1O"))
        assertEquals("2*3", MathText.normalize("2x3"))
        assertEquals("ln2", MathText.normalize("In2"))
        assertEquals("2x", MathText.normalize("2×"))
        assertEquals("11", MathText.normalize("l1"))
    }

    /** A small circle (a degree sign) with its top-left corner at ([x], [top]). */
    private fun Sheet.ring(x: Float, top: Float, d: Float = 6f) {
        val ts = (0..16).map { it / 16f * 2 * Math.PI }
        add("o", ts.map { x + d / 2 + d / 2 * Math.sin(it).toFloat() }, ts.map { top + d / 2 - d / 2 * Math.cos(it).toFloat() })
    }

    @Test
    fun degreeSigns() {
        val s = Sheet()
        s.glyph("3", 100f, 100f)
        s.glyph("0", 118f, 100f)
        s.ring(134f, 99f)
        s.glyph("+", 146f, 106f, 12f, 12f)
        s.glyph("6", 164f, 100f)
        s.glyph("0", 182f, 100f)
        s.ring(198f, 99f)
        s.equals(212f, 112f)
        assertEquals("90°", text(s.answers().single()))
    }

    @Test
    fun sineOfDegrees() {
        val s = Sheet()
        s.glyph("sin", 100f, 104f, 30f, 20f)
        s.glyph("3", 140f, 100f)
        s.glyph("0", 158f, 100f)
        s.ring(174f, 99f)
        s.equals(190f, 112f)
        assertEquals("0,5", text(s.answers().single()))
    }

    @Test
    fun exponentRaisedOnlyALittle() {
        val s = Sheet()
        s.glyph("3", 100f, 100f)
        s.glyph("2", 116f, 94f, 9f, 13f)
        s.equals(134f, 112f)
        assertEquals("9", text(s.answers().single()))
    }

    @Test
    fun decimalCommaIsNoIndex() {
        val s = Sheet()
        s.glyph("2", 100f, 100f)
        s.add(",", listOf(117f, 116f), listOf(122f, 128f))
        s.glyph("5", 120f, 100f)
        s.glyph("+", 140f, 106f, 12f, 12f)
        s.glyph("1", 160f, 100f, 8f)
        s.equals(176f, 112f)
        assertEquals("3,5", text(s.answers().single()))
    }

    @Test
    fun misreadBracketsAndNames() {
        assertTrue("(2+3)*4" in MathPage.bracketFixes("C2+3)*4"))
        assertTrue("(2+3*4)" in MathPage.bracketFixes("(2+3*4"))
        assertEquals("sin30", MathText.normalize("5in30"))
        assertEquals("cos60", MathText.normalize("c0s60"))
        assertEquals("25in", MathText.normalize("25in"))
    }

    @Test
    fun surfaceWhenZIsReadAsTwo() {
        val s = Sheet()
        // z = x² + y², the reader taking the z for a 2 first.
        s.glyph("z", 100f, 100f)
        s.equals(122f, 112f)
        s.glyph("x", 150f, 106f, 14f, 16f)
        s.glyph("2", 166f, 94f, 9f, 12f)
        s.glyph("+", 182f, 106f, 12f, 12f)
        s.glyph("y", 200f, 106f, 14f, 16f)
        s.glyph("2", 216f, 94f, 9f, 12f)
        val answers = s.answers(mapOf("z" to listOf("2", "z")))
        assertTrue(answers.any { it.answer is Answer.Surface })
    }

    @Test
    fun unitsBeatDigitsForLetters() {
        // 9,8 м/с² · 3 с with units on: the reader's first guess has digits for the letters.
        val s = Sheet()
        s.glyph("9,8m/c^2*3c", 100f, 100f, 160f, 24f)
        s.equals(280f, 112f)
        Parser.units = true
        try {
            val answers = s.answers(mapOf("9,8m/c^2*3c" to listOf("9,84/2^2*32", "9,8m/c^2*3c")))
            val text = (answers.single().answer as Answer.Text).lines.single()
            assertEquals("29,4 м/с", text)
        } finally {
            Parser.units = false
        }
    }
}
