package dev.inknotes.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The graph of y = f(x): a window that shows its roots, extrema and breaks, and the curve
 * sampled once (on the analysis thread) so drawing only maps points.
 */
object Plot {

    /** Box size in line heights (the answer's size). */
    const val WIDTH = 9f
    const val HEIGHT = 6f

    private const val SAMPLES = 400
    private const val SCAN = 2001
    private const val SCAN_RANGE = 50.0
    private const val MAX_FEATURES = 12

    /**
     * The graph of [f], or null when there is nothing to draw (undefined everywhere, or a
     * constant: a horizontal line says nothing the formula doesn't).
     */
    fun of(f: (Double) -> Double, variable: String, trig: Boolean): Answer.Graph? {
        val (x0, x1) = xWindow(f, trig) ?: return null
        val xs = DoubleArray(SAMPLES + 1) { x0 + (x1 - x0) * it / SAMPLES }
        val ys = DoubleArray(xs.size) { safe(f, xs[it]) }
        val (y0, y1) = yWindow(ys) ?: return null
        return Answer.Graph(variable, x0.toFloat(), x1.toFloat(), y0.toFloat(), y1.toFloat(), segments(xs, ys, x0, x1, y0, y1))
    }

    private fun safe(f: (Double) -> Double, x: Double): Double = runCatching { f(x) }.getOrDefault(Double.NaN)

    /**
     * From −5…5 out to what matters: roots, turning points and breaks within ±50, padded.
     * Periodic graphs (many features) get two periods, others a window around the features.
     */
    private fun xWindow(f: (Double) -> Double, trig: Boolean): Pair<Double, Double>? {
        val xs = DoubleArray(SCAN) { -SCAN_RANGE + 2 * SCAN_RANGE * it / (SCAN - 1) }
        val ys = DoubleArray(SCAN) { safe(f, xs[it]) }
        if (ys.none { it.isFinite() }) return null
        val features = ArrayList<Double>()
        for (i in 1 until SCAN) {
            val a = ys[i - 1]
            val b = ys[i]
            if (a.isFinite() != b.isFinite()) features += xs[i] // edge of the domain
            if (!a.isFinite() || !b.isFinite()) continue
            if (a == 0.0 || (a < 0) != (b < 0)) features += xs[i]
            if (i >= 2 && ys[i - 2].isFinite()) {
                val d1 = a - ys[i - 2]
                val d2 = b - a
                if (d1 != 0.0 && d2 != 0.0 && (d1 < 0) != (d2 < 0)) features += xs[i - 1]
            }
        }
        if (trig && features.size > MAX_FEATURES) return -2 * PI to 2 * PI
        if (features.isEmpty() || features.size > MAX_FEATURES) return -5.0 to 5.0
        var lo = min(features.min(), 0.0)
        var hi = max(features.max(), 0.0)
        val pad = max(2.0, 0.3 * (hi - lo))
        lo -= pad
        hi += pad
        // Square-ish windows read best; keep the origin in view when it is near.
        if (hi - lo < 8) {
            val mid = (lo + hi) / 2
            lo = mid - 4
            hi = mid + 4
        }
        return lo to hi
    }

    /** The middle 96% of the values, padded; 0 included when it is close. */
    private fun yWindow(ys: DoubleArray): Pair<Double, Double>? {
        val finite = ys.filter { it.isFinite() }.sorted()
        if (finite.size < 2) return null
        var lo = finite[(finite.size * 0.02).toInt()]
        var hi = finite[((finite.size - 1) * 0.98).toInt()]
        if (hi - lo < 1e-9) {
            // y = const: still a line, around it.
            lo -= 1
            hi += 1
        }
        val span = hi - lo
        if (lo > 0 && lo < 0.5 * span) lo = 0.0
        if (hi < 0 && -hi < 0.5 * span) hi = 0.0
        val pad = 0.1 * (hi - lo)
        return (lo - pad) to (hi + pad)
    }

    /**
     * The curve as polylines in 0…1 box coordinates (y up), clipped to the window; broken
     * where the function is undefined or jumps across the window (tan x at π/2).
     */
    private fun segments(xs: DoubleArray, ys: DoubleArray, x0: Double, x1: Double, y0: Double, y1: Double): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val cur = ArrayList<Float>()
        fun flush() {
            if (cur.size >= 4) out += cur.toFloatArray()
            cur.clear()
        }
        val span = y1 - y0
        val lo = y0 - 2 * span
        val hi = y1 + 2 * span
        for (i in xs.indices) {
            val y = ys[i]
            if (!y.isFinite() || y < lo || y > hi) {
                flush()
                continue
            }
            if (i > 0 && ys[i - 1].isFinite() && abs(y - ys[i - 1]) > span) flush()
            cur += ((xs[i] - x0) / (x1 - x0)).toFloat()
            cur += ((y - y0) / span).toFloat()
        }
        flush()
        return out
    }

    /** Tick values for an axis: 1, 2 or 5 × 10ⁿ apart, about [count] of them. */
    fun ticks(lo: Float, hi: Float, count: Int = 5): List<Float> {
        val raw = (hi - lo) / count
        if (raw <= 0f || !raw.isFinite()) return emptyList()
        val mag = 10.0.pow(floor(log10(raw.toDouble())))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
        val first = ceil(lo / step) * step
        return generateSequence(first) { it + step }.takeWhile { it <= hi + 1e-9 }.map { (if (abs(it) < step * 1e-6) 0.0 else it).toFloat() }.toList()
    }
}
