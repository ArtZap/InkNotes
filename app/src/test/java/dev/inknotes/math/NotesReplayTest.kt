package dev.inknotes.math

import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * [RealNotesTest] on a computer: the owner's pages (tools/notes-ink.py writes them as JSON)
 * read with what ML Kit read on the emulator (reads.tsv or reads.tsv.gz beside them), in
 * NOTES_DIR. The pages are private: never in this repository, and the report stays in
 * build/notes-report.txt. Skipped without NOTES_DIR.
 */
class NotesReplayTest {
    @Test
    fun notes() {
        val dir = System.getenv("NOTES_DIR")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val readsFile = listOf("reads.tsv", "reads.tsv.gz").map { File(dir, it) }.firstOrNull { it.isFile }
        val reads = readsFile?.let { f ->
            val stream = if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()) else f.inputStream()
            stream.bufferedReader().useLines { RecordedReader.parse(it) }
        }.orEmpty()
        val report = StringBuilder()
        for (page in dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }) {
            val id = page.name.removeSuffix(".json")
            val ink = load(page)
            val reader = RecordedReader(reads["notes/$id"].orEmpty())
            val r = NotesJudge.measure(ink, reader)
            report.append("== $id: $r\n")
            // What is really written, where it has been transcribed (truth/<id>.tsv).
            File(dir, "truth/$id.tsv").takeIf { it.isFile }?.let { f ->
                val t = NotesTruth.score(MathPage.equalities(ink, reader), NotesTruth.parse(f.readLines().asSequence()))
                report.append("== $id: $t\n")
                t.lines.forEach { report.append("  $it\n") }
            }
            r.lines.forEach { report.append("  $it\n") }
            report.append("  -- ответы на странице\n")
            r.answers.forEach { report.append("  $it\n") }
        }
        println(report.lineSequence().filter { it.startsWith("==") }.joinToString("\n"))
        File("build").mkdirs()
        File("build/notes-report.txt").writeText(report.toString())
    }

    companion object {
        /** Strokes as tools/notes-ink.py writes them: [{"o":…,"x":[…],"y":[…],"t":[…]}, …]. */
        fun load(f: File): List<Ink> {
            val re = Regex("\\{\"o\": (\\d+), \"x\": \\[([^\\]]*)\\], \"y\": \\[([^\\]]*)\\], \"t\": \\[([^\\]]*)\\]\\}")
            return re.findAll(f.readText()).map { m ->
                fun floats(s: String) = s.split(',').filter { it.isNotBlank() }.map { it.trim().toFloat() }.toFloatArray()
                Ink(floats(m.groupValues[2]), floats(m.groupValues[3]), m.groupValues[1].toInt(), m.groupValues[4].split(',').filter { it.isNotBlank() }.map { it.trim().toLong() }.toLongArray())
            }.toList()
        }
    }
}
