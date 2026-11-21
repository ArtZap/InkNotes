package dev.inknotes.math

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * The reading checked on pages of worked solutions, which carry their own answers: in
 * 68·51 = 3468 the written result says what the left side is. Every "=" whose two sides read
 * as numbers is a check: they agree (within the written result's rounding), or something was
 * misread (or the writer slipped, which is rare). Also lists what the page would show.
 */
object NotesJudge {

    class Result(val equalities: Int, val numeric: Int, val agree: Int, val lines: List<String>, val answers: List<String>) {
        override fun toString() = "равенств %d, оба числа %d, сходятся %d (%.1f%%), показано ответов %d".format(
            equalities, numeric, agree, if (numeric == 0) 0.0 else 100.0 * agree / numeric, answers.size,
        )
    }

    fun measure(ink: List<Ink>, reader: RunReader): Result {
        val eqs = runCatching { MathPage.equalities(ink, reader) }.getOrDefault(emptyList()).sortedWith(compareBy({ it.y }, { it.x }))
        val lines = ArrayList<String>()
        var numeric = 0
        var agree = 0
        for (e in eqs) {
            val l = e.left
            val r = e.right
            val mark = when {
                l == null || r == null -> "  "
                same(l, r, e.rightText) -> "ok".also { numeric++; agree++ }
                else -> "≠ ".also { numeric++ }
            }
            lines += "%s  (%4.0f, %4.0f)  %s = %s".format(mark, e.x, e.y, e.leftText, e.rightText)
        }
        val answers = runCatching { MathPage.analyze(ink, reader) }.getOrDefault(emptyList()).filter { !it.missing }
            .sortedWith(compareBy({ it.y }, { it.x }))
            .map { a ->
                val text = when (val v = a.answer) {
                    is Answer.Text -> v.lines.joinToString(" | ")
                    is Answer.Graph -> "graph"
                    is Answer.Surface -> "surface"
                    is Answer.Grid -> "grid"
                }
                "(%4.0f, %4.0f) %s%s".format(a.x, a.y, text, if (a.unsure) " ?" else "")
            }
        return Result(eqs.size, numeric, agree, lines, answers)
    }

    /** [left] is the written [right] rounded as written (2446,59: to a hundredth), or within half a percent. */
    fun same(left: Double, right: Double, rightText: String): Boolean {
        val decimals = Regex("[.,](\\d+)").find(rightText)?.groupValues?.get(1)?.length ?: 0
        val step = 10.0.pow(-decimals)
        return abs(left - right) <= max(0.51 * step, 0.005 * abs(right)) + 1e-9
    }
}
