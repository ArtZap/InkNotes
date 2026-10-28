package dev.inknotes.editor

import android.graphics.RectF
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageStroke
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Tables are plain lines on the page. A selection is a table when its level and upright
 * straight lines form a closed grid (a table from the menu, or one drawn by hand, also cell by
 * cell); whatever is written in the cells is the table's content and may be anything. Rows go
 * on below and columns on the right, as tall or wide as the last ones; the last row or column
 * can be taken away again, with what is written in it.
 */
object Tables {

    /** A straight level or upright selected stroke: at [at] across, from [from] to [to] along. */
    private class Piece(val stroke: PageStroke, val level: Boolean, val at: Float, val from: Float, val to: Float)

    /** Pieces in one row (or column) line, and the stretches of it they cover. */
    private class Line(val level: Boolean, val at: Float, val pieces: List<Piece>) {
        val spans: List<Pair<Float, Float>> = merge(pieces.map { it.from to it.to })
        val from get() = spans.first().first
        val to get() = spans.last().second

        /** Covers [lo]..[hi] without gaps wider than [tol]. */
        fun covers(lo: Float, hi: Float, tol: Float): Boolean {
            var reach = lo
            for ((a, b) in spans) {
                if (b < reach) continue
                if (a > reach + tol) return false
                reach = b
                if (reach >= hi - tol) return true
            }
            return reach >= hi - tol
        }

        /** Some piece of it passes by [x] (within [tol]). */
        fun reaches(x: Float, tol: Float) = spans.any { (a, b) -> x >= a - tol && x <= b + tol }

        private fun merge(spans: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            val out = ArrayList<Pair<Float, Float>>()
            for ((a, b) in spans.sortedBy { it.first }) {
                val last = out.lastOrNull()
                if (last != null && a <= last.second) out[out.size - 1] = last.first to max(last.second, b) else out += a to b
            }
            return out
        }
    }

    /** The grid of a selection: where its row lines ([rows]) and column lines ([cols]) are. */
    class Grid internal constructor(
        val rows: List<Float>,
        val cols: List<Float>,
        internal val tol: Float,
        internal val lines: List<Any>,
        internal val content: List<Any>,
    ) {
        val top get() = rows.first()
        val bottom get() = rows.last()
        val left get() = cols.first()
        val right get() = cols.last()
    }

    /** The grid of [items], or null when they are not a table (with what is written in it). */
    fun grid(items: Collection<Any>): Grid? {
        if (items.size < 4 || items.size > MAX_ITEMS) return null
        val pieces = ArrayList<Piece>()
        val other = ArrayList<Any>()
        for (x in items) {
            when (x) {
                is PageFill -> other += x
                is PageStroke -> piece(x)?.let(pieces::add) ?: other.add(x)
                else -> return null
            }
        }
        val hs = pieces.filter { it.level }
        val vs = pieces.filter { !it.level }
        if (hs.size < 2 || vs.size < 2) return null
        // The frame comes from the long lines; short straight marks are content until they prove otherwise.
        val longH = hs.maxOf { it.to - it.from }
        val longV = vs.maxOf { it.to - it.from }
        val frameH = hs.filter { it.to - it.from >= longH * 0.5f }
        val frameV = vs.filter { it.to - it.from >= longV * 0.5f }
        val top = frameH.minOf { it.at }
        val bottom = frameH.maxOf { it.at }
        val left = frameV.minOf { it.at }
        val right = frameV.maxOf { it.at }
        if (bottom - top < MIN_LENGTH || right - left < MIN_LENGTH) return null
        val tol = max(TOLERANCE, 0.04f * max(right - left, bottom - top))

        val rowLines = lines(hs, true, tol)
        val colLines = lines(vs, false, tol)
        // A line belongs to the table when both its ends meet lines across it (or the frame);
        // a dash or a "1" written in a cell does not.
        // Ends must come close: a "1" written in a cell nearly reaches both borders.
        val touch = min(tol, MEET)
        fun meets(line: Line, across: List<Line>): Boolean {
            if (line.at < (if (line.level) top else left) - tol || line.at > (if (line.level) bottom else right) + tol) return false
            fun end(x: Float) = across.any { abs(it.at - x) <= touch && it.reaches(line.at, tol) }
            return end(line.from) && end(line.to)
        }
        var rowsIn = rowLines.filter { meets(it, colLines) }
        val colsIn = colLines.filter { meets(it, rowsIn) }
        rowsIn = rowsIn.filter { meets(it, colsIn) }
        if (rowsIn.size < 2 || colsIn.size < 2) return null
        val rowTop = rowsIn.first()
        val rowBottom = rowsIn.last()
        val colLeft = colsIn.first()
        val colRight = colsIn.last()
        // A closed frame.
        if (!rowTop.covers(colLeft.at, colRight.at, tol) || !rowBottom.covers(colLeft.at, colRight.at, tol)) return null
        if (!colLeft.covers(rowTop.at, rowBottom.at, tol) || !colRight.covers(rowTop.at, rowBottom.at, tol)) return null

        val kept: MutableSet<PageStroke> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        for (l in rowsIn + colsIn) for (p in l.pieces) kept += p.stroke
        val content = other + pieces.filter { it.stroke !in kept }.map { it.stroke }
        // What is written must be in the table: a selection reaching outside it is something else.
        val frame = RectF(colLeft.at, rowTop.at, colRight.at, rowBottom.at).apply { inset(-tol * 2, -tol * 2) }
        for (x in content) {
            val b = bounds(x) ?: continue
            if (!frame.contains(b)) return null
        }
        return Grid(rowsIn.map { it.at }, colsIn.map { it.at }, tol, rowsIn + colsIn, content)
    }

    /** New lines for a row under the table: the column lines carried down and a new bottom line. */
    fun addRow(grid: Grid): List<PageStroke> = add(grid, row = true)

    /** New lines for a column on the right: the row lines carried on and a new right line. */
    fun addColumn(grid: Grid): List<PageStroke> = add(grid, row = false)

    private fun add(grid: Grid, row: Boolean): List<PageStroke> {
        val ats = if (row) grid.rows else grid.cols
        val edge = ats.last()
        val next = edge + (edge - ats[ats.size - 2])
        val lines = grid.lines.filterIsInstance<Line>()
        val out = ArrayList<PageStroke>()
        // Lines across the edge that come up to it go on (not those that stop at merged cells).
        for (l in lines) {
            if (l.level == row) continue
            if (l.to < edge - grid.tol) continue
            val like = l.pieces.maxBy { it.to }.stroke
            out += if (row) line(like, l.at, edge, l.at, next) else line(like, edge, l.at, next, l.at)
        }
        val base = lines.filter { it.level == row && abs(it.at - edge) <= grid.tol }.flatMap { it.pieces }.maxByOrNull { it.to - it.from }
            ?: return out
        out += if (row) line(base.stroke, grid.left, next, grid.right, next) else line(base.stroke, next, grid.top, next, grid.bottom)
        return out
    }

    /**
     * The table without its last row ([row]) or column: lines past the line before it are cut
     * back to it, those wholly past it go, and so does what is written there. Null when only one
     * row (column) is left, or the line before it does not run all across (merged cells).
     * Returns the elements to drop and the lines to put in their place.
     */
    fun removeLast(grid: Grid, row: Boolean): Pair<List<Any>, List<PageStroke>>? {
        val ats = if (row) grid.rows else grid.cols
        if (ats.size < 3) return null
        val edge = ats[ats.size - 2]
        val lines = grid.lines.filterIsInstance<Line>()
        val newEdge = lines.firstOrNull { it.level == row && abs(it.at - edge) <= grid.tol } ?: return null
        val (lo, hi) = if (row) grid.left to grid.right else grid.top to grid.bottom
        if (!newEdge.covers(lo, hi, grid.tol)) return null
        val drop = ArrayList<Any>()
        val add = ArrayList<PageStroke>()
        for (l in lines) for (p in l.pieces) {
            if (p.level == row) {
                // Lines along: the last one and any past the new edge go.
                if (p.at > edge + grid.tol) drop += p.stroke
                continue
            }
            when {
                p.from >= edge - grid.tol -> drop += p.stroke
                p.to > edge + grid.tol -> {
                    drop += p.stroke
                    cut(p.stroke, row, edge)?.let(add::add)
                }
            }
        }
        for (x in grid.content) {
            val b = bounds(x) ?: continue
            if ((if (row) b.centerY() else b.centerX()) > edge) drop += x
        }
        return drop to add
    }

    // ------------------------------------------------------------------ pieces

    private fun piece(s: PageStroke): Piece? {
        val inputs = s.stroke.inputs
        if (inputs.size < 2) return null
        val p = StrokeInput()
        inputs.populate(0, p)
        val x0 = p.x
        val y0 = p.y
        inputs.populate(inputs.size - 1, p)
        val x1 = p.x
        val y1 = p.y
        val len = hypot(x1 - x0, y1 - y0)
        if (len < MIN_LENGTH) return null
        val level = abs(y1 - y0) <= abs(x1 - x0)
        // Straight and close to level or upright.
        if (min(abs(x1 - x0), abs(y1 - y0)) > len * SLANT) return null
        var off = 0f
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until inputs.size) {
            inputs.populate(i, p)
            off = max(off, PageEdits.distanceToSegment(p.x, p.y, x0, y0, x1, y1))
            val along = if (level) p.x else p.y
            lo = min(lo, along)
            hi = max(hi, along)
        }
        if (off > max(TOLERANCE, len * 0.04f)) return null
        val at = if (level) (y0 + y1) / 2 else (x0 + x1) / 2
        return Piece(s, level, at, lo, hi)
    }

    /** Pieces at about the same place across joined into lines, in order. */
    private fun lines(pieces: List<Piece>, level: Boolean, tol: Float): List<Line> {
        val out = ArrayList<Line>()
        var group = ArrayList<Piece>()
        for (p in pieces.sortedBy { it.at }) {
            if (group.isNotEmpty() && p.at - group.last().at > tol) {
                out += Line(level, group.map { it.at }.average().toFloat(), group)
                group = ArrayList()
            }
            group += p
        }
        if (group.isNotEmpty()) out += Line(level, group.map { it.at }.average().toFloat(), group)
        return out
    }

    private fun bounds(x: Any): RectF? = when (x) {
        is PageStroke -> PageEdits.bounds(x)
        is PageFill -> x.bounds
        else -> null
    }

    /** A straight line from (x0, y0) to (x1, y1) drawn like [s] (kind, colour, width). */
    private fun line(s: PageStroke, x0: Float, y0: Float, x1: Float, y1: Float): PageStroke {
        val batch = MutableStrokeInputBatch()
        val n = (hypot(x1 - x0, y1 - y0) / STEP).toInt().coerceAtLeast(1)
        for (k in 0..n) batch.add(InputToolType.STYLUS, x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n, k * 4L, pressure = 0.6f)
        return PageStroke(s.kind, Stroke(s.stroke.brush, batch))
    }

    /** [s] cut off at [edge] (a level position when [row], else an upright one), or null if nothing is left. */
    private fun cut(s: PageStroke, row: Boolean, edge: Float): PageStroke? {
        val inputs = s.stroke.inputs
        val batch = MutableStrokeInputBatch()
        val p = StrokeInput()
        var prevX = Float.NaN
        var prevY = Float.NaN
        var t = 0L
        var count = 0
        for (i in 0 until inputs.size) {
            inputs.populate(i, p)
            val v = if (row) p.y else p.x
            val pv = if (row) prevY else prevX
            // Where the line crosses the edge, either way.
            if (!pv.isNaN() && (pv <= edge) != (v <= edge) && v != pv) {
                val k = (edge - pv) / (v - pv)
                batch.add(InputToolType.STYLUS, prevX + (p.x - prevX) * k, prevY + (p.y - prevY) * k, t, pressure = 0.6f)
                t += 4
                count++
            }
            if (v <= edge) {
                batch.add(InputToolType.STYLUS, p.x, p.y, t, pressure = if (p.hasPressure) p.pressure else 0.6f)
                t += 4
                count++
            }
            prevX = p.x
            prevY = p.y
        }
        if (count < 2) return null
        return PageStroke(s.kind, Stroke(s.stroke.brush, batch))
    }

    private const val TOLERANCE = 6f

    /** How close the end of a table line comes to the line it meets (about 2 mm). */
    private const val MEET = 8f
    private const val MIN_LENGTH = 20f
    private const val SLANT = 0.08f
    private const val STEP = 3f
    private const val MAX_ITEMS = 2000
}
