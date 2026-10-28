package dev.inknotes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Straightens a highlighter pass over text: the wobbly stroke becomes a level band of the
 * same thickness, centred on the letters under it and ending at the first and last letter.
 * The letters are found in pixels (a small picture of
 * that strip of the page), so it works the same for handwriting, a photo and a PDF page.
 */
object HighlightAligner {

    /** A level highlight from [x0] to [x1] around [y], [thickness] tall (page units). */
    class Band(val x0: Float, val x1: Float, val y: Float, val thickness: Float)

    /**
     * The band for a highlighter stroke through points [xs], [ys] drawn [size] thick, or
     * null when it is not a pass along a line (a circle, a tick, a vertical mark).
     * [draw] paints what lies under it (no paper ruling, no other highlighter) on a canvas
     * in page coordinates; only what crosses the given strip is needed.
     */
    fun align(xs: FloatArray, ys: FloatArray, size: Float, draw: (Canvas, RectF) -> Unit): Band? {
        if (xs.size < 2) return null
        val minX = xs.min()
        val maxX = xs.max()
        val dx = maxX - minX
        if (dx < max(1.2f * size, MIN_LENGTH)) return null
        // Least squares line: a pass is nearly level and stays close to it.
        val mx = xs.average().toFloat()
        val my = ys.average().toFloat()
        var sxy = 0f
        var sxx = 0f
        for (i in xs.indices) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        val slope = if (sxx > 0f) sxy / sxx else 0f
        if (abs(slope) > MAX_SLOPE) return null
        val off = FloatArray(xs.size) { abs(ys[it] - (my + slope * (xs[it] - mx))) }.sorted()
        if (off[(off.size * 9) / 10] > max(0.75f * size, 12f)) return null
        val y0 = ys.sorted()[ys.size / 2]
        return textBand(minX, maxX, y0, size, draw) ?: Band(minX, maxX, y0, size)
    }

    private fun textBand(x0: Float, x1: Float, y0: Float, size: Float, draw: (Canvas, RectF) -> Unit): Band? {
        val half = max(2f * size, 40f)
        val left = x0 - 0.6f * size
        val top = y0 - half
        val wUnits = x1 - x0 + 1.2f * size
        val k = min(WINDOW_ROWS / (2 * half), MAX_COLS / wUnits)
        val cols = (wUnits * k).roundToInt().coerceAtLeast(1)
        val rows = (2 * half * k).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        val px = IntArray(cols * rows)
        try {
            bmp.eraseColor(Color.WHITE)
            val canvas = Canvas(bmp)
            canvas.scale(k, k)
            canvas.translate(-left, -top)
            draw(canvas, RectF(left, top, left + wUnits, top + 2 * half))
            bmp.getPixels(px, 0, cols, 0, 0, cols, rows)
        } finally {
            bmp.recycle()
        }
        val lum = IntArray(px.size) { (Color.red(px[it]) * 299 + Color.green(px[it]) * 587 + Color.blue(px[it]) * 114) / 1000 }
        // Ink is what stands out from the usual brightness of the strip (paper or photo).
        val bg = lum.copyOf().also { it.sort() }[lum.size / 2]
        val ink = BooleanArray(lum.size) { if (bg >= 100) lum[it] < bg - CONTRAST else abs(lum[it] - bg) > CONTRAST }
        val ruleCols = removeRules(ink, cols, rows)

        // Share of ink in each row, along the length of the pass only.
        val c0 = ((x0 - left) * k).roundToInt().coerceIn(0, cols - 1)
        val c1 = ((x1 - left) * k).roundToInt().coerceIn(c0, cols - 1)
        val density = FloatArray(rows) { r ->
            var n = 0
            for (c in c0..c1) if (ink[r * cols + c]) n++
            n.toFloat() / (c1 - c0 + 1)
        }
        val smooth = FloatArray(rows) { r -> (density[max(r - 1, 0)] + density[r] + density[min(r + 1, rows - 1)]) / 3 }
        val centre = ((y0 - top) * k).roundToInt().coerceIn(0, rows - 1)
        val reach = (1.5f * size * k).roundToInt()
        var peak = 0f
        for (r in max(0, centre - reach)..min(rows - 1, centre + reach)) peak = max(peak, smooth[r])
        if (peak < MIN_INK) return null
        val threshold = max(MIN_INK, ROW_SHARE * peak)

        // Rows of one line of text: a run over the threshold, small gaps allowed.
        val gap = max(1, (0.06f * size * k).roundToInt())
        val runs = ArrayList<IntArray>()
        var start = -1
        var lastOn = -10_000
        for (r in 0 until rows) {
            if (smooth[r] >= threshold) {
                if (start < 0 || r - lastOn > gap + 1) {
                    if (start >= 0) runs += intArrayOf(start, lastOn)
                    start = r
                }
                lastOn = r
            }
        }
        if (start >= 0) runs += intArrayOf(start, lastOn)
        val run = runs.minByOrNull { if (centre in it[0]..it[1]) 0 else min(abs(centre - it[0]), abs(centre - it[1])) } ?: return null
        val distance = if (centre in run[0]..run[1]) 0 else min(abs(centre - run[0]), abs(centre - run[1]))
        if (distance > 1.2f * size * k) return null

        val textTop = top + run[0] / k
        val textBottom = top + (run[1] + 1) / k
        // The marker keeps the thickness it was given, centred on the line of letters.
        val thickness = size
        val y = (textTop + textBottom) / 2

        // Ends: to the first and last letter near where the pass began and ended.
        val inkCols = BooleanArray(cols) { c -> (run[0]..run[1]).any { r -> ink[r * cols + c] } }
        val snap = 0.6f * max(thickness, textBottom - textTop)
        var a = x0
        var b = x1
        // Never across a line of a table: the text of the next cell is not this one.
        val startCol = ((x0 - left) * k).roundToInt().coerceIn(0, cols - 1)
        val endCol = ((x1 - left) * k).roundToInt().coerceIn(0, cols - 1)
        var minCol = max(0, ((x0 - snap - left) * k).roundToInt())
        for (c in startCol downTo minCol) if (ruleCols[c]) { minCol = c + 1; break }
        var maxCol = min(cols - 1, ((x1 + snap - left) * k).roundToInt())
        for (c in endCol..maxCol) if (ruleCols[c]) { maxCol = c - 1; break }
        val firstCol = (minCol..min(cols - 1, ((x0 + snap - left) * k).roundToInt())).firstOrNull { inkCols[it] }
        if (firstCol != null) a = left + firstCol / k - 0.12f * thickness
        val lastCol = (maxCol downTo max(0, ((x1 - snap - left) * k).roundToInt())).firstOrNull { inkCols[it] }
        if (lastCol != null) b = left + (lastCol + 1) / k + 0.12f * thickness
        if (b - a < 0.5f * (x1 - x0)) {
            a = x0
            b = x1
        }
        return Band(a, b, y, thickness)
    }

    /**
     * Lines of a table, frames and underlines are not letters: rows with a long unbroken run
     * of ink and columns inked almost top to bottom are cleared.
     */
    private fun removeRules(ink: BooleanArray, cols: Int, rows: Int): BooleanArray {
        val ruleCols = BooleanArray(cols)
        val longRun = max(RULE_MIN_PX, (RULE_SHARE * cols).roundToInt())
        for (r in 0 until rows) {
            var run = 0
            var gap = 0
            var best = 0
            for (c in 0 until cols) {
                if (ink[r * cols + c]) {
                    run += 1 + gap
                    gap = 0
                    best = max(best, run)
                } else if (run > 0 && gap < 1) {
                    gap++
                } else {
                    run = 0
                    gap = 0
                }
            }
            if (best >= longRun) {
                // The rule and the antialiased rows right next to it.
                for (rr in max(0, r - 1)..min(rows - 1, r + 1)) for (c in 0 until cols) ink[rr * cols + c] = false
            }
        }
        for (c in 0 until cols) {
            var n = 0
            for (r in 0 until rows) if (ink[r * cols + c]) n++
            if (n >= RULE_COLUMN * rows) {
                for (cc in max(0, c - 1)..min(cols - 1, c + 1)) {
                    ruleCols[cc] = true
                    for (r in 0 until rows) ink[r * cols + cc] = false
                }
            }
        }
        return ruleCols
    }

    private const val RULE_SHARE = 0.45f
    private const val RULE_MIN_PX = 40
    private const val RULE_COLUMN = 0.8f
    private const val MIN_LENGTH = 15f
    private const val MAX_SLOPE = 0.3f
    private const val WINDOW_ROWS = 220f
    private const val MAX_COLS = 2400f
    private const val CONTRAST = 55
    private const val MIN_INK = 0.012f
    private const val ROW_SHARE = 0.18f
}
