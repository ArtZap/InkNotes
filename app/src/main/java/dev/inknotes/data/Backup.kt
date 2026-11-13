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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

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
        if (folder != tree) prefs.backupWhole = emptySet()
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

    /**
     * A copy if a day passed since the last one and something changed (or always, [force]).
     * [onDone] is called exactly once, on the main thread.
     *
     * The copy is written as `….partial`, read back whole, and only then given its name, so a
     * copy cut off (the system may stop the app at any time) is never taken for a good one.
     * Where the storage can't rename, the copy is written under its name and read back; only
     * copies that read back are counted when old ones are removed.
     */
    fun run(force: Boolean = false, onDone: (Result) -> Unit = {}) {
        val done = once(onDone)
        val tree = folder ?: return done(Result.Skipped)
        val last = prefs.lastBackupAt
        val now = System.currentTimeMillis()
        if (!force && now - last < INTERVAL_MS) return done(Result.Skipped)
        if (!running.compareAndSet(false, true)) return done(Result.Skipped)
        val resolver = context.contentResolver
        val name = "InkNotes ${SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(Date(now))}.inknotes"
        var created: Uri? = null
        var renames = false
        val written = java.util.concurrent.atomic.AtomicLong(-1)
        val open = {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val partial = DocumentsContract.createDocument(resolver, parent, MIME, name + BackupFiles.PARTIAL)
            renames = partial != null && supportsRename(partial)
            created = if (renames || partial == null) partial else {
                // No renaming here: the copy is written under its own name and checked after.
                runCatching { DocumentsContract.deleteDocument(resolver, partial) }
                DocumentsContract.createDocument(resolver, parent, MIME, name)
            }
            created?.let { resolver.openOutputStream(it, "wt") }?.let { Counting(it, written) }
        }
        // Whether anything changed is read off the main thread too; then the copy on its own.
        Thread {
            val changed = force || runCatching { repo.lastChange() }.getOrDefault(Long.MAX_VALUE) > last
            if (!changed) {
                running.set(false)
                return@Thread done(Result.Skipped)
            }
            repo.backupAsync(open) { ok ->
                Thread {
                    val doc = created
                    var final: Uri? = null
                    if (ok && doc != null && isWhole(doc, written.get())) {
                        final = if (renames) runCatching { DocumentsContract.renameDocument(resolver, doc, name) }.getOrNull() else doc
                    }
                    if (final == null) {
                        // A half-written or unreadable copy must not stay as one of the copies.
                        doc?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                        running.set(false)
                        val lost = resolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }
                        return@Thread done(Result.Failed(if (lost) "Нет доступа к папке: выберите её заново" else "Не удалось записать копию в папку"))
                    }
                    prefs.backupWhole = prefs.backupWhole + name
                    prefs.lastBackupAt = now
                    prune(tree)
                    running.set(false)
                    done(Result.Done)
                }.start()
            }
        }.start()
    }

    /** [onDone] on the main thread, and only the first time. */
    private fun once(onDone: (Result) -> Unit): (Result) -> Unit {
        val called = java.util.concurrent.atomic.AtomicBoolean(false)
        return { r -> if (called.compareAndSet(false, true)) main.post { onDone(r) } }
    }

    private fun supportsRename(doc: Uri): Boolean = runCatching {
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
        } ?: false
    }.getOrDefault(false)

    /**
     * The copy just written is all there. On this device's storage it is read back whole (cheap).
     * From a cloud provider reading back means downloading it again every day: there the size
     * the provider reports is compared with what was written, and the whole copy is read back
     * once a week (or whenever the provider does not tell the size).
     */
    private fun isWhole(doc: Uri, written: Long): Boolean {
        val local = doc.authority == LOCAL_STORAGE
        val weekly = System.currentTimeMillis() - prefs.backupReadBackAt > READ_BACK_EVERY_MS
        if (!local && !weekly && written > 0) {
            sizeOf(doc)?.let { return it == written }
        }
        return readsBackWhole(doc).also { if (it) prefs.backupReadBackAt = System.currentTimeMillis() }
    }

    private fun sizeOf(doc: Uri): Long? = runCatching {
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()

    /** Counts what goes through to the copy; [total] is set when it is closed. */
    private class Counting(out: java.io.OutputStream, private val total: java.util.concurrent.atomic.AtomicLong) : java.io.FilterOutputStream(out) {
        private var n = 0L

        override fun write(b: Int) {
            out.write(b)
            n++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            n += len
        }

        override fun close() {
            super.close()
            total.set(n)
        }
    }

    private fun readsBackWhole(doc: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(doc)?.use(BackupFiles::isWhole) ?: false
    }.getOrDefault(false)

    /**
     * Removes copies older than the newest [KEEP] copies known to be whole, and leftovers of
     * copies cut off more than a day ago; other files in the folder are not touched.
     */
    private fun prune(tree: Uri) {
        runCatching {
            val resolver = context.contentResolver
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val ids = HashMap<String, String>()
            val modified = HashMap<String, Long>()
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (BackupFiles.isCopy(name) || BackupFiles.isPartial(name)) {
                        ids[name] = c.getString(0)
                        modified[name] = if (c.isNull(2)) 0L else c.getLong(2)
                    }
                }
            }
            val copies = ids.keys.filter(BackupFiles::isCopy)
            val whole = prefs.backupWhole.filter { it in ids }.toSet()
            val gone = BackupFiles.toDelete(copies, whole, KEEP) + BackupFiles.stalePartials(modified, System.currentTimeMillis())
            // One copy that can't be removed does not keep the others.
            val removed = gone.filter { name ->
                runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, ids.getValue(name))) }
                    .onFailure { Log.w(TAG, "could not remove old backup $name", it) }
                    .getOrDefault(false)
            }
            prefs.backupWhole = whole - removed.toSet()
        }.onFailure { Log.w(TAG, "pruning old backups failed", it) }
    }

    companion object {
        private const val TAG = "Backup"
        private const val MIME = "application/octet-stream"
        const val KEEP = 7
        private const val INTERVAL_MS = 24L * 3600 * 1000
        private const val READ_BACK_EVERY_MS = 7 * INTERVAL_MS
        private const val LOCAL_STORAGE = "com.android.externalstorage.documents"
    }
}
