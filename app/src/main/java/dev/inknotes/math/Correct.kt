package dev.inknotes.math

import dev.inknotes.data.tr

/**
 * A formula taken apart to be corrected by hand: every symbol with the other readings it could
 * have, and where it stands (on the line, raised, lowered). A choice of symbols and places
 * gives the formula's text again, read and answered as the page reads it.
 */
object Correct {
    const val LINE = 0
    const val UP = 1
    const val DOWN = 2

    /** A symbol taken out: a stray mark (the dot of an i read on its own), not part of the formula. */
    const val OFF = 3

    /**
     * One symbol as written: its [strokes], what it could be (best first: [candidates] are
     * parser text, "*" for a dot, "\pi" for π), on [level]. [sign] marks a sign told by its
     * shape (+, −, a dot); [unsure] a symbol the reader read two ways.
     */
    class Sym(val strokes: List<Ink>, val candidates: List<String>, val level: Int, val sign: Boolean, val unsure: Boolean) {
        val box = Box.of(strokes)
    }

    sealed class Part {
        class Raw(val s: String) : Part()
        class S(val index: Int) : Part()
    }

    /** One side of "=", or a whole formula: its structure with the symbols in it, as first read. */
    class Side(val strokes: List<Ink>, val parts: List<Part>, val syms: List<Sym>, val initial: List<Pick>)

    /** The candidate taken for a symbol, and its level. */
    data class Pick(val choice: Int, val level: Int)

    /**
     * Reads [strokes] symbol by symbol. When [target] is given (the expression the page read),
     * the first choices are the ones that give it; [learned] puts what the writer chose before
     * first among the other candidates.
     */
    fun side(strokes: List<Ink>, h: Float, axis: Float, reader: RunReader, target: Expr? = null, learned: Learned? = null, samples: Samples? = null): Side? {
        val (draft, options) = build(strokes, h, axis, reader, learned, samples, null) ?: return null
        if (target == null) return draft
        find(draft, options, target)?.let { return it }
        // The page read it otherwise than the runs read here give (as digits, a 1 for an l,
        // a sign for a degree): those readings first, so that the window shows what the page did.
        val chosen = MathPage.choiceFor(strokes, h, axis, reader, target) ?: return draft
        val (again, options2) = build(strokes, h, axis, reader, learned, samples, chosen) ?: return draft
        return find(again, options2, target) ?: again
    }

    /** The page's reading: the first combination of whole-run readings that gives [target]. */
    private fun find(draft: Side, holeOptions: List<List<List<Pair<Int, Int>>>>, target: Expr): Side? {
        var tried = 0
        for (choice in combos(holeOptions)) {
            if (++tried > MAX_TRIES) break
            val picks = draft.initial.toMutableList()
            for (hole in choice) for ((s, c) in hole) picks[s] = Pick(c, draft.syms[s].level)
            if (MathPage.parseText(text(draft, picks)) == target) return Side(draft.strokes, draft.parts, draft.syms, picks)
        }
        return null
    }

    /** [strokes] taken apart into symbols, and per run the readings that fit its symbols; [chosen] (per piece) first. */
    private fun build(strokes: List<Ink>, h: Float, axis: Float, reader: RunReader, learned: Learned?, samples: Samples?, chosen: List<String?>?): Pair<Side, List<List<List<Pair<Int, Int>>>>>? {
        if (strokes.isEmpty()) return null
        val pieces = runCatching { MathLayout.formula(strokes, h, axis) }.getOrNull() ?: return null
        val parts = ArrayList<Part>()
        val syms = ArrayList<Sym>()
        // Per hole: the readings of the whole run that fit its symbols, as indices into each
        // symbol's candidates; tried to find the page's reading.
        val holeOptions = ArrayList<List<List<Pair<Int, Int>>>>()
        for ((i, p) in pieces.withIndex()) {
            val page = chosen?.getOrNull(i)
            when (p) {
                is Piece.Text -> if (p.strokes.isEmpty()) {
                    parts += Part.Raw(p.s)
                } else {
                    parts += Part.S(syms.size)
                    syms += Sym(p.strokes, (listOfNotNull(page) + (SIGN_ALTS[p.s] ?: listOf(p.s))).distinct(), p.level, sign = true, unsure = false)
                }
                is Piece.Hole -> {
                    val raw = reader.read(p.strokes, p.h).map(MathText::normalize).filter { it.isNotEmpty() }.take(MAX_RUNS)
                    // As the page reads runs: a lone upright may be 1, letters may be digits misread.
                    val runs = (listOfNotNull(page) + raw + raw.filter { it.length == 1 && it[0] in "lI|/" }.map { "1" } + raw.take(3).mapNotNull(MathPage::asDigits)).distinct()
                    val n = p.glyphs.size
                    val fitting = runs.map(::tokens).filter { it.size == n }
                    // What the page read for it does not fit its symbols: read as a whole, as the page did.
                    val pageApart = page != null && tokens(page).size != n
                    if (pageApart || fitting.isEmpty() || n == 1 && runs.isNotEmpty() && fitting.size < runs.size && tokens(runs[0]).size != 1) {
                        // The reader saw the run otherwise than as its symbols ("sin" from two
                        // shapes): one wheel for the whole run, its readings as they are.
                        val cands = runs.ifEmpty { listOf("?") }
                        val index = syms.size
                        parts += Part.S(index)
                        syms += Sym(p.strokes, cands, p.level, sign = false, unsure = !reader.sure(p.strokes) || runs.size > 1 && n > 1)
                        holeOptions += listOf(listOf(index to 0))
                        continue
                    }
                    val first = syms.size
                    for (i in 0 until n) {
                        val glyph = p.glyphs[i]
                        val cands = LinkedHashSet<String>()
                        for (t in fitting) cands += t[i]
                        // Alone a symbol reads as itself, not as the word the run made.
                        if (n > 1) reader.read(glyph, p.h).map(MathText::normalize).filter { tokens(it).size == 1 }.take(MAX_ALONE).forEach { cands += it }
                        for (c in cands.toList().take(2)) LOOKALIKES[c]?.let { cands += it }
                        var ordered = learned?.order(cands.toList()) ?: cands.toList()
                        // The writer's own symbols: what they say comes right after the first.
                        val vote = samples?.vote(glyph)?.takeIf { it.sure }
                        if (vote != null && ordered.firstOrNull() != vote.token) ordered = listOf(ordered[0], vote.token) + (ordered.drop(1) - vote.token)
                        val differs = fitting.size > 1 && fitting[0][i] != fitting[1][i] || vote != null && vote.token != ordered[0]
                        parts += Part.S(syms.size)
                        syms += Sym(glyph, ordered.take(MAX_CANDIDATES), p.level, sign = false, unsure = differs || n == 1 && !reader.sure(p.strokes))
                    }
                    holeOptions += fitting.map { t -> (0 until n).map { i -> (first + i) to syms[first + i].candidates.indexOf(t[i]).coerceAtLeast(0) } }
                }
            }
        }
        val base = syms.map { Pick(0, it.level) }
        return Side(strokes, parts, syms, base) to holeOptions
    }

    private fun <T> combos(options: List<List<T>>): Sequence<List<T>> = sequence {
        if (options.any { it.isEmpty() }) return@sequence
        val idx = IntArray(options.size)
        while (true) {
            yield(options.indices.map { options[it][idx[it]] })
            var k = options.size - 1
            while (k >= 0 && ++idx[k] == options[k].size) {
                idx[k] = 0
                k--
            }
            if (k < 0) break
        }
    }

    /** The formula's text with [picks]: the chosen symbols, raised or lowered as chosen. */
    fun text(side: Side, picks: List<Pick>): String {
        val b = StringBuilder()
        for (p in side.parts) {
            when (p) {
                is Part.Raw -> b.append(p.s)
                is Part.S -> {
                    val sym = side.syms[p.index]
                    val pick = picks.getOrNull(p.index) ?: side.initial[p.index]
                    if (pick.level == OFF) continue
                    val tok = sym.candidates.getOrElse(pick.choice) { sym.candidates[0] }
                    b.append(placed(tok, sym.level, pick.level))
                }
            }
        }
        var s = b.toString()
        // A symbol moved out of an exponent or index may leave it empty.
        while (true) {
            val t = s.replace("^{}", "").replace("_{}", "")
            if (t == s) break
            s = t
        }
        return s
    }

    /** [tok], written on [from], put on [to]: braces closed and opened around it. */
    private fun placed(tok: String, from: Int, to: Int): String = when {
        from == to -> tok
        from == LINE -> (if (to == UP) "^{" else "_{") + tok + "}"
        to == LINE -> "}" + tok + (if (from == UP) "^{" else "_{")
        else -> "}" + (if (to == UP) "^{" else "_{") + tok + "}" + (if (from == UP) "^{" else "_{")
    }

    /** Symbols of a reading: `\name` as one, every other character alone. */
    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val name = if (s[i] == '\\') COMMANDS.firstOrNull { s.startsWith(it, i + 1) } else null
            if (name != null) {
                out += "\\" + name
                i += name.length + 1
            } else {
                out += s[i].toString()
                i++
            }
        }
        return out
    }

    /** A candidate as shown on a wheel: "·" for the parser's "*", "π" for "\pi". */
    fun show(tok: String): String = SHOWN[tok] ?: tokens(tok).joinToString("") { SHOWN[it] ?: it }

    /**
     * Which strokes these are, wherever they are in the page's list (their order changes when
     * strokes before them are erased): their points, rounded.
     */
    fun fingerprint(strokes: List<Ink>): String {
        var hash = 1469598103934665603L
        for (s in strokes.sortedWith(compareBy<Ink>({ it.left }, { it.top }))) {
            hash = hash * 1099511628211L + s.xs.size
            for (k in s.xs.indices) {
                hash = hash * 1099511628211L + (s.xs[k] * 2).toInt()
                hash = hash * 1099511628211L + (s.ys[k] * 2).toInt()
            }
        }
        return "${strokes.size}-${java.lang.Long.toHexString(hash)}"
    }

    /** What a formula gives: an answer, a graph or surface, or why there is none ([note]). */
    class Outcome(val answer: Answer?, val note: String?, val error: Boolean = false)

    /**
     * Answers [left] (`left =`) or [left] = [right] with the page's definitions [env], as the
     * page would: a value, the roots of an equation, a graph of y = … or a surface z = ….
     */
    fun outcome(left: String, right: String?, env: Env): Outcome {
        val l = MathPage.parseText(left) ?: return unreadable(left)
        return try {
            if (right == null) {
                val a = Calc.evaluate(l, env) ?: return Outcome(null, tr("Здесь нечего считать", "Nothing to compute here"))
                return Outcome(a, null)
            }
            val r = MathPage.parseText(right) ?: return unreadable(right)
            val param = MathPage.graphParam(l)
            when {
                param != null -> Calc.graph(r, env, param.ifEmpty { null })?.let { Outcome(it, null) }
                    ?: Outcome(null, tr("График не строится: формула не от одной буквы", "No graph: the formula is not in one letter"))
                (l as? Expr.Sym)?.name?.equals("z", ignoreCase = true) == true -> Calc.surface(r, env)?.let { Outcome(it, null) }
                    ?: Outcome(null, tr("Поверхность не строится: нужна формула от двух букв", "No surface: a formula in two letters is needed"))
                Calc.isDefinition(l) && Algebra.freeVars(r).none { it == Calc.definedName(l) } ->
                    Outcome(null, tr("Запомнено: ${Printer.expr(l)} — для строк ниже", "Remembered: ${Printer.expr(l)} — for the lines below"))
                else -> Outcome(Calc.solve(listOf(l to r), env), null)
            }
        } catch (e: MathError) {
            Outcome(null, e.message ?: "?", true)
        }
    }

    /**
     * Why [text] gives no answer: an empty place left; something the app does not compute
     * (a limit, a sum, a set — kept as written all the same); or a misread symbol.
     */
    fun unreadable(text: String): Outcome = when {
        Composer.EMPTY in text -> Outcome(null, tr("Заполните пустые места □", "Fill in the empty places □"))
        cannotCompute(text) -> Outcome(null, tr("Такое пока не умею считать — формула сохранится как написана", "This cannot be computed yet — the formula is kept as written"))
        else -> Outcome(null, tr("Не читается — проверьте выделенные символы", "Does not read — check the highlighted symbols"), true)
    }

    /** [text] has something the parser does not compute: a command other than its own. */
    fun cannotCompute(text: String): Boolean =
        Regex("\\\\([a-zA-Z]+)").findAll(text).any { it.groupValues[1] !in COMPUTED } || text.any { it in "≠∈∉⊂⊆∪∩∖∅∀∃¬∧∨⇒⇔≈≡∼≪∝∥⊥∠⟨⟩‖⌊⌈±∓" }

    private val COMPUTED = setOf(
        "frac", "sqrt", "int", "det", "mat", "dd", "pi", "cdot", "times", "div", "left", "right", "sin", "cos", "tan", "cot", "ln", "lg", "log", "exp",
        "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "max", "min", "infty", "circ",
    )

    /** Solves a system written [equations] (left and right text of each), as the page does. */
    fun outcomeSystem(equations: List<Pair<String, String>>, env: Env): Outcome {
        val parsed = equations.mapIndexed { k, (l, r) ->
            val le = MathPage.parseText(l)
            val re = MathPage.parseText(r)
            if (le == null || re == null) {
                val bad = if (le == null) l else r
                if (Composer.EMPTY in bad || cannotCompute(bad)) return unreadable(bad)
                return Outcome(null, tr("Уравнение ${k + 1} не читается — проверьте выделенные символы", "Equation ${k + 1} does not read — check the highlighted symbols"), true)
            }
            le to re
        }
        return try {
            Outcome(Calc.solve(parsed, env), null)
        } catch (e: MathError) {
            Outcome(null, e.message ?: "?", true)
        }
    }

    /** Signs told by their shape, and what else the same strokes could be. */
    private val SIGN_ALTS = mapOf(
        "+" to listOf("+", "t"),
        "-" to listOf("-"),
        "*" to listOf("*", "."),
        "°" to listOf("°", "0", "o"),
        "'" to listOf("'", "1", "l"),
    )

    /** Symbols the reader confuses with one another. */
    private val LOOKALIKES = mapOf(
        "1" to listOf("l", "7", "I"), "l" to listOf("1", "I"), "I" to listOf("1", "l"), "|" to listOf("1", "l"),
        "2" to listOf("z", "Z"), "z" to listOf("2"), "Z" to listOf("2", "z"),
        "0" to listOf("o", "O"), "o" to listOf("0", "a"), "O" to listOf("0"),
        "5" to listOf("s", "S"), "s" to listOf("5"), "S" to listOf("5", "s"),
        "6" to listOf("b", "G"), "b" to listOf("6"), "9" to listOf("g", "q"), "g" to listOf("9", "q"), "q" to listOf("9", "g"),
        "4" to listOf("y"), "y" to listOf("4", "g"), "7" to listOf("1", "T"), "8" to listOf("B"), "B" to listOf("8"),
        "x" to listOf("*", "X"), "X" to listOf("x"), "t" to listOf("+"), "+" to listOf("t"),
        "u" to listOf("v"), "v" to listOf("u"), "n" to listOf("h"), "h" to listOf("n"), "e" to listOf("c"), "c" to listOf("e", "("),
        "a" to listOf("\\alpha", "o"), "\\alpha" to listOf("a"), "p" to listOf("\\rho"), "w" to listOf("\\omega"), "3" to listOf("z"),
    )

    private val SHOWN = mapOf(
        "*" to "·", "-" to "−", "'" to "′", "\\pi" to "π", "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\theta" to "θ", "\\lambda" to "λ", "\\mu" to "μ", "\\rho" to "ρ", "\\sigma" to "σ", "\\phi" to "φ", "\\varphi" to "φ", "\\omega" to "ω",
        "\\infty" to "∞",
    )

    /** Names after a backslash read as one symbol (the longest that fits). */
    private val COMMANDS = listOf(
        "varepsilon", "varphi", "lambda", "alpha", "gamma", "delta", "theta", "sigma", "omega", "infty", "beta", "zeta", "iota", "kappa",
        "phi", "psi", "chi", "tau", "rho", "eta", "mu", "nu", "xi", "pi",
    ).sortedByDescending { it.length }

    private const val MAX_RUNS = 8
    private const val MAX_ALONE = 4
    private const val MAX_CANDIDATES = 8
    private const val MAX_TRIES = 200
}

/**
 * What the writer chose on the wheels before: for each symbol the reader put first, how often
 * each symbol was taken instead (itself included: a confirmed reading). Plain counts, kept on
 * the device only.
 */
class Learned(private val counts: MutableMap<String, MutableMap<String, Int>> = HashMap()) {

    @Synchronized
    fun record(read: String, chosen: String) {
        val m = counts.getOrPut(read) { HashMap() }
        m[chosen] = (m[chosen] ?: 0) + 1
    }

    /** [cands] (the reader's first first) with what was chosen instead of it more than once next. */
    @Synchronized
    fun order(cands: List<String>): List<String> {
        val first = cands.firstOrNull() ?: return cands
        val m = counts[first] ?: return cands
        val best = m.entries.filter { it.key != first && it.value >= 2 }.maxByOrNull { it.value }?.key ?: return cands
        return listOf(first, best) + (cands - setOf(first, best))
    }

    /**
     * What the writer means by [read], when they corrected it to the same symbol at least
     * [STRONG] times and more often than they kept it: else null.
     */
    @Synchronized
    fun meant(read: String): String? {
        val m = counts[read] ?: return null
        val (best, n) = m.entries.filter { it.key != read }.maxByOrNull { it.value }?.toPair() ?: return null
        return if (n >= STRONG && n > (m[read] ?: 0)) best else null
    }

    /**
     * The reader's readings of a run of [symbols], with what the writer means put in: for one
     * symbol the meant one first, for longer runs a reading with it after the first. The
     * reading changed is the first with as many symbols as the run has: when the reader ran
     * two symbols together, a later guess (or the run read glyph by glyph) still has them.
     */
    @Synchronized
    fun adjust(texts: List<String>, symbols: Int): List<String> {
        if (counts.isEmpty() || texts.isEmpty()) return texts
        val first = texts[0]
        val base = texts.firstOrNull { Correct.tokens(it).size == symbols } ?: return texts
        val toks = Correct.tokens(base)
        val fixed = toks.map { meant(it) ?: it }
        if (fixed == toks) return texts
        val alt = fixed.joinToString("")
        return if (symbols == 1) listOf(alt) + (texts - alt) else listOf(first, alt) + (texts.drop(1) - alt)
    }

    @Synchronized
    fun isEmpty() = counts.isEmpty()

    @Synchronized
    fun clear() = counts.clear()

    /** Takes in what [other] holds (restored from a backup into an empty store). */
    @Synchronized
    fun absorb(other: Learned) {
        for ((k, v) in other.counts) counts.getOrPut(k) { HashMap() }.putAll(v)
    }

    /** One line per pair: read, tab, chosen, tab, count. */
    @Synchronized
    fun serialize(): String = buildString {
        for ((r, m) in counts) for ((c, n) in m) append(r).append('\t').append(c).append('\t').append(n).append('\n')
    }

    companion object {
        const val STRONG = 3

        fun parse(text: String): Learned {
            val counts = HashMap<String, MutableMap<String, Int>>()
            for (line in text.lineSequence()) {
                val f = line.split('\t')
                if (f.size != 3) continue
                val n = f[2].toIntOrNull() ?: continue
                counts.getOrPut(f[0]) { HashMap() }[f[1]] = n
            }
            return Learned(counts)
        }
    }
}
