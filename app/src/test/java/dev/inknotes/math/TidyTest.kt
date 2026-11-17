package dev.inknotes.math

import dev.inknotes.editor.Tidy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class TidyTest {
    /** A letter: a small loop of height [h] standing on [base] at [x]; [tail] hangs below. */
    private fun letter(x: Float, base: Float, h: Float = 20f, w: Float = 14f, tail: Boolean = false): Tidy.Mark {
        val n = 16
        val xs = FloatArray(n) { x + w / 2 + w / 2 * kotlin.math.cos(it * 2 * Math.PI / (n - 1)).toFloat() }
        val ys = FloatArray(n) { base - h / 2 + h / 2 * kotlin.math.sin(it * 2 * Math.PI / (n - 1)).toFloat() }
        if (tail) ys[n / 4] = base + h * 0.8f
        return Tidy.Mark(xs, ys)
    }

    /** A line of words of [letters] letters each; [slope] per unit of x, [jump] for word k's baseline. */
    private fun line(y: Float, words: List<Int>, slope: Float = 0f, jump: (Int) -> Float = { 0f }, gap: (Int) -> Float = { 30f }): List<Tidy.Mark> {
        val out = ArrayList<Tidy.Mark>()
        var x = 100f
        for ((k, n) in words.withIndex()) {
            repeat(n) {
                out += letter(x, y + slope * x + jump(k))
                x += 16f
            }
            x += gap(k)
        }
        return out
    }

    private fun apply(ms: List<Tidy.Mark>): List<Tidy.Mark> = Tidy.moves(ms).mapIndexed { i, m ->
        val s = ms[i]
        Tidy.Mark(FloatArray(s.xs.size) { m.x(s.xs[it], s.ys[it]) }, FloatArray(s.xs.size) { m.y(s.xs[it], s.ys[it]) })
    }

    @Test
    fun aSlopingLineIsMadeLevel() {
        val ms = line(200f, listOf(4, 3, 5, 4), slope = 0.12f)
        assertTrue(Tidy.tilt(ms).toString(), abs(Tidy.tilt(ms) - 6.84f) < 0.5f)
        val out = apply(ms)
        val bottoms = out.map { it.bottom }
        assertTrue(bottoms.toString(), bottoms.max() - bottoms.min() < 2f)
        // Still left to right, in the same order.
        val lefts = out.map { it.left }
        assertEquals(lefts.sorted(), lefts)
    }

    @Test
    fun wordsJumpingOffTheLineGoBackOnIt() {
        val ms = line(200f, listOf(3, 4, 3, 4), jump = { listOf(0f, -9f, 7f, 0f)[it] })
        val out = apply(ms)
        val bottoms = out.map { it.bottom }
        assertTrue(bottoms.toString(), bottoms.max() - bottoms.min() < 1f)
        // Letters inside a word keep their places relative to one another.
        assertTrue(abs((ms[4].left - ms[3].left) - (out[4].left - out[3].left)) < 0.01f)
    }

    @Test
    fun aTailOrADotDoesNotMoveTheBaseline() {
        // «ру» with a tail on р, then a word with a dot above it.
        val ms = listOf(letter(100f, 200f, tail = true), letter(116f, 200f), letter(132f, 200f),
            letter(180f, 200f), letter(196f, 200f), Tidy.Mark(floatArrayOf(200f, 202f), floatArrayOf(170f, 171f)))
        val out = apply(ms)
        for (i in ms.indices) assertTrue("stroke $i", abs(ms[i].bottom - out[i].bottom) < 0.5f)
    }

    @Test
    fun gapsBecomeAboutEven() {
        val ms = line(200f, listOf(3, 3, 3, 3), gap = { listOf(20f, 90f, 22f, 0f)[it] })
        val out = apply(ms)
        val words = listOf(0..2, 3..5, 6..8, 9..11).map { r -> out.slice(r) }
        val gaps = words.zipWithNext { a, b -> b.minOf { it.left } - a.maxOf { it.right } }
        assertTrue(gaps.toString(), gaps[1] < 50f && gaps[1] > gaps[0])
    }

    @Test
    fun aTinyWordGrowsTowardsTheOthers() {
        val ms = line(200f, listOf(4, 4)).toMutableList()
        // The third word half the size.
        for (k in 0 until 3) ms += letter(260f + 20f + k * 9f, 200f, h = 10f, w = 7f)
        val out = apply(ms)
        val h = out.takeLast(3).maxOf { it.h }
        assertTrue(h.toString(), h > 13f && h < 20f)
    }

    @Test
    fun twoLinesStayTwoLines() {
        val ms = line(200f, listOf(3, 4), slope = 0.05f) + line(260f, listOf(5, 2), jump = { if (it == 1) 6f else 0f })
        val lines = Tidy.lines(ms)
        assertEquals(2, lines.size)
        val out = apply(ms)
        val first = out.take(7).maxOf { it.bottom }
        val second = out.drop(7).minOf { it.top }
        assertTrue("$first $second", first < second)
    }

    @Test
    fun anUnderlineStaysPut() {
        val ms = line(200f, listOf(3, 4), jump = { if (it == 1) 6f else 0f }) +
            Tidy.Mark(floatArrayOf(100f, 250f, 400f), floatArrayOf(208f, 209f, 208f))
        val m = Tidy.moves(ms).last()
        assertTrue(m.toString(), m.still)
    }

    @Test
    fun neatHandwritingStaysWhereItIs() {
        val ms = line(200f, listOf(3, 4, 3))
        for (m in Tidy.moves(ms)) assertTrue(m.toString(), m.still)
    }

    @Test
    fun realPagesDoNotFlyApart() {
        // The owner's pages: everything stays near where it was, nothing crosses into another line.
        for (name in listOf("page1.json", "page2.json", "page6.json", "page7.json", "page8.json", "page9.json")) {
            val ink = RealPage.load(name)
            val ms = ink.map { Tidy.Mark(it.xs, it.ys) }
            val h = ms.map { it.h }.sorted()[ms.size / 2]
            val out = apply(ms)
            var worst = 0f
            for (i in ms.indices) worst = max(worst, abs(out[i].cy - ms[i].cy))
            assertTrue("$name: moved ${worst / h} letter heights", worst < 2.5f * h)
        }
    }

    @Test
    fun anExponentDoesNotPullItsWordDown() {
        // x² beside other words: the x stays on the line, the 2 above it.
        val ms = line(200f, listOf(3, 4)).toMutableList()
        ms += letter(280f, 200f)
        ms += letter(296f, 188f, h = 12f, w = 9f)
        ms += line(200f, listOf(3)).map { m -> Tidy.Mark(FloatArray(m.xs.size) { m.xs[it] + 240f }, m.ys) }
        val out = apply(ms)
        val x = ms.size - 5
        assertTrue("x moved ${out[x].bottom - ms[x].bottom}", abs(out[x].bottom - ms[x].bottom) < 0.5f)
        assertTrue("${ms.map { it.bottom }} -> ${out.map { it.bottom }}", out[x + 1].bottom < out[x].bottom - 5f)
    }
}
