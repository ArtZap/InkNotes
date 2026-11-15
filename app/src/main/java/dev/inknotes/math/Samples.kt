package dev.inknotes.math

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The writer's own symbols, kept from the correction window: each one's shape with what it
 * is. A second voice beside ML Kit, by nearest neighbour: a symbol drawn like one this writer
 * already said is a 1 is likely a 1. Only on the device, never sent anywhere.
 */
class Samples(private val byToken: MutableMap<String, ArrayDeque<FloatArray>> = HashMap()) {

    /** What the samples say a symbol is: [token], how far its nearest sample is, how sure. */
    class Vote(val token: String, val distance: Float, val sure: Boolean)

    /** Keeps [strokes] as a sample of [token] (the oldest go past [PER_TOKEN] of one symbol). */
    @Synchronized
    fun add(token: String, strokes: List<Ink>) {
        val shape = shape(strokes) ?: return
        val list = byToken.getOrPut(token) { ArrayDeque() }
        list.addLast(shape)
        while (list.size > PER_TOKEN) list.removeFirst()
    }

    /**
     * What [strokes] are by the samples: the symbol with the nearest one, sure when it is near,
     * the symbol has at least two samples, and every other symbol is clearly farther.
     */
    @Synchronized
    fun vote(strokes: List<Ink>): Vote? {
        if (byToken.isEmpty()) return null
        val shape = shape(strokes) ?: return null
        var best: String? = null
        var d1 = Float.MAX_VALUE
        var d2 = Float.MAX_VALUE
        for ((token, list) in byToken) {
            val d = list.minOf { distance(shape, it) }
            if (d < d1) {
                d2 = d1
                d1 = d
                best = token
            } else if (d < d2) {
                d2 = d
            }
        }
        val token = best ?: return null
        val enough = (byToken[token]?.size ?: 0) >= 2
        val sure = enough && d1 < NEAR && (d2 == Float.MAX_VALUE && d1 < NEAR * 0.7f || d1 < MARGIN * d2)
        return Vote(token, d1, sure)
    }

    /** How many symbols are kept. */
    @Synchronized
    fun count(): Int = byToken.values.sumOf { it.size }

    @Synchronized
    fun isEmpty() = byToken.isEmpty()

    @Synchronized
    fun clear() = byToken.clear()

    /** One line per sample: the symbol, tab, its shape's numbers. */
    @Synchronized
    fun serialize(): String = buildString {
        for ((t, list) in byToken) for (s in list) {
            append(t.replace('\t', ' ').replace('\n', ' ')).append('\t')
            s.joinTo(this, ",") { "%.4f".format(java.util.Locale.ROOT, it) }
            append('\n')
        }
    }

    companion object {
        /** Points a shape is resampled to. */
        const val POINTS = 32
        const val PER_TOKEN = 30

        /**
         * Nearer than this (in symbol sizes, on average per point): the same shape. With
         * [MARGIN], set on CROHME across writers (`SamplesBenchTest`): sure votes right in
         * 96–97 % with 15–30 samples a symbol, on about a quarter of symbols. One's own
         * handwriting is more alike than other people's.
         */
        @JvmField var NEAR = 0.10f

        /** The nearest symbol must be this much nearer than any other to be sure. */
        @JvmField var MARGIN = 0.5f

        fun parse(text: String): Samples {
            val map = HashMap<String, ArrayDeque<FloatArray>>()
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val nums = line.substring(tab + 1).split(',').mapNotNull { it.toFloatOrNull() }
                if (nums.size != SIZE) continue
                map.getOrPut(line.substring(0, tab)) { ArrayDeque() }.addLast(nums.toFloatArray())
            }
            return Samples(map)
        }

        private const val SIZE = 2 * POINTS + 2

        /**
         * A symbol's shape: its strokes in the order drawn, the path through them resampled to
         * [POINTS] points, centred and scaled by its larger side; then how wide it is against
         * how tall, and how many strokes. Null for a dot or nothing.
         */
        fun shape(strokes: List<Ink>): FloatArray? {
            if (strokes.isEmpty()) return null
            val ordered = strokes.sortedBy { it.order }
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            for (s in ordered) for (k in s.xs.indices) {
                xs += s.xs[k]
                ys += s.ys[k]
            }
            val left = xs.min()
            val right = xs.max()
            val top = ys.min()
            val bottom = ys.max()
            val w = right - left
            val h = bottom - top
            val size = max(w, h)
            if (size < 1e-3f) return null
            // Length along the path, pen lifts included (a jump is part of how it is drawn).
            val n = xs.size
            val along = FloatArray(n)
            for (k in 1 until n) along[k] = along[k - 1] + hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
            val total = along[n - 1]
            val out = FloatArray(SIZE)
            val cx = (left + right) / 2
            val cy = (top + bottom) / 2
            var j = 0
            for (p in 0 until POINTS) {
                val at = if (POINTS == 1 || total == 0f) 0f else total * p / (POINTS - 1)
                while (j < n - 2 && along[j + 1] < at) j++
                val seg = (along[min(j + 1, n - 1)] - along[j]).takeIf { it > 0 } ?: 1f
                val t = if (n == 1) 0f else ((at - along[j]) / seg).coerceIn(0f, 1f)
                val x = if (n == 1) xs[0] else xs[j] + (xs[min(j + 1, n - 1)] - xs[j]) * t
                val y = if (n == 1) ys[0] else ys[j] + (ys[min(j + 1, n - 1)] - ys[j]) * t
                out[2 * p] = (x - cx) / size
                out[2 * p + 1] = (y - cy) / size
            }
            out[2 * POINTS] = ln(max(w, 1e-3f) / max(h, 1e-3f)).coerceIn(-3f, 3f)
            out[2 * POINTS + 1] = strokes.size.toFloat()
            return out
        }

        /** How unlike two shapes are: mean distance of their points, plus proportions and stroke count. */
        fun distance(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (p in 0 until POINTS) {
                val dx = a[2 * p] - b[2 * p]
                val dy = a[2 * p + 1] - b[2 * p + 1]
                sum += sqrt(dx * dx + dy * dy)
            }
            return sum / POINTS + 0.06f * abs(a[2 * POINTS] - b[2 * POINTS]) + 0.08f * abs(a[2 * POINTS + 1] - b[2 * POINTS + 1])
        }
    }
}

/** The second voice applied to what ML Kit read. */
object Voices {
    /**
     * ML Kit's readings [texts] of a run of [glyphs] (each its strokes, left to right), with
     * what the writer's samples are sure of put in: for one symbol that reading first, for a
     * longer run a reading with it right after ML Kit's first. Unchanged when the samples are
     * not sure or the reading does not split into as many symbols as there are glyphs.
     */
    fun adjust(texts: List<String>, glyphs: List<List<Ink>>, samples: Samples): List<String> {
        if (texts.isEmpty() || samples.isEmpty() || glyphs.isEmpty()) return texts
        val first = texts[0]
        val toks = Correct.tokens(first)
        if (toks.size != glyphs.size) return texts
        var changed = false
        var veryNear = true
        val fixed = toks.mapIndexed { i, t ->
            val v = samples.vote(glyphs[i])
            if (v != null && v.sure && v.token != t) {
                changed = true
                if (v.distance >= VERY_NEAR * Samples.NEAR) veryNear = false
                v.token
            } else {
                t
            }
        }
        if (!changed) return texts
        val alt = fixed.joinToString("")
        // Only a very near sample of a lone symbol outweighs ML Kit; otherwise it is the next
        // reading to try (and makes the answer unsure where the two disagree).
        return if (glyphs.size == 1 && veryNear) listOf(alt) + (texts - alt) else listOf(first, alt) + (texts.drop(1) - alt)
    }

    /** Of [Samples.NEAR]: a sample this near is the writer's symbol beyond doubt. */
    private const val VERY_NEAR = 0.6f
}
