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
    /**
     * What one sync did. [held]: notebooks deleted on the Drive that were not put in the trash
     * here, because so many went at once ([SyncPlan.massTrash]): id and title, for the question.
     * [unread]: files on the Drive that could not be read (damaged, or from a newer app).
     * [folderLost]: the InkNotes folder the last sync used is gone from the Drive; notebooks went
     * up to a new one instead of being deleted here.
     */
    class Report(
        val uploaded: Int,
        val downloaded: Int,
        val conflicts: Int,
        val trashedHere: Int,
        val trashedThere: Int,
        val held: List<Pair<String, String>> = emptyList(),
        val unread: Int = 0,
        val folderLost: Boolean = false,
    ) {
        val nothing get() = uploaded + downloaded + conflicts + trashedHere + trashedThere == 0

        override fun toString() = buildList {
            if (folderLost) add("папки InkNotes на Диске больше нет — тетради отправлены в новую")
            if (uploaded > 0) add("отправлено $uploaded")
            if (downloaded > 0) add("получено $downloaded")
            if (conflicts > 0) add("изменены в двух местах $conflicts (сохранены обе версии)")
            if (trashedHere > 0) add("в корзину здесь $trashedHere")
            if (trashedThere > 0) add("в корзину на Диске $trashedThere")
            if (held.isNotEmpty()) add("на Диске нет ${held.size} тетрадей — здесь они не удалены, решите ниже")
            if (unread > 0) add("не удалось прочитать файлов с Диска: $unread")
        }.joinToString(", ").ifEmpty { "всё уже совпадает" }
    }

    /** What is remembered between syncs: the folder used, and both sides of every notebook. */
    private class State(var folder: String?, val notebooks: MutableMap<String, SyncPlan.Synced>)

    /**
     * One sync. [busy]: notebooks open in an editor now (saved first by the caller), asked again
     * before each step that would replace or remove one, so a notebook opened while the sync
     * runs is not changed under the editor either. [trashMany]: the user agreed that many
     * notebooks deleted on the Drive go to the trash here too ([SyncPlan.massTrash]).
     * Runs on the calling thread's coroutine; the network and disk work blocks it.
     */
    suspend fun run(busy: () -> Set<String> = { emptySet() }, trashMany: Boolean = false): Report {
        val state = loadState()
        // The folder by the id it had: moved or renamed on the Drive, it is still the same one.
        // Gone (deleted, in the trash), every notebook would look deleted on the other side:
        // what was synced with it is forgotten instead, and the notebooks go up to a new folder.
        var folderLost = false
        val folder = state.folder?.takeIf { api.exists(it) } ?: api.folder().also { found ->
            if (state.folder != null && state.folder != found) {
                folderLost = true
                state.notebooks.clear()
            }
            state.folder = found
            saveState(state)
        }
        val remoteFiles = api.list(folder)
        val remote = remoteFiles.mapNotNull { f ->
            val id = f.props[PROP_ID] ?: return@mapNotNull null
            SyncPlan.Remote(f.id, id, f.version, f.props[PROP_TIME]?.toLongOrNull() ?: 0L)
        }
        val notebooks = repo.io { list() }.associateBy { it.id }
        val local = notebooks.values.map { SyncPlan.Local(it.id, it.title, it.updatedAt) }
        val synced = state.notebooks
        val actions = SyncPlan.plan(local, remote, synced, busy())
        val holdTrash = !trashMany && SyncPlan.massTrash(actions, local.size)
        val held = ArrayList<Pair<String, String>>()
        var up = 0
        var down = 0
        var both = 0
        var trashHere = 0
        var trashThere = 0
        var unread = 0
        for (a in actions) {
            // Opened in the meantime: left for the next sync.
            val replaces = a is SyncPlan.Action.Download || a is SyncPlan.Action.Conflict || a is SyncPlan.Action.TrashLocal
            if (replaces && a.notebookId in notebooks && a.notebookId in busy()) continue
            when (a) {
                is SyncPlan.Action.Upload -> {
                    val nb = notebooks.getValue(a.notebookId)
                    synced[nb.id] = upload(folder, a.fileId, nb)
                    up++
                }
                is SyncPlan.Action.Download -> {
                    val r = remote.first { it.fileId == a.fileId }
                    val nb = download(a.fileId, replace = true)
                    if (nb == null) {
                        unread++
                        continue
                    }
                    synced[nb.id] = SyncPlan.Synced(a.fileId, nb.updatedAt, r.version)
                    down++
                }
                is SyncPlan.Action.Conflict -> {
                    // Theirs as a copy (a new notebook here, it goes up next time), ours over theirs.
                    if (download(a.fileId, replace = false) == null) {
                        unread++
                        continue
                    }
                    val nb = notebooks.getValue(a.notebookId)
                    synced[nb.id] = upload(folder, a.fileId, nb)
                    both++
                }
                is SyncPlan.Action.TrashLocal -> {
                    val nb = notebooks[a.notebookId]
                    if (holdTrash) {
                        // Kept as synced: asked about again until the user decides.
                        if (nb != null) held += nb.id to nb.title
                        continue
                    }
                    nb?.let(repo::delete)
                    synced.remove(a.notebookId)
                    trashHere++
                }
                is SyncPlan.Action.TrashRemote -> {
                    api.trash(a.fileId)
                    synced.remove(a.notebookId)
                    trashThere++
                }
                is SyncPlan.Action.Remember -> synced[a.notebookId] = SyncPlan.Synced(a.fileId, a.localAt, a.remoteVersion)
            }
            saveState(state)
        }
        // Gone on both sides: forgotten.
        synced.keys.retainAll(notebooks.keys + remote.map { it.notebookId })
        saveState(state)
        return Report(up, down, both, trashHere, trashThere, held, unread, folderLost)
    }

    /**
     * Notebooks [ids] that were deleted on the Drive are kept here after all: forgotten as
     * synced, so the next sync sends them up again as new.
     */
    fun keepHere(ids: Collection<String>) {
        val state = loadState()
        state.notebooks.keys.removeAll(ids.toSet())
        saveState(state)
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

    private fun loadState(): State = runCatching {
        val o = JSONObject(stateFile.readText())
        val folder = o.optString(KEY_FOLDER).ifEmpty { null }
        val map = o.keys().asSequence().filter { !it.startsWith("_") }.associateWith { id ->
            val s = o.getJSONObject(id)
            SyncPlan.Synced(s.getString("file"), s.getLong("local"), s.getLong("remote"))
        }.toMutableMap()
        State(folder, map)
    }.getOrElse { State(null, HashMap()) }

    private fun saveState(state: State) {
        val o = JSONObject()
        state.folder?.let { o.put(KEY_FOLDER, it) }
        for ((id, s) in state.notebooks) o.put(id, JSONObject().put("file", s.fileId).put("local", s.localAt).put("remote", s.remoteVersion))
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

        /** The id of the Drive folder in the state file (notebook ids never start with "_"). */
        private const val KEY_FOLDER = "_folder"

        /** One sync at a time in the whole app. */
        val running = AtomicBoolean(false)
    }
}
