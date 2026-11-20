package dev.inknotes.math

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision between an index, a plain symbol and an exponent: a small symbol after a letter
 * moved step by step from well below to well above. The layout must change its mind in order
 * (index → beside → exponent, each at most once), and where it is unsure ([MathLayout.Shift])
 * must be next to where it changes, not elsewhere.
 */
class BoundaryTest {

    /** A zigzag glyph in a box, as the page tests draw them. */
    private fun glyph(x: Float, top: Float, w: Float, h: Float, order: Int) =
        Ink(floatArrayOf(x, x + w, x, x + w), floatArrayOf(top, top + h / 3, top + 2 * h / 3, top + h), order)

    private enum class Kind(val mark: String) { SUB("_"), BESIDE("="), SUP("^"), OTHER("?") }

    private fun kind(pieces: List<Piece>): Kind = when (pieces.joinToString("") { if (it is Piece.Text) it.s else "#" }) {
        "#_{#}" -> Kind.SUB
        "#" -> Kind.BESIDE
        "#^{#}" -> Kind.SUP
        else -> Kind.OTHER
    }

    /** Kinds along the way, and where the moved layouts disagree, for one size of the small symbol. */
    private fun walk(size: Float, gap: Float): Pair<List<Kind>, List<Boolean>> {
        val h = 24f
        val axis = 112f
        val kinds = ArrayList<Kind>()
        val unsure = ArrayList<Boolean>()
        // From its top at the base's foot to its bottom at the base's top, in 1/24 of the height.
        var top = 124f + 0.1f * h
        while (top + size > 100f - 0.1f * h) {
            val strokes = listOf(glyph(100f, 100f, 14f, h, 0), glyph(114f + gap, top, 0.6f * size, size, 1))
            val k = runCatching { kind(MathLayout.formula(strokes, h, axis)) }.getOrDefault(Kind.OTHER)
            kinds += k
            unsure += MathLayout.Shift.CHECKS.any { runCatching { kind(MathLayout.formula(strokes, h, axis, it)) }.getOrDefault(Kind.OTHER) != k }
            top -= h / 24
        }
        return kinds to unsure
    }

    @Test
    fun indexBesideExponentInOrder() {
        val problems = ArrayList<String>()
        for (size in listOf(9f, 12f, 15f)) for (gap in listOf(1f, 4f)) {
            val (kinds, unsure) = walk(size, gap)
            val path = kinds.filter { it != Kind.OTHER }
            val order = listOf(Kind.SUB, Kind.BESIDE, Kind.SUP)
            // Never back: an exponent lower down than a plain symbol, a plain symbol below an index.
            val back = path.zipWithNext().any { (a, b) -> order.indexOf(b) < order.indexOf(a) }
            val changes = path.zipWithNext().count { (a, b) -> a != b }
            if (back || changes > 2) problems += "size $size gap $gap: ${kinds.joinToString("") { it.mark }}"
            // Unsure only near a change (within two steps).
            val changesAt = kinds.indices.filter { it > 0 && kinds[it] != kinds[it - 1] }
            val far = unsure.indices.filter { unsure[it] && changesAt.none { c -> kotlin.math.abs(c - it) <= 2 } }
            if (far.isNotEmpty()) problems += "size $size gap $gap: unsure away from a change at $far: ${kinds.joinToString("") { it.mark }}"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun extremesAreClear() {
        // Well below the foot: an index; well above the top: an exponent; neither unsure.
        for (size in listOf(9f, 12f)) {
            val (kinds, unsure) = walk(size, 2f)
            assertTrue(kinds.joinToString(), kinds.first() == Kind.SUB && kinds.last() == Kind.SUP)
            assertTrue(unsure.toString(), !unsure.first() && !unsure.last())
        }
    }
}
