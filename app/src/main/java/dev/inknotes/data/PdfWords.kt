package dev.inknotes.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A word of a PDF page's text and its box, in reading order; [line] counts lines from 0. */
data class PdfWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val line: Int = 0) {
    val height get() = bottom - top
    val centerY get() = (top + bottom) / 2

    fun mapped(m: FloatArray): PdfWord {
        val (x1, y1) = PdfGeometry.map(m, left, top)
        val (x2, y2) = PdfGeometry.map(m, right, bottom)
        return copy(left = min(x1, x2), top = min(y1, y2), right = max(x1, x2), bottom = max(y1, y2))
    }
}

/** A link of a PDF page: its box (0…1 of the page) and a page of the same PDF or a web address. */
data class PdfLink(val left: Float, val top: Float, val right: Float, val bottom: Float, val page: Int = -1, val y: Float = 0f, val url: String? = null) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** An entry of a PDF's own contents: [title] goes to page [page] (from 0), [y] 0…1 down it; [depth] from 0. */
data class PdfOutlineItem(val title: String, val page: Int, val y: Float, val depth: Int)

/** Text of a PDF page: picking words, what they say, and laying a marker along them. Plain arithmetic. */
object PdfWords {
    /** [words] in reading order with their lines numbered: a word on the line of the one before shares its number. */
    fun numberLines(words: List<PdfWord>): List<PdfWord> {
        var line = 0
        var prev: PdfWord? = null
        return words.map { w ->
            val p = prev
            if (p != null && !sameLine(p, w)) line++
            prev = w
            w.copy(line = line)
        }
    }

    private fun sameLine(a: PdfWord, b: PdfWord): Boolean {
        val h = max(min(a.height, b.height), 1e-6f)
        return abs(a.centerY - b.centerY) < h * 0.5f && b.left > a.left - h * 0.5f
    }

    /** The word at ([x], [y]), or the nearest within [reach]; -1 when none. */
    fun wordAt(words: List<PdfWord>, x: Float, y: Float, reach: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((i, w) in words.withIndex()) {
            val dx = max(0f, max(w.left - x, x - w.right))
            val dy = max(0f, max(w.top - y, y - w.bottom))
            // Lines are close together: being off the line counts more than along it.
            val d = dx + dy * 3f
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return if (bestD <= reach) best else -1
    }

    /** One box per line for the words [from]…[to] (either order). */
    fun boxes(words: List<PdfWord>, from: Int, to: Int): List<FloatArray> {
        if (words.isEmpty()) return emptyList()
        val a = min(from, to).coerceIn(0, words.lastIndex)
        val b = max(from, to).coerceIn(0, words.lastIndex)
        val out = ArrayList<FloatArray>()
        var cur: FloatArray? = null
        var line = -1
        for (i in a..b) {
            val w = words[i]
            val c = cur
            if (c != null && w.line == line) {
                c[0] = min(c[0], w.left)
                c[1] = min(c[1], w.top)
                c[2] = max(c[2], w.right)
                c[3] = max(c[3], w.bottom)
            } else {
                cur = floatArrayOf(w.left, w.top, w.right, w.bottom).also(out::add)
                line = w.line
            }
        }
        return out
    }

    /** What the words [from]…[to] say: spaces between words, a new line between lines; hyphenated breaks joined. */
    fun text(words: List<PdfWord>, from: Int, to: Int): String {
        if (words.isEmpty()) return ""
        val a = min(from, to).coerceIn(0, words.lastIndex)
        val b = max(from, to).coerceIn(0, words.lastIndex)
        val sb = StringBuilder()
        for (i in a..b) {
            val w = words[i]
            if (i > a) {
                val p = words[i - 1]
                when {
                    p.line == w.line -> sb.append(' ')
                    sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter() -> sb.setLength(sb.length - 1)
                    else -> sb.append('\n')
                }
            }
            sb.append(w.text)
        }
        return sb.toString()
    }

    /**
     * A marker pass ([xs], [ys]) laid along the text it goes over: the line whose words it
     * covers most, from the first of them to the last. Null when it does not go along one line.
     * Returns left, centre y, right and the height of the band.
     */
    fun snapPass(words: List<PdfWord>, xs: FloatArray, ys: FloatArray): FloatArray? {
        if (xs.size < 2 || words.isEmpty()) return null
        val x0 = xs.min()
        val x1 = xs.max()
        val y0 = ys.min()
        val y1 = ys.max()
        val midY = ys.average().toFloat()
        // Words the pass crosses along x, by line: how close each line is to the pass.
        val byLine = words.filter { it.right > x0 && it.left < x1 && it.top < y1 + it.height * 0.6f && it.bottom > y0 - it.height * 0.6f }
            .groupBy { it.line }
        if (byLine.isEmpty()) return null
        val (_, line) = byLine.minBy { (_, ws) -> abs(ws.map { it.centerY }.average().toFloat() - midY) }
        val h = line.maxOf { it.height }
        // A pass over several lines is not one line's marker.
        if (y1 - y0 > h * 1.8f) return null
        // Words mostly under the pass (a word just touched at its end is left out).
        val covered = line.filter { w -> min(w.right, x1) - max(w.left, x0) > (w.right - w.left) * 0.4f }.ifEmpty { return null }
        val left = covered.minOf { it.left }
        val right = covered.maxOf { it.right }
        val top = covered.minOf { it.top }
        val bottom = covered.maxOf { it.bottom }
        return floatArrayOf(left, (top + bottom) / 2, right, (bottom - top) * 1.15f)
    }

    /**
     * A colour matrix (Android's ColorMatrix, 4×5) that turns a page dark: light becomes [paper]
     * (0…255 grey), dark becomes light, colours keep their hue (inverted, then turned half round).
     */
    fun darkMatrix(paper: Float = 34f, ink: Float = 228f): FloatArray {
        // Hue turned by 180° (the luminance-keeping matrix), applied to the inverted colour.
        val h = arrayOf(
            floatArrayOf(-0.574f, 1.430f, 0.144f),
            floatArrayOf(0.426f, 0.430f, 0.144f),
            floatArrayOf(0.426f, 1.430f, -0.856f),
        )
        // Inverted: c' = 255 − c; then squeezed into paper…ink: c'' = paper + c'·(ink − paper)/255.
        val k = (ink - paper) / 255f
        val out = FloatArray(20)
        for (r in 0..2) {
            var sum = 0f
            for (c in 0..2) {
                out[r * 5 + c] = -h[r][c] * k
                sum += h[r][c]
            }
            out[r * 5 + 4] = paper + 255f * sum * k
        }
        out[18] = 1f
        return out
    }
}
