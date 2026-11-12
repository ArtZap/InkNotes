package dev.inknotes.ui

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.inknotes.R
import dev.inknotes.data.Notebook
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PaperKind
import dev.inknotes.editor.PenButtonBinding
import kotlinx.coroutines.launch

/** The notebook of quick notes: made the first time, an empty page at its end to write on. */
object QuickNotes {
    const val TITLE = "Быстрые заметки"

    /** Asked to open a notebook (from the widget): its id. */
    const val EXTRA_NOTEBOOK = "dev.inknotes.NOTEBOOK"

    suspend fun prepare(repo: NotebookRepository, prefs: AppPrefs): Notebook = repo.io {
        val nb = list().firstOrNull { it.id == prefs.quickNoteId }
            ?: create(TITLE, PaperKind.LINED).also { prefs.quickNoteId = it.id }
        val last = nb.pageIds.lastOrNull()
        val empty = last != null && loadPage(nb, last).let { it.strokes.isEmpty() && it.fills.isEmpty() && it.images.isEmpty() }
        if (empty) nb else insertPage(nb, nb.pageIds.size)
    }

    fun intent(context: Context) = Intent(context, QuickNoteActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}

/**
 * A note at once, even over the lock screen: only the quick notes notebook, on an empty page.
 * Locked, nothing else of the library can be reached from here (back closes it); the other
 * notebooks open after unlocking, in the app.
 */
class QuickNoteActivity : ComponentActivity() {
    private var controller by mutableStateOf<EditorController?>(null)
    private lateinit var penButton: PenButtonBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shown over the lock screen and waking it (the manifest says the same for Android 8.1+).
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val services = AppServices.get(this)
        val prefs = services.prefs
        penButton = PenButtonBinding(this)
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        lifecycleScope.launch {
            val nb = QuickNotes.prepare(services.repo, prefs)
            val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            controller = EditorController(services.repo, nb, penButton, prefs, dark, services.handwriting, services.math).also {
                it.openAtPage = nb.pageIds.lastIndex
                it.showStatus(if (locked) "Быстрая заметка поверх экрана блокировки. Остальные тетради — после разблокировки" else "Быстрая заметка: пишите, она сохранится сама")
            }
        }
        setContent {
            val dark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            InkNotesTheme(dark, prefs.uiStyle) {
                controller?.let { c ->
                    EditorScreen(controller = c, themeMode = prefs.themeMode, onThemeModeChange = { prefs.themeMode = it }, onClose = { finish() })
                }
            }
        }
    }

    override fun onPause() {
        controller?.flush()
        // The app may have this notebook open in a tab: it reads it anew when it comes back.
        AppServices.get(this).prefs.quickNoteUsedAt = System.currentTimeMillis()
        super.onPause()
    }

    override fun onDestroy() {
        controller?.close()
        controller = null
        QuickWidget.update(this)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        controller?.handleKey(event) == true || super.dispatchKeyEvent(event) || controller?.handleShortcut(event) == true
}

/** A tile in the quick settings: a quick note, over the lock screen too. */
class QuickNoteTile : TileService() {
    override fun onClick() {
        val intent = QuickNotes.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** The home screen widget: a quick note, the app, and the three notebooks changed last. */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private val ROWS = listOf(R.id.widget_recent_1, R.id.widget_recent_2, R.id.widget_recent_3)

        /** Redraws every widget (off the main thread): notebooks were changed. */
        fun update(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(app, QuickWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            Thread { runCatching { render(app, manager, ids) } }.start()
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val services = AppServices.get(context)
            val recent = runCatching { services.repo.list() }.getOrDefault(emptyList())
                .filter { it.id != services.prefs.quickNoteId }
                .sortedByDescending { it.updatedAt }
                .take(ROWS.size)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val views = RemoteViews(context.packageName, R.layout.widget_quick)
            views.setOnClickPendingIntent(R.id.widget_quick_note, PendingIntent.getActivity(context, 1, QuickNotes.intent(context), flags))
            val app = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(R.id.widget_open_app, PendingIntent.getActivity(context, 2, app, flags))
            ROWS.forEachIndexed { i, row ->
                val nb = recent.getOrNull(i)
                if (nb == null) {
                    views.setViewVisibility(row, android.view.View.GONE)
                } else {
                    views.setViewVisibility(row, android.view.View.VISIBLE)
                    views.setTextViewText(row, nb.title)
                    val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(QuickNotes.EXTRA_NOTEBOOK, nb.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    views.setOnClickPendingIntent(row, PendingIntent.getActivity(context, 10 + i, open, flags))
                }
            }
            views.setViewVisibility(R.id.widget_empty, if (recent.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)
            manager.updateAppWidget(ids, views)
        }

        const val ACTION_OPEN = "dev.inknotes.OPEN_NOTEBOOK"
    }
}
