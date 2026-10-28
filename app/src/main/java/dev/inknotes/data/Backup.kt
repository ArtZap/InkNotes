package dev.inknotes.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dev.inknotes.ui.AppPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily copies of every notebook into a folder the user picked (any storage provider:
 * a local folder, Google Drive…). The newest [KEEP] copies stay; older ones are removed.
 */
class Backup(private val context: Context, private val repo: NotebookRepository, private val prefs: AppPrefs) {

    sealed interface Result {
        data object Done : Result
        data object Skipped : Result
        class Failed(val reason: String) : Result
    }

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    val folder: Uri? get() = prefs.backupFolder?.let(Uri::parse)

    /** The folder's name as the storage provider shows it, or null. */
    fun folderName(): String? = folder?.let { tree ->
        runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    /** Remembers [tree] (permission kept across restarts) and makes the first copy. */
    fun setFolder(tree: Uri, onDone: (Result) -> Unit) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { context.contentResolver.takePersistableUriPermission(tree, flags) }.isFailure) {
            return onDone(Result.Failed("Эта папка не даёт постоянного доступа: выберите другую"))
        }
        folder?.takeIf { it != tree }?.let { old -> runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) } }
        prefs.backupFolder = tree.toString()
        prefs.lastBackupAt = 0L
        run(force = true, onDone)
    }

    fun turnOff() {
        val old = folder ?: return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        prefs.backupFolder = null
    }

    /** A copy if a day passed since the last one and something changed (or always, [force]). */
    fun run(force: Boolean = false, onDone: (Result) -> Unit = {}) {
        val tree = folder ?: return onDone(Result.Skipped)
        val last = prefs.lastBackupAt
        val now = System.currentTimeMillis()
        if (!force && now - last < INTERVAL_MS) return onDone(Result.Skipped)
        if (!running.compareAndSet(false, true)) return onDone(Result.Skipped)
        val resolver = context.contentResolver
        val name = "InkNotes ${SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(Date(now))}.inknotes"
        var created: Uri? = null
        val open = {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            created = DocumentsContract.createDocument(resolver, parent, MIME, name)
            created?.let { resolver.openOutputStream(it, "wt") }
        }
        // Whether anything changed is read on the IO thread too; then the copy on its own.
        Thread {
            val changed = force || runCatching { repo.lastChange() }.getOrDefault(Long.MAX_VALUE) > last
            if (!changed) {
                running.set(false)
                return@Thread
            }
            repo.backupAsync(open) { ok ->
                running.set(false)
                if (ok) {
                    prefs.lastBackupAt = now
                    Thread { prune(tree) }.start()
                    onDone(Result.Done)
                } else {
                    // A half-written copy must not count as one of the kept copies.
                    created?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                    val lost = context.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }
                    onDone(Result.Failed(if (lost) "Нет доступа к папке: выберите её заново" else "Не удалось записать копию в папку"))
                }
            }
        }.start()
    }

    /** Removes our copies beyond the newest [KEEP]; other files in the folder are not touched. */
    private fun prune(tree: Uri) {
        runCatching {
            val resolver = context.contentResolver
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val ours = ArrayList<Pair<String, String>>()
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (OUR_NAME.matches(name)) ours += c.getString(0) to name
                }
            }
            // Names carry the date, so their order is the order of the copies.
            for ((id, _) in ours.sortedByDescending { it.second }.drop(KEEP)) {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }.onFailure { Log.w(TAG, "pruning old backups failed", it) }
    }

    companion object {
        private const val TAG = "Backup"
        private const val MIME = "application/octet-stream"
        const val KEEP = 7
        private const val INTERVAL_MS = 24L * 3600 * 1000
        private val OUR_NAME = Regex("InkNotes \\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}( \\(\\d+\\))?\\.inknotes")
    }
}
