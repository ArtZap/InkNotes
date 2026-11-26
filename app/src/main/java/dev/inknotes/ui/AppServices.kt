package dev.inknotes.ui

import android.content.Context
import dev.inknotes.data.Backup
import dev.inknotes.data.InkTextures
import dev.inknotes.data.NotebookRepository
import dev.inknotes.math.MathNotes
import dev.inknotes.search.HandwritingIndex

/**
 * Process-wide singletons: one IO thread for notebooks and one recognition thread must
 * survive activity recreation (rotation, theme change) instead of being duplicated.
 */
object AppServices {
    private var instance: Services? = null

    /** How long after the start background work (indexing, sync) waits for the first notebook. */
    const val QUIET_START_MS = 8_000L

    class Services(val repo: NotebookRepository, val prefs: AppPrefs, val handwriting: HandwritingIndex, val math: MathNotes, val backup: Backup, val previews: CoverPreviews, val cloud: CloudSync)

    fun get(context: Context): Services = instance ?: run {
        val app = context.applicationContext
        // Before any text is made: the interface language of this run.
        Language.init(app)
        // A dot low between digits: a decimal point in English handwriting, multiplication in Russian.
        dev.inknotes.math.Tune.decimalPoint = if (Language.handwritingEnglish(app)) 1f else 0f
        InkTextures.store = androidx.ink.brush.StockTextureBitmapStore(app.resources)
        val repo = NotebookRepository(app.filesDir)
        val prefs = AppPrefs(app)
        Services(
            repo,
            prefs,
            HandwritingIndex(repo, { prefs.handwritingEnabled }, { prefs.handwritingEnabled = it }, Language.handwritingEnglish(app)),
            MathNotes({ prefs.mathEnabled }, { prefs.mathEnabled = it }, dev.inknotes.math.FixStore(app.filesDir, background = true)),
            Backup(app, repo, prefs),
            CoverPreviews(repo),
            CloudSync(app, repo, prefs),
        )
            .also {
                instance = it
                val fixes = it.math.fixes
                repo.extras = object : NotebookRepository.Extras {
                    override fun files() = fixes.backupFiles()

                    override fun restore(files: Map<String, String>) {
                        fixes.restore(files)
                    }
                }
                it.handwriting.quietUntil = android.os.SystemClock.uptimeMillis() + QUIET_START_MS
                // Off the main thread: the start of the app does not wait for the disk.
                // Notes written over the lock screen by a run of the app that was stopped first.
                Thread {
                    runCatching { repo.purgeOldTrash() }
                    runCatching {
                        for (note in repo.lockNotes(leftOnly = true)) {
                            repo.mergeLockNote(note, prefs.quickNoteId, QuickNotes.TITLE)?.let { nb -> prefs.quickNoteId = nb.id }
                        }
                    }
                }.start()
            }
    }
}
