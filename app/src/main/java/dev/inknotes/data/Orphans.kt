package dev.inknotes.data

/**
 * Pictures that no page uses any more (their page was deleted). Page files keep each picture's
 * name as plain text, so a name found in no page's bytes is used by none. Plain JVM.
 */
object Orphans {
    /** Which of [names] appear in any of [pages] (each a page file's bytes). */
    fun referenced(names: Set<String>, pages: Sequence<ByteArray>): Set<String> {
        if (names.isEmpty()) return emptySet()
        val left = names.toMutableSet()
        val found = HashSet<String>()
        for (bytes in pages) {
            // Names are ASCII: one byte a character, so Latin-1 text keeps them as they are.
            val text = String(bytes, Charsets.ISO_8859_1)
            val hits = left.filter { text.contains(it) }
            found += hits
            left -= hits.toSet()
            if (left.isEmpty()) break
        }
        return found
    }
}
