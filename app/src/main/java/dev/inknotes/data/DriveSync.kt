package dev.inknotes.data

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Syncing the library with a cloud folder: each notebook is one .inknote file there (the same
 * file as "Поделиться"). What to do is decided by [SyncPlan]; what both sides were after the
 * last sync is kept in [stateFile], written after every step, so a sync cut off anywhere
 * goes on from there next time.
 */
class DriveSync(
    private val repo: NotebookRepository,
    private val api: DriveApi,
    private val stateFile: File,
    private val cacheDir: File,
) {
    class Report(val uploaded: Int, val downloaded: Int, val conflicts: Int, val trashedHere: Int, val trashedThere: Int) {
        val nothing get() = uploaded + downloaded + conflicts + trashedHere + trashedThere == 0

        override fun toString() = buildList {
            if (uploaded > 0) add("отправлено $uploaded")
            if (downloaded > 0) add("получено $downloaded")
            if (conflicts > 0) add("изменены в двух местах $conflicts (сохранены обе версии)")
            if (trashedHere > 0) add("в корзину здесь $trashedHere")
            if (trashedThere > 0) add("в корзину на Диске $trashedThere")
        }.joinToString(", ").ifEmpty { "всё уже совпадает" }
    }

    /**
     * One sync. [busy]: notebooks open in the editor (saved first by the caller): not replaced
     * under it. Runs on the calling thread's coroutine; the network and disk work blocks it.
     */
    suspend fun run(busy: Set<String> = emptySet()): Report {
        val folder = api.folder()
        val remoteFiles = api.list(folder)
        val remote = remoteFiles.mapNotNull { f ->
            val id = f.props[PROP_ID] ?: return@mapNotNull null
            SyncPlan.Remote(f.id, id, f.version, f.props[PROP_TIME]?.toLongOrNull() ?: 0L)
        }
        val notebooks = repo.io { list() }.associateBy { it.id }
        val local = notebooks.values.map { SyncPlan.Local(it.id, it.title, it.updatedAt) }
        val state = loadState()
        val actions = SyncPlan.plan(local, remote, state, busy)
        var up = 0
        var down = 0
        var both = 0
        var trashHere = 0
        var trashThere = 0
        for (a in actions) {
            when (a) {
                is SyncPlan.Action.Upload -> {
                    val nb = notebooks.getValue(a.notebookId)
                    state[nb.id] = upload(folder, a.fileId, nb)
                    up++
                }
                is SyncPlan.Action.Download -> {
                    val r = remote.first { it.fileId == a.fileId }
                    val nb = download(a.fileId, replace = true) ?: continue
                    state[nb.id] = SyncPlan.Synced(a.fileId, nb.updatedAt, r.version)
                    down++
                }
                is SyncPlan.Action.Conflict -> {
                    // Theirs as a copy (a new notebook here, it goes up next time), ours over theirs.
                    download(a.fileId, replace = false) ?: continue
                    val nb = notebooks.getValue(a.notebookId)
                    state[nb.id] = upload(folder, a.fileId, nb)
                    both++
                }
                is SyncPlan.Action.TrashLocal -> {
                    notebooks[a.notebookId]?.let(repo::delete)
                    state.remove(a.notebookId)
                    trashHere++
                }
                is SyncPlan.Action.TrashRemote -> {
                    api.trash(a.fileId)
                    state.remove(a.notebookId)
                    trashThere++
                }
                is SyncPlan.Action.Remember -> state[a.notebookId] = SyncPlan.Synced(a.fileId, a.localAt, a.remoteVersion)
            }
            saveState(state)
        }
        // Gone on both sides: forgotten.
        state.keys.retainAll(notebooks.keys + remote.map { it.notebookId })
        saveState(state)
        return Report(up, down, both, trashHere, trashThere)
    }

    private suspend fun upload(folder: String, fileId: String?, nb: Notebook): SyncPlan.Synced {
        val tmp = File(cacheDir, "sync-up-${nb.id}.inknote")
        try {
            val ok = suspendCancellableCoroutine { c -> repo.exportArchiveAsync(nb, { tmp.outputStream() }) { c.resume(it) } }
            if (!ok) throw DriveError("Не удалось упаковать «${nb.title}»")
            val name = nb.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + ".inknote"
            val f = api.upload(folder, fileId, name, mapOf(PROP_ID to nb.id, PROP_TIME to nb.updatedAt.toString()), tmp)
            return SyncPlan.Synced(f.id, nb.updatedAt, f.version)
        } finally {
            tmp.delete()
        }
    }

    /** The notebook of [fileId] here: in place of the one with its id, or as a copy. */
    private suspend fun download(fileId: String, replace: Boolean): Notebook? {
        val tmp = File(cacheDir, "sync-down-$fileId.inknote")
        try {
            api.download(fileId, tmp)
            val ready = when (val r = suspendCancellableCoroutine { c -> repo.readArchiveAsync({ tmp.inputStream() }) { c.resume(it) } }) {
                is NotebookRepository.ArchiveImport.Ready -> r
                is NotebookRepository.ArchiveImport.Failed -> {
                    Log.w(TAG, "file $fileId from the Drive not read: ${r.reason}")
                    return null
                }
            }
            return suspendCancellableCoroutine { c -> repo.commitImportAsync(ready, replace = replace) { c.resume(it) } }
        } finally {
            tmp.delete()
        }
    }

    private fun loadState(): MutableMap<String, SyncPlan.Synced> = runCatching {
        val o = JSONObject(stateFile.readText())
        o.keys().asSequence().associateWith { id ->
            val s = o.getJSONObject(id)
            SyncPlan.Synced(s.getString("file"), s.getLong("local"), s.getLong("remote"))
        }.toMutableMap()
    }.getOrElse { HashMap() }

    private fun saveState(state: Map<String, SyncPlan.Synced>) {
        val o = JSONObject()
        for ((id, s) in state) o.put(id, JSONObject().put("file", s.fileId).put("local", s.localAt).put("remote", s.remoteVersion))
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(o.toString())
            tmp.delete()
        }
    }

    /** Forget what was synced (signed out, or another account): the next sync compares afresh. */
    fun forget() {
        stateFile.delete()
    }

    companion object {
        private const val TAG = "DriveSync"
        const val PROP_ID = "inknotesId"
        const val PROP_TIME = "inknotesUpdatedAt"

        /** One sync at a time in the whole app. */
        val running = AtomicBoolean(false)
    }
}
