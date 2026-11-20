package dev.inknotes.math

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * The working of a graph's own window: what is in view, where the curves cross each other and
 * the axes, the marks on the axes, the value at a point. Without Android, for tests.
 */
object GraphMath {

    enum class Kind {
        /** A curve meets the x axis: [Mark.a] is the curve. */
        ROOT,

        /** Curves [Mark.a] and [Mark.b] cross. */
        CROSS,

        /** A curve meets the y axis. */
        Y_AXIS,
    }

    class Mark(val x: Double, val y: Double, val kind: Kind, val a: Int, val b: Int = -1)

    /**
     * Where the curves of [fs] cross each other and the axes between [x0] and [x1]: sign
     * changes on [n] steps, made exact by halving. A jump through infinity (tan x at π/2) is
     * not a crossing. At most [max] marks, crossings of curves first.
     */
    fun marks(fs: List<(Double) -> Double>, x0: Double, x1: Double, n: Int = 800, max: Int = 30): List<Mark> {
        if (!(x1 > x0)) return emptyList()
        val xs = DoubleArray(n + 1) { x0 + (x1 - x0) * it / n }
        val ys = fs.map { f -> DoubleArray(xs.size) { Plot.safe(f, xs[it]) } }
        val out = ArrayList<Mark>()
        for (i in fs.indices) for (j in i + 1 until fs.size) {
            for (x in zeros({ v -> Plot.safe(fs[i], v) - Plot.safe(fs[j], v) }, xs, DoubleArray(xs.size) { ys[i][it] - ys[j][it] })) {
                out += Mark(x, Plot.safe(fs[i], x), Kind.CROSS, i, j)
            }
        }
        for (i in fs.indices) {
            for (x in zeros({ v -> Plot.safe(fs[i], v) }, xs, ys[i])) out += Mark(x, 0.0, Kind.ROOT, i)
            if (x0 <= 0.0 && x1 >= 0.0) {
                val y = Plot.safe(fs[i], 0.0)
                // Where it also meets the x axis, the root is the mark.
                if (y.isFinite() && abs(y) > 1e-9) out += Mark(0.0, y, Kind.Y_AXIS, i)
            }
        }
        return out.take(max)
    }

    /** Zeros of [h] (sampled as [hs] at [xs]): exact samples and sign changes, halved down. */
    private fun zeros(h: (Double) -> Double, xs: DoubleArray, hs: DoubleArray): List<Double> {
        val out = ArrayList<Double>()
        val finite = hs.filter { it.isFinite() }
        if (finite.isEmpty()) return out
        val scale = finite.maxOf { abs(it) }.coerceAtLeast(1.0)
        val step = xs[1] - xs[0]
        fun add(x: Double) {
            if (out.none { abs(it - x) < step * 0.5 }) out += x
        }
        for (k in xs.indices) {
            val a = hs[k]
            if (!a.isFinite()) continue
            if (a == 0.0) {
                add(xs[k])
                continue
            }
            if (k + 1 >= xs.size) continue
            val b = hs[k + 1]
            if (!b.isFinite() || b == 0.0 || (a < 0) == (b < 0)) continue
            var lo = xs[k]
            var hi = xs[k + 1]
            var flo = a
            repeat(60) {
                val mid = (lo + hi) / 2
                val fm = h(mid)
                if (!fm.isFinite()) return@repeat
                if ((fm < 0) == (flo < 0)) {
                    lo = mid
                    flo = fm
                } else {
                    hi = mid
                }
            }
            val x = (lo + hi) / 2
            val fx = h(x)
            // Through infinity the sign changes too, but the value there is huge, not zero.
            if (fx.isFinite() && abs(fx) <= 1e-6 * scale) add(clean(x))
        }
        return out
    }

    /** −1.0000000002 → −1: the halving stops a hair away from a round answer. */
    fun clean(x: Double): Double {
        val r = Math.rint(x * 1e6) / 1e6
        return if (abs(r - x) < 1e-7) r else x
    }

    /** The curves of [fs] in the window, as polylines in 0…1 of it (y up), [n] steps. */
    fun curves(fs: List<(Double) -> Double>, x0: Double, x1: Double, y0: Double, y1: Double, n: Int = 600): List<List<FloatArray>> {
        val xs = DoubleArray(n + 1) { x0 + (x1 - x0) * it / n }
        return fs.map { f -> Plot.segments(xs, DoubleArray(xs.size) { Plot.safe(f, xs[it]) }, x0, x1, y0, y1) }
    }

    /** The heights that show [fs] between [x0] and [x1]; null when nothing there is drawn. */
    fun fitY(fs: List<(Double) -> Double>, x0: Double, x1: Double, n: Int = 400): Pair<Double, Double>? {
        val ys = fs.flatMap { f -> (0..n).map { Plot.safe(f, x0 + (x1 - x0) * it / n) } }
        return Plot.yWindow(ys.toDoubleArray())
    }

    /** A round step between marks on an axis of [span]: 1, 2 or 5 times a power of ten, ~[ticks] of them. */
    fun step(span: Double, ticks: Int = 6): Double {
        if (!(span > 0) || !span.isFinite()) return 1.0
        val raw = span / ticks
        val p = 10.0.pow(floor(log10(raw)))
        val m = raw / p
        return p * when {
            m < 1.5 -> 1.0
            m < 3.5 -> 2.0
            m < 7.5 -> 5.0
            else -> 10.0
        }
    }

    /** The marks of an axis from [lo] to [hi] at [step]. */
    fun ticks(lo: Double, hi: Double, step: Double): List<Double> {
        if (!(step > 0) || hi < lo || (hi - lo) / step > 200) return emptyList()
        val first = Math.ceil(lo / step)
        return (0..((hi - lo) / step).toInt() + 1).map { clean((first + it) * step) }.filter { it in lo..hi }
    }

    /**
     * The curve nearest to ([x], [y]) at that x, and its value: what a finger on the graph
     * asks about. [tol]: how far (in y) a curve may be; null when none is that near.
     */
    fun nearest(fs: List<(Double) -> Double>, x: Double, y: Double, tol: Double): Pair<Int, Double>? {
        var best: Pair<Int, Double>? = null
        var d = tol
        for ((i, f) in fs.withIndex()) {
            val v = Plot.safe(f, x)
            if (!v.isFinite()) continue
            if (abs(v - y) <= d) {
                d = abs(v - y)
                best = i to v
            }
        }
        return best
    }

    /** A number short enough for a label: 3 significant digits past the step's own. */
    fun label(v: Double, step: Double): String {
        if (!v.isFinite()) return "?"
        val digits = max(0, -floor(log10(step.coerceAtLeast(1e-12))).toInt() + 2).coerceAtMost(6)
        val r = Math.rint(v * 10.0.pow(digits)) / 10.0.pow(digits)
        return Printer.number(if (r == -0.0) 0.0 else r)
    }
}
