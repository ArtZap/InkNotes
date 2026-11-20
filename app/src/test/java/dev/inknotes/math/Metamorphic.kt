package dev.inknotes.math

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Changes to handwriting after which a formula means the same: the answer must not change.
 * Run on CROHME formulas by [MetamorphicTest]; each change says how many answers it turns.
 */
object Metamorphic {

    /** A change of a sample: the same formula, other strokes. [seed] makes random ones repeatable. */
    class Change(val name: String, val apply: (InkSample, Random) -> InkSample)

    private fun map(s: InkSample, f: (Float, Float) -> Pair<Float, Float>): InkSample =
        InkSample(
            s.id,
            s.truth,
            s.strokes.map { k ->
                val xs = FloatArray(k.xs.size)
                val ys = FloatArray(k.ys.size)
                for (i in k.xs.indices) {
                    val (x, y) = f(k.xs[i], k.ys[i])
                    xs[i] = x
                    ys[i] = y
                }
                Ink(xs, ys, k.order, k.times)
            },
            s.symbols,
        )

    private fun points(s: InkSample, f: (Ink) -> Pair<FloatArray, FloatArray>): InkSample =
        InkSample(s.id, s.truth, s.strokes.map { k -> f(k).let { (xs, ys) -> Ink(xs, ys, k.order) } }, s.symbols)

    private fun height(s: InkSample): Float {
        val hs = s.strokes.map { it.height }.sorted()
        return max(hs[hs.size * 3 / 4], 8f)
    }

    private fun rotate(deg: Double) = Change("поворот ${if (deg > 0) "+" else ""}${deg.toInt()}°") { s, _ ->
        val b = Box.of(s.strokes)
        val a = Math.toRadians(deg)
        val c = cos(a).toFloat()
        val n = sin(a).toFloat()
        map(s) { x, y -> (b.cx + (x - b.cx) * c - (y - b.cy) * n) to (b.cy + (x - b.cx) * n + (y - b.cy) * c) }
    }

    val CHANGES = listOf(
        Change("масштаб ×0,5") { s, _ -> map(s) { x, y -> x * 0.5f to y * 0.5f } },
        Change("масштаб ×3") { s, _ -> map(s) { x, y -> x * 3f to y * 3f } },
        Change("сдвиг") { s, _ -> map(s) { x, y -> x + 1234.5f to y + 678.25f } },
        rotate(4.0),
        rotate(-4.0),
        Change("наклон 10°") { s, _ ->
            val b = Box.of(s.strokes)
            map(s) { x, y -> x + (b.cy - y) * 0.18f to y }
        },
        Change("дрожание") { s, r ->
            val j = 0.015f * height(s)
            map(s) { x, y -> x + (r.nextFloat() - 0.5f) * 2 * j to y + (r.nextFloat() - 0.5f) * 2 * j }
        },
        Change("каждая вторая точка") { s, _ ->
            points(s) { k ->
                val keep = k.xs.indices.filter { it % 2 == 0 || it == k.xs.lastIndex }
                FloatArray(keep.size) { k.xs[keep[it]] } to FloatArray(keep.size) { k.ys[keep[it]] }
            }
        },
        Change("вдвое больше точек") { s, _ ->
            points(s) { k ->
                val n = k.xs.size * 2 - 1
                FloatArray(n) { if (it % 2 == 0) k.xs[it / 2] else (k.xs[it / 2] + k.xs[it / 2 + 1]) / 2 } to
                    FloatArray(n) { if (it % 2 == 0) k.ys[it / 2] else (k.ys[it / 2] + k.ys[it / 2 + 1]) / 2 }
            }
        },
        Change("порядок штрихов") { s, r ->
            val orders = s.strokes.map { it.order }.shuffled(r)
            InkSample(s.id, s.truth, s.strokes.mapIndexed { i, k -> Ink(k.xs, k.ys, orders[i]) }, s.symbols)
        },
        Change("разрыв одного штриха") { s, r ->
            // One long stroke written in two: the pen lifted halfway once.
            val strokes = ArrayList<Ink>()
            val index = HashMap<Int, List<Int>>()
            var order = 0
            val long = s.strokes.indices.filter { val k = s.strokes[it]; k.xs.size >= 12 && max(k.width, k.height) > 0.5f * height(s) }
            val cut = long.randomOrNull(r)
            for ((i, k) in s.strokes.sortedBy { it.order }.let { sorted -> sorted.map { s.strokes.indexOf(it) to it } }) {
                if (i == cut) {
                    val m = k.xs.size / 2
                    strokes += Ink(k.xs.copyOfRange(0, m + 1), k.ys.copyOfRange(0, m + 1), order++)
                    strokes += Ink(k.xs.copyOfRange(m, k.xs.size), k.ys.copyOfRange(m, k.ys.size), order++)
                    index[i] = listOf(strokes.size - 2, strokes.size - 1)
                } else {
                    strokes += Ink(k.xs, k.ys, order++)
                    index[i] = listOf(strokes.size - 1)
                }
            }
            InkSample(s.id, s.truth, strokes, s.symbols.map { sym -> InkSymbol(sym.label, sym.strokes.flatMap { index[it]!! }) })
        },
    )
}
