package dev.inknotes.math

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
)

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

    fun analyze(strokes: List<Ink>, reader: RunReader, pageWidth: Float = 1000f): List<PageAnswer> {
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
        class Def(val index: Int, val left: Expr, val right: Expr)
        val defs = ArrayList<Def>()
        // Equations outside a "{" (x³ = 6x² − 12x + 11): solved when one unknown is left.
        val single = ArrayList<Def>()
        val trailing = ArrayList<Pair<Int, List<Ink>>>()
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
            // f(x) among the readings ("flx" first) is a function being defined.
            val l = readings(left, eq.h, eq.cy, reader, Prefer.FIRST).let { all -> all.firstOrNull { it is Expr.Apply && graphParam(it) != null } ?: all.firstOrNull() } ?: continue
            // y = 2x + 1 is a formula in x: "2+1" (fewest letters) would lose it.
            // y = 5x − 1: a formula in x ("5x−1", not "bx−I" or "5−1").
            val surface = (l as? Expr.Sym)?.name?.equals("z", ignoreCase = true) == true
            val r = graphParam(l)?.let { v -> readings(right, eq.h, eq.cy, reader, Prefer.GRAPH, variable = v.ifEmpty { "x" }).firstOrNull() }
                // z = x² + y²: about letters, not the reading with fewest of them.
                ?: (if (surface) read(right, eq.h, eq.cy, reader, Prefer.FIRST) else null)
                ?: read(right, eq.h, eq.cy, reader, Prefer.NUMBERS) ?: continue
            if (Calc.isDefinition(l)) defs += Def(i, l, r) else single += Def(i, l, r)
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
            val l = readTrimmed(left, eq, reader, known) ?: continue
            // "3 =" has nothing to compute.
            if (l is Expr.Num) continue
            val after = side(eq, 1, eqs, rest, limit = null).maxOfOrNull { it.right } ?: eq.right
            val at = after + 0.4f * eq.h
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
                    PageAnswer(at, eq.cy, eq.h, Calc.evaluate(l, envAt(i)) ?: continue)
                }
            } catch (e: MathError) {
                PageAnswer(at, eq.cy, eq.h, Answer.Text(listOf(e.message ?: "?")), error = true)
            }
        }
        // y = x² − 3 and f(x) = …: the graph, beside the line where that place is free, else
        // under it; never over the ink or another graph.
        class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)
        val graphs = ArrayList<Area>()
        for (d in defs) {
            val param = graphParam(d.left)
            val graph: Answer = when {
                param != null -> runCatching { Calc.graph(d.right, envAt(d.index), param.ifEmpty { null }) }.getOrNull()
                // z = x² + y²: a surface.
                (d.left as? Expr.Sym)?.name?.equals("z", ignoreCase = true) == true -> runCatching { Calc.surface(d.right, envAt(d.index)) }.getOrNull()
                else -> null
            } ?: continue
            val eq = ordered[d.index]
            val lineInk = side(eq, -1, eqs, rest, limit = null) + side(eq, 1, eqs, rest, limit = null)
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
            out += PageAnswer(gx, gy, size, graph)
        }
        for (d in single) {
            if (d in asked || unknownOf(d) == null) continue
            val eq = ordered[d.index]
            val after = side(eq, 1, eqs, rest, limit = null).maxOfOrNull { it.right } ?: continue
            val at = after + 1.0f * eq.h
            trace?.invoke("equation: ${d.left} = ${d.right}")
            // An identity (a result written out by hand: (x³)' = 3x²) or an unreadable line says
            // nothing worth showing; errors are kept for a "{" only.
            val answer = runCatching { Calc.solve(listOf(d.left to d.right), envAt(d.index)) }.getOrNull() ?: continue
            if ((answer as? Answer.Text)?.lines == listOf("∞ решений")) continue
            out += PageAnswer(at, eq.cy, eq.h, answer, steps = Steps.of(listOf(d.left to d.right), envAt(d.index)))
        }
        val done = HashSet<Ink>()
        for ((i, eq) in ordered.withIndex()) {
            val brace = systemOf[eq] ?: continue
            if (!done.add(brace)) continue
            val members = ordered.filter { systemOf[it] === brace }
            solveSystem(brace, members, eqs, rest, reader, envAt(i))?.let { out += it }
        }
        val anchors = eqs + ineqs.map { it.first }
        for ((a, rel) in ineqs) {
            val left = side(a, -1, anchors, rest, limit = null)
            val right = side(a, 1, anchors, rest, limit = null)
            if (left.isEmpty() || right.isEmpty() || left.size > MAX_SIDE || right.size > MAX_SIDE) continue
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
    private fun graphParam(e: Expr): String? = when {
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

    private fun solveSystem(brace: Ink, members: List<Equals>, eqs: List<Equals>, rest: List<Ink>, reader: RunReader, env: Env): PageAnswer? {
        // Readings of every side; then the combination with as many unknowns as equations.
        val options = ArrayList<List<Expr>>()
        var bottom = brace.bottom
        var right = brace.right
        var h = 0f
        val sides = members.map { eq ->
            val l = side(eq, -1, eqs, rest, limit = brace.right)
            val r = side(eq, 1, eqs, rest, limit = null)
            if (l.isEmpty() || r.isEmpty() || l.size > MAX_SIDE || r.size > MAX_SIDE) return null
            Triple(eq, l, r)
        }
        val lefts = sides.map { (eq, l, _) -> readings(l, eq.h, eq.cy, reader, Prefer.LETTERS, maxLetters = members.size).ifEmpty { return null } }
        // The unknowns are the letters of the left sides (a = …, б = …): on the right a run that
        // reads as one of them or as digits is that letter (3б, not 36: б looks like a 6).
        val unknowns = lefts.flatMap { Algebra.freeVars(it[0]) }.filter { env.variable(it) == null }.toSet()
        for ((k, parts) in sides.withIndex()) {
            val (eq, l, r) = parts
            options += lefts[k]
            options += readings(r, eq.h, eq.cy, reader, Prefer.NUMBERS, known = { env.variable(it) != null }, favored = unknowns).ifEmpty { return null }
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
                if (!taken) return PageAnswer(at, brace.cy, h, answer, error, steps = if (error) null else Steps.of(equations, env))
            }
            val size = max(fit, MIN_UNDER * h)
            return PageAnswer(brace.left, bottom + 0.25f * size + lines.size * perLine * size / 2, size, answer, error, steps = if (error) null else Steps.of(equations, env))
        }
        return try {
            under(Calc.solve(equations, env), error = false)
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
    ): Expr? = readings(strokes, h, axis, reader, prefer, known).firstOrNull()

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
    ): List<Expr> {
        val pieces = try {
            MathLayout.formula(strokes, h, axis)
        } catch (e: MathError) {
            trace?.invoke("  layout failed: ${e.message}")
            return emptyList()
        }
        val holes = pieces.filterIsInstance<Piece.Hole>()
        if (holes.size > MAX_HOLES) return emptyList()
        val cands = holes.map { hole ->
            val texts = reader.read(hole.strokes, hole.h).map(MathText::normalize).filter { it.isNotEmpty() }
            // A lone upright is 1 as often as l or I.
            val main = texts.flatMap { if (it.length == 1 && it[0] in ONE_LIKE) listOf(it, "1") else listOf(it) }
            // In a system a misread coefficient (oz for 9z) adds an unknown, which the choice
            // of readings then rules out; elsewhere it would only add wrong guesses.
            val lookalikes = if (prefer == Prefer.LETTERS) main.take(2).mapNotNull(::digitLookalike) else emptyList()
            // Digits read as letters without context ("60" as Go): the same run as digits.
            val digits = if (prefer != Prefer.FIRST) main.take(3).mapNotNull(::asDigits) else emptyList()
            val all = (main.take(MAX_CANDIDATES) + digits + lookalikes).distinct().ifEmpty { return emptyList() }
            // Computing: digit readings first, so the combinations tried first are numbers
            // (1/5 · 5/1, not I/s · s/d) — there are too many to try them all.
            if (prefer == Prefer.NUMBERS) all.sortedBy { t -> if (t.all { it.isDigit() || it == ',' || it == '.' }) 0 else 1 } else all
        }
        trace?.invoke("  layout: " + pieces.joinToString("") { if (it is Piece.Text) it.s else "#" } + "  reads: " + cands)
        val found = ArrayList<Pair<Expr, Int>>()
        for (choice in combinations(cands)) {
            var k = 0
            val text = buildString {
                for (p in pieces) {
                    when (p) {
                        is Piece.Text -> append(p.s)
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
                    Prefer.NUMBERS -> names.count { it !in favored } - names.count { it in favored } + 2 * oddIntegrals(e)
                    // About letters ("2z", not "22"), but not more than the system has unknowns
                    // (x⁶ rather than t^G); 9z rather than oz (o, l, I are digits misread far more often).
                    Prefer.LETTERS -> (if (unknowns > maxLetters) 20 + unknowns else -unknowns) + 2 * names.count { it in RARE_UNKNOWNS }
                    Prefer.GRAPH -> names.count { !it.equals(variable, ignoreCase = true) } + if (names.none { it.equals(variable, ignoreCase = true) }) 1 else 0
                }
                if (found.none { it.first == e }) {
                    found += e to score
                    trace?.invoke("  parsed: $variant -> $e")
                }
                break
            }
            if (prefer != Prefer.LETTERS && favored.isEmpty() && found.count { it.second == 0 } >= MAX_READINGS) break
        }
        return found.sortedBy { it.second }.map { it.first }.take(MAX_READINGS)
    }

    /**
     * Reads the left side of an "=" to compute. Words before the formula on the same line
     * ("Ответ: 2+3 =") spoil the reading; then the parts left of wide gaps are dropped one by
     * one while that gives a cleaner reading (one that parses, with fewer unknown letters).
     */
    private fun readTrimmed(left: List<Ink>, eq: Equals, reader: RunReader, known: (String) -> Boolean): Expr? {
        fun score(e: Expr) = letters(e).count { !known(it) }
        var best = read(left, eq.h, eq.cy, reader, Prefer.NUMBERS, known)
        if (best != null && score(best) == 0) return best
        // Split points: gaps in the x-extent of the side wider than a symbol's width.
        val spans = left.map { it.left to it.right }.sortedBy { it.first }
        val cuts = ArrayList<Float>()
        var reach = spans[0].second
        for ((l, r) in spans.drop(1)) {
            if (l - reach > 0.7f * eq.h) cuts += l
            reach = max(reach, r)
        }
        for (cut in cuts.take(MAX_TRIMS)) {
            val part = left.filter { it.cx >= cut }
            if (part.isEmpty()) break
            val e = read(part, eq.h, eq.cy, reader, Prefer.NUMBERS, known) ?: continue
            if (best == null || score(e) < score(best)) best = e
            if (score(e) == 0) break
        }
        return best
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
            if (gap < 0.1f * w || gap > 0.85f * w || gap < max(a.height, b.height) * 0.6f) {
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
        val otherLine = cand.filter { c ->
            otherLines.any { o ->
                abs(c.cy - o.cy) < 0.45f * o.h && abs(c.cy - o.cy) < abs(c.cy - eq.cy) - 0.3f * h &&
                    // that line's own formula, not one in another column at the same height
                    c.cx in o.left - 10 * o.h..o.right + 10 * o.h
            }
        }.toSet()
        val chain = ArrayList<Ink>()
        var frontier = if (dir < 0) eq.left else eq.right
        for (c in cand) {
            val near = if (dir < 0) c.right >= frontier - GAP * h else c.left <= frontier + GAP * h
            if (!near) break
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
                            max(c.top - m.bottom, m.top - c.bottom) < 0.8f * h && c !in otherLine) ||
                        (c.left in m.right - 0.3f * h..m.right + 0.8f * h && c.bottom in m.top - 0.5f * h..m.cy + 0.25f * h && c.height < 1.3f * h) ||
                        // the next or previous symbol of an exponent already taken (the − and 1 of ⁻¹)
                        (m !in chain && (c.left - m.right in -0.2f * h..0.6f * h || m.left - c.right in -0.2f * h..0.6f * h) &&
                            min(c.bottom, m.bottom) - max(c.top, m.top) >= 0.3f * min(c.height, m.height))
                }
                // A sign between two symbols already taken on its row (the − of 5x³ − x over a
                // fraction bar, a little farther from the bar than the letters).
                val between = !attached && c.height < 1.3f * h && c !in otherLine &&
                    members.any { c.cy in it.top..it.bottom && it.right <= c.left + 0.1f * h && c.left - it.right < 3 * h } &&
                    members.any { c.cy in it.top..it.bottom && it.left >= c.right - 0.1f * h && it.left - c.right < 1.5f * h }
                if (attached || between) {
                    members += c
                    grew = true
                }
            }
        }
        return members
    }

    /** A long bar just above the line with symbols of the line under it (√ over 2 + √2). */
    private fun bridge(c: Ink, cand: List<Ink>, bandTop: Float, bandBottom: Float, h: Float): Boolean =
        c.isDash && c.width > 1.2f * h && c.bottom < bandTop && c.bottom > bandTop - 1.2f * h &&
            cand.any { o -> o !== c && o.top <= bandBottom && o.bottom >= bandTop && o.cx in c.left..c.right && o.top > c.cy }

    private const val MAX_STROKE = 600f
    private const val MIN_EQ_WIDTH = 3.5f
    private const val MAX_EQ_WIDTH = 160f
    private const val MIN_H = 8f

    /** An inequality sign is the size of a letter, not of a drawing. */
    private const val MAX_SIGN = 120f
    private val GAP get() = Tune.sideGap
    private const val MAX_CANDIDATES = 6
    private const val MAX_COMBINATIONS = 40
    private const val MAX_READINGS = 6
    private const val MAX_TRIMS = 4

    // A crafted page must not keep the reader busy for long.
    private const val MAX_EQUALS = 150
    private const val MAX_SIDE = 300

    /** A system answer squeezed above the next line is still at least this part of its size. */
    private const val MIN_UNDER = 0.6f

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
