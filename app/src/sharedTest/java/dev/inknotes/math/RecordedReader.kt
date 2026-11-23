package dev.inknotes.math

/**
 * The device's handwriting reader replayed: for each run (the orders of its strokes) what ML Kit
 * guessed as is and after arithmetic, recorded by FormulaReadTest. Answers as MathNotes does with
 * the same guesses: the two interleaved, glyph by glyph when ReadOptions says so, unsure when the
 * two first guesses differ. A run not recorded reads as nothing.
 */
class RecordedReader(
    private val reads: Map<List<Int>, Pair<List<String>, List<String>>>,
    /**
     * A run never read on the emulator (a rule joined 327 , 2 into one) is read as the
     * recorded runs it is made of, side by side: an approximation of what ML Kit would read,
     * for trying layout rules on the owner's pages before the emulator confirms them.
     */
    private val compose: Boolean = false,
) : RunReader {

    private fun key(run: List<Ink>) = run.map { it.order }.sorted()

    private fun raw(run: List<Ink>): List<String> {
        val (plain, math) = reads[key(run)] ?: return if (compose) composed(run) else emptyList()
        return ReadOptions.rank(ReadOptions.interleave(plain, math))
    }

    private fun composed(run: List<Ink>): List<String> {
        val byOrder = run.associateBy { it.order }
        val parts = reads.keys.filter { it.isNotEmpty() && byOrder.keys.containsAll(it) }.sortedByDescending { it.size }
        val used = HashSet<Int>()
        val chosen = ArrayList<List<Int>>()
        for (k in parts) if (k.none { it in used }) {
            chosen += k
            used += k
        }
        if (used.size != byOrder.size || chosen.size < 2) return emptyList()
        val texts = chosen.sortedBy { k -> k.minOf { byOrder.getValue(it).left } }.map { k ->
            val (plain, math) = reads.getValue(k)
            ReadOptions.rank(ReadOptions.interleave(plain, math)).firstOrNull() ?: return emptyList()
        }
        return listOf(texts.joinToString(""))
    }

    override fun read(strokes: List<Ink>, lineHeight: Float): List<String> {
        val read = raw(strokes)
        if (read.isEmpty() || !ReadOptions.glyphByGlyph) return read
        val glyphs = runCatching {
            MathLayout.items(strokes, lineHeight).filterIsInstance<MathLayout.G>().sortedBy { it.box.left }.map { it.glyph.strokes }
        }.getOrDefault(emptyList())
        return GlyphReading.extend(read, glyphs) { raw(it) }
    }

    override fun sure(strokes: List<Ink>): Boolean {
        val (plain, math) = reads[key(strokes)] ?: return true
        return plain.firstOrNull() == math.firstOrNull()
    }

    /** As both guess lists, for ranking by more than the interleaved order. */
    fun guesses(strokes: List<Ink>): Pair<List<String>, List<String>>? = reads[key(strokes)]

    companion object {
        private const val SEP = '\u001f'

        /** One line of reads.tsv: formula id, stroke orders, guesses as is, guesses after arithmetic. */
        fun line(id: String, run: List<Ink>, plain: List<String>, math: List<String>): String =
            listOf(id, run.map { it.order }.sorted().joinToString(","), plain.joinToString("$SEP"), math.joinToString("$SEP")).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }

        /** reads.tsv by formula id. */
        fun parse(lines: Sequence<String>): Map<String, Map<List<Int>, Pair<List<String>, List<String>>>> {
            val out = HashMap<String, HashMap<List<Int>, Pair<List<String>, List<String>>>>()
            for (l in lines) {
                val p = l.split('\t')
                if (p.size < 4) continue
                val orders = p[1].split(',').filter { it.isNotBlank() }.map { it.toInt() }
                fun list(s: String) = if (s.isEmpty()) emptyList() else s.split(SEP)
                out.getOrPut(p[0]) { HashMap() }[orders] = list(p[2]) to list(p[3])
            }
            return out
        }
    }
}
