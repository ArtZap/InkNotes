package dev.inknotes.data

/**
 * What happens to a page file that could not be read, and to the copies of damaged pages kept
 * next to it (`<page>.ink.broken-<time>`, `<page>.ink.kept-<time>`). Plain JVM.
 */
object PageCopies {
    enum class Failure {
        /** The file was read but is not a page: it is set aside and the page starts empty. */
        DAMAGED,

        /** The file could not be read now (storage, memory) or is from a newer app: it stays as it is, the page can't be changed. */
        LOCKED,
    }

    /** [reading]: the error came while the bytes were read from storage, not while they were decoded. */
    fun failure(t: Throwable, reading: Boolean): Failure = when {
        reading -> Failure.LOCKED
        t is NewerFormatException -> Failure.LOCKED
        t is OutOfMemoryError -> Failure.LOCKED
        t is Exception -> Failure.DAMAGED
        else -> Failure.LOCKED
    }

    const val KEEP = 3
    private val COPY = Regex("(.+\\.ink)\\.(broken|kept)-(\\d+)")

    /** The page file a kept copy belongs to, or null if [name] is not one. */
    fun pageOf(name: String): String? = COPY.matchEntire(name)?.groupValues?.get(1)

    /**
     * Copies of page [page] to remove from [names] (a folder listing): none unless the page has
     * just been saved anew and read back ([savedAndRead]); then all but the newest [KEEP].
     */
    fun toDelete(names: Collection<String>, page: String, savedAndRead: Boolean): List<String> {
        if (!savedAndRead) return emptyList()
        return names.mapNotNull { n -> COPY.matchEntire(n)?.takeIf { it.groupValues[1] == page }?.let { n to it.groupValues[3].toLong() } }
            .sortedByDescending { it.second }
            .drop(KEEP)
            .map { it.first }
    }
}
