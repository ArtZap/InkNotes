package dev.inknotes.editor

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * «Выровнять почерк»: lines of handwriting made straight, as Smart Script does. The letters stay
 * as they were written; only whole words move:
 *  - a sloping line is turned level;
 *  - every word is set on the line's common baseline;
 *  - words much smaller or larger than the others of the line are brought part of the way
 *    to their size;
 *  - gaps between words are made about even.
 *
 * Pure Kotlin, without Android: the answer is an affine map per stroke, [Move].
 */
object Tidy {
    /** One stroke: its points. */
    class Mark(val xs: FloatArray, val ys: FloatArray) {
        val left = xs.min()
        val right = xs.max()
        val top = ys.min()
        val bottom = ys.max()
        val cy get() = (top + bottom) / 2
        val h get() = bottom - top
    }

    /** x' = a·x + b·y + c, y' = d·x + e·y + f; [angle] in degrees, [scale] the size change. */
    data class Move(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float, val angle: Float, val scale: Float) {
        fun x(x: Float, y: Float) = a * x + b * y + c
        fun y(x: Float, y: Float) = d * x + e * y + f
        val still get() = abs(a - 1) < 1e-4f && abs(b) < 1e-4f && abs(c) < 0.05f && abs(d) < 1e-4f && abs(e - 1) < 1e-4f && abs(f) < 0.05f

        /** This move, then [next]. */
        fun then(next: Move) = Move(
            next.a * a + next.b * d, next.a * b + next.b * e, next.a * c + next.b * f + next.c,
            next.d * a + next.e * d, next.d * b + next.e * e, next.d * c + next.e * f + next.f,
            angle + next.angle, scale * next.scale,
        )

        companion object {
            val NONE = Move(1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f)
            fun shift(dx: Float, dy: Float) = Move(1f, 0f, dx, 0f, 1f, dy, 0f, 1f)
            fun turn(deg: Float, cx: Float, cy: Float): Move {
                val r = Math.toRadians(deg.toDouble())
                val co = cos(r).toFloat()
                val si = sin(r).toFloat()
                return Move(co, -si, cx - co * cx + si * cy, si, co, cy - si * cx - co * cy, deg, 1f)
            }
            fun size(k: Float, cx: Float, cy: Float) = Move(k, 0f, cx - k * cx, 0f, k, cy - k * cy, 0f, k)
        }
    }

    /** Lines steeper than this are taken as written so on purpose. */
    const val MAX_TILT = 20f
    /** Words further than this many letter heights apart are not one word. */
    const val WORD_GAP = 0.45f
    /** A word's size is brought this part of the way to the line's. */
    const val SIZE_PULL = 0.6f
    /** Gaps are brought this part of the way to the line's usual gap. */
    const val GAP_PULL = 0.7f

    /** The move of every stroke of [marks], in their order. */
    fun moves(marks: List<Mark>): List<Move> {
        val out = MutableList(marks.size) { Move.NONE }
        if (marks.isEmpty()) return out
        // Long dashes (fraction bars, underlines) and big strokes (brackets, drawings) are not
        // letters: they stay where they are and do not count.
        val h = median(marks.map { it.h }.filter { it > 0 }.ifEmpty { listOf(1f) })
        val letters = marks.indices.filter { val m = marks[it]; m.right - m.left < 4 * h && m.h < 3.5f * h }
        if (letters.isEmpty()) return out
        for (sub in lines(letters.map { marks[it] })) {
            val line = sub.map { letters[it] }
            val tilt = tiltOf(words(line, line.associateWith { marks[it] }).map { w -> w.map { marks[it] } })
            var cur = line.associateWith { Move.NONE }.toMutableMap()
            if (abs(tilt) in 0.5f..MAX_TILT) {
                val l = line.minOf { marks[it].left }
                val r = line.maxOf { marks[it].right }
                val cy = median(line.map { marks[it].cy })
                val turn = Move.turn(-tilt, (l + r) / 2, cy)
                cur = line.associateWith { turn }.toMutableMap()
            }
            fun moved(i: Int): Mark {
                val m = cur.getValue(i)
                val s = marks[i]
                return Mark(FloatArray(s.xs.size) { m.x(s.xs[it], s.ys[it]) }, FloatArray(s.xs.size) { m.y(s.xs[it], s.ys[it]) })
            }
            val level = line.associateWith { moved(it) }
            val words = words(line, level)
            if (words.isEmpty()) continue
            // Each word's baseline and letter height.
            class W(val ids: List<Int>, val base: Float, val size: Float, val left: Float, val right: Float)
            val ws = words.map { ids ->
                val ms = ids.map { level.getValue(it) }
                W(ids, baseline(ms), letterHeight(ms), ms.minOf { it.left }, ms.maxOf { it.right })
            }
            val base = median(ws.map { it.base })
            val size = median(ws.map { it.size })
            val gaps = ws.zipWithNext { p, n -> n.left - p.right }
            val gap = if (gaps.isEmpty()) 0f else median(gaps)
            var x = ws.first().left
            for ((k, w) in ws.withIndex()) {
                // Size: only a word clearly off is changed; a capital or a dot is left alone.
                val ratio = if (w.size > 0) size / w.size else 1f
                val k1 = if (ratio !in 0.8f..1.25f && w.ids.size > 1) 1 + (ratio.coerceIn(0.6f, 1.6f) - 1) * SIZE_PULL else 1f
                val width = (w.right - w.left) * k1
                if (k > 0) {
                    val g = gaps[k - 1]
                    x += if (gap > 0 && g > 0) g + (gap - g) * GAP_PULL else g
                }
                val m = Move.size(k1, w.left, w.base).then(Move.shift(x - w.left, base - w.base))
                for (i in w.ids) out[i] = cur.getValue(i).then(m)
                x += width
            }
        }
        return out
    }

    /** Strokes in lines: each list goes left to right. */
    internal fun lines(marks: List<Mark>): List<List<Int>> {
        val h = max(median(marks.map { it.h }.filter { it > 0 }.ifEmpty { listOf(1f) }), 1f)
        // Left to right, each stroke goes on with the line whose end is at its height: a
        // sloping line is followed along its slope.
        val lines = ArrayList<MutableList<Int>>()
        val ends = ArrayList<Float>()
        val rights = ArrayList<Float>()
        for (i in marks.indices.sortedBy { marks[it].left }) {
            val m = marks[i]
            val k = ends.indices.filter { abs(ends[it] - m.cy) < 0.9f * h && m.left - rights[it] < 8 * h }.minByOrNull { abs(ends[it] - m.cy) }
            if (k != null) {
                lines[k] += i
                // The end follows the letters, not dots or tails: the middle of the last few.
                ends[k] = median(lines[k].takeLast(4).map { marks[it].cy })
                rights[k] = max(rights[k], m.right)
            } else {
                lines += mutableListOf(i)
                ends += m.cy
                rights += m.right
            }
        }
        return lines.sortedBy { l -> l.minOf { marks[it].top } }.map { l -> l.sortedBy { marks[it].left } }
    }

    /** Words of one line, left to right. */
    internal fun words(line: List<Int>, marks: Map<Int, Mark>): List<List<Int>> {
        val sorted = line.sortedBy { marks.getValue(it).left }
        val h = letterHeight(sorted.map { marks.getValue(it) })
        val words = ArrayList<MutableList<Int>>()
        var right = Float.NEGATIVE_INFINITY
        for (i in sorted) {
            val m = marks.getValue(i)
            if (words.isEmpty() || m.left - right > WORD_GAP * h) words += mutableListOf(i) else words.last() += i
            right = max(right, m.right)
        }
        return words
    }

    /** Slope of a line, in degrees, down to the right positive. */
    internal fun tilt(ms: List<Mark>): Float =
        tiltOf(words(ms.indices.toList(), ms.indices.associateWith { ms[it] }).map { w -> w.map { ms[it] } })

    /**
     * Slope of a line of [words]: the median slope of pairs of letter bottoms inside the same
     * word. A word written a bit higher than the others is not a slope; one sloping word in a
     * row of others is outvoted. Pairs across words only when the words are too short.
     */
    internal fun tiltOf(words: List<List<Mark>>): Float {
        val ms = words.flatten()
        if (ms.isEmpty()) return 0f
        val h = letterHeight(ms)
        val l = ms.minOf { it.left }
        val r = ms.maxOf { it.right }
        if (r - l < 4 * h) return 0f
        fun points(w: List<Mark>) = w.filter { max(it.h, it.right - it.left) >= 0.4f * h }.map { (it.left + it.right) / 2 to it.bottom }
        fun slopes(pts: List<Pair<Float, Float>>, into: MutableList<Float>) {
            for (a in pts.indices) for (b in a + 1 until pts.size) {
                val dx = pts[b].first - pts[a].first
                if (abs(dx) >= h) into += (pts[b].second - pts[a].second) / dx
            }
        }
        val inside = ArrayList<Float>()
        for (w in words) slopes(points(w), inside)
        val slopes = if (inside.size >= 3) inside else ArrayList<Float>().also { slopes(points(ms), it) }
        if (slopes.size < 3) return 0f
        return Math.toDegrees(atan2(median(slopes).toDouble(), 1.0)).toFloat()
    }

    /** Where the word stands: the bottom most of its strokes reach, not the tails below. */
    internal fun baseline(ms: List<Mark>): Float {
        // Dots and accents stand above it, tails (у, р, д) reach below: the middle of the
        // bottoms of the strokes that are letters, the upper one of two.
        val h = letterHeight(ms)
        val bottoms = ms.filter { max(it.h, it.right - it.left) >= 0.4f * h }.ifEmpty { ms }.map { it.bottom }.sorted()
        return bottoms[(bottoms.size - 1) / 2]
    }

    /** Height of the usual letter: median height of the strokes that are not dots. */
    internal fun letterHeight(ms: List<Mark>): Float {
        val hs = ms.map { max(it.h, it.right - it.left) }.sorted()
        val big = hs.filter { it >= 0.3f * hs.last() }
        return median(big.ifEmpty { hs }).coerceAtLeast(1e-3f)
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
