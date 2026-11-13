package dev.inknotes.ui

/**
 * The open tabs, kept across a restart of the app: notebook ids in tab order and the one shown
 * (or none: the library). Plain JVM.
 */
object TabsMemory {
    class Saved(val ids: List<String>, val active: String?)

    fun encode(ids: List<String>, active: String?): String = ids.joinToString(",") + ";" + (active ?: "")

    fun decode(text: String?): Saved {
        if (text.isNullOrBlank()) return Saved(emptyList(), null)
        val (list, active) = text.split(";", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return Saved(list.split(",").filter { it.isNotBlank() }, active.ifBlank { null })
    }

    /**
     * What to open again: the saved tabs whose notebooks still exist ([existing]), at most
     * [max] (the newest), and the one to show if it is among them.
     */
    fun restore(saved: Saved, existing: Set<String>, max: Int): Saved {
        val ids = saved.ids.distinct().filter { it in existing }.takeLast(max)
        return Saved(ids, saved.active?.takeIf { it in ids })
    }
}
