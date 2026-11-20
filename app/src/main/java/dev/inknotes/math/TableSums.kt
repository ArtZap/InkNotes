package dev.inknotes.math

/**
 * Sums and averages of a handwritten table: what is written in each cell is read as a number
 * (cells with words, empty cells and unreadable ones are left out), then totalled by row and by
 * column. A row or column with fewer than two numbers gets nothing: a header has none, and one
 * number is its own sum.
 */
object TableSums {

    /** Totals of each row and column ([rows] top to bottom, [cols] left to right); null where there is none. */
    class Result(val rows: List<Total?>, val cols: List<Total?>, val numbers: Int)

    class Total(val sum: Double, val count: Int) {
        val mean get() = sum / count
    }

    /**
     * Reads the cells between the row lines [rowLines] and column lines [colLines] (page
     * coordinates, sorted) from [ink], each stroke in the cell its middle is in.
     */
    fun compute(rowLines: List<Float>, colLines: List<Float>, ink: List<Ink>, reader: RunReader): Result {
        val nr = rowLines.size - 1
        val nc = colLines.size - 1
        val cells = HashMap<Pair<Int, Int>, MutableList<Ink>>()
        for (s in ink) {
            val r = (0 until nr).firstOrNull { s.cy in rowLines[it]..rowLines[it + 1] } ?: continue
            val c = (0 until nc).firstOrNull { s.cx in colLines[it]..colLines[it + 1] } ?: continue
            cells.getOrPut(r to c) { ArrayList() } += s
        }
        val values = HashMap<Pair<Int, Int>, Double>()
        for ((rc, strokes) in cells) number(strokes, reader)?.let { values[rc] = it }
        fun total(of: List<Double>) = if (of.size < MIN_NUMBERS) null else Total(of.sum(), of.size)
        return Result(
            rows = (0 until nr).map { r -> total((0 until nc).mapNotNull { c -> values[r to c] }) },
            cols = (0 until nc).map { c -> total((0 until nr).mapNotNull { r -> values[r to c] }) },
            numbers = values.size,
        )
    }

    /** What is written in a cell as a number (12, −3, 2,5, 1/4), or null when it is something else. */
    fun number(strokes: List<Ink>, reader: RunReader): Double? {
        if (strokes.isEmpty() || strokes.size > MAX_STROKES) return null
        val heights = strokes.filter { !it.isDash }.map { it.height }.sorted()
        val h = maxOf(heights.getOrNull(heights.size * 3 / 4) ?: strokes.maxOf { it.height }, 4f)
        val mids = strokes.filter { it.height in 0.4f * h..1.6f * h }.map { it.cy }
        val axis = MathLayout.median(mids) ?: Box.of(strokes).cy
        // As read first, not with letters made digits: a "b" or an "s" in a header stays a letter.
        val e = runCatching { MathPage.read(strokes.sortedBy { it.order }, h, axis, reader, MathPage.Prefer.FIRST) }.getOrNull() ?: return null
        if (Algebra.freeVars(e).isNotEmpty()) return null
        val v = (runCatching { Numeric.eval(e) }.getOrNull() as? Scalar)?.v ?: return null
        return v.takeIf { it.isFinite() }
    }

    private const val MIN_NUMBERS = 2

    /** More strokes than a number has: a drawing or a sentence. */
    private const val MAX_STROKES = 40
}
