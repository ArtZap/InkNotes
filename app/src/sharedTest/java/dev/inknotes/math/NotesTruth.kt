package dev.inknotes.math

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The reading of a page checked against what is really written on it, transcribed by hand
 * (kept with the private pages, never in this repository): for every "=" its position and both
 * sides, `n:` a number (units dropped) or `t:` letters as written. Counts the "=" found and the
 * number sides read to the right value.
 */
object NotesTruth {
    sealed class Side {
        class Num(val v: Double) : Side()
        class Text(val s: String) : Side()

        override fun toString() = when (this) {
            is Num -> "%s".format(v)
            is Text -> s
        }
    }

    class Row(val x: Float, val y: Float, val left: Side, val right: Side)

    class Score(
        val rows: Int,
        val found: Int,
        val sides: Int,
        val right: Int,
        val lines: List<String>,
        // left sides that are numbers: what an answer after "expr =" would be
        val leftSides: Int = 0,
        val leftRight: Int = 0,
    ) {
        override fun toString() = "по расшифровке: «=» найдено %d из %d, числа прочитаны верно %d из %d (%.0f%%)".format(
            found, rows, right, sides, if (sides == 0) 0.0 else 100.0 * right / sides,
        )
    }

    fun parse(lines: Sequence<String>): List<Row> = lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
        val c = line.split('\t')
        fun side(s: String) = if (s.startsWith("n:")) Side.Num(s.drop(2).toDouble()) else Side.Text(s.removePrefix("t:"))
        Row(c[0].toFloat(), c[1].toFloat(), side(c[2]), side(c[3]))
    }.toList()

    /** A number read as [read] where [truth] is written: equal up to a thousandth of it. */
    /** The equality of [eqs] at the "=" of [r], if found. */
    fun match(eqs: List<MathPage.Equality>, r: Row): MathPage.Equality? =
        eqs.filter { abs(it.x - r.x) < 16f && abs(it.y - r.y) < 12f }.minByOrNull { hypot(it.x - r.x, it.y - r.y) }

    fun same(read: Double?, truth: Double) = read != null && abs(read - truth) <= max(1e-3 * abs(truth), 1e-9)

    fun score(eqs: List<MathPage.Equality>, rows: List<Row>): Score {
        var found = 0
        var sides = 0
        var right = 0
        var leftSides = 0
        var leftRight = 0
        val lines = ArrayList<String>()
        for (r in rows) {
            val e = eqs.filter { abs(it.x - r.x) < 16f && abs(it.y - r.y) < 12f }.minByOrNull { hypot(it.x - r.x, it.y - r.y) }
            if (e != null) found++
            val marks = listOf(r.left to e?.left, r.right to e?.right).map { (t, v) ->
                if (t !is Side.Num) " " else {
                    sides++
                    if (same(v, t.v)) "✓".also { right++ } else "✗"
                }
            }
            (r.left as? Side.Num)?.let { t ->
                leftSides++
                if (same(e?.left, t.v)) leftRight++
            }
            val read = if (e == null) "— не найдено" else "${e.leftText} = ${e.rightText}"
            lines += "%s%s (%4.0f, %4.0f)  %s = %s   ← %s".format(marks[0], marks[1], r.x, r.y, r.left, r.right, read)
        }
        return Score(rows.size, found, sides, right, lines, leftSides, leftRight)
    }
}
