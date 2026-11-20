package dev.inknotes.math

import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Synthetic handwritten formulas with known answers: a formula from a small grammar is laid out
 * by [Typeset], and each printed symbol is replaced by a handwritten one of the same kind from
 * CROHME (one writer per formula where they wrote it), a little moved, scaled and turned. Like the
 * synthetic part of MathWriting and Khuong et al. (2019).
 *
 * What CROHME does not have: equations (linear, with a fraction, quadratic) with their roots, so
 * the page's solving is measured too. CROHME is for tests only (decision 14.11), and so is this.
 */
object SynthFormulas {

    /** A generated formula: what the benches judge, the source it was laid out from, and its symbols for [PerfectReader]. */
    class Synth(val formula: FormulaJudge.Formula, val source: String, val sample: InkSample)

    /** Handwritten symbols by CROHME label: for each writer (file), the strokes of each symbol, at their own place. */
    class Bank(val byWriter: List<Map<String, List<List<Ink>>>>) {
        val all: Map<String, List<List<Ink>>> = byWriter.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, v) -> v.flatten() }

        companion object {
            fun of(samples: List<InkSample>): Bank = Bank(
                samples.map { s ->
                    s.symbols.groupBy({ it.label }, { sym -> sym.strokes.mapNotNull { s.strokes.getOrNull(it) } }).filterValues { it.isNotEmpty() }
                }.filter { it.isNotEmpty() },
            )
        }
    }

    /** Letter size the formulas are laid out at: digits come out about [InkMl.TARGET_H] tall, as the CROHME bench. */
    private const val SIZE = 42f

    /** How many formulas of each kind. */
    const val PER_KIND = 40

    /** CROHME label for a printed character; null: drawn as a dot or not available. */
    private fun label(c: Char): String? = when (c) {
        '−' -> "-"
        '×' -> "\\times"
        'π' -> "\\pi"
        in '0'..'9', in 'a'..'z', in 'A'..'Z', '+', '=', '(', ')', '[', ']', '/' -> c.toString()
        else -> null
    }

    private val X_HEIGHT = "acemnorsuvwxz"
    private val DESCENDERS = "gjpqy"

    /** Width of printed [text] at [size]: what the layout gives each symbol room for. */
    private val measure = Typeset.Measure { text, size, _ ->
        text.sumOf { c ->
            when (c) {
                in "mw" -> 0.7
                in "il1()[]" -> 0.35
                '.', ',', '·' -> 0.25
                in "+−=×÷" -> 0.6
                else -> 0.52
            } * size
        }.toFloat()
    }

    /** All synthetic formulas, the same every time (seeded): [PER_KIND] of each kind. */
    fun generate(bank: Bank, seed: Long = 1): List<Synth> {
        val rnd = Random(seed)
        val out = ArrayList<Synth>()
        val makers = listOf<Pair<String, () -> Pair<String, String?>>>(
            "arith" to { arith(rnd) },
            "fraction" to { fraction(rnd) },
            "power" to { power(rnd) },
            "root" to { root(rnd) },
            "letters" to { letters(rnd) },
            FormulaJudge.EQUATION + "-linear" to { linear(rnd) },
            FormulaJudge.EQUATION + "-fraction" to { linearFraction(rnd) },
            FormulaJudge.EQUATION + "-quadratic" to { quadratic(rnd) },
        )
        for ((kind, make) in makers) {
            var made = 0
            var tries = 0
            while (made < PER_KIND && tries < PER_KIND * 5) {
                tries++
                val (source, roots) = make()
                val synth = render(bank, kind, "$kind-$made", source, roots, rnd) ?: continue
                out += synth
                made++
            }
        }
        return out
    }

    // ------------------------------------------------------------ grammar

    private fun n(rnd: Random, from: Int, to: Int) = from + rnd.nextInt(to - from + 1)

    private fun arith(rnd: Random): Pair<String, String?> {
        val ops = listOf("+", "-", "*")
        val k = n(rnd, 2, 3)
        val src = (0 until k).joinToString("") { i -> (if (i > 0) ops[rnd.nextInt(3)] else "") + n(rnd, 1, 30) }
        return src to null
    }

    private fun fraction(rnd: Random): Pair<String, String?> = when (rnd.nextInt(3)) {
        0 -> "\\frac{${n(rnd, 1, 20)}}{${n(rnd, 2, 9)}}+${n(rnd, 1, 9)}"
        1 -> "\\frac{${n(rnd, 1, 9)}}{${n(rnd, 2, 9)}}+\\frac{${n(rnd, 1, 9)}}{${n(rnd, 2, 9)}}"
        else -> "\\frac{${n(rnd, 2, 9)}+${n(rnd, 1, 9)}}{${n(rnd, 2, 9)}}"
    } to null

    private fun power(rnd: Random): Pair<String, String?> = when (rnd.nextInt(3)) {
        0 -> "${n(rnd, 2, 9)}^{${n(rnd, 2, 3)}}+${n(rnd, 1, 20)}"
        1 -> "${n(rnd, 2, 5)}^{${n(rnd, 2, 4)}}-${n(rnd, 2, 5)}^{2}"
        else -> "(${n(rnd, 1, 9)}+${n(rnd, 1, 9)})^{2}"
    } to null

    private fun root(rnd: Random): Pair<String, String?> {
        val k = n(rnd, 2, 12)
        return when (rnd.nextInt(2)) {
            0 -> "\\sqrt{${k * k}}+${n(rnd, 1, 9)}"
            else -> "${n(rnd, 2, 9)}\\sqrt{${k * k}}"
        } to null
    }

    private fun letters(rnd: Random): Pair<String, String?> {
        val v = listOf("x", "y", "a", "b", "n")
        val a = v[rnd.nextInt(v.size)]
        val b = v.filter { it != a }[rnd.nextInt(v.size - 1)]
        return when (rnd.nextInt(4)) {
            0 -> "${n(rnd, 2, 9)}$a+${n(rnd, 1, 9)}$b"
            1 -> "($a+${n(rnd, 1, 9)})^{2}"
            2 -> "$a^{2}-$b^{2}"
            else -> "\\frac{$a+$b}{${n(rnd, 2, 9)}}"
        } to null
    }

    private fun linear(rnd: Random): Pair<String, String?> {
        val x = n(rnd, -9, 9)
        val a = n(rnd, 2, 9)
        val b = n(rnd, 1, 20)
        return "${a}x+$b=${a * x + b}" to "$x"
    }

    private fun linearFraction(rnd: Random): Pair<String, String?> {
        val a = n(rnd, 2, 6)
        val x = a * n(rnd, -5, 5)
        val b = n(rnd, 1, 9)
        return "\\frac{x}{$a}+$b=${x / a + b}" to "$x"
    }

    private fun quadratic(rnd: Random): Pair<String, String?> {
        val r1 = n(rnd, -6, 6)
        var r2 = n(rnd, -6, 6)
        if (r2 == r1) r2 = r1 + 1
        val b = -(r1 + r2)
        val c = r1 * r2
        fun term(k: Int, v: String) = when {
            k == 0 -> ""
            k == 1 -> "+$v"
            k == -1 -> "-$v"
            k > 0 -> "+$k$v"
            else -> "$k$v"
        }
        val cs = when {
            c > 0 -> "+$c"
            c < 0 -> "$c"
            else -> ""
        }
        return "x^{2}${term(b, "x")}$cs=0" to "$r1;$r2"
    }

    // ------------------------------------------------------------ rendering

    private fun render(bank: Bank, kind: String, id: String, source: String, roots: String?, rnd: Random): Synth? {
        val laid = runCatching { Typeset.layout(source, SIZE, measure) }.getOrNull() ?: return null
        val writer = bank.byWriter[rnd.nextInt(bank.byWriter.size)]
        val strokes = ArrayList<Ink>()
        val symbols = ArrayList<InkSymbol>()
        fun add(label: String, inks: List<Ink>) {
            val ids = inks.map { s -> strokes.size.also { strokes += Ink(s.xs, s.ys, strokes.size) } }
            symbols += InkSymbol(label, ids)
        }
        for (op in laid.ops) {
            when (op) {
                is Typeset.Op.Text -> {
                    var x = op.x
                    for (c in op.text) {
                        val w = measure.width(c.toString(), op.size, op.italic)
                        val glyph = glyph(c, x, op.y, w, op.size, writer, bank, rnd) ?: return null
                        add(glyph.first, glyph.second)
                        x += w
                    }
                }
                // A fraction bar is a dash in CROHME.
                is Typeset.Op.Line -> add("-", listOf(line(floatArrayOf(op.x1, op.y1, op.x2, op.y2), rnd)))
                is Typeset.Op.Poly -> when (op.points.size) {
                    // A round bracket: a handwritten one, as tall as the printed.
                    26 -> {
                        val open = op.points[12] < op.points[0]
                        val g = fitted(if (open) "(" else ")", op.points, writer, bank, rnd) ?: return null
                        add(g.first, g.second)
                    }
                    10 -> add("\\sqrt", listOf(line(op.points, rnd)))
                    else -> return null
                }
            }
        }
        if (strokes.isEmpty()) return null
        turn(strokes, rnd)
        moveTo(strokes)
        val sample = InkSample(id, source, strokes, symbols)
        val (h, ax) = FormulaBench.axis(sample)
        val (want, pageWant) = if (roots != null) {
            "" to roots
        } else {
            val expected = Latex.toParser(source)?.let { t -> runCatching { Parser.parse(t) }.getOrNull() } ?: return null
            val fp = FormulaValues.fingerprint(expected) ?: return null
            fp to if (Algebra.freeVars(expected).any { it !in Algebra.CONSTANTS }) "" else FormulaValues.values(expected, 0).orEmpty()
        }
        val formula = FormulaJudge.Formula("synthetic", id, kind, h, ax, want, pageWant, if ("\\frac" in source) Box.of(strokes).cy else ax, strokes)
        return Synth(formula, source, sample)
    }

    /** A handwritten [c] in the room [x]…[x] + [w] on the baseline [y]: its CROHME label and strokes. */
    private fun glyph(c: Char, x: Float, y: Float, w: Float, s: Float, writer: Map<String, List<List<Ink>>>, bank: Bank, rnd: Random): Pair<String, List<Ink>>? {
        if (c == '·') return "\\cdot" to listOf(dot(x + w / 2, y - Typeset.AXIS * s, s, rnd))
        val label = label(c) ?: return null
        val pool = writer[label] ?: bank.all[label] ?: return null
        // Where the printed symbol stands: digits and tall letters from the baseline up, small
        // letters to half that, signs on the axis.
        val axis = y - Typeset.AXIS * s
        val (top, bottom, fitWidth) = when {
            c in "−=" -> Triple(axis - 0.12f * s, axis + 0.12f * s, true)
            c in "+×" -> Triple(axis - 0.22f * s, axis + 0.22f * s, false)
            c in "()[]" -> Triple(y - 0.78f * s, y + 0.22f * s, false)
            c in X_HEIGHT -> Triple(y - 0.46f * s, y, false)
            c in DESCENDERS -> Triple(y - 0.46f * s, y + 0.24f * s, false)
            else -> Triple(y - 0.72f * s, y, false)
        }
        fun scale(b: Box) = if (fitWidth) 0.8f * w / max(b.width, 1f) else (bottom - top) / max(b.height, 1f)
        // A symbol that comes out much wider than its room (a "1" with a long foot, a symbol cut
        // wrongly in CROHME) would run into its neighbours: another one of the same, a few tries.
        var src = pool[rnd.nextInt(pool.size)]
        repeat(6) {
            val b = Box.of(src)
            if (fitWidth || b.width * scale(b) <= 1.15f * w) return@repeat
            src = pool[rnd.nextInt(pool.size)]
        }
        val box = Box.of(src)
        val jitter = 1f + 0.07f * rnd.nextGaussian().toFloat().coerceIn(-2f, 2f)
        val k = jitter * min(scale(box), if (fitWidth) Float.MAX_VALUE else 1.15f * w / max(box.width, 1f))
        val cx = x + w / 2 + 0.03f * s * rnd.nextGaussian().toFloat()
        val cy = (top + bottom) / 2 + 0.04f * s * rnd.nextGaussian().toFloat()
        return label to src.map { st ->
            Ink(FloatArray(st.xs.size) { cx + (st.xs[it] - box.cx) * k }, FloatArray(st.ys.size) { cy + (st.ys[it] - box.cy) * k }, 0)
        }
    }

    /** A handwritten [label] fitted into the box of [points]. */
    private fun fitted(label: String, points: FloatArray, writer: Map<String, List<List<Ink>>>, bank: Bank, rnd: Random): Pair<String, List<Ink>>? {
        val pool = writer[label] ?: bank.all[label] ?: return null
        val src = pool[rnd.nextInt(pool.size)]
        val box = Box.of(src)
        val xs = points.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = points.filterIndexed { i, _ -> i % 2 == 1 }
        val k = (ys.max() - ys.min()) / max(box.height, 1f)
        val cx = (xs.min() + xs.max()) / 2
        val cy = (ys.min() + ys.max()) / 2
        return label to src.map { st -> Ink(FloatArray(st.xs.size) { cx + (st.xs[it] - box.cx) * k }, FloatArray(st.ys.size) { cy + (st.ys[it] - box.cy) * k }, 0) }
    }

    /** A stroke along [points] (x, y pairs), drawn by hand: a few points between corners, a little wobble. */
    private fun line(points: FloatArray, rnd: Random): Ink {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until points.size / 2 - 1) {
            val (x1, y1, x2, y2) = listOf(points[2 * i], points[2 * i + 1], points[2 * i + 2], points[2 * i + 3])
            for (k in 0 until 6) {
                val t = k / 6f
                xs += x1 + (x2 - x1) * t + 0.6f * rnd.nextGaussian().toFloat()
                ys += y1 + (y2 - y1) * t + 0.6f * rnd.nextGaussian().toFloat()
            }
        }
        xs += points[points.size - 2]
        ys += points[points.size - 1]
        return Ink(xs.toFloatArray(), ys.toFloatArray(), 0)
    }

    private fun dot(x: Float, y: Float, s: Float, rnd: Random): Ink {
        val r = 0.05f * s
        return Ink(FloatArray(5) { x + r * cos(it * 1.3f) + rnd.nextFloat() }, FloatArray(5) { y + r * sin(it * 1.3f) }, 0)
    }

    /** The whole formula turned by up to ±2° about its middle, as written on a page not quite level. */
    private fun turn(strokes: MutableList<Ink>, rnd: Random) {
        val a = Math.toRadians((rnd.nextDouble() * 4 - 2)).toFloat()
        val box = Box.of(strokes)
        val (c, sn) = cos(a) to sin(a)
        for (i in strokes.indices) {
            val s = strokes[i]
            strokes[i] = Ink(
                FloatArray(s.xs.size) { box.cx + (s.xs[it] - box.cx) * c - (s.ys[it] - box.cy) * sn },
                FloatArray(s.xs.size) { box.cy + (s.xs[it] - box.cx) * sn + (s.ys[it] - box.cy) * c },
                s.order,
            )
        }
    }

    /** Placed where CROHME formulas are (left 100, top 200), as the device bench expects. */
    private fun moveTo(strokes: MutableList<Ink>) {
        val box = Box.of(strokes)
        for (i in strokes.indices) {
            val s = strokes[i]
            strokes[i] = Ink(FloatArray(s.xs.size) { 100f + s.xs[it] - box.left }, FloatArray(s.ys.size) { 200f + s.ys[it] - box.top }, s.order)
        }
    }

    /** The formulas as lines of formulas.tsv (FormulaExportTest), for the device bench. */
    fun exportLine(f: FormulaJudge.Formula): String {
        val strokes = f.strokes.joinToString(";") { k -> k.xs.indices.joinToString(",") { "%.1f %.1f".format(java.util.Locale.ROOT, k.xs[it], k.ys[it]) } }
        return listOf(f.set, f.id, f.kind, "%.2f".format(java.util.Locale.ROOT, f.h), "%.2f".format(java.util.Locale.ROOT, f.ax), f.want, f.pageWant, "%.2f".format(java.util.Locale.ROOT, f.pageAx), strokes).joinToString("\t")
    }

    /** The bank from CROHME 2013 Train (the training part: the test parts stay for the bench). */
    fun bank(root: File): Bank? {
        val dir = File(root, FormulaBench.SETS[0]).takeIf { it.isDirectory } ?: return null
        return Bank.of(InkMl.loadDir(dir, limit = 4000))
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
