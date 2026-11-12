package dev.inknotes.data

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Rules for the files of automatic copies, apart from Android so that they are tested on the
 * computer: which file is a whole copy, which may be removed.
 */
object BackupFiles {
    /** A copy being written; it is renamed to its own name only once it reads back whole. */
    const val PARTIAL = ".partial"

    /** First entry of a copy: what it holds (see [NotebookRepository]'s bundle). */
    const val MANIFEST = "bundle.json"
    const val FORMAT = "inknotes-bundle"

    /** Names of our copies: the date and time in them orders them. */
    val NAME = Regex("InkNotes \\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}( \\(\\d+\\))?\\.inknotes")

    private val FILE = Regex("\"file\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun isCopy(name: String) = NAME.matches(name)

    fun isPartial(name: String) = name.endsWith(PARTIAL) && NAME.matches(name.removeSuffix(PARTIAL))

    /**
     * Does [input] hold a whole copy: every entry reads to its end with a right checksum, the
     * first is the bundle's manifest, and each notebook it lists is there.
     */
    fun isWhole(input: InputStream): Boolean = runCatching {
        ZipInputStream(input.buffered()).use { zip ->
            val first = zip.nextEntry ?: return false
            if (first.name != MANIFEST) return false
            val manifest = zip.readBytes().toString(Charsets.UTF_8)
            if (!Regex("\"format\"\\s*:\\s*\"$FORMAT\"").containsMatchIn(manifest)) return false
            // The files the manifest lists (JSON escapes "/" as "\/").
            val wanted = FILE.findAll(manifest).map { it.groupValues[1].replace("\\/", "/") }.toMutableSet()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = zip.nextEntry ?: break
                // Reading to the end checks the entry's length and checksum.
                while (zip.read(buf) >= 0) Unit
                wanted.remove(e.name)
            }
            wanted.isEmpty()
        }
    }.getOrDefault(false)

    /**
     * Copies to remove: only those older than [keep] copies known to be whole ([whole]). A copy
     * not known to be whole never counts toward the kept ones, so cut-off copies can't push
     * good ones out. [names] are copy names (any order).
     */
    fun toDelete(names: Collection<String>, whole: Set<String>, keep: Int): List<String> {
        val newestFirst = names.filter(::isCopy).sortedDescending()
        var good = 0
        val out = ArrayList<String>()
        for (n in newestFirst) {
            if (good >= keep) out += n else if (n in whole) good++
        }
        return out
    }

    /** Leftovers of copies cut off long ago: partial files older than [maxAgeMs]. */
    fun stalePartials(files: Map<String, Long>, now: Long, maxAgeMs: Long = 24L * 3600 * 1000): List<String> =
        files.filter { (name, modified) -> isPartial(name) && now - modified > maxAgeMs }.keys.toList()
}
