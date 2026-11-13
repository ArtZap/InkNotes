package dev.inknotes.ui

/**
 * The open tabs, kept across a restart of the app: notebook ids in tab order and the one shown
 * (or none: the library). Plain JVM.
 */
object TabsMemory {
    /**
     * [side]: the notebook shown beside the active one (0.36), not among [ids]; [sideIds]: the
     * tabs of that half (0.39), [side] among them.
     */
    class Saved(val ids: List<String>, val active: String?, val side: String? = null, val sideIds: List<String> = listOfNotNull(side))

    fun encode(ids: List<String>, active: String?, side: String? = null, sideIds: List<String> = listOfNotNull(side)): String =
        ids.joinToString(",") + ";" + (active ?: "") + ";" + (side ?: "") + ";" + sideIds.joinToString(",")

    fun decode(text: String?): Saved {
        if (text.isNullOrBlank()) return Saved(emptyList(), null)
        val parts = text.split(";")
        val side = parts.getOrElse(2) { "" }.ifBlank { null }
        // Saved before 0.39: the half beside had only its one notebook.
        val sideIds = parts.getOrNull(3)?.split(",")?.filter { it.isNotBlank() } ?: listOfNotNull(side)
        return Saved(
            parts[0].split(",").filter { it.isNotBlank() },
            parts.getOrElse(1) { "" }.ifBlank { null },
            side,
            sideIds,
        )
    }

    /**
     * What to open again: the saved tabs whose notebooks still exist ([existing]), at most
     * [max] (the newest), and the one to show if it is among them.
     */
    fun restore(saved: Saved, existing: Set<String>, max: Int): Saved {
        val ids = saved.ids.distinct().filter { it in existing }.takeLast(max)
        val sideIds = saved.sideIds.distinct().filter { it in existing && it !in ids }.takeLast(max)
        val side = saved.side?.takeIf { it in sideIds } ?: sideIds.lastOrNull()
        return Saved(ids, saved.active?.takeIf { it in ids }, side, sideIds)
    }
}
