package dev.inknotes.math

import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * How much the writer's own corrections would help, on their transcribed pages (NOTES_DIR, kept
 * private): as if they had corrected every number side of the other pages in the correction
 * window (the symbols chosen counted, their shapes kept as samples), each page is read again
 * with what was learned from the others. Counts the left sides read to the written value: the
 * answer "expr =" would give. Prints only; writes build/personal-sim.txt.
 */
class PersonalSimTest {
    @Test
    fun simulate() {
        val dir = System.getenv("NOTES_DIR")?.let(::File)?.takeIf { it.isDirectory }
        Assume.assumeTrue("NOTES_DIR is not set", dir != null)
        checkNotNull(dir)
        val reads = GZIPInputStream(File(dir, "reads.tsv.gz").inputStream()).bufferedReader().useLines { RecordedReader.parse(it) }
        val pages = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.mapNotNull { f ->
            val id = f.name.removeSuffix(".json")
            val truth = File(dir, "truth/$id.tsv").takeIf { it.isFile } ?: return@mapNotNull null
            Triple(id, NotesReplayTest.load(f), NotesTruth.parse(truth.readLines().asSequence()))
        }
        val numbers = Printer.numbers
        Printer.numbers = Printer.Numbers.DECIMAL
        val out = StringBuilder()
        try {
            fun recorded(id: String) = RecordedReader(reads["notes/$id"].orEmpty(), compose = true)
            fun personal(rec: RunReader, learned: Learned, samples: Samples) = RunReader { run, h ->
                val texts = rec.read(run, h)
                if (texts.isEmpty()) {
                    texts
                } else {
                    val glyphs = runCatching {
                        MathLayout.items(run, h).filterIsInstance<MathLayout.G>().sortedBy { it.box.left }.map { it.glyph.strokes }
                    }.getOrDefault(emptyList())
                    Voices.adjust(learned.adjust(texts, glyphs.size), glyphs, samples)
                }
            }
            fun value(e: Expr) = (runCatching { Numeric.eval(e) }.getOrNull() as? Scalar)?.v
            // The corrections of one page: every number side the reader's guesses can give.
            fun teach(id: String, ink: List<Ink>, rows: List<NotesTruth.Row>, learned: Learned, samples: Samples): Int {
                val rec = recorded(id)
                val eqs = MathPage.equalities(ink, rec)
                var taught = 0
                for (r in rows) {
                    val e = NotesTruth.match(eqs, r) ?: continue
                    for ((side, sideInk) in listOf(r.left to e.leftInk, r.right to e.rightInk)) {
                        val v = (side as? NotesTruth.Side.Num)?.v ?: continue
                        if (sideInk.isEmpty()) continue
                        val choice = MathPage.choiceFor(sideInk, e.h, e.axis, rec, Expr.Num(v), accept = { x -> value(x)?.let { NotesTruth.same(it, v) } == true }) ?: continue
                        val pieces = runCatching { MathLayout.formula(sideInk, e.h, e.axis) }.getOrNull() ?: continue
                        taught++
                        for ((i, p) in pieces.withIndex()) {
                            if (p !is Piece.Hole) continue
                            val chosen = choice.getOrNull(i) ?: continue
                            val toks = Correct.tokens(chosen)
                            if (toks.size != p.glyphs.size) continue
                            toks.forEachIndexed { k, t -> samples.add(t, p.glyphs[k]) }
                            val first = rec.read(p.strokes, p.h).firstOrNull()?.let(MathText::normalize) ?: continue
                            val readToks = Correct.tokens(first)
                            if (readToks.size == toks.size) readToks.forEachIndexed { k, t -> learned.record(t, toks[k]) }
                        }
                    }
                }
                return taught
            }
            var base = 0
            var pers = 0
            var all = 0
            var lefts = 0
            for ((id, ink, rows) in pages) {
                val b = NotesTruth.score(MathPage.equalities(ink, recorded(id)), rows)
                val learned = Learned()
                val samples = Samples()
                var taught = 0
                for ((oid, oink, orows) in pages) if (oid != id) taught += teach(oid, oink, orows, learned, samples)
                val p = NotesTruth.score(MathPage.equalities(ink, personal(recorded(id), learned, samples)), rows)
                // learned from every page, this one too: what correcting as one goes would reach
                val l2 = Learned()
                val s2 = Samples()
                for ((oid, oink, orows) in pages) teach(oid, oink, orows, l2, s2)
                val a = NotesTruth.score(MathPage.equalities(ink, personal(recorded(id), l2, s2)), rows)
                out.append("%s: левые части %d из %d → по другим страницам %d → по всем %d (выучено сторон %d, образцов %d)\n".format(
                    id.take(8), b.leftRight, b.leftSides, p.leftRight, a.leftRight, taught, samples.count()))
                base += b.leftRight
                pers += p.leftRight
                all += a.leftRight
                lefts += b.leftSides
            }
            out.append("итого: сейчас %d из %d, по другим страницам %d, по всем %d\n".format(base, lefts, pers, all))
        } finally {
            Printer.numbers = numbers
        }
        File("build").mkdirs()
        File("build/personal-sim.txt").writeText(out.toString())
        println(out)
    }
}
