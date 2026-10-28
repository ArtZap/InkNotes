package dev.inknotes.search

import kotlin.math.max
import kotlin.math.min

/**
 * Pure helpers for handwriting search: grouping strokes into lines of text and matching a
 * query against recognized text. No Android types, so they are unit-tested on the JVM.
 */
object TextLines {

    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height get() = bottom - top
        val centerY get() = (top + bottom) / 2
    }

    /**
     * Splits strokes (by their bounding [boxes]) into lines of writing, top to bottom, left to
     * right. Each group lists stroke indices in their original (writing) order. Strokes much
     * taller than the handwriting around them (drawings, frames) are left out.
     */
    fun group(boxes: List<Box?>): List<List<Int>> {
        val items = boxes.indices.filter { boxes[it] != null && boxes[it]!!.height >= 0f }
        if (items.isEmpty()) return emptyList()
        val heights = items.map { max(boxes[it]!!.height, 1f) }.sorted()
        val typical = max(heights[heights.size / 2], 8f)
        val textItems = items.filter { boxes[it]!!.height <= typical * TALL_FACTOR }

        class Line(var top: Float, var bottom: Float, val members: MutableList<Int>)
        val lines = ArrayList<Line>()
        // Letters first; small marks (dots, commas, accents) then join the line they belong to.
        val (marks, letters) = textItems.partition { boxes[it]!!.height < typical * 0.4f }
        for (i in letters.sortedBy { boxes[it]!!.centerY }) {
            val b = boxes[i]!!
            val best = lines
                .map { it to min(b.bottom, it.bottom) - max(b.top, it.top) }
                .filter { (line, overlap) -> overlap >= 0.5f * min(max(b.height, 1f), max(line.bottom - line.top, 1f)) }
                .maxByOrNull { it.second }?.first
            if (best == null) {
                lines += Line(b.top, b.bottom, mutableListOf(i))
            } else {
                // Keep the band at the core of the line so descenders don't swallow the next one.
                if (b.height <= typical * 1.5f) {
                    best.top = min(best.top, b.top)
                    best.bottom = max(best.bottom, b.bottom)
                }
                best.members += i
            }
        }
        for (i in marks) {
            val b = boxes[i]!!
            val line = lines
                .filter { b.centerY in it.top - typical * 0.6f..it.bottom + typical * 0.4f }
                .minByOrNull { kotlin.math.abs(b.centerY - (it.top + it.bottom) / 2) }
            if (line != null) line.members += i else lines += Line(b.top, b.bottom, mutableListOf(i))
        }

        // Split lines at wide horizontal gaps (two columns, a note in the margin).
        val out = ArrayList<List<Int>>()
        for (line in lines.sortedBy { it.top }) {
            val byX = line.members.sortedBy { boxes[it]!!.left }
            val gap = max(typical * GAP_FACTOR, MIN_GAP)
            var segment = ArrayList<Int>()
            var right = Float.NEGATIVE_INFINITY
            for (i in byX) {
                val b = boxes[i]!!
                if (segment.isNotEmpty() && b.left - right > gap) {
                    out += segment.sorted()
                    segment = ArrayList()
                }
                segment += i
                right = max(right, b.right)
            }
            if (segment.isNotEmpty()) out += segment.sorted()
        }
        return out
    }

    /** Lowercase, ё → е, single spaces: how both the query and recognized text are compared. */
    fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    /**
     * True if [query] occurs in any of the recognition [candidates]. Longer single words also
     * match with one or two wrong letters, since recognition is not perfect.
     */
    fun matches(query: String, candidates: List<String>): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return false
        val texts = candidates.map(::normalize)
        if (texts.any { q in it }) return true
        if (' ' in q || q.length < 4) return false
        val allowed = if (q.length >= 8) 2 else 1
        return texts.any { t -> t.split(' ').any { w -> nearMatch(q, w, allowed) } }
    }

    /** [query] within [allowed] edits of [word] or of a word prefix (for partial words). */
    private fun nearMatch(query: String, word: String, allowed: Int): Boolean {
        if (word.length < query.length - allowed) return false
        val w = word.take(query.length + allowed)
        // Edit distance of the query against the best prefix of w.
        var prev = IntArray(w.length + 1) { it }
        for (i in 1..query.length) {
            val cur = IntArray(w.length + 1)
            cur[0] = i
            for (j in 1..w.length) {
                val cost = if (query[i - 1] == w[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev.min() <= allowed
    }

    private const val TALL_FACTOR = 4f
    private const val GAP_FACTOR = 5f
    private const val MIN_GAP = 120f
}
