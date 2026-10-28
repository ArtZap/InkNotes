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

    class Services(val repo: NotebookRepository, val prefs: AppPrefs, val handwriting: HandwritingIndex, val math: MathNotes, val backup: Backup, val previews: CoverPreviews)

    fun get(context: Context): Services = instance ?: run {
        val app = context.applicationContext
        InkTextures.store = androidx.ink.brush.StockTextureBitmapStore(app.resources)
        val repo = NotebookRepository(app.filesDir)
        val prefs = AppPrefs(app)
        Services(
            repo,
            prefs,
            HandwritingIndex(repo, { prefs.handwritingEnabled }, { prefs.handwritingEnabled = it }),
            MathNotes({ prefs.mathEnabled }, { prefs.mathEnabled = it }),
            Backup(app, repo, prefs),
            CoverPreviews(repo),
        )
            .also {
                instance = it
                repo.purgeOldTrash()
            }
    }
}
