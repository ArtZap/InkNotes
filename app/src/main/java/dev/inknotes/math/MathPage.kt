package dev.inknotes.math

import dev.inknotes.data.tr
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * An answer to draw on the page: [x] where it starts, [y] the middle of the line, [size] the
 * height of the handwriting it continues. [error] marks messages such as "деление на ноль".
 */
/** An answer at (x, y): the middle of its left edge, or the top-left corner of a graph. */
class PageAnswer(
    val x: Float,
    val y: Float,
    val size: Float,
    val answer: Answer,
    val error: Boolean = false,
    /** How it was solved, shown when the answer is tapped; null when there is nothing to show. */
    val steps: List<String>? = null,
    /**
     * Other answers the formula could have, read a little differently: when not empty, the
     * answer is not sure (shown grey with "?") and these are offered when it is tapped.
     */
    val others: List<String> = emptyList(),
    /**
     * Nothing came out where an answer was expected (an "=" with nothing after it, a y = … with
     * no graph): a mark to tap instead of an empty place.
     */
    val missing: Boolean = false,
    /** What was read, to correct it (null: this answer cannot be corrected). */
    val fix: Fix? = null,
) {
    val unsure get() = others.isNotEmpty()
}

/**
 * The formula of an answer, to correct how it was read: the strokes left of its "=", right
 * of it (null for `expr =`), the "=" itself, the line's height and middle, and the page's
 * definitions above it. [graph]: a y = … or z = … that should give a graph.
 */
class Fix(
    val left: List<Ink>,
    val right: List<Ink>?,
    val equals: List<Ink>,
    val h: Float,
    val axis: Float,
    val env: Env,
    val graph: Boolean = false,
    /** What the page read left and right of "=", to start from. */
    val leftRead: Expr? = null,
    val rightRead: Expr? = null,
    /**
     * A system: its equations, each a Fix of its own (left, "=", right); this Fix's own sides
     * are the first equation's. Empty for one formula.
     */
    val system: List<Fix> = emptyList(),
) {
    /** The formulas to correct: the equations of a system, or this one. */
    val rows: List<Fix> get() = system.ifEmpty { listOf(this) }
}

/**
 * A number given to a letter on the page (a = 3): it can be turned on a scale beside it, and
 * the lines that use it change at once. [written]: the number as read off the ink; [value]:
 * the one in use, set on the scale when [set]. [key]: [Correct.fingerprint] of the number's
 * strokes, where the set value is kept; the box ([left]…[bottom]) is those strokes.
 */
class Knob(
    val name: String,
    val value: Double,
    val written: Double,
    val set: Boolean,
    val key: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val h: Float,
) {
    /** The step of one mark on the scale: the last written digit (1 for 3, 0.1 for 2.5). */
    val step: Double get() = stepOf(written)

    companion object {
        fun stepOf(v: Double): Double {
            for (d in 0..3) {
                val k = Math.pow(10.0, d.toDouble())
                if (abs(v * k - Math.rint(v * k)) < 1e-6) return 1 / k
            }
            return 0.001
        }

        /** A plain number, or one with a minus: what a scale can turn. */
        fun constant(e: Expr): Double? = when (e) {
            is Expr.Num -> e.v
            is Expr.Mul -> if (e.factors.size == 2 && e.factors.all { it is Expr.Num }) e.factors.fold(1.0) { p, f -> p * (f as Expr.Num).v } else null
            else -> null
        }?.takeIf { it.isFinite() }
    }
}

/**
 * Finds the math on a page: every handwritten "=" (two short stacked dashes) with what is
 * written to its left and right. Read top to bottom:
 * - `expr =` with nothing after it gets an answer;
 * - `a = …` and `f(x) = …` define a variable or function for the lines below;
 * - equations to the right of a tall "{" form a system, solved to the right of it.
 */
object MathPage {

    class Equals(val top: Ink, val bottom: Ink, val extra: List<Ink> = emptyList()) {
        val left = min(top.left, bottom.left)
        val right = max(top.right, bottom.right)
        val cy = (top.cy + bottom.cy) / 2
        val width = right - left
        var h = 0f
    }

    /** Debug log of every step (set by the probe in tests); null in the app. */
    @Volatile
    var trace: ((String) -> Unit)? = null

    /**
     * The answers on a page. [pinned]: formula text the writer set by hand for strokes, by
     * their [Correct.fingerprint]; taken instead of reading them.
     */
    fun analyze(
        strokes: List<Ink>,
        reader: RunReader,
        pageWidth: Float = 1000f,
        pinned: Map<String, String> = emptyMap(),
        /** Numbers set on a scale, by [Correct.fingerprint] of their strokes. */
        scaled: Map<String, Double> = emptyMap(),
        /** Filled with the page's numbers that can be turned on a scale. */
        knobs: MutableList<Knob>? = null,
    ): List<PageAnswer> {
        fun pin(side: List<Ink>): Expr? = if (pinned.isEmpty() || side.isEmpty()) null else pinned[Correct.fingerprint(side)]?.let(::parseText)
        val ink = strokes.filter { it.height < MAX_STROKE && it.width < MAX_STROKE }
        val eqs = findEquals(ink).take(MAX_EQUALS)
        val eqStrokes0 = eqs.flatMap { listOf(it.top, it.bottom) + it.extra }.toSet()
        // "<", ">", "≤", "≥": anchors of their own, solved by intervals at the end.
        val ineqs = findInequalities(ink.filter { it !in eqStrokes0 }).take(MAX_EQUALS)
        if (eqs.isEmpty() && ineqs.isEmpty()) return emptyList()
        val eqStrokes = eqStrokes0 + ineqs.flatMap { (a, _) -> listOf(a.top) + a.extra }
        val rest = ink.filter { it !in eqStrokes }
        for (eq in eqs) eq.h = lineHeight(eq, rest)
        for ((a, _) in ineqs) a.h = lineHeight(a, rest)
        trace?.invoke("equals: " + eqs.joinToString { "(${it.left.toInt()},${it.cy.toInt()} h=${it.h.toInt()})" })

        // Systems: a "{" with equations to its right.
        val braces = braces(rest)
        trace?.invoke("braces: " + braces.map { "(${it.left.toInt()},${it.top.toInt()}-${it.bottom.toInt()})" })
        val systemOf = HashMap<Equals, Ink>()
        for (b in braces) {
            for (eq in eqs) {
                if (eq.cy in b.top..b.bottom && eq.left > b.right && eq.left < b.right + 15 * eq.h && b.height > 1.4f * eq.h) {
                    val prev = systemOf[eq]
                    if (prev == null || prev.right < b.right) systemOf[eq] = b
                }
            }
        }

        // Writing order: by line, then left to right.
        val ordered = eqs.sortedWith { a, b ->
            if (abs(a.cy - b.cy) < 0.5f * max(a.h, b.h)) a.left.compareTo(b.left) else a.cy.compareTo(b.cy)
        }.take(MAX_EQUALS)
        // First the definitions (a = 5, f(x) = …): they hold for the whole page. A line uses
        // the nearest definition above it, or else the first one below (a = 2 written later).
        /** An equation read off the page; [again] reads it anew with moved thresholds (null: same layout). */
        class Def(val index: Int, val left: Expr, val right: Expr, val again: ((MathLayout.Shift) -> Pair<Expr, Expr>?)? = null, val leftInk: List<Ink> = emptyList(), val rightInk: List<Ink> = emptyList())
        val defs = ArrayList<Def>()
        // Equations outside a "{" (x³ = 6x² − 12x + 11): solved when one unknown is left.
        val single = ArrayList<Def>()
        val trailing = ArrayList<Pair<Int, List<Ink>>>()
        // Graphs expected (y = …, f(x) = …, z = …) whose right side did not read.
        val noGraph = ArrayList<Triple<Int, List<Ink>, List<Ink>>>()
        for ((i, eq) in ordered.withIndex()) {
            if (systemOf[eq] != null) continue
            val left = side(eq, -1, eqs, rest, limit = null)
            if (left.isEmpty() || left.size > MAX_SIDE) continue
            val right = side(eq, 1, eqs, rest, limit = null)
            if (right.size > MAX_SIDE) continue
            trace?.invoke("= at (${eq.left.toInt()},${eq.cy.toInt()}) h=${eq.h.toInt()}: left ${left.size} strokes, right ${right.size}")
            if (right.isEmpty() || isQuestion(right, eq, reader)) {
                trailing += i to left
                continue
            }
            val pl = pin(left)
            val pr = pin(right)
            // f(x) among the readings ("flx" first) is a function being defined.
            val l = pl ?: readings(left, eq.h, eq.cy, reader, Prefer.FIRST).let { all ->
                all.firstOrNull { it is Expr.Apply && graphParam(it) != null }
                    // A lone number before "=" with a formula after it defines nothing: the
                    // reader took the z or y of z = x² + y² for a 2 or a 4.
                    ?: all.firstOrNull { it is Expr.Sym && it.name.lowercase() in GRAPH_LETTERS }?.takeIf { all.firstOrNull() is Expr.Num }
                    ?: all.firstOrNull()
            } ?: continue
            // y = 2x + 1 is a formula in x: "2+1" (fewest letters) would lose it.
            // y = 5x − 1: a formula in x ("5x−1", not "bx−I" or "5−1").
            val surface = (l as? Expr.Sym)?.name?.equals("z", ignoreCase = true) == true
            val r = pr ?: graphParam(l)?.let { v -> readings(right, eq.h, eq.cy, reader, Prefer.GRAPH, variable = v.ifEmpty { "x" }).firstOrNull() }
                // z = x² + y²: about letters, not the reading with fewest of them.
                ?: (if (surface) read(right, eq.h, eq.cy, reader, Prefer.SURFACE) else null)
                ?: read(right, eq.h, eq.cy, reader, Prefer.NUMBERS)
            if (r == null) {
                // y = … that does not read: a mark where its graph would be.
                if (graphParam(l) != null || surface) noGraph += Triple(i, left, right)
                continue
            }
            // The same reading, the thresholds moved: for "unsure" (an equation is solved only
            // when read the same; a side laid out the same is taken as it was).
            val again = { shift: MathLayout.Shift ->
                val l2 = read(left, eq.h, eq.cy, reader, Prefer.FIRST, shift = shift)
                val r2 = read(right, eq.h, eq.cy, reader, if (surface) Prefer.SURFACE else Prefer.NUMBERS, shift = shift)
                if (l2 == null && r2 == null) null else (l2 ?: l) to (r2 ?: r)
            }
            // Set by hand: read as set, not otherwise.
            val pinnedHere = pl != null || pr != null
            // a = 3: a number that can be turned on a scale; the value set there replaces it.
            val written = if (l is Expr.Sym && graphParam(l) == null && Calc.isDefinition(l)) Knob.constant(r) else null
            if (written != null) {
                val key = Correct.fingerprint(right)
                val v = scaled[key]
                knobs?.add(Knob((l as Expr.Sym).name, v ?: written, written, v != null, key, right.minOf { it.left }, right.minOf { it.top }, right.maxOf { it.right }, right.maxOf { it.bottom }, eq.h))
                if (v != null) {
                    defs += Def(i, l, Expr.Num(v), leftInk = left, rightInk = right)
                    continue
                }
            }
            if (Calc.isDefinition(l)) defs += Def(i, l, r, leftInk = left, rightInk = right) else single += Def(i, l, r, again.takeIf { !pinnedHere }, left, right)
        }
        val definedNames = defs.mapNotNull { Calc.definedName(it.left) }.toSet()
        fun envAt(index: Int): Env {
            val env = Env()
            for (d in defs) if (d.index < index) runCatching { Calc.define(d.left, d.right, env) }
            for (d in defs) {
                val name = Calc.definedName(d.left) ?: continue
                if (d.index > index && env.variable(name) == null && env.function(name) == null) runCatching { Calc.define(d.left, d.right, env) }
            }
            return env
        }

        /** The one letter [d] is to be solved for, or null (none, or several). */
        fun unknownOf(d: Def): String? {
            val env = envAt(d.index)
            return Algebra.freeVars(Expr.sub(d.left, d.right)).filter { env.variable(it) == null }.singleOrNull()
        }
        val out = ArrayList<PageAnswer>()
        // Equations answered by an "x =" under them rather than beside themselves.
        val asked = HashSet<Def>()
        for ((i, left) in trailing) {
            val eq = ordered[i]
            val known = { name: String -> definedNames.any { it.equals(name, ignoreCase = true) } }
            val after = side(eq, 1, eqs, rest, limit = null).maxOfOrNull { it.right } ?: eq.right
            val at = after + 0.4f * eq.h
            // Set by hand as something the app does not compute (a limit, a sum): said so,
            // not read again from the strokes.
            if (pinned.isNotEmpty() && left.isNotEmpty()) {
                val raw = pinned[Correct.fingerprint(left)]
                if (raw != null && parseText(raw) == null) {
                    val after0 = side(eq, 1, eqs, rest, limit = null).maxOfOrNull { it.right } ?: eq.right
                    out += PageAnswer(after0 + 0.4f * eq.h, eq.cy, eq.h, Answer.Text(listOf(tr("не умею считать такое", "cannot compute this"))), error = true,
                        fix = Fix(left, null, listOf(eq.top, eq.bottom) + eq.extra, eq.h, eq.cy, envAt(i)))
                    continue
                }
            }
            // The strokes the formula was read from: without a word before it ("Ответ: 2 + 3 =").
            // The correction window gets these, so that it shows what the page read.
            val pinnedWhole = pin(left)
            val trimmed = if (pinnedWhole != null) null else readTrimmedPart(left, eq, reader, known)
            val used = trimmed?.second ?: left
            val pl = pinnedWhole ?: pin(used).takeIf { used !== left }
            var l = pl ?: trimmed?.first
            fun fix(read: Expr?) = Fix(used, null, listOf(eq.top, eq.bottom) + eq.extra, eq.h, eq.cy, envAt(i), leftRead = read)
            if (l == null) {
                // Nothing after "=" though something is before it: a mark to tap, not a blank.
                out += PageAnswer(at, eq.cy, eq.h, Answer.Text(listOf("?")), missing = true, fix = fix(null))
                continue
            }
            // "3 =" has nothing to compute.
            if (l is Expr.Num) continue
            // A misread letter that is also a unit (g for 4, B for 8) makes "8,5 + g" an error:
            // a reading that computes wins over it, shown unsure with the error as the other answer.
            var fellBack: String? = null
            if (pl == null) {
                val error = try {
                    Calc.evaluate(l, envAt(i))
                    null
                } catch (e: MathError) {
                    e.message ?: "?"
                }
                if (error != null) {
                    val first = l
                    val better = readings(used, eq.h, eq.cy, reader, Prefer.NUMBERS, known).firstOrNull { e ->
                        e != first && runCatching { Calc.evaluate(e, envAt(i)) != null }.getOrDefault(false)
                    }
                    if (better != null) {
                        l = better
                        fellBack = error
                    }
                }
            }
            // "x =" under an equation in x: its roots.
            val asking = (l as? Expr.Sym)?.takeIf { envAt(i).variable(it.name) == null }?.let { sym ->
                single.filter { it.index < i && ordered[it.index].cy < eq.cy && unknownOf(it)?.equals(sym.name, ignoreCase = true) == true }
                    .maxByOrNull { it.index }
            }
            out += try {
                if (asking != null) {
                    asked += asking
                    val equation = listOf(asking.left to asking.right)
                    PageAnswer(at, eq.cy, eq.h, valuesOnly(Calc.solve(equation, envAt(asking.index))), steps = Steps.of(equation, envAt(asking.index)))
                } else {
                    val answer = Calc.evaluate(l, envAt(i))
                    if (answer == null) {
                        PageAnswer(at, eq.cy, eq.h, Answer.Text(listOf("?")), missing = true, fix = fix(l))
                    } else {
                        val others = if (pl != null) emptyList() else otherAnswers(answer) { shift -> readTrimmed(left, eq, reader, known, shift)?.let { Calc.evaluate(it, envAt(i)) } }
                            .ifEmpty { if (readSure(used, eq, reader)) emptyList() else otherReadings(answer, used, eq, reader, known) { Calc.evaluate(it, envAt(i)) } }
                            .ifEmpty { listOfNotNull(fellBack) }
                        PageAnswer(at, eq.cy, eq.h, answer, others = others, fix = fix(l))
                    }
                }
            } catch (e: MathError) {
                PageAnswer(at, eq.cy, eq.h, Answer.Text(listOf(e.message ?: "?")), error = true, fix = fix(l))
            }
        }
        // y = x² − 3 and f(x) = …: the graph, beside the line where that place is free, else
        // under it; never over the ink or another graph.
        class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)
        val graphs = ArrayList<Area>()
        // Functions with different names written on lines right under one another, in the same
        // letter, share one graph (f(x) = x², then g(x) = 2x + 1): drawn by the first, the
        // others in it. A list of y = … lines keeps a graph each: that is how they are written
        // to be looked at one by one.
        fun side(e: Expr): String = when (e) {
            is Expr.Apply -> e.name + "'".repeat(e.primes) + e.args.joinToString(", ", "(", ")") { side(it) }
            else -> runCatching { Printer.expr(e) }.getOrDefault("?")
        }
        fun curveName(left: Expr, right: Expr) = side(left) + " = " + side(right)
        val joined = HashMap<Def, List<Def>>()
        val inOthers = HashSet<Def>()
        run {
            fun fn(d: Def) = graphParam(d.left)?.let { p -> runCatching { Calc.function(d.right, envAt(d.index), p.ifEmpty { null }) }.getOrNull() }
            val plain = defs.filter { graphParam(it.left) != null }.sortedBy { ordered[it.index].cy }
            var k = 0
            while (k < plain.size) {
                val group = arrayListOf(plain[k])
                val letter = fn(plain[k])?.variable
                var j = k + 1
                while (letter != null && j < plain.size && group.size < MAX_CURVES) {
                    val prev = ordered[group.last().index]
                    val next = ordered[plain[j].index]
                    val near = next.cy - prev.cy < 2.6f * max(prev.h, next.h) && abs(next.left - prev.left) < 6f * max(prev.h, next.h)
                    val named = group.none { it.left == plain[j].left }
                    if (!near || !named || fn(plain[j])?.variable != letter) break
                    group += plain[j]
                    j++
                }
                if (group.size > 1) {
                    joined[group[0]] = group.drop(1)
                    inOthers += group.drop(1)
                }
                k = if (group.size > 1) j else k + 1
            }
        }
        for (d in defs) {
            if (d in inOthers) continue
            val param = graphParam(d.left)
            val isSurface = (d.left as? Expr.Sym)?.name?.equals("z", ignoreCase = true) == true
            val eq = ordered[d.index]
            val fix = Fix(d.leftInk, d.rightInk, listOf(eq.top, eq.bottom) + eq.extra, eq.h, eq.cy, envAt(d.index), graph = true, leftRead = d.left, rightRead = d.right)
            val graph: Answer = when {
                param != null && d in joined -> runCatching {
                    val group = listOf(d) + joined.getValue(d)
                    val fns = group.mapNotNull { g -> graphParam(g.left)?.let { p -> Calc.function(g.right, envAt(g.index), p.ifEmpty { null }) } }
                    Plot.ofMany(fns.map { it.f }, fns[0].variable, fns.any { it.trig }, group.map { curveName(it.left, it.right) })?.withExprs(fns.map { it.expr })
                }.getOrNull()
                param != null -> runCatching { Calc.graph(d.right, envAt(d.index), param.ifEmpty { null })?.titled(listOf(curveName(d.left, d.right))) }.getOrNull()
                // z = x² + y²: a surface.
                isSurface -> runCatching { Calc.surface(d.right, envAt(d.index))?.let { sf -> sf.expr?.let { e -> sf.withExpr(e, sf.trig, curveName(d.left, d.right)) } ?: sf } }.getOrNull()
                else -> null
            } ?: run {
                // A graph was meant but none came out: a mark beside the line.
                if (param != null || isSurface) {
                    val end = d.rightInk.maxOfOrNull { it.right } ?: eq.right
                    out += PageAnswer(end + 0.6f * eq.h, eq.cy, eq.h, Answer.Text(listOf("?")), missing = true, fix = fix)
                }
                null
            } ?: continue
            // The lines of the functions it shows: beside all of them.
            val lineInk = (listOf(d) + joined[d].orEmpty()).flatMap { g ->
                val e = ordered[g.index]
                side(e, -1, eqs, rest, limit = null) + side(e, 1, eqs, rest, limit = null) + listOf(e.top, e.bottom)
            }
            val lineLeft = lineInk.minOfOrNull { it.left } ?: eq.left
            val lineRight = max(eq.right, lineInk.maxOfOrNull { it.right } ?: eq.right)
            val lineTop = min(eq.top.top, lineInk.minOfOrNull { it.top } ?: eq.cy)
            val lineBottom = max(eq.bottom.bottom, lineInk.maxOfOrNull { it.bottom } ?: eq.cy)
            // As tall as the formula it belongs to (a fraction is taller than its letters),
            // within sizes that read well on the page.
            val unit = pageWidth / 1000f
            val full = max(eq.h, 0.45f * (lineBottom - lineTop)).coerceIn(GRAPH_MIN * unit, GRAPH_MAX * unit)
            fun boxAt(size: Float, x: Float, y: Float) = Area(x, y, x + size * Plot.WIDTH, y + size * Plot.HEIGHT)
            fun noInk(a: Area) = ink.none { it.right > a.left && it.left < a.right && it.bottom > a.top && it.top < a.bottom }
            /** How much handwriting a graph at [a] would cover (area of the stroke boxes under it). */
            fun inkUnder(a: Area) = ink.sumOf { (max(0f, min(it.right, a.right) - max(it.left, a.left)) * max(0f, min(it.bottom, a.bottom) - max(it.top, a.top))).toDouble() }
            fun noGraph(a: Area) = graphs.none { it.right > a.left && it.left < a.right && it.bottom > a.top && it.top < a.bottom }
            // Beside the line (level, then a little lower or higher), under it, then further right.
            fun places(size: Float): List<Area> {
                val w = size * Plot.WIDTH
                val h = size * Plot.HEIGHT
                val midY = (lineTop + lineBottom) / 2 - h / 2
                val x0 = lineRight + 1.0f * eq.h
                return buildList {
                    for (k in listOf(0f, 0.3f, -0.3f, 0.6f, -0.6f)) add(boxAt(size, x0, midY + k * h))
                    // Formulas close under each other: a little further down or up beside the line.
                    for (k in listOf(0.9f, -0.9f, 1.2f)) add(boxAt(size, x0, midY + k * h))
                    add(boxAt(size, lineLeft, lineBottom + 0.5f * eq.h))
                    for (k in 1..4) for (dy in listOf(0f, 0.4f, -0.4f)) add(boxAt(size, x0 + k * (w + 0.5f * eq.h), midY + dy * h))
                }.filter { it.right <= pageWidth && it.top >= 0f }
            }
            // Free paper first, smaller if need be (formulas written close under each other
            // leave little room); else at least not over another graph — never one on another.
            val sizes = listOf(full, 0.75f * full, GRAPH_MIN * unit, 0.75f * GRAPH_MIN * unit, 0.6f * GRAPH_MIN * unit).distinct()
            val box = sizes.firstNotNullOfOrNull { sz -> places(sz).firstOrNull { noInk(it) && noGraph(it) } }
                ?: sizes.flatMap(::places).filter(::noGraph).minByOrNull(::inkUnder)
                ?: continue
            val size = (box.right - box.left) / Plot.WIDTH
            val gx = box.left
            val gy0 = box.top
            // Near the top of the page the box goes down rather than off the page.
            val gy = max(gy0, 0.3f * eq.h)
            graphs += Area(gx, gy, box.right, gy + (box.bottom - box.top))
            trace?.invoke("graph: y = ${d.right} at ($gx, $gy)")
            out += PageAnswer(gx, gy, size, graph, fix = fix)
        }
        for (d0 in single) {
            if (d0 in asked) continue
            // A link of a chain of working (F₁ = 8a·6a = 68·51 = 3468): another "=" right
            // before or after it on the line. Not an equation to solve: its sides are the same
            // value written two ways, and solving a misreading of them gives nonsense.
            if (chained(ordered[d0.index], eqs)) {
                trace?.invoke("chained: ${d0.left} = ${d0.right}")
                continue
            }
            // Two unknowns as read (ga + 6 = 2: g and a): the readings with one letter, as for a
            // system (9a + 6 = 2), shown unsure with why.
            var rescued: List<String> = emptyList()
            fun again(noSolution: Boolean): Def? {
                if (d0.again == null) return null
                val (l, r, why) = rescue(d0.left, d0.right, d0.leftInk, d0.rightInk, ordered[d0.index], reader, envAt(d0.index), noSolution) ?: return null
                rescued = listOf(why)
                return Def(d0.index, l, r, null, d0.leftInk, d0.rightInk)
            }
            var d = if (unknownOf(d0) != null) d0 else again(noSolution = false) ?: continue
            val eq = ordered[d.index]
            val after = side(eq, 1, eqs, rest, limit = null).maxOfOrNull { it.right } ?: continue
            val at = after + 1.0f * eq.h
            trace?.invoke("equation: ${d.left} = ${d.right}")
            // An identity (a result written out by hand: (x³)' = 3x²) or an unreadable line says
            // nothing worth showing; errors are kept for a "{" only.
            var answer = runCatching { Calc.solve(listOf(d.left to d.right), envAt(d.index)) }.getOrNull() ?: continue
            // No solutions as read (x² + 40 − 1 = 0 for x² + 4x − 1 = 0): an exercise has some,
            // so the other readings are tried; shown unsure.
            if (d === d0 && (answer as? Answer.Text)?.lines == listOf(tr("нет решений", "no solutions"))) {
                val other = again(noSolution = true)
                val otherAnswer = other?.let { o -> runCatching { Calc.solve(listOf(o.left to o.right), envAt(o.index)) }.getOrNull() }
                if (other != null && otherAnswer != null) {
                    d = other
                    answer = otherAnswer
                }
            }
            if ((answer as? Answer.Text)?.lines == listOf(tr("∞ решений", "∞ solutions"))) continue
            val others = rescued.ifEmpty {
                d.again?.let { again ->
                    otherAnswers(answer) { shift -> again(shift)?.let { (l2, r2) -> Calc.solve(listOf(l2 to r2), envAt(d.index)) } }
                }.orEmpty()
            }
            val fix = Fix(d.leftInk, d.rightInk, listOf(eq.top, eq.bottom) + eq.extra, eq.h, eq.cy, envAt(d.index), leftRead = d.left, rightRead = d.right)
            out += PageAnswer(at, eq.cy, eq.h, answer, steps = Steps.of(listOf(d.left to d.right), envAt(d.index)), others = others, fix = fix)
        }
        for ((i, left, right) in noGraph) {
            val eq = ordered[i]
            val end = right.maxOfOrNull { it.right } ?: eq.right
            out += PageAnswer(end + 0.6f * eq.h, eq.cy, eq.h, Answer.Text(listOf("?")), missing = true,
                fix = Fix(left, right, listOf(eq.top, eq.bottom) + eq.extra, eq.h, eq.cy, envAt(i), graph = true))
        }
        val done = HashSet<Ink>()
        for ((i, eq) in ordered.withIndex()) {
            val brace = systemOf[eq] ?: continue
            if (!done.add(brace)) continue
            val members = ordered.filter { systemOf[it] === brace }
            solveSystem(brace, members, eqs, rest, reader, envAt(i), ::pin)?.let { out += it }
        }
        val anchors = eqs + ineqs.map { it.first }
        for ((a, rel) in ineqs) {
            val left = side(a, -1, anchors, rest, limit = null)
            val right = side(a, 1, anchors, rest, limit = null)
            if (left.isEmpty() || right.isEmpty() || left.size > MAX_SIDE || right.size > MAX_SIDE) continue
            // Worked on further (D ≥ ∛(32·23,81/3,14) = 6,2): the writer has its value already,
            // and a misread side (the 3 of the root lost) would be solved into nonsense.
            if (Tune.workedInequalities > 0f && eqs.any { o ->
                    abs(o.cy - a.cy) < 0.6f * max(a.h, o.h) &&
                        (o.left - right.maxOf { it.right } in -0.5f * a.h..CHAIN_GAP * a.h || left.minOf { it.left } - o.right in -0.5f * a.h..CHAIN_GAP * a.h)
                }) {
                trace?.invoke("inequality worked on: skipped")
                continue
            }
            val l = read(left, a.h, a.cy, reader, Prefer.FIRST) ?: continue
            val r = read(right, a.h, a.cy, reader, Prefer.FIRST) ?: continue
            val env = envAt(ordered.count { it.cy < a.cy })
            trace?.invoke("inequality: $l ${rel.sign} $r")
            // A sign that is not one (a letter that looked like <) gives nothing to solve: no answer.
            val text = runCatching {
                val (residuals, inDegrees) = Calc.residuals(listOf(l to r), env)
                if (inDegrees) return@runCatching null
                val x = Algebra.freeVars(residuals[0]).singleOrNull() ?: return@runCatching null
                Inequality.solve(residuals[0], x, rel)
            }.getOrNull() ?: continue
            out += PageAnswer(right.maxOf { it.right } + 1.0f * a.h, a.cy, a.h, Answer.Text(listOf(text)))
        }
        return out
    }

    /**
     * "<" and ">": one stroke bent at a sharp point, both arms straight, the point on the
     * left (or right) of the two ends; with a dash under it "≤" and "≥".
     */
    fun findInequalities(ink: List<Ink>): List<Pair<Equals, Inequality.Rel>> {
        val out = ArrayList<Pair<Equals, Inequality.Rel>>()
        for (s in ink) {
            if (s.height < MIN_H || s.height > MAX_SIGN || s.xs.size < 3) continue
            if (s.width < 0.35f * s.height || s.width > 1.8f * s.height) continue
            val less = chevron(s, pointLeft = true)
            val more = !less && chevron(s, pointLeft = false)
            if (!less && !more) continue
            // The point must be free: k, K and 4 have a bent stroke touching an upright.
            val vx = if (less) s.left else s.right
            val vi = s.xs.indices.minBy { abs(s.xs[it] - vx) }
            val tol = 0.18f * s.height
            if (ink.any { o -> o !== s && o.xs.indices.any { k -> abs(o.xs[k] - s.xs[vi]) < tol && abs(o.ys[k] - s.ys[vi]) < tol } }) continue
            // An arrowhead: a long line (an axis) runs up to its point from behind, between its
            // arms (a fraction bar after a sign starts beyond the point instead).
            if (Tune.arrowHeads > 0f) {
                val vxp = s.xs[vi]
                val vyp = s.ys[vi]
                val reach = 1.0f * s.height
                if (ink.any { o ->
                        o !== s && max(o.width, o.height) > 3f * s.height && (if (less) o.cx > vxp else o.cx < vxp) &&
                            o.xs.indices.any { k -> abs(o.xs[k] - vxp) < reach && abs(o.ys[k] - vyp) < 0.4f * s.height }
                    }) continue
            }
            val under = ink.firstOrNull { d ->
                d !== s && d.isDash && d.width in 0.5f * s.width..1.8f * s.width && d.top > s.bottom - 0.15f * s.height &&
                    d.top - s.bottom < 0.6f * s.height && d.cx in s.left..s.right
            }
            val rel = when {
                less && under != null -> Inequality.Rel.LE
                less -> Inequality.Rel.LT
                under != null -> Inequality.Rel.GE
                else -> Inequality.Rel.GT
            }
            out += Equals(s, s, listOfNotNull(under)) to rel
        }
        return out
    }

    private fun chevron(s: Ink, pointLeft: Boolean): Boolean {
        val v = if (pointLeft) s.xs.indices.minBy { s.xs[it] } else s.xs.indices.maxBy { s.xs[it] }
        val last = s.xs.lastIndex
        if (v < 1 || v > last - 1) return false
        val x0 = s.xs[0]
        val x1 = s.xs[last]
        val vx = s.xs[v]
        val vy = s.ys[v]
        val reach = 0.5f * s.width
        val ends = if (pointLeft) x0 > vx + reach && x1 > vx + reach else x0 < vx - reach && x1 < vx - reach
        if (!ends || abs(x0 - x1) > 0.5f * s.width) return false
        // One end above the point, the other below, both clearly.
        val d0 = s.ys[0] - vy
        val d1 = s.ys[last] - vy
        if (d0 * d1 >= 0 || abs(d0) < 0.25f * s.height || abs(d1) < 0.25f * s.height) return false
        fun part(from: Int, to: Int) = Ink(s.xs.copyOfRange(from, to + 1), s.ys.copyOfRange(from, to + 1), s.order)
        return MathLayout.straight(part(0, v), 0.16f) && MathLayout.straight(part(v, last), 0.16f)
    }

    /**
     * What makes a definition a graph: "" for `y = …` (any one letter), the argument for
     * `f(x) = …`; null for other definitions (a = 5).
     */
    internal fun graphParam(e: Expr): String? = when {
        (e as? Expr.Sym)?.name?.equals("y", ignoreCase = true) == true -> ""
        e is Expr.Apply && e.primes == 0 && e.args.size == 1 && e.args[0] is Expr.Sym -> (e.args[0] as Expr.Sym).name
        else -> null
    }

    /** "x₁ = 1 − √2 ≈ −0,41" → "1 − √2 ≈ −0,41": after a written "x =" only the values. */
    private fun valuesOnly(a: Answer): Answer {
        if (a !is Answer.Text) return a
        val lines = a.lines.map { line -> if (" = " in line && line.indexOf(" = ") < 6) line.substringAfter(" = ") else line }
        return Answer.Text(if (lines.size > 1 && lines.all { it.length < 24 }) listOf(lines.joinToString("; ")) else lines)
    }

    private fun solveSystem(brace: Ink, members: List<Equals>, eqs: List<Equals>, rest: List<Ink>, reader: RunReader, env: Env, pin: (List<Ink>) -> Expr? = { null }): PageAnswer? {
        // Readings of every side; then the combination with as many unknowns as equations.
        val options = ArrayList<List<Expr>>()
        var bottom = brace.bottom
        var right = brace.right
        var h = 0f
        // The brace itself (one stroke or its two halves) is no part of a side.
        val withoutBrace = rest.filter { st ->
            !(st.height > 0.4f * brace.height && st.left >= brace.left - 1 && st.right <= brace.right + 1 && st.top >= brace.top - 1 && st.bottom <= brace.bottom + 1)
        }
        val sides = members.map { eq ->
            // Bounded by the brace where the equation is: its hooks at the top and bottom
            // reach farther right, over the first letters (a/b of the top line).
            val l = side(eq, -1, eqs, withoutBrace, limit = braceAt(brace, eq.cy, eq.h))
            val r = side(eq, 1, eqs, rest, limit = null)
            if (l.isEmpty() || r.isEmpty() || l.size > MAX_SIDE || r.size > MAX_SIDE) return null
            Triple(eq, l, r)
        }
        // A side set by hand is read only so.
        val lefts = sides.map { (eq, l, _) -> pin(l)?.let { listOf(it) } ?: readings(l, eq.h, eq.cy, reader, Prefer.LETTERS, maxLetters = members.size).ifEmpty { return null } }
        // The unknowns are the letters of the left sides (a = …, б = …): on the right a run that
        // reads as one of them or as digits is that letter (3б, not 36: б looks like a 6).
        val unknowns = lefts.flatMap { Algebra.freeVars(it[0]) }.filter { env.variable(it) == null }.toSet()
        for ((k, parts) in sides.withIndex()) {
            val (eq, l, r) = parts
            options += lefts[k]
            options += pin(r)?.let { listOf(it) } ?: readings(r, eq.h, eq.cy, reader, Prefer.NUMBERS, known = { env.variable(it) != null }, favored = unknowns).ifEmpty { return null }
            bottom = max(bottom, max(l.maxOf { it.bottom }, r.maxOf { it.bottom }))
            right = max(right, r.maxOf { it.right })
            h = max(h, eq.h)
        }
        // Letters defined above (x = 2) may still be the system's unknowns.
        fun letters(sides: List<Expr>) = sides.flatMap { Algebra.freeVars(it) }.distinct()
        // Every equation must keep an unknown: y − y = 1 is a misreading of x − y = 1.
        fun sound(sides: List<Expr>) = sides.chunked(2).all { (l, r) ->
            runCatching { Algebra.freeVars(Algebra.simplify(Expr.sub(l, r))).isNotEmpty() }.getOrDefault(true)
        }
        val picked = combinations(options).firstOrNull { letters(it).size == members.size && sound(it) }
            ?: combinations(options).firstOrNull { sides -> letters(sides).count { env.variable(it) == null } == members.size && sound(sides) }
            ?: options.map { it[0] }
        val equations = picked.chunked(2).map { it[0] to it[1] }
        trace?.invoke("system: $equations")
        // To correct how it was read: every equation, from what was taken.
        val rows = sides.mapIndexed { k, (eq, l, r) ->
            Fix(l, r, listOf(eq.top, eq.bottom) + eq.extra, eq.h, eq.cy, env, leftRead = equations[k].first, rightRead = equations[k].second)
        }
        val fix = rows[0].let { f -> Fix(f.left, f.right, f.equals, f.h, f.axis, env, leftRead = f.leftRead, rightRead = f.rightRead, system = rows) }
        // Under the system, from the brace: to its right the answer runs into long equations.
        // Written smaller when the next line below leaves less room; in notes too dense for
        // that, to the right of the system if that place is free. The y is the middle of the
        // text block (lines 1.1·h tall, 1.25 apart, see drawAnswers).
        val ink = rest + eqs.flatMap { listOf(it.top, it.bottom) }
        fun under(answer: Answer, error: Boolean): PageAnswer {
            val lines = (answer as? Answer.Text)?.lines ?: listOf("")
            val perLine = (if (error) 0.75f else 1.1f) * 1.25f
            val next = ink.filter { it.top > bottom && it.right > brace.left && it.left < right }.minOfOrNull { it.top }
            val fit = if (next == null) h else min(h, (next - bottom) / (lines.size * perLine + 0.4f))
            if (fit < MIN_UNDER * h) {
                val at = right + 1.0f * h
                val wide = lines.maxOf { it.length } * CHAR_W * h
                val half = lines.size * perLine * h / 2
                val taken = ink.any { it.right > at && it.left < at + wide && it.bottom > brace.cy - half && it.top < brace.cy + half }
                if (!taken) return PageAnswer(at, brace.cy, h, answer, error, steps = if (error) null else Steps.of(equations, env), fix = fix)
            }
            val size = max(fit, MIN_UNDER * h)
            return PageAnswer(brace.left, bottom + 0.25f * size + lines.size * perLine * size / 2, size, answer, error, steps = if (error) null else Steps.of(equations, env), fix = fix)
        }
        return try {
            val answer = Calc.solve(equations, env)
            // Lines of values stacked under each other (F₂ = 118 см², J = 55962 см⁴) are no
            // system, even when a letter's stem looks like "{": they give ∞ solutions.
            if (Tune.workedInequalities > 0f && (answer as? Answer.Text)?.lines == listOf(tr("∞ решений", "∞ solutions"))) return null
            under(answer, error = false)
        } catch (e: MathError) {
            under(Answer.Text(listOf(e.message ?: "?")), error = true)
        }
    }

    /** Which reading to take when several parse. */
    enum class Prefer {
        FIRST,

        /** Fewest unknown letters: "7−2" rather than "f−2" before an "=" to compute. */
        NUMBERS,

        /** Some unknown, as few as fit: an equation of a system is about letters ("2z", not "22"). */
        LETTERS,

        /** The formula of a graph: in its variable, and no other unknown letter. */
        GRAPH,

        /** The formula of a surface z = f(x, y): in x and y, and no other unknown letter. */
        SURFACE,
    }

    /**
     * Reads strokes as an expression: combinations of the reader's guesses that parse,
     * chosen by [prefer]; [known] tells defined names (not unknowns).
     */
    fun read(
        strokes: List<Ink>,
        h: Float,
        axis: Float,
        reader: RunReader,
        prefer: Prefer = Prefer.FIRST,
        known: (String) -> Boolean = { false },
        shift: MathLayout.Shift = MathLayout.Shift.NONE,
    ): Expr? = readings(strokes, h, axis, reader, prefer, known, shift = shift).firstOrNull()

    /** Angles written in degrees (cos 10°) as radians, for a value: −149,6·cos 10°. */
    private fun inRadians(e: Expr): Expr = when (e) {
        is Expr.Fn -> if (e.name in Angles.UNITS && e.args.size == 1) Angles.radians(inRadians(e.args[0])) else Expr.Fn(e.name, e.args.map(::inRadians))
        is Expr.Add -> Expr.Add(e.terms.map(::inRadians))
        is Expr.Mul -> Expr.Mul(e.factors.map(::inRadians))
        is Expr.Pow -> Expr.Pow(inRadians(e.base), inRadians(e.exp))
        else -> e
    }

    /** The height of a side's own symbols: three in four of its strokes (not dashes) are no taller. */
    private fun sideHeight(strokes: List<Ink>): Float {
        val heights = strokes.filter { !it.isDash }.map { it.height }.sorted()
        return max(heights.getOrNull(heights.size * 3 / 4) ?: 0f, MIN_H)
    }

    /** Distinct readings that parse, best first (at most [MAX_READINGS]). */
    fun readings(
        strokes: List<Ink>,
        h: Float,
        axis: Float,
        reader: RunReader,
        prefer: Prefer = Prefer.FIRST,
        known: (String) -> Boolean = { false },
        maxLetters: Int = Int.MAX_VALUE,
        variable: String = "x",
        favored: Set<String> = emptySet(),
        shift: MathLayout.Shift = MathLayout.Shift.NONE,
    ): List<Expr> {
        val pieces = try {
            MathLayout.formula(strokes, h, axis, shift)
        } catch (e: MathError) {
            trace?.invoke("  layout failed: ${e.message}")
            // The line's height can be taken from small symbols beside (126 см² after the
            // "="): then the 1s of 118 stand tall as braces. Again with the side's own height.
            val own = sideHeight(strokes)
            if (Tune.sideHeight <= 0f || own < 1.1f * h) return emptyList()
            try {
                MathLayout.formula(strokes, own, axis, shift).also { trace?.invoke("  layout again with its own height $own") }
            } catch (e2: MathError) {
                trace?.invoke("  layout failed again: ${e2.message}")
                return emptyList()
            }
        }
        // Read again with moved thresholds only to see whether the answer changes: when the
        // layout came out the same, so would the answer, and nothing is read.
        if (shift != MathLayout.Shift.NONE && runCatching { MathLayout.same(pieces, MathLayout.formula(strokes, h, axis)) }.getOrDefault(false)) {
            return emptyList()
        }
        val holes = pieces.filterIsInstance<Piece.Hole>()
        if (holes.size > MAX_HOLES) return emptyList()
        val cands = holes.map { hole -> holeTexts(hole, reader, prefer, known).ifEmpty { return emptyList() } }
        // Signs told by their shape may be what the reader would take them for (a prime is
        // a 1 written short, a degree a small 0): tried after the sign, and a reading with one
        // counts a little worse than the same reading with the sign.
        val signs = if (ReadOptions.signAlternatives) {
            pieces.filterIsInstance<Piece.Text>().filter { it.strokes.isNotEmpty() && (SIGN_ALTERNATIVES[it.s]?.size ?: 0) > 1 }
        } else {
            emptyList()
        }
        val slots = cands + signs.map { SIGN_ALTERNATIVES.getValue(it.s) }
        trace?.invoke("  layout: " + pieces.joinToString("") { if (it is Piece.Text) it.s else "#" } + "  reads: " + cands)
        val found = ArrayList<Pair<Expr, Int>>()
        for (choice in combinations(slots)) {
            var k = 0
            var changedSigns = 0
            val text = buildString {
                for (p in pieces) {
                    when (p) {
                        is Piece.Text -> {
                            val at = signs.indexOfFirst { it === p }
                            if (at < 0) {
                                append(p.s)
                            } else {
                                val c = choice[holes.size + at]
                                if (c != p.s) changedSigns++
                                append(c)
                            }
                        }
                        is Piece.Hole -> append(choice[k++])
                    }
                }
            }
            for (variant in integralVariants(text).flatMap { listOf(it) + bracketFixes(it) }) {
                val e = runCatching { Parser.parse(variant) }.getOrNull() ?: continue
                val names = letters(e).filter { !known(it) }
                val unknowns = names.size
                val score = when (prefer) {
                    Prefer.FIRST -> 0
                    // ∫ v dv rather than ∫ r dv: the integrand is about the variable of dv. Letters
                    // [favored] (a system's unknowns) count for, not against, a reading.
                    // With units on, a reading with units (9,8 м/с²) beats one with digits for its letters.
                    Prefer.NUMBERS -> names.count { it !in favored } - names.count { it in favored } + 2 * oddIntegrals(e) -
                        (if (Parser.units && Algebra.freeVars(e).any { it.startsWith(Units.PREFIX) }) 1 else 0)
                    // About letters ("2z", not "22"), but not more than the system has unknowns
                    // (x⁶ rather than t^G); 9z rather than oz (o, l, I are digits misread far more often).
                    Prefer.LETTERS -> (if (unknowns > maxLetters) 20 + unknowns else -unknowns) + 2 * names.count { it in RARE_UNKNOWNS }
                    Prefer.GRAPH -> names.count { !it.equals(variable, ignoreCase = true) } + if (names.none { it.equals(variable, ignoreCase = true) }) 1 else 0
                    // z = x² + y² rather than z = x² + 4²: both letters of the axes, no others.
                    Prefer.SURFACE -> names.count { it.lowercase() !in SURFACE_AXES } + SURFACE_AXES.count { a -> names.none { it.lowercase() == a } }
                }
                if (found.none { it.first == e }) {
                    found += e to score + SIGN_CHANGE_COST * changedSigns
                    trace?.invoke("  parsed: $variant -> $e")
                }
                break
            }
            if (prefer != Prefer.LETTERS && favored.isEmpty() && found.count { it.second <= 0 } >= MAX_READINGS && (!Parser.units || found.any { it.second < 0 })) break
        }
        return found.sortedBy { it.second }.map { it.first }.take(MAX_READINGS)
    }

    /**
     * An equation read with two unknowns, or with one and no solutions ([noSolution]): the first readings of its sides with
     * exactly one letter left unknown in [env] that solve to something, and the reason it was
     * read again ("g, a: two unknowns"); null when there is none. Letters that look like digits
     * become digits here (g 9, o 0, l 1), as in systems.
     */
    private fun rescue(left: Expr, right: Expr, leftInk: List<Ink>, rightInk: List<Ink>, eq: Equals, reader: RunReader, env: Env, noSolution: Boolean): Triple<Expr, Expr, String>? {
        if (leftInk.isEmpty() || rightInk.isEmpty()) return null
        // Two letters read where one was meant (ga + 6 = 2, 9x read gx): on the synthetic
        // equations 2 of 3 come right. With none (811 + 9 = 1) or three and more, hardly any.
        val names = Algebra.freeVars(Expr.sub(left, right)).filter { env.variable(it) == null }
        if (names.size != (if (noSolution) 1 else 2)) return null
        val known = { name: String -> env.variable(name) != null }
        val lefts = readings(leftInk, eq.h, eq.cy, reader, Prefer.LETTERS, known, maxLetters = 1).take(RESCUE_READINGS)
        val rights = (listOf(right) + readings(rightInk, eq.h, eq.cy, reader, Prefer.LETTERS, known, maxLetters = 1)).distinct().take(RESCUE_READINGS)
        val pairs = lefts.indices.flatMap { i -> rights.indices.map { j -> i to j } }.sortedBy { (i, j) -> i + j }
        for ((i, j) in pairs) {
            val l = lefts[i]
            val r = rights[j]
            if (l == left && r == right) continue
            val unknowns = Algebra.freeVars(Expr.sub(l, r)).filter { env.variable(it) == null }
            if (unknowns.size != 1) continue
            val answer = runCatching { Calc.solve(listOf(l to r), env) }.getOrNull() as? Answer.Text ?: continue
            if (answer.lines.isEmpty() || answer.lines == listOf(tr("∞ решений", "∞ solutions")) || answer.lines.any { '=' !in it }) continue
            val why = if (noSolution) {
                tr("как написано — нет решений", "as written: no solutions")
            } else {
                tr("прочитано две неизвестные: ${names.joinToString(", ")}", "read as two unknowns: ${names.joinToString(", ")}")
            }
            return Triple(l, r, why)
        }
        return null
    }

    /** Another "=" on the line of [eq], close before or after it (within [CHAIN_GAP] lines): a chain of working. */
    private fun chained(eq: Equals, eqs: List<Equals>): Boolean = Tune.chains > 0f && eqs.any { o ->
        o !== eq && abs(o.cy - eq.cy) < 0.6f * max(eq.h, o.h) &&
            (o.left - eq.right in 0f..CHAIN_GAP * eq.h || eq.left - o.right in 0f..CHAIN_GAP * eq.h)
    }

    /** How far right [brace] reaches level with [y] (within half a line [h]); its whole width where it does not pass that level. */
    private fun braceAt(brace: Ink, y: Float, h: Float): Float {
        var x = -Float.MAX_VALUE
        for (i in brace.xs.indices) {
            if (abs(brace.ys[i] - y) < 0.5f * h) x = max(x, brace.xs[i])
            // Where the stroke passes the line between two points.
            if (i > 0 && (brace.ys[i - 1] - y) * (brace.ys[i] - y) <= 0f && brace.ys[i] != brace.ys[i - 1]) {
                val t = (y - brace.ys[i - 1]) / (brace.ys[i] - brace.ys[i - 1])
                x = max(x, brace.xs[i - 1] + t * (brace.xs[i] - brace.xs[i - 1]))
            }
        }
        return if (x == -Float.MAX_VALUE) brace.right else x
    }

    /** A written equality "left = right" whose two sides are numbers: what they read as ([left], [right]: null when not a number). */
    class Equality(
        val x: Float,
        val y: Float,
        val left: Double?,
        val right: Double?,
        val leftText: String,
        val rightText: String,
        // the strokes of each side, the line's height and middle (for the benches)
        val leftInk: List<Ink> = emptyList(),
        val rightInk: List<Ink> = emptyList(),
        val h: Float = 0f,
        val axis: Float = 0f,
    )

    /**
     * Every "=" on the page with what is written on both sides read as numbers: for checking
     * the reading on pages of worked solutions (68·51 = 3468: the written result says what the
     * left side is). Units after a number (cm², kN) and letters are left as they read.
     */
    fun equalities(strokes: List<Ink>, reader: RunReader): List<Equality> {
        val ink = strokes.filter { it.height < MAX_STROKE && it.width < MAX_STROKE }
        val eqs = findEquals(ink).take(MAX_EQUALS)
        if (eqs.isEmpty()) return emptyList()
        val rest = ink.filter { s -> eqs.none { s === it.top || s === it.bottom || s in it.extra } }
        for (eq in eqs) eq.h = lineHeight(eq, rest)
        val ineqs = runCatching { findInequalities(ink) }.getOrDefault(emptyList())
        for ((a, _) in ineqs) if (a.h <= 0f) a.h = lineHeight(a, rest)
        val out = ArrayList<Equality>()
        for (eq in eqs) {
            // An inequality sign before it on the line (D ≥ ∛… = 6,24) bounds the left side.
            val signs = ineqs.filter { (a, _) -> abs(a.cy - eq.cy) < 0.6f * max(a.h, eq.h) && a.right <= eq.left }
            val signEdge = signs.maxOfOrNull { it.first.right }
            val left = side(eq, -1, eqs, rest, limit = null).filter { signEdge == null || it.cx > signEdge }
            val right = side(eq, 1, eqs, rest, limit = null)
            if (left.isEmpty() || right.isEmpty()) continue
            fun value(side: List<Ink>, unitsAfter: Boolean): Pair<Double?, String> {
                val e0 = runCatching { read(side, eq.h, eq.cy, reader, Prefer.NUMBERS) }.getOrNull() ?: return null to "?"
                val text = runCatching { Printer.expr(e0) }.getOrDefault("?")
                // A result with its unit (3468 см², 52,92 кН): the number before the letters.
                val e = if (unitsAfter && e0 is Expr.Mul && e0.factors.first() is Expr.Num &&
                    e0.factors.drop(1).all { it is Expr.Sym || (it is Expr.Pow && it.base is Expr.Sym) }
                ) e0.factors.first() else e0
                if (Algebra.freeVars(e).any { it !in Algebra.CONSTANTS }) return null to text
                val v = (runCatching { Numeric.eval(inRadians(e)) }.getOrNull() as? Scalar)?.v?.takeIf { it.isFinite() }
                return v to text
            }
            val (lv, lt) = value(left, unitsAfter = false)
            var (rv, rt) = value(right, unitsAfter = true)
            // Units written as a fraction (−8,2 кН/см²) leave the side unreadable: the number
            // before the first clear gap.
            if (rv == null) {
                val sorted = right.sortedBy { it.left }
                var reach = sorted[0].right
                for (k in 1 until sorted.size) {
                    if (sorted[k].left - reach > 0.5f * eq.h) {
                        val (v, t) = value(sorted.take(k), unitsAfter = true)
                        if (v != null) {
                            rv = v
                            rt = t
                        }
                        break
                    }
                    reach = max(reach, sorted[k].right)
                }
            }
            out += Equality(eq.left, eq.cy, lv, rv, lt, rt, left, right, eq.h, eq.cy)
        }
        return out
    }

    /** A small tick under the foot of the digits around it, with digits on both sides: a decimal comma. */
    private fun hasComma(strokes: List<Ink>, h: Float): Boolean {
        val big = strokes.filter { it.height > 0.5f * h }
        if (big.size < 2) return false
        val foot = big.map { it.bottom }.sorted()[big.size / 2]
        return strokes.any { c ->
            max(c.width, c.height) < 0.45f * h && c.width < 0.8f * c.height + 1f && c.top > foot - 0.25f * h && c.bottom > foot + 0.05f * h &&
                big.any { it.right <= c.left + 0.2f * h } && big.any { it.left >= c.right - 0.2f * h }
        }
    }

    /** The texts a run is read as, best first, for [readings]; empty when the reader saw nothing. */
    private fun holeTexts(hole: Piece.Hole, reader: RunReader, prefer: Prefer, known: (String) -> Boolean): List<String> {
        val read = reader.read(hole.strokes, hole.h).map(MathText::normalize).filter { it.isNotEmpty() }.map(::digitIndex)
        // A comma drawn between digits, under their foot, that the first reading left out
        // (2446,59 read 244659 first): the next reading with it goes first.
        val texts = if (Tune.commaSeen > 0f && read.size > 1 && read[0].none { it == ',' || it == '.' } && hasComma(hole.strokes, hole.h)) {
            val withIt = read.take(3).firstOrNull { t -> Regex("\\d[,.]\\d").containsMatchIn(t) && t.filter { it.isDigit() } == read[0].filter { it.isDigit() } }
            if (withIt != null) listOf(withIt) + (read - withIt) else read
        } else read
        // A lone upright read as l or I is a 1 nine times in ten (CROHME, read by ML Kit on
        // the emulator: l 47 of 51, I 33 of 38), so 1 is tried first; not when the page has
        // that letter already (l = 2 м above: the length).
        val main = texts.flatMap {
            when {
                it.length != 1 || it[0] !in ONE_LIKE -> listOf(it)
                ReadOptions.oneFirst && !known(it) -> listOf("1", it)
                else -> listOf(it, "1")
            }
        }.distinct()
        // In a system a misread coefficient (oz for 9z) adds an unknown, which the choice
        // of readings then rules out; elsewhere it would only add wrong guesses.
        val lookalikes = if (prefer == Prefer.LETTERS) main.take(2).mapNotNull(::digitLookalike) else emptyList()
        // Digits read as letters without context ("60" as Go): the same run as digits.
        val digits = if (prefer != Prefer.FIRST) main.take(3).mapNotNull(::asDigits) else emptyList()
        val all = (main.take(MAX_CANDIDATES) + digits + lookalikes).distinct()
        // Computing: digit readings first, so the combinations tried first are numbers
        // (1/5 · 5/1, not I/s · s/d) — there are too many to try them all.
        return if (prefer == Prefer.NUMBERS) all.sortedBy { t -> if (t.all { it.isDigit() || it == ',' || it == '.' }) 0 else 1 } else all
    }

    /**
     * What each piece of the layout of [strokes] was read as to give [target]: the text of
     * each run (and of each sign told by its shape), null for the rest; null when no reading
     * tried gives it. The correction window starts from these, so that it shows what the
     * page read, whichever way of reading (numbers, letters, a graph) the page took.
     */
    fun choiceFor(
        strokes: List<Ink>,
        h: Float,
        axis: Float,
        reader: RunReader,
        target: Expr,
        known: (String) -> Boolean = { false },
        // in place of equal to [target] (the benches: a value written by hand)
        accept: ((Expr) -> Boolean)? = null,
    ): List<String?>? {
        val pieces = runCatching { MathLayout.formula(strokes, h, axis) }.getOrNull() ?: return null
        val slots = pieces.map { p ->
            when (p) {
                is Piece.Hole -> (holeTexts(p, reader, Prefer.NUMBERS, known) + holeTexts(p, reader, Prefer.LETTERS, known)).distinct().ifEmpty { return null }
                is Piece.Text -> if (p.strokes.isNotEmpty() && (SIGN_ALTERNATIVES[p.s]?.size ?: 0) > 1) SIGN_ALTERNATIVES.getValue(p.s) else listOf(p.s)
            }
        }
        if (pieces.count { it is Piece.Hole } > MAX_HOLES) return null
        // As [combinations], without its limit: the page's reading differs from the first
        // guesses in at most two places.
        val best = slots.map { it[0] }
        val tries = sequence {
            yield(best)
            for (i in slots.indices) for (j in 1 until slots[i].size) yield(best.toMutableList().also { it[i] = slots[i][j] })
            for (i in slots.indices) for (k in i + 1 until slots.size) for (a in 1 until slots[i].size) for (b in 1 until slots[k].size) {
                yield(best.toMutableList().also { it[i] = slots[i][a]; it[k] = slots[k][b] })
            }
        }
        for (choice in tries.take(MAX_CHOICE_TRIES)) {
            val text = choice.joinToString("")
            if (integralVariants(text).flatMap { listOf(it) + bracketFixes(it) }.any { v ->
                    runCatching { Parser.parse(v) }.getOrNull()?.let { e -> if (accept != null) accept(e) else e == target } == true
                }
            ) {
                return pieces.indices.map { i -> if (pieces[i] is Piece.Hole || choice[i] != (pieces[i] as Piece.Text).s) choice[i] else null }
            }
        }
        return null
    }

    /**
     * The text of a formula written by hand (a lasso selection), in the parser's LaTeX-like
     * form: the first reading that parses (each side of an "=" on its own), else the first
     * one read; null when nothing could be read.
     */
    fun formulaSource(strokes: List<Ink>, reader: RunReader): String? {
        if (strokes.isEmpty()) return null
        val heights = strokes.filter { !it.isDash }.map { it.height }.sorted()
        val h = max(heights.getOrNull(heights.size * 3 / 4) ?: strokes.maxOf { it.height }, MIN_H)
        val eq = findEquals(strokes).firstOrNull()
        val mids = strokes.filter { it.height in 0.4f * h..1.6f * h }.map { it.cy }
        val axis = eq?.cy ?: MathLayout.median(mids) ?: Box.of(strokes).cy
        val pieces = try {
            MathLayout.formula(strokes, h, axis)
        } catch (e: MathError) {
            return reader.read(strokes.sortedBy { it.order }, h).firstOrNull()?.let(MathText::normalize)?.ifEmpty { null }
        }
        val holes = pieces.filterIsInstance<Piece.Hole>()
        if (holes.size > MAX_HOLES) return null
        val cands = holes.map { hole ->
            reader.read(hole.strokes, hole.h)
                .map { if (it.trim() == "=") "=" else MathText.normalize(it) }
                .filter { it.isNotEmpty() }.take(MAX_CANDIDATES).ifEmpty { listOf("?") }
        }
        fun parses(t: String) = t.split('=').all { side -> side.isNotBlank() && runCatching { Parser.parse(side) }.isSuccess }
        var first: String? = null
        for (choice in combinations(cands).take(FORMULA_TRIES)) {
            var k = 0
            val text = buildString {
                for (p in pieces) {
                    when (p) {
                        is Piece.Text -> append(p.s)
                        is Piece.Hole -> append(choice[k++])
                    }
                }
            }
            if (first == null) first = text
            for (variant in integralVariants(text).flatMap { listOf(it) + bracketFixes(it) }) if (parses(variant)) return variant
        }
        return first
    }

    private const val FORMULA_TRIES = 120

    /**
     * Reads the left side of an "=" to compute. Words before the formula on the same line
     * ("Ответ: 2+3 =") spoil the reading; then the parts left of wide gaps are dropped one by
     * one while that gives a cleaner reading (one that parses, with fewer unknown letters).
     */
    private fun readTrimmed(left: List<Ink>, eq: Equals, reader: RunReader, known: (String) -> Boolean, shift: MathLayout.Shift = MathLayout.Shift.NONE): Expr? =
        readTrimmedPart(left, eq, reader, known, shift).first

    /** [readTrimmed] and the strokes it read: [left], or its part after a word. */
    private fun readTrimmedPart(left: List<Ink>, eq: Equals, reader: RunReader, known: (String) -> Boolean, shift: MathLayout.Shift = MathLayout.Shift.NONE): Pair<Expr?, List<Ink>> {
        fun score(e: Expr) = letters(e).count { !known(it) }
        var best = read(left, eq.h, eq.cy, reader, Prefer.NUMBERS, known, shift)
        var bestInk = left
        if (best != null && score(best) == 0) return best to left
        // Split points: gaps in the x-extent of the side wider than a symbol's width.
        val spans = left.map { it.left to it.right }.sortedBy { it.first }
        val cuts = ArrayList<Float>()
        var reach = spans[0].second
        for ((l, r) in spans.drop(1)) {
            if (l - reach > 0.7f * eq.h) cuts += l
            reach = max(reach, r)
        }
        // After a colon ("Ответ: 2 + 3 =") the formula starts, however close it is written.
        val colons = colonEnds(left, eq.h)
        cuts.removeAll { c -> colons.any { abs(it - c) < 0.7f * eq.h } }
        cuts.addAll(0, colons)
        for (cut in cuts.take(MAX_TRIMS)) {
            val part = left.filter { it.cx >= cut }
            if (part.isEmpty()) break
            // A part that starts with a sign is the tail of the formula, not the formula after
            // some text: "7/9 + 4/3 =" with the 7 unread would answer 4/3.
            if (startsWithSign(part, eq, reader, shift)) continue
            val e = read(part, eq.h, eq.cy, reader, Prefer.NUMBERS, known, shift) ?: continue
            if (best == null || score(e) < score(best)) {
                best = e
                bestInk = part
            }
            if (score(e) == 0) break
        }
        return best to bestInk
    }

    /** Where colons among [strokes] end (two dots one over the other), left to right. */
    private fun colonEnds(strokes: List<Ink>, h: Float): List<Float> {
        val dots = strokes.filter { it.width < 0.3f * h && it.height < 0.3f * h }
        val out = ArrayList<Float>()
        for (a in dots) for (b in dots) {
            if (b.cy <= a.cy || abs(a.cx - b.cx) > 0.25f * h) continue
            val gap = b.cy - a.cy
            if (gap < 0.2f * h || gap > 0.9f * h) continue
            // Nothing else between the dots: a colon, not dots inside some symbol.
            if (strokes.any { it !== a && it !== b && it.cx in a.left - 0.1f * h..a.right + 0.1f * h && it.cy > a.cy && it.cy < b.cy }) continue
            out += max(a.right, b.right) + 0.01f * h
        }
        return out.distinct().sorted()
    }

    private fun startsWithSign(part: List<Ink>, eq: Equals, reader: RunReader, shift: MathLayout.Shift): Boolean {
        // The sign told by its shape, or read first in the first run.
        val text = when (val first = runCatching { MathLayout.formula(part, eq.h, eq.cy, shift) }.getOrNull()?.firstOrNull()) {
            is Piece.Text -> first.s
            is Piece.Hole -> runCatching { reader.read(first.strokes, first.h).firstOrNull() }.getOrNull()
            else -> null
        }
        return text?.trimStart()?.firstOrNull()?.let { it in LEADING_SIGNS } == true
    }

    /** The reader was sure of every run of the formula left of [eq]. */
    private fun readSure(left: List<Ink>, eq: Equals, reader: RunReader): Boolean {
        val holes = runCatching { MathLayout.formula(left, eq.h, eq.cy) }.getOrNull()?.filterIsInstance<Piece.Hole>() ?: return true
        return holes.all { reader.sure(it.strokes) }
    }

    /**
     * When the reader was unsure: the answers of the next readings that differ from [answer],
     * as the other possible answers (the answer is then shown unsure, these offered).
     */
    private fun otherReadings(answer: Answer, left: List<Ink>, eq: Equals, reader: RunReader, known: (String) -> Boolean, value: (Expr) -> Answer?): List<String> {
        val text = (answer as? Answer.Text)?.lines ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (e in readings(left, eq.h, eq.cy, reader, Prefer.NUMBERS, known).drop(1)) {
            val other = runCatching { value(e) }.getOrNull() as? Answer.Text ?: continue
            if (other.lines != text) out += other.lines.joinToString("; ")
            if (out.size >= MAX_OTHERS) break
        }
        return out.toList()
    }

    /**
     * Answers the formula could have with its layout read a little differently
     * ([MathLayout.Shift]): not empty when the layout is not clear-cut, and then the answer is
     * shown as unsure, these offered beside it. [again] gives null when a shift changes nothing
     * in the layout ([readings] then reads nothing): the usual case costs only the layouts.
     */
    fun otherAnswers(answer: Answer, again: (MathLayout.Shift) -> Answer?): List<String> {
        val text = (answer as? Answer.Text)?.lines ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (shift in MathLayout.Shift.CHECKS) {
            val other = runCatching { again(shift) }.getOrNull() as? Answer.Text ?: continue
            if (other.lines != text) out += other.lines.joinToString("; ")
        }
        return out.take(MAX_OTHERS)
    }

    /**
     * [text] (formula text, as runs read into, or as set by hand) as an expression: with an
     * integral sign put back or a bracket mended when it parses only so; null when it does not.
     */
    fun parseText(text: String): Expr? {
        if (text.isBlank()) return null
        for (variant in integralVariants(text).flatMap { listOf(it) + bracketFixes(it) }) {
            runCatching { Parser.parse(variant) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** "2 + 3 = ?": a question mark after "=" asks for the answer like an empty place. */
    private fun isQuestion(right: List<Ink>, eq: Equals, reader: RunReader): Boolean {
        if (right.size > 2 || Box.of(right).width > 1.2f * eq.h) return false
        return reader.read(right.sortedBy { it.order }, eq.h).firstOrNull()?.trim() == "?"
    }

    /** Letters of [e] standing for numbers: the T of a transposed matrix is not one. */
    private fun letters(e: Expr): Set<String> =
        (if (transposes(e)) Algebra.freeVars(e) - "T" else Algebra.freeVars(e)).filterTo(LinkedHashSet()) { !it.startsWith(Units.PREFIX) }

    private fun transposes(e: Expr): Boolean = when (e) {
        is Expr.Pow -> (e.exp == Expr.Sym("T") && e.base is Expr.Mat) || transposes(e.base)
        is Expr.Add -> e.terms.any(::transposes)
        is Expr.Mul -> e.factors.any(::transposes)
        else -> false
    }

    private const val RARE_UNKNOWNS = "oOlIiD"

    /** Other possible answers offered beside an unsure one. */
    private const val MAX_OTHERS = 3

    /** Integrals whose integrand does not contain their variable (likely misread). */
    private fun oddIntegrals(e: Expr): Int = when (e) {
        is Expr.Integral -> (if (Algebra.has(e.body, e.v)) 0 else 1) + oddIntegrals(e.body)
        is Expr.Add -> e.terms.sumOf(::oddIntegrals)
        is Expr.Mul -> e.factors.sumOf(::oddIntegrals)
        is Expr.Pow -> oddIntegrals(e.base) + oddIntegrals(e.exp)
        is Expr.Fn -> e.args.sumOf(::oddIntegrals)
        else -> 0
    }

    /**
     * A coefficient written before a letter is a digit even when it reads as one: oz → 9z,
     * bx → 6x, sy → 5y, zt → 2t. Null when nothing changes.
     */
    private fun digitLookalike(text: String): String? {
        val b = StringBuilder(text)
        for (i in text.indices) {
            val next = text.getOrNull(i + 1) ?: continue
            if (!next.isLetter() || text.getOrNull(i - 1)?.isLetter() == true) continue
            LOOKALIKE[text[i]]?.let { b.setCharAt(i, it) }
        }
        return b.toString().takeIf { it != text }
    }

    /**
     * The run with letters that look like digits made digits: all of them when the run has
     * nothing else ("Go" → 60, "l+I" → 1+1), else those next to a digit ("1O" → 10).
     */
    internal fun asDigits(text: String): String? {
        val whole = text.all { it.isDigit() || it in DIGIT_LIKE || it in "+-*/.,()" }
        val b = StringBuilder(text)
        for (i in text.indices) {
            val d = DIGIT_LIKE[text[i]] ?: continue
            if (whole || text.getOrNull(i - 1)?.isDigit() == true || text.getOrNull(i + 1)?.isDigit() == true) b.setCharAt(i, d)
        }
        return b.toString().takeIf { it != text }
    }

    private val DIGIT_LIKE = mapOf(
        'o' to '0', 'O' to '0', 'D' to '0', 'l' to '1', 'I' to '1', 'i' to '1', '|' to '1', 'z' to '2', 'Z' to '2',
        'B' to '8', 's' to '5', 'S' to '5', 'G' to '6', 'b' to '6', 'T' to '7', 'F' to '7', 'J' to '7', 'g' to '9', 'q' to '9',
    )

    private val LOOKALIKE = mapOf('o' to '9', 'a' to '9', 'g' to '9', 'q' to '9', 'b' to '6', 's' to '5', 'z' to '2', 'l' to '1', 'I' to '1')

    /**
     * An integral sign the layout missed (too short, or read as s, f, "(") still shows by its
     * "d<variable>" at the end: try the text with the sign put back first.
     */
    private fun integralVariants(text0: String): List<String> {
        // ∫ 2·dx: a dot written before dx.
        val text = if (text0.contains("\\int") || INTEGRAL_TEXT.matches(text0)) text0.replace(DOT_DX, "d$1") else text0
        if (text.startsWith("\\int")) return listOf(text)
        val m = INTEGRAL_TEXT.matchEntire(text) ?: return listOf(text)
        return listOf("\\int " + m.groupValues[1], text)
    }

    private val INTEGRAL_TEXT = Regex("[sSfJʃ∫(](.+d[a-zA-Z])")
    private val DOT_DX = Regex("\\*\\s*d([a-zA-Z])$")

    /**
     * Unbalanced brackets: a small "(" read as C, c, l, 1 or "[", a ")" read as J or 7, or a
     * bracket the writer forgot. Variants with one of them made a bracket, or closed at the end.
     */
    internal fun bracketFixes(text: String): List<String> {
        var depth = 0
        var firstClose = -1
        for ((i, c) in text.withIndex()) {
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth < 0 && firstClose < 0) firstClose = i
            }
        }
        if (depth == 0 && firstClose < 0) return emptyList()
        val out = ArrayList<String>()
        if (firstClose >= 0) {
            // Too many ")": the "(" before it was misread.
            for (i in firstClose - 2 downTo 0) {
                if (text[i] in OPEN_LIKE && (i == 0 || !text[i - 1].isLetter())) {
                    out += text.substring(0, i) + "(" + text.substring(i + 1)
                    break
                }
            }
            out += text.removeRange(firstClose, firstClose + 1)
        } else if (depth > 0) {
            val open = text.lastIndexOf('(')
            for (i in text.length - 1 downTo open + 2) {
                if (text[i] in CLOSE_LIKE && (i == text.length - 1 || !text[i + 1].isLetter())) {
                    out += text.substring(0, i) + ")" + text.substring(i + 1)
                    break
                }
            }
            out += text + ")".repeat(depth)
        }
        return out.take(2)
    }

    private const val OPEN_LIKE = "Cc[{<lI1"
    private const val CLOSE_LIKE = "J]}>71"

    private const val ONE_LIKE = "lI|/"

    /**
     * A sign told by its shape, and what the same strokes are when it is not that sign: a
     * prime or a degree sits up at the top right, so as a digit it is an exponent. Not the
     * middle dot: as a decimal point it turned x = −2,5 wrong on the owner's pages (0.41). Not
     * + as t: a + left hanging by a broken layout ("1+") then parses, and a formula with no
     * answer gets a wrong one (87 more wrong on CROHME).
     */
    private val SIGN_ALTERNATIVES = mapOf(
        "°" to listOf("°", "^{0}"),
        "'" to listOf("'", "^{1}"),
    )

    private val SURFACE_AXES = setOf("x", "y")

    /** How much worse a reading counts for each sign taken as something else. */
    private const val SIGN_CHANGE_COST = 2

    /** A lone letter with digits right after it, read in one run: x1 is x₁ (x·1 is written 1x or x·1). */
    private val LETTER_DIGITS = Regex("(?<![\\p{L}_^{])(\\p{L})(\\d+)(?![\\d.,])")

    /** [text] with each lone letter's digits right after it as its index, by [ReadOptions.digitIndex]; function words left as they are. */
    fun digitIndex(text: String): String {
        if (!ReadOptions.digitIndex || text.none { it.isDigit() }) return text
        val words = ReadOptions.wordRanges(text)
        return LETTER_DIGITS.replace(text) { m ->
            if (words.any { m.range.first in it }) m.value else "${m.groupValues[1]}_{${m.groupValues[2]}}"
        }
    }

    /** The best choice first, then one or two runs changed to their next guesses. */
    private fun <T> combinations(cands: List<List<T>>): Sequence<List<T>> = sequence {
        val best = cands.map { it[0] }
        yield(best)
        for (i in cands.indices) for (j in 1 until cands[i].size) yield(best.toMutableList().also { it[i] = cands[i][j] })
        for (i in cands.indices) for (k in i + 1 until cands.size) {
            for (a in 1 until cands[i].size) for (b in 1 until cands[k].size) {
                yield(best.toMutableList().also { it[i] = cands[i][a]; it[k] = cands[k][b] })
            }
        }
    }.take(MAX_COMBINATIONS)

    // ------------------------------------------------------------ geometry

    /** Pairs of short horizontal strokes stacked closely: "=". */
    fun findEquals(ink: List<Ink>): List<Equals> {
        // A dash drawn twice over the same place (an "=" traced again) counts once.
        val all = ink.filter { equalsLine(it) }
        val dashes = ArrayList<Ink>()
        val copies = HashMap<Ink, MutableList<Ink>>()
        for (d in all) {
            val same = dashes.firstOrNull { o ->
                // Closer than the two lines of any "=" are (a long flat = has them 0.14 of its
                // width apart).
                min(o.right, d.right) - max(o.left, d.left) > 0.6f * min(o.width, d.width) && abs(o.cy - d.cy) < 0.08f * max(o.width, d.width)
            }
            if (same != null) copies.getOrPut(same) { ArrayList() } += d else dashes += d
        }
        val ignored = copies.values.flatten().toSet()
        val used = HashSet<Ink>()
        val out = ArrayList<Equals>()
        val pairs = ArrayList<Triple<Ink, Ink, Float>>()
        trace?.invoke("dashes: " + dashes.joinToString { "(${it.left.toInt()}..${it.right.toInt()},${it.cy})" })
        for (a in dashes) for (b in dashes) {
            if (a === b || b.cy <= a.cy) continue
            val w = max(a.width, b.width)
            val gap = b.cy - a.cy
            val overlap = min(a.right, b.right) - max(a.left, b.left)
            if (overlap < 0.55f * min(a.width, b.width)) continue
            if (min(a.width, b.width) < 0.45f * w) continue
            // A tiny "=" (lines 3–5 px) has its lines farther apart than they are long.
            val tiny = w < 6f && max(a.height, b.height) < 0.35f * w && gap <= 1.2f * w
            if (gap < 0.1f * w || (gap > 0.85f * w && !tiny) || gap < max(a.height, b.height) * 0.6f) {
                if (gap < w) trace?.invoke("  pair at ${a.cx.toInt()},${a.cy.toInt()}: gap $gap w $w heights ${a.height},${b.height}")
                continue
            }
            pairs += Triple(a, b, gap / w)
        }
        for ((a, b, _) in pairs.sortedBy { it.third }) {
            if (a in used || b in used) continue
            // A third dash right above or below makes it "≡" or part of a drawing.
            val crowded = ink.any { o ->
                o !== a && o !== b && o !in ignored && o.isDash && o.cx in a.left..a.right &&
                    (abs(o.cy - a.cy) < (b.cy - a.cy) * 1.2f || abs(o.cy - b.cy) < (b.cy - a.cy) * 1.2f) && o.cy !in a.cy..b.cy &&
                    min(abs(o.cy - a.cy), abs(o.cy - b.cy)) < (b.cy - a.cy) * 1.1f
            }
            // The top bar of a 5 under a fraction bar: a dash that is part of a symbol.
            val h = 1.6f * max(a.width, b.width)
            fun capOf(d: Ink, o: Ink) = o.cy > d.cy + 0.25f * o.height && o.left < d.left + 0.3f * d.width &&
                min(d.right, o.right) - max(d.left, o.left) > 0.4f * d.width && MathLayout.cap(d, o, h)
            if (ink.any { o -> o !== a && o !== b && !o.isDash && (capOf(a, o) || capOf(b, o)) }) {
                trace?.invoke("  part of a symbol = at ${a.cx.toInt()},${a.cy.toInt()}")
                continue
            }
            // A dash another stroke runs through in its middle is part of a symbol: the bar of a
            // crossed 7 (over a fraction bar it looked like "="), of a t, of a Z.
            if (crossed(a, ink) || crossed(b, ink)) {
                trace?.invoke("  crossed = at ${a.cx.toInt()},${a.cy.toInt()}")
                continue
            }
            // The bars of an F or E (ΣS/F read "ΣS/=" gave a confident 55): both dashes start on
            // the same upright stroke.
            if (Tune.letterBars > 0f && letterBars(a, b, ink)) {
                trace?.invoke("  letter bars = at ${a.cx.toInt()},${a.cy.toInt()}")
                continue
            }
            if (crowded) {
                trace?.invoke("  crowded = at ${a.cx.toInt()},${a.cy.toInt()}")
                continue
            }
            // Nothing written between the two dashes.
            val between = ink.any { o -> o !== a && o !== b && o !in ignored && o.cx in a.left..a.right && o.cy > a.cy && o.cy < b.cy }
            if (between) {
                trace?.invoke("  something between = at ${a.cx.toInt()},${a.cy.toInt()}")
                continue
            }
            used += a
            used += b
            out += Equals(a, b, copies[a].orEmpty() + copies[b].orEmpty())
        }
        // "=" in one stroke, Z-like: a line, back diagonally, a second line.
        for (z in ink) {
            if (z in used || z in ignored) continue
            val (top, bottom) = zEquals(z) ?: continue
            if (out.any { e -> abs(e.cy - z.cy) < z.height && min(e.right, z.right) > max(e.left, z.left) }) continue
            out += Equals(top, bottom, listOf(z))
        }
        return out
    }

    /**
     * Whether one upright stroke (not a dash) touches the left ends of both [a] and [b], from the
     * top dash down to the lower one: the stem of an F or E with its bars.
     */
    private fun letterBars(a: Ink, b: Ink, ink: List<Ink>): Boolean {
        val gap = b.cy - a.cy
        val tol = max(0.2f * max(a.width, b.width), 1.5f)
        fun near(o: Ink, x: Float, y: Float) = o.xs.indices.any { k -> abs(o.xs[k] - x) < tol && abs(o.ys[k] - y) < tol }
        fun leftEnd(d: Ink): Pair<Float, Float> = d.xs.indices.minBy { d.xs[it] }.let { d.xs[it] to d.ys[it] }
        val (ax, ay) = leftEnd(a)
        val (bx, by) = leftEnd(b)
        return ink.any { o ->
            o !== a && o !== b && !o.isDash && o.height > 0.9f * gap && o.width < 0.6f * max(a.width, b.width) &&
                o.top < a.cy + 0.3f * gap && o.bottom > b.cy - 0.3f * gap && near(o, ax, ay) && near(o, bx, by)
        }
    }

    /** Whether a stroke other than a dash crosses [d] away from its ends (the middle 70 %). */
    private fun crossed(d: Ink, ink: List<Ink>): Boolean {
        val from = d.left + 0.15f * d.width
        val to = d.right - 0.15f * d.width
        for (o in ink) {
            if (o === d || o.isDash || o.right < from || o.left > to || o.bottom < d.top || o.top > d.bottom) continue
            for (i in 0 until o.xs.size - 1) for (j in 0 until d.xs.size - 1) {
                val p = intersection(o.xs[i], o.ys[i], o.xs[i + 1], o.ys[i + 1], d.xs[j], d.ys[j], d.xs[j + 1], d.ys[j + 1]) ?: continue
                if (p in from..to) return true
            }
        }
        return false
    }

    /** The x where segments (x1, y1)–(x2, y2) and (x3, y3)–(x4, y4) cross, or null. */
    private fun intersection(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float): Float? {
        val den = (x2 - x1) * (y4 - y3) - (y2 - y1) * (x4 - x3)
        if (abs(den) < 1e-6f) return null
        val t = ((x3 - x1) * (y4 - y3) - (y3 - y1) * (x4 - x3)) / den
        val u = ((x3 - x1) * (y2 - y1) - (y3 - y1) * (x2 - x1)) / den
        return if (t in 0f..1f && u in 0f..1f) x1 + t * (x2 - x1) else null
    }

    /**
     * A line of an "=": wide, roughly level (a slight slant or wave is fine). A short hook at an
     * end (the pen already heading back for the second line) is left out of the test.
     */
    private fun equalsLine(stroke: Ink): Boolean {
        val s = withoutHooks(stroke)
        // A short dash written a little slanted has a box only about twice as wide as high;
        // its slope is checked below.
        if (s.width !in MIN_EQ_WIDTH..MAX_EQ_WIDTH || s.width < 1.25f * max(s.height, 0.5f)) return false
        val dy = s.ys.last() - s.ys[0]
        val dx = s.xs.last() - s.xs[0]
        return (abs(dy) < 0.4f * abs(dx) && MathLayout.straight(s, 0.3f) && !wavy(s)).also {
            if (!it && s.width < 12f) trace?.invoke("  not a dash: ${s.left.toInt()}..${s.right.toInt()},${s.cy} h=${s.height} dy=$dy dx=$dx")
        }
    }

    /**
     * [s] from its leftmost to its rightmost point (in the direction it was drawn), when what
     * lies outside that is a short hook: under 40% of the stroke's length. A z or 2 turns back
     * for most of its length and stays as it is.
     */
    private fun withoutHooks(s: Ink): Ink {
        val n = s.xs.size
        if (n < 6) return s
        var iMin = 0
        var iMax = 0
        for (k in 1 until n) {
            if (s.xs[k] < s.xs[iMin]) iMin = k
            if (s.xs[k] > s.xs[iMax]) iMax = k
        }
        val (from, to) = if (s.xs.last() >= s.xs[0]) iMin to iMax else iMax to iMin
        if (from >= to || (from == 0 && to == n - 1)) return s
        fun length(a: Int, b: Int): Float {
            var l = 0f
            for (k in a + 1..b) l += hypot(s.xs[k] - s.xs[k - 1], s.ys[k] - s.ys[k - 1])
            return l
        }
        val total = length(0, n - 1)
        if (total <= 0f || length(from, to) < 0.6f * total) return s
        return Ink(s.xs.copyOfRange(from, to + 1), s.ys.copyOfRange(from, to + 1), s.order)
    }

    /** A wavy line (~): it goes up and down several times, a dash at most once. */
    private fun wavy(s: Ink): Boolean {
        val step = max(1.2f, 0.08f * s.width)
        var turns = 0
        var dir = 0
        var ext = s.ys[0]
        for (y in s.ys) {
            when {
                dir >= 0 && y > ext -> { ext = y; dir = 1 }
                dir <= 0 && y < ext -> { ext = y; dir = -1 }
                dir == 1 && ext - y > step -> { turns++; dir = -1; ext = y }
                dir == -1 && y - ext > step -> { turns++; dir = 1; ext = y }
            }
        }
        return turns >= 3
    }

    /** The two lines of a one-stroke "=", or null. Flat, unlike z and 2. */
    private fun zEquals(s: Ink): Pair<Ink, Ink>? {
        if (s.width !in MIN_EQ_WIDTH..MAX_EQ_WIDTH || s.height > 0.55f * s.width || s.height < 0.12f * s.width || s.xs.size < 6 || wavy(s)) return null
        val band = 0.3f * s.height
        val top = s.xs.indices.filter { s.ys[it] < s.top + band }
        val bottom = s.xs.indices.filter { s.ys[it] > s.bottom - band }
        if (top.size + bottom.size < 0.6f * s.xs.size) return null
        fun part(idx: List<Int>) = Ink(FloatArray(idx.size) { s.xs[idx[it]] }, FloatArray(idx.size) { s.ys[idx[it]] }, s.order)
        val a = part(top)
        val b = part(bottom)
        if (a.width < 0.6f * s.width || b.width < 0.6f * s.width) return null
        // Drawn top line first, bottom line last.
        if (top.average() > bottom.average()) return null
        return a to b
    }

    /**
     * Height of digits and capitals around [eq]: the middle of the taller half of the stroke
     * heights (lowercase letters and parts of + and x are shorter), not counting brackets,
     * braces and integral signs, which are much taller than the rest.
     */
    private fun lineHeight(eq: Equals, ink: List<Ink>): Float {
        val h0 = max(eq.width * 1.2f, MIN_H)
        val near = ink.filter {
            !it.isDash && abs(it.cy - eq.cy) < 1.5f * h0 && it.height in 0.3f * h0..4f * h0 &&
                (eq.left - it.right).coerceAtLeast(it.left - eq.right) < 6 * h0
        }
        if (near.isEmpty()) return h0
        val m = MathLayout.median(near.map { it.height })!!
        val kept = near.filter {
            val shape = if (it.height > 1.8f * m) MathLayout.tallShape(MathLayout.Glyph(listOf(it)), m) else null
            !(shape != null && shape != '|') && !(shape == '|' && it.height > 2.2f * m)
        }.map { it.height }.sorted()
        if (kept.isEmpty()) return m.coerceIn(0.6f * h0, 2.5f * h0)
        val upper = kept.subList(kept.size / 2, kept.size)
        return upper[upper.size / 2].coerceIn(0.6f * h0, 2.5f * h0)
    }

    /** Tall "{" drawn in one stroke, or in two halves meeting in the middle. */
    private fun braces(ink: List<Ink>): List<Ink> {
        fun isBrace(i: Ink) = i.height > 1.6f * MIN_H && MathLayout.tallShape(MathLayout.Glyph(listOf(i)), i.height / 2f) == '{'
        // Drawn in one stroke, a "{" starts and ends at its right; the tail of a y does not.
        fun endsRight(i: Ink) = i.xs[0] > i.left + 0.5f * i.width && i.xs.last() > i.left + 0.5f * i.width
        val out = ink.filter { isBrace(it) && endsRight(it) }.toMutableList()
        val parts = ink.filter { it.height > MIN_H && it.height > 1.5f * it.width && it !in out }.sortedBy { it.top }
        for (a in parts) for (b in parts) {
            if (a === b || b.top < a.top) continue
            val gap = b.top - a.bottom
            if (gap > 0.25f * min(a.height, b.height) || gap < -0.3f * min(a.height, b.height)) continue
            if (min(a.right, b.right) - max(a.left, b.left) < -0.3f * max(a.width, b.width)) continue
            val joined = Ink(a.xs + b.xs, a.ys + b.ys, a.order)
            if (isBrace(joined)) out += joined
        }
        return out
    }

    /**
     * Strokes of one side of [eq] ([dir] −1 left, +1 right): a chain of strokes crossing the
     * line of the "=" without wide gaps, plus what hangs off it (numerators and denominators,
     * exponents, matrix rows, integral limits). Stops at another "=" and at [limit].
     */
    fun side(eq: Equals, dir: Int, eqs: List<Equals>, ink: List<Ink>, limit: Float?): List<Ink> {
        val h = eq.h
        val bandTop = eq.cy - 0.5f * h
        val bandBottom = eq.cy + 0.5f * h
        // The nearest other "=" on this line bounds the side.
        var bound = if (dir < 0) -Float.MAX_VALUE else Float.MAX_VALUE
        for (o in eqs) {
            if (o === eq || abs(o.cy - eq.cy) > 0.6f * h) continue
            if (dir < 0 && o.right <= eq.left) bound = max(bound, o.right)
            if (dir > 0 && o.left >= eq.right) bound = min(bound, o.left)
        }
        val cand = ink.filter {
            // By centre: an exponent often reaches over the "=" written under it.
            // or stands wholly above it.
            val overEq = it.bottom < eq.top.top - 0.15f * h && it.cx <= eq.right + 0.3f * h
            // [limit] (a brace) by centre: its hooks reach over the first letter after it.
            if (dir < 0) {
                (it.cx <= eq.left + 0.3f * h || overEq) && it.left >= bound && (limit == null || it.cx > limit)
            } else {
                it.cx >= eq.right - 0.3f * h && !overEq && it.right <= bound && (limit == null || it.cx < limit)
            }
        }.sortedBy { if (dir < 0) -it.right else it.left }
        // Strokes of another formula line just above or below (dense notes, the lines of a
        // system) are not attached, even when a long tail (of a y) reaches into this line.
        val otherLines = eqs.filter { it !== eq && abs(it.cy - eq.cy) > 0.8f * h && abs(it.left - eq.left) < 15 * h }
        fun above(r: Ink) = r.bottom < eq.cy - 0.8f * h && r.height in 0.4f * h..1.4f * h
        fun gapUnder(r: Ink) = cand.any { u -> u !== r && u.height >= 0.4f * h && min(u.right, r.right) - max(u.left, r.left) > 0f && u.top in r.bottom + 0.2f * h..r.bottom + 1.2f * h }
        // a fraction's bar under them makes them its numerator
        // (nearer than the symbols under them: a bar under those is theirs)
        fun barUnder(r: Ink): Boolean {
            val below = cand.filter { u -> u !== r && !u.isDash && u.height >= 0.4f * h && min(u.right, r.right) - max(u.left, r.left) > 0f && u.top > r.bottom }.minOfOrNull { it.top } ?: Float.MAX_VALUE
            return ink.any { u -> u.isDash && u.width > 0.5f * h && u.left < r.right && u.right > r.left && u.cy > r.bottom - 0.1f * h && u.cy < r.bottom + 1.5f * h && u.cy < below }
        }
        // Strokes that could be such digits: just over the side, within its reach.
        val upper = if (Tune.otherCommas > 0f && cand.isNotEmpty()) {
            val l = cand.minOf { it.left } - 6f * h
            val r = cand.maxOf { it.right } + 6f * h
            ink.filter { it.bottom < eq.cy - 0.8f * h && it.top > eq.cy - 6f * h && it.right > l && it.left < r }
        } else emptyList()
        val rowMemo = HashMap<Ink, Boolean>()
        fun rowAbove(c: Ink): Boolean = rowMemo.getOrPut(c) {
            if (!above(c) || c.height < 0.5f * h) return@getOrPut false
            val row = upper.filter { r -> above(r) && abs(r.cy - c.cy) < 0.4f * h && abs(r.cx - c.cx) < 3f * h }
            row.size >= 3 && row.count(::gapUnder) >= 2 && row.none(::barUnder) && row.none { r -> cand.any { u -> u !== r && u !in row && min(u.right, r.right) - max(u.left, r.left) > 0f && u.top in r.bottom - 0.3f * h..r.bottom + 0.2f * h } }
        }
        val otherLine = cand.filter { c ->
            otherLines.any { o ->
                abs(c.cy - o.cy) < 0.9f * o.h && abs(c.cy - o.cy) < abs(c.cy - eq.cy) - 0.3f * h &&
                    // that line's own formula, not one in another column at the same height
                    c.cx in o.left - 10 * o.h..o.right + 10 * o.h
            }
        }.toSet() + if (Tune.otherCommas > 0f) cand.filter { c ->
            // the decimal comma of a line above, hanging down into this one (218,6 over 2051,0)
            max(c.width, c.height) < 0.5f * h && c.cy < eq.cy && otherLines.any { o ->
                o.cy < eq.cy && abs(c.cy - o.cy) < 1.2f * o.h && c.cx in o.left - 10 * o.h..o.right + 10 * o.h &&
                    listOf(-1, 1).all { side ->
                        ink.any { m ->
                            m !== c && abs(m.cy - o.cy) < 0.6f * o.h && m.height in 0.4f * o.h..1.3f * o.h &&
                                c.top in m.bottom - 0.4f * m.height..m.bottom + 0.3f * o.h &&
                                (if (side < 0) m.cx < c.left && c.left - m.right < 0.5f * o.h else m.cx > c.right && m.left - c.right < 0.6f * o.h)
                        }
                    }
            } || (Tune.otherCommas > 0f && c.top < eq.cy - 0.3f * h &&
                // or of digits above the side's reach (a denominator over this line): between two of them, at their feet
                listOf(-1, 1).all { side ->
                    ink.any { m ->
                        m !== c && m.bottom < eq.cy - 0.8f * h && c.bottom < eq.cy - 0.6f * h && m.height in 0.5f * h..1.4f * h && m.bottom < c.top + 0.15f * h &&
                            c.top in m.bottom - 0.4f * m.height..m.bottom + 0.3f * h &&
                            (if (side < 0) m.cx < c.left && c.left - m.right < 0.5f * h else m.cx > c.right && m.left - c.right < 0.6f * h)
                    }
                } ||
                // or a small part (the bar of a crossed 7) inside a digit wholly above the side
                (max(c.width, c.height) < 0.6f * h && c.bottom < eq.cy - 0.6f * h && upper.any { m ->
                    m !== c && rowAbove(m) &&
                        c.left > m.left - 0.15f * h && c.right < m.right + 0.15f * h && c.top > m.top - 0.15f * h && c.bottom < m.bottom + 0.15f * h
                }) ||
                // or a digit of a row of them wholly above the side, with a gap under it (160,79 over 2446,59)
                rowAbove(c))
        } else emptyList()
        val chain = ArrayList<Ink>()
        var frontier = if (dir < 0) eq.left else eq.right
        // A long straight slanted line crossing the line is part of a drawing beside the formula
        // (the section of a beam): through it the side took in the whole drawing.
        fun drawingLine(c: Ink) = Tune.drawingLines > 0f && c.width > 2f * h && c.height > 1.5f * h && MathLayout.straight(c, 0.06f)
        for (c in cand) {
            val near = if (dir < 0) c.right >= frontier - GAP * h else c.left <= frontier + GAP * h
            if (!near) break
            if (drawingLine(c)) continue
            if (c.top <= bandBottom && c.bottom >= bandTop && c.height < 8 * h && c !in otherLine) {
                chain += c
                frontier = if (dir < 0) min(frontier, c.left) else max(frontier, c.right)
            } else if (bridge(c, cand, bandTop, bandBottom, h) && c !in otherLine) {
                // The bar of a root (or an overline) reaching far past its content: the
                // content under it is near too.
                chain += c
                frontier = if (dir < 0) min(frontier, c.left) else max(frontier, c.right)
            }
        }
        if (chain.isEmpty()) return chain
        val minX = chain.minOf { it.left } - 0.2f * h
        val maxX = max(chain.maxOf { it.right } + 0.8f * h, if (dir < 0) eq.right + 0.3f * h else -Float.MAX_VALUE)
        val members = ArrayList(chain)
        var grew = true
        var rounds = 0
        while (grew && rounds++ < 6) {
            grew = false
            for (c in cand) {
                if (c in members || c.cx < minX || c.cx > maxX || c.height > 3 * h) continue
                val attached = members.any { m ->
                    // What a tall bracket or integral spans (not the long tail of a y).
                    (m.height > MathLayout.TALL * h && c.cy in m.top - 0.7f * h..m.bottom + 0.7f * h && c.cx in m.left - 0.3f * h..maxX &&
                        c !in otherLine && MathLayout.tallShape(MathLayout.Glyph(listOf(m)), h).let { it != null && it != '}' }) ||
                        (m.isDash && m.width > 0.45f * h && c.cx in m.left - 0.15f * h..m.right + 0.15f * h &&
                            max(c.top - m.bottom, m.top - c.bottom) < 0.8f * h && c !in otherLine &&
                            // a root with its long bar holds what is under the bar, not the line below its foot
                            (Tune.rootFoot <= 0f || m.height < 0.6f * h || c.top < m.bottom)) ||
                        // an exponent; not the foot of the line above (dense notes: its denominators)
                        (c.left in m.right - 0.3f * h..m.right + 0.8f * h && c.bottom in m.top - 0.5f * h..m.cy + 0.25f * h && c.height < 1.3f * h && c !in otherLine) ||
                        // the next or previous symbol of an exponent already taken (the − and 1 of ⁻¹)
                        (m !in chain && c !in otherLine && (c.left - m.right in -0.2f * h..0.6f * h || m.left - c.right in -0.2f * h..0.6f * h) &&
                            min(c.bottom, m.bottom) - max(c.top, m.top) >= 0.3f * min(c.height, m.height))
                }
                // A sign between two symbols already taken on its row (the − of 5x³ − x over a
                // fraction bar, a little farther from the bar than the letters).
                val between = !attached && c.height < 1.3f * h && c !in otherLine &&
                    members.any { c.cy in it.top..it.bottom && it.right <= c.left + 0.1f * h && c.left - it.right < 3 * h } &&
                    members.any { c.cy in it.top..it.bottom && it.left >= c.right - 0.1f * h && it.left - c.right < 1.5f * h }
                // A minus right before a symbol taken, under a bar (−0,38 over −3,25): it signs it.
                val leadingSign = !attached && !between && Tune.leadingSign > 0f && c.isDash && c.width < 1.2f * h && c !in otherLine &&
                    members.any { m -> m.isDash && m.width > 1.5f * h && c.left >= m.left - 0.3f * h && c.right <= m.right && abs(c.cy - m.cy) < 1.5f * h } &&
                    members.any { c.cy in it.top..it.bottom && it.left >= c.right - 0.1f * h && it.left - c.right < 0.8f * h && it.height < 1.3f * h }
                // A decimal comma between two digits taken, hanging just under their foot
                // (2446,59 in a denominator): small, below the line the band follows.
                // (the dot of ! right under its stem is no comma)
                val bangDot by lazy { ink.any { u -> u !== c && u.width < 0.3f * h && u.height > 0.5f * h && c.cx in u.left - 0.1f * h..u.right + 0.1f * h && u.bottom in c.top - 0.6f * h..c.top + 0.05f * h } }
                fun foot(m: Ink) = c.top in m.bottom - 0.4f * m.height..m.bottom + (if (Tune.commaLow > 0f && !bangDot) 0.4f else 0.3f) * h
                val small = max(c.width, c.height) < 0.4f * h
                val before = members.filter { foot(it) && it.right <= c.left + 0.15f * h && c.left - it.right < 0.5f * h }
                val after = members.filter { foot(it) && it.left >= c.right - 0.15f * h && it.left - c.right < 0.6f * h }
                // A comma drawn as a long slanting tick (0,1), up to half a line: under the right
                // edge of the digit before it, hanging below the feet of the digits on both sides.
                fun hangs(m: Ink) = c.bottom > m.bottom + 0.1f * h
                // (a tick, taller than wide, between symbols of digit size: not the o of log)
                fun digitSize(m: Ink) = m.height in 0.5f * h..1.3f * h
                val long = Tune.longCommas > 0f && !small && max(c.width, c.height) < 0.55f * h && c.height >= 0.8f * c.width &&
                    members.any { digitSize(it) && foot(it) && hangs(it) && it.cx < c.left && c.left - it.right < 0.5f * h } &&
                    members.any { digitSize(it) && foot(it) && hangs(it) && it.cx > c.right && it.left - c.right < 0.6f * h }
                // A small one may also start under the right edge of the digit before it (2051,0).
                val tucked = Tune.longCommas > 0f && small &&
                    members.any { digitSize(it) && foot(it) && it.cx < c.left && c.left - it.right < 0.5f * h } &&
                    members.any { digitSize(it) && foot(it) && it.cx > c.right && it.left - c.right < 0.6f * h }
                val comma = !attached && !between && c !in otherLine && ((small && before.isNotEmpty() && after.isNotEmpty()) || long || tucked)
                // A bar touching the top of a symbol taken, no wider than it (the bar of a 5 just over the band), is of it.
                val touching = !attached && !between && !comma && Tune.touchingStrokes > 0f && c.height < 1.3f * h && c !in otherLine &&
                    c.isDash && members.any { m ->
                        m in chain && c.left >= m.left - 0.1f * h && c.right <= m.right + 0.3f * h && c.cy < m.top + 0.25f * m.height &&
                            touches(c, m, 0.12f * h)
                    }
                if (attached || between || comma || touching || leadingSign) {
                    members += c
                    grew = true
                }
            }
        }
        return members
    }

    /** Some point of [a] within [tol] of some point of [b]. */
    private fun touches(a: Ink, b: Ink, tol: Float): Boolean {
        if (a.left > b.right + tol || b.left > a.right + tol || a.top > b.bottom + tol || b.top > a.bottom + tol) return false
        for (i in a.xs.indices) for (j in b.xs.indices) {
            if (abs(a.xs[i] - b.xs[j]) < tol && abs(a.ys[i] - b.ys[j]) < tol) return true
        }
        return false
    }

    /** A long bar just above the line with symbols of the line under it (√ over 2 + √2). */
    private fun bridge(c: Ink, cand: List<Ink>, bandTop: Float, bandBottom: Float, h: Float): Boolean =
        c.isDash && c.width > 1.2f * h && c.bottom < bandTop && c.bottom > bandTop - 1.2f * h &&
            cand.any { o -> o !== c && o.top <= bandBottom && o.bottom >= bandTop && o.cx in c.left..c.right && o.top > c.cy }

    /** Letters a graph or surface is defined by (y = …, z = …). */
    private val GRAPH_LETTERS = setOf("y", "z")

    private const val MAX_STROKE = 600f
    private const val MIN_EQ_WIDTH = 3.0f
    private const val MAX_EQ_WIDTH = 160f
    private const val MIN_H = 8f

    /** An inequality sign is the size of a letter, not of a drawing. */
    private const val MAX_SIGN = 120f
    private val GAP get() = Tune.sideGap
    private const val MAX_CANDIDATES = 6
    private const val MAX_COMBINATIONS = 40
    private const val MAX_READINGS = 6
    private const val MAX_TRIMS = 4
    private const val MAX_CHOICE_TRIES = 2000
    private const val CHAIN_GAP = 12f
    private const val RESCUE_READINGS = 6

    /** Signs a formula does not start with: a part read from one is the tail of a formula. */
    private const val LEADING_SIGNS = "+-−·×÷*/"

    // A crafted page must not keep the reader busy for long.
    private const val MAX_EQUALS = 150
    private const val MAX_SIDE = 300

    /** A system answer squeezed above the next line is still at least this part of its size. */
    private const val MIN_UNDER = 0.6f

    /** At most this many functions share one graph. */
    private const val MAX_CURVES = 4

    /** Graph sizes (line heights, see [Plot.WIDTH]) in units of a 1000-wide page. */
    private const val GRAPH_MIN = 22f
    private const val GRAPH_MAX = 30f

    /** Rough width of an answer character, in line heights (1.1·h text). */
    private const val CHAR_W = 0.6f
    private const val MAX_HOLES = 60
}

/** Cleans what the handwriting reader returned for one run. */
object MathText {
    private const val SAME_SHAPE = "CKOPSUVWXYZ"

    /** Function names as the reader often gets them wrong: 5in, c0s, tq, 1n, l0g. */
    private val FUNCTION_FIXES = listOf<Pair<String, String>>(
        "5in" to "sin", "Sin" to "sin", "sln" to "sin", "s1n" to "sin", "siu" to "sin",
        "c0s" to "cos", "co5" to "cos", "Cos" to "cos", "cOs" to "cos",
        "ctq" to "ctg", "tq" to "tg", "lq" to "lg", "1g" to "lg", "1n" to "ln", "Ln" to "ln",
        "l0g" to "log", "loq" to "log", "1og" to "log", "Log" to "log",
        "arc5in" to "arcsin", "arcc0s" to "arccos",
    ).map { (wrong, right) -> Regex("(?<![0-9A-Za-z])" + Regex.escape(wrong)) to right }
    fun normalize(raw: String): String {
        var s = raw.replace(" ", "").replace('—', '-').replace('–', '-').replace('−', '-').replace('_', '-').replace('&', 'd')
            .replace("In", "ln").replace("Ig", "lg").replace('÷', '/').replace("TT", "π")
        s = s.trim('=')
        for ((wrong, right) in FUNCTION_FIXES) s = wrong.replace(s, right)
        // These letters look the same in both cases; the reader picks either at random.
        s = s.map { if (it in SAME_SHAPE) it.lowercaseChar() else it }.joinToString("")
        val b = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            val prev = s.getOrNull(i - 1)
            val next = s.getOrNull(i + 1)
            val digitAround = (prev?.isDigit() == true || prev == null) && (next?.isDigit() == true || next == null) &&
                (prev?.isDigit() == true || next?.isDigit() == true)
            b.append(
                when {
                    (c == 'O' || c == 'o') && digitAround -> '0'
                    (c == 'l' || c == 'I' || c == '|') && digitAround && s.length > 1 -> '1'
                    c == 'S' && digitAround && s.length > 1 -> '5'
                    (c == 'x' || c == 'X' || c == '×') && prev?.isDigit() == true && next?.isDigit() == true -> '*'
                    c == '×' -> 'x'
                    else -> c
                },
            )
        }
        return b.toString()
    }
}
