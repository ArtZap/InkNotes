package dev.inknotes.ui

/**
 * The open tabs, kept across a restart of the app: notebook ids in tab order and the one shown
 * (or none: the library). Plain JVM.
 */
object TabsMemory {
    /** [side]: the notebook shown beside the active one (0.36), not among [ids]. */
    class Saved(val ids: List<String>, val active: String?, val side: String? = null)

    fun encode(ids: List<String>, active: String?, side: String? = null): String =
        ids.joinToString(",") + ";" + (active ?: "") + ";" + (side ?: "")

    fun decode(text: String?): Saved {
        if (text.isNullOrBlank()) return Saved(emptyList(), null)
        val parts = text.split(";")
        return Saved(
            parts[0].split(",").filter { it.isNotBlank() },
            parts.getOrElse(1) { "" }.ifBlank { null },
            parts.getOrElse(2) { "" }.ifBlank { null },
        )
    }

    /**
     * What to open again: the saved tabs whose notebooks still exist ([existing]), at most
     * [max] (the newest), and the one to show if it is among them.
     */
    fun restore(saved: Saved, existing: Set<String>, max: Int): Saved {
        val ids = saved.ids.distinct().filter { it in existing }.takeLast(max)
        return Saved(ids, saved.active?.takeIf { it in ids }, saved.side?.takeIf { it in existing && it !in ids })
    }
}
