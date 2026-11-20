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
            if (folderLost) add(tr("папки InkNotes на Диске больше нет — тетради отправлены в новую", "the InkNotes folder is gone from the Drive — notebooks were sent to a new one"))
            if (uploaded > 0) add(tr("отправлено $uploaded", "sent $uploaded"))
            if (downloaded > 0) add(tr("получено $downloaded", "received $downloaded"))
            if (conflicts > 0) add(tr("изменены в двух местах $conflicts (сохранены обе версии)", "changed in two places $conflicts (both versions kept)"))
            if (trashedHere > 0) add(tr("в корзину здесь $trashedHere", "to the trash here $trashedHere"))
            if (trashedThere > 0) add(tr("в корзину на Диске $trashedThere", "to the trash on the Drive $trashedThere"))
            if (held.isNotEmpty()) add(tr("пропало сразу ${held.size} тетрадей — ничего не удалено, решите ниже", "${held.size} notebooks vanished at once — nothing was deleted, decide below"))
            if (unread > 0) add(tr("не удалось прочитать файлов с Диска: $unread", "files on the Drive that could not be read: $unread"))
        }.joinToString(", ").ifEmpty { tr("всё уже совпадает", "everything is already in sync") }
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
        val synced = state.notebooks
        // A notebook rebuilt here from its pages (its notebook.json was damaged) lacks its page
        // order, title and PDF: it must not go up over the whole copy on the Drive. It becomes a
        // notebook of its own, and the copy on the Drive comes down in its place.
        val remoteIds = remote.map { it.notebookId }.toSet()
        // Read once first: a damaged notebook.json is recovered while the library is read.
        repo.io { listForSync() }
        for (id in repo.io { recoveredIds() }) {
            if (id in busy()) continue
            if (id in remoteIds && id in synced) {
                if (repo.io { detachRecovered(id) } != null) {
                    synced.remove(id)
                    saveState(state)
                }
            } else {
                // Nothing of it on the Drive to write over: it syncs as any other from now on
                // (else it would be split off again at every sync).
                repo.io { settleRecovered(id) }
            }
        }
        val notebooks = repo.io { listForSync() }.associateBy { it.id }
        // Here but unreadable for now: neither deleted here nor to be replaced.
        val unknown = repo.unlisted
        val local = notebooks.values.map { SyncPlan.Local(it.id, it.title, it.updatedAt) }
        val actions = SyncPlan.plan(local, remote, synced, busy() + unknown)
        val holdTrash = !trashMany && SyncPlan.massTrash(actions, local.size, remote.map { it.notebookId }.distinct().size)
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
            // Unreadable here for now: left alone both ways until it can be read again.
            if (a.notebookId in unknown) continue
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
                    if (holdTrash) {
                        // So many gone here at once (a library that could not be read?): asked first.
                        val name = remoteFiles.firstOrNull { it.id == a.fileId }?.name?.removeSuffix(".inknote") ?: "?"
                        held += a.notebookId to name
                        continue
                    }
                    // Every file of it: an older second one would bring it back next time.
                    for (f in remote.filter { it.notebookId == a.notebookId }) api.trash(f.fileId)
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
            if (!ok) throw DriveError(tr("Не удалось упаковать «${nb.title}»", "Could not pack “${nb.title}”"))
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

    /**
     * What the last sync left, or the copy before it if that file is damaged. Neither readable:
     * a fresh start, which only compares (notebooks changed on one side become two copies, none
     * is lost).
     */
    private fun loadState(): State = readState(stateFile) ?: readState(backupFile()) ?: State(null, HashMap())

    private fun backupFile() = File(stateFile.parentFile, stateFile.name + ".bak")

    private fun readState(file: File): State? = if (!file.isFile) null else runCatching {
        val o = JSONObject(file.readText())
        val folder = o.optString(KEY_FOLDER).ifEmpty { null }
        val map = o.keys().asSequence().filter { !it.startsWith("_") }.associateWith { id ->
            val s = o.getJSONObject(id)
            SyncPlan.Synced(s.getString("file"), s.getLong("local"), s.getLong("remote"))
        }.toMutableMap()
        State(folder, map)
    }.onFailure { Log.w(TAG, "sync state ${file.name} unreadable", it) }.getOrNull()

    private fun saveState(state: State) {
        val o = JSONObject()
        state.folder?.let { o.put(KEY_FOLDER, it) }
        for ((id, s) in state.notebooks) o.put(id, JSONObject().put("file", s.fileId).put("local", s.localAt).put("remote", s.remoteVersion))
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(o.toString())
        // The last good one is kept: a damaged state file does not start the sync afresh.
        if (stateFile.isFile) runCatching { stateFile.copyTo(backupFile(), overwrite = true) }
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(o.toString())
            tmp.delete()
        }
    }

    /** Forget what was synced (signed out, or another account): the next sync compares afresh. */
    fun forget() {
        stateFile.delete()
        backupFile().delete()
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
