package dev.inknotes.data

/**
 * What one sync with the cloud folder does, decided from three lists: the notebooks here, the
 * files there, and what was the same on both sides after the last sync. Plain JVM, so every
 * case is tested on the computer. Nothing is ever lost: a notebook changed on both sides is
 * kept twice, a notebook deleted on one side goes to the other side's trash.
 */
object SyncPlan {
    /** A notebook here: [updatedAt] changes with every change. */
    data class Local(val id: String, val title: String, val updatedAt: Long)

    /** A notebook file there: [version] changes with every upload; [updatedAt] is the notebook's time when uploaded. */
    data class Remote(val fileId: String, val notebookId: String, val version: Long, val updatedAt: Long)

    /** Both sides after the last sync of a notebook. */
    data class Synced(val fileId: String, val localAt: Long, val remoteVersion: Long)

    sealed class Action {
        abstract val notebookId: String

        /** New here: put it there. [fileId] null: a new file. */
        data class Upload(override val notebookId: String, val fileId: String?) : Action()

        /** Changed there (or new there): take it, replacing the one here if any. */
        data class Download(override val notebookId: String, val fileId: String) : Action()

        /** Changed on both sides: theirs is taken as a copy, then ours goes up over theirs. */
        data class Conflict(override val notebookId: String, val fileId: String) : Action()

        /** Deleted there: here it goes to the trash (it can be brought back from there). */
        data class TrashLocal(override val notebookId: String) : Action()

        /** Deleted here: there it goes to the trash of the cloud. */
        data class TrashRemote(override val notebookId: String, val fileId: String) : Action()

        /** The same on both sides already (first sync of a second device): only remembered. */
        data class Remember(override val notebookId: String, val fileId: String, val localAt: Long, val remoteVersion: Long) : Action()
    }

    /**
     * [busy]: notebooks open in the editor. They are not replaced or removed under it (left for
     * the next sync); what was written in them still goes up.
     */
    fun plan(local: List<Local>, remote: List<Remote>, synced: Map<String, Synced>, busy: Set<String> = emptySet()): List<Action> {
        val here = local.associateBy { it.id }
        // Two files of one notebook (a sync cut off between two devices): the latest counts.
        val there = remote.groupBy { it.notebookId }.mapValues { (_, files) -> files.maxBy { it.version } }
        val out = ArrayList<Action>()
        for (id in (here.keys + there.keys + synced.keys).sorted()) {
            val l = here[id]
            val r = there[id]
            val s = synced[id]
            when {
                l != null && r == null ->
                    // Synced before and gone there: deleted on the other device.
                    if (s != null) { if (id !in busy) out += Action.TrashLocal(id) } else out += Action.Upload(id, null)
                l == null && r != null ->
                    // Synced before and gone here: deleted on this device.
                    out += if (s != null) Action.TrashRemote(id, r.fileId) else Action.Download(id, r.fileId)
                l != null && r != null -> {
                    if (s == null) {
                        // Both have it, never synced from here: the same if the times agree.
                        out += when {
                            l.updatedAt == r.updatedAt -> Action.Remember(id, r.fileId, l.updatedAt, r.version)
                            id in busy -> continue
                            else -> Action.Conflict(id, r.fileId)
                        }
                        continue
                    }
                    // Any other time than at the last sync, earlier too: the clock may have been set back.
                    val localChanged = l.updatedAt != s.localAt
                    val remoteChanged = r.version != s.remoteVersion || r.fileId != s.fileId
                    when {
                        localChanged && remoteChanged -> if (id !in busy) out += Action.Conflict(id, r.fileId)
                        localChanged -> out += Action.Upload(id, r.fileId)
                        remoteChanged -> if (id !in busy) out += Action.Download(id, r.fileId)
                    }
                }
                // Gone on both sides: only the memory of it goes (nothing to do).
            }
        }
        return out
    }

    /**
     * Whether [actions] put so many notebooks here in the trash at once that it looks like a
     * mistake on the Drive (the folder deleted, emptied or replaced) rather than deletions made
     * on another device: at least [MASS_TRASH_MIN] of them, and half the library or more (or
     * [MASS_TRASH_MAX] and more). Then the sync asks before it does that; [localCount] is the
     * number of notebooks here.
     */
    fun massTrash(actions: List<Action>, localCount: Int): Boolean {
        val n = actions.count { it is Action.TrashLocal }
        return n >= MASS_TRASH_MIN && (n * 2 >= localCount || n >= MASS_TRASH_MAX)
    }

    const val MASS_TRASH_MIN = 2
    const val MASS_TRASH_MAX = 10
}
