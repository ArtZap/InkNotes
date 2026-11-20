package dev.inknotes.math

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where this writer puts exponents and indexes: every symbol confirmed in the correction
 * window, how high it stood against the symbol before it, and where the writer said it
 * belongs (on the line, raised, lowered). When enough of the writer's own placements around
 * a new symbol all agree, the layout follows them instead of its general thresholds.
 */
class Placements(private val points: ArrayDeque<P> = ArrayDeque()) {

    /**
     * A placement: the symbol's [bottom] and [middle] against the symbol before it (0 its top,
     * 1 its foot), its [size] against it, and the [level] the writer chose ([Correct.LINE],
     * [Correct.UP], [Correct.DOWN]).
     */
    class P(val bottom: Float, val middle: Float, val size: Float, val level: Int)

    @Synchronized
    fun add(p: P) {
        points.addLast(p)
        while (points.size > MAX) points.removeFirst()
    }

    @Synchronized
    fun count() = points.size

    @Synchronized
    fun isEmpty() = points.isEmpty()

    @Synchronized
    fun clear() = points.clear()

    /**
     * The level the writer's placements give a symbol [b] after [ref] on a line [h] high: when
     * there are enough of them, of more than one level, and the [K] nearest are all near and
     * agree. Else null: the general rules decide.
     */
    @Synchronized
    fun level(b: Box, ref: Box, h: Float): Int? {
        if (points.size < MIN) return null
        if (points.map { it.level }.distinct().size < 2) return null
        val q = of(b, ref, h, -1)
        val near = points.map { it to dist(it, q) }.sortedBy { it.second }.take(K)
        if (near.size < K || near.last().second > NEAR) return null
        val level = near.first().first.level
        return level.takeIf { near.all { it.first.level == level } }
    }

    /** One line per placement: bottom, middle, size, level. */
    @Synchronized
    fun serialize(): String = buildString {
        for (p in points) append("%.3f\t%.3f\t%.3f\t%d\n".format(java.util.Locale.ROOT, p.bottom, p.middle, p.size, p.level))
    }

    companion object {
        const val MAX = 400
        const val MIN = 8
        const val K = 5
        const val NEAR = 0.2f

        fun parse(text: String): Placements {
            val out = ArrayDeque<P>()
            for (line in text.lineSequence()) {
                val f = line.split('\t')
                if (f.size != 4) continue
                val p = P(f[0].toFloatOrNull() ?: continue, f[1].toFloatOrNull() ?: continue, f[2].toFloatOrNull() ?: continue, f[3].toIntOrNull() ?: continue)
                out.addLast(p)
            }
            return Placements(out)
        }

        /** A symbol [b] after [ref] on a line [h] high, as a placement at [level]. */
        fun of(b: Box, ref: Box, h: Float, level: Int): P {
            val rh = max(ref.height, 0.5f * h)
            return P((b.bottom - ref.top) / rh, (b.cy - ref.top) / rh, b.height / max(ref.height, 0.75f * h), level)
        }

        private fun dist(a: P, b: P): Float {
            val db = a.bottom - b.bottom
            val dm = a.middle - b.middle
            val ds = 0.5f * (a.size - b.size)
            return sqrt(db * db + dm * dm + ds * ds)
        }

        /**
         * The placements a corrected formula shows: each symbol (not a sign, not taken out) at
         * the level chosen for it, against the last symbol before it left on the line.
         */
        fun observe(side: Correct.Side, picks: List<Correct.Pick>, h: Float): List<P> {
            val out = ArrayList<P>()
            var ref: Box? = null
            for ((i, sym) in side.syms.withIndex()) {
                val pick = picks.getOrNull(i) ?: continue
                if (pick.level == Correct.OFF || sym.sign) continue
                val r = ref
                // Only symbols of a real size: a dot or a dash says nothing of heights.
                if (r != null && max(sym.box.width, sym.box.height) >= 0.22f * h) out += of(sym.box, r, h, pick.level)
                if (pick.level == Correct.LINE) ref = sym.box
            }
            return out
        }
    }
}
