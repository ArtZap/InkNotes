package dev.inknotes.math

import kotlin.math.abs

/**
 * How the formula bench judges one CROHME formula, the same on the device (FormulaReadTest, the
 * real reader) and on a computer (ReplayBenchTest, the device's readings replayed): RIGHT,
 * WRONG, UNSURE or NONE, for the formula selected and for it on a page with "=" after it.
 */
object FormulaJudge {
    /** One line of formulas.tsv (FormulaExportTest). */
    class Formula(val set: String, val id: String, val kind: String, val h: Float, val ax: Float, val want: String, val pageWant: String, val pageAx: Float, val strokes: List<Ink>)

    fun parse(line: String): Formula? {
        val p = line.split('\t')
        if (p.size < 9) return null
        val strokes = p[8].split(';').mapIndexed { i, s ->
            val pts = s.split(',').map { q -> q.trim().split(' ').map(String::toFloat) }
            Ink(FloatArray(pts.size) { pts[it][0] }, FloatArray(pts.size) { pts[it][1] }, i)
        }
        return Formula(p[0], p[1], p[2], p[3].toFloat(), p[4].toFloat(), p[5], p[6], p[7].toFloat(), strokes)
    }

    /** An equation (kind [EQUATION]): judged on the page as written, by its roots; not as a formula selected. */
    const val EQUATION = "equation"

    /** Whether [f] is judged as a formula selected (an equation is not). */
    fun hasSelected(f: Formula) = f.want.isNotEmpty()

    /** The formula selected, read as the app reads it, with the same check for "unsure"; and what it was read as. */
    fun selected(f: Formula, reader: RunReader): Pair<String, Expr?> {
        val got = runCatching { MathPage.read(f.strokes, f.h, f.ax, reader, MathPage.Prefer.FIRST) }.getOrNull()
        val o = when {
            got == null -> "NONE"
            MathLayout.Shift.CHECKS.any { shift ->
                val again = runCatching { MathPage.read(f.strokes, f.h, f.ax, reader, MathPage.Prefer.FIRST, shift = shift) }.getOrNull()
                again != null && again != got && !FormulaValues.same(again, got)
            } -> "UNSURE"
            right(got, f) -> "RIGHT"
            else -> "WRONG"
        }
        return o to got
    }

    fun right(got: Expr, f: Formula) = FormulaValues.fingerprint(got)?.let { FormulaValues.sameFingerprint(it, f.want) } == true

    /** The strokes of the formula with "=" drawn after it, and where the answer is to be. */
    fun onPage(f: Formula): Pair<List<Ink>, Float> {
        val x0 = f.strokes.maxOf { it.right } + 0.5f * f.h
        val w = 0.7f * f.h
        fun dash(y: Float, order: Int) = Ink(FloatArray(8) { x0 + w * it / 7 }, FloatArray(8) { y + (if (it % 3 == 0) 0.3f else 0f) }, order)
        return f.strokes + dash(f.pageAx - 0.16f * f.h, f.strokes.size) + dash(f.pageAx + 0.16f * f.h, f.strokes.size + 1) to x0
    }

    /** The formula on a page, "=" after it, the whole page analysed; null when it has no page answer to check. */
    fun page(f: Formula, reader: RunReader): String? {
        if (f.pageWant.isEmpty()) return null
        if (f.kind.startsWith(EQUATION)) return equation(f, reader)
        val (all, x0) = onPage(f)
        val a = runCatching { MathPage.analyze(all, reader, 4000f) }.getOrDefault(emptyList()).filter { !it.missing }
            .firstOrNull { it.x > x0 && abs(it.y - f.pageAx) < f.h }
        val text = (a?.answer as? Answer.Text)?.lines?.joinToString(";")
        return when {
            a == null || text == null || a.error -> "NONE"
            a.unsure -> "UNSURE"
            FormulaValues.close(text, f.pageWant) -> "RIGHT"
            else -> "WRONG"
        }
    }

    /**
     * An equation on a page as written (its own "=" and right side): the answer the page gives
     * for it, right when its numbers after "=" are the roots [Formula.pageWant] (";" between
     * them), in any order.
     */
    private fun equation(f: Formula, reader: RunReader): String {
        val a = runCatching { MathPage.analyze(f.strokes, reader, 4000f) }.getOrDefault(emptyList()).filter { !it.missing }.firstOrNull()
        val text = (a?.answer as? Answer.Text)?.lines ?: return "NONE"
        if (a.error) return "NONE"
        if (a.unsure) return "UNSURE"
        fun num(t: String) = t.replace(Printer.MINUS, "-").replace(',', '.').toDoubleOrNull()
        val got = text.map { line -> num(line.substringAfterLast('=', "").trim()) ?: return "WRONG" }.sorted()
        val want = f.pageWant.split(';').map { num(it) ?: return "WRONG" }.sorted()
        val right = got.size == want.size && got.indices.all { i -> FormulaValues.close(got[i].toString(), want[i].toString()) }
        return if (right) "RIGHT" else "WRONG"
    }
}
