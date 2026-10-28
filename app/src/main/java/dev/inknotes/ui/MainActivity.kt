package dev.inknotes.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.NotebookRepository.ArchiveImport
import dev.inknotes.editor.PenButtonBinding
import dev.inknotes.probe.ProbeActivity
import dev.inknotes.search.HandwritingIndex
import dev.inknotes.search.SearchHit

class MainActivity : ComponentActivity() {

    private lateinit var repo: NotebookRepository
    private lateinit var penButton: PenButtonBinding
    private lateinit var prefs: AppPrefs
    private lateinit var handwriting: HandwritingIndex
    private var editor by mutableStateOf<EditorController?>(null)
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var uiStyle by mutableStateOf(UiStyle.CLASSIC)
    private var darkNow = false

    /** Imported notebooks some of which already exist here: waiting for "replace or copy". */
    private var pendingImport by mutableStateOf<Pair<NotebookRepository.ImportSet, String?>?>(null)

    /** Bumped when notebooks were added, so the library reloads. */
    private var libraryVersion by mutableStateOf(0)
    private var libraryMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services = AppServices.get(this)
        repo = services.repo
        handwriting = services.handwriting
        penButton = PenButtonBinding(this)
        prefs = services.prefs
        themeMode = prefs.themeMode
        uiStyle = prefs.uiStyle
        if (savedInstanceState == null) handleIntent(intent)
        // The name of the app greets a fresh start only (not a rotation or a theme change).
        var splash by mutableStateOf(savedInstanceState == null)

        setContent {
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            darkNow = dark
            LaunchedEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                editor?.setDarkTheme(dark)
            }
            val changeTheme = { mode: ThemeMode ->
                themeMode = mode
                prefs.themeMode = mode
            }

            val changeStyle = { style: UiStyle ->
                uiStyle = style
                prefs.uiStyle = style
            }

            InkNotesTheme(dark, uiStyle) {
                val current = editor
                val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                if (current == null) {
                    saved.SaveableStateProvider("library") { LibraryScreen(
                        repo = repo,
                        backup = AppServices.get(this).backup,
                        handwriting = handwriting,
                        onOpenHit = { openNotebook(it.notebook, it) },
                        themeMode = themeMode,
                        onThemeModeChange = changeTheme,
                        uiStyle = uiStyle,
                        onUiStyleChange = changeStyle,
                        message = libraryMessage,
                        onOpen = { openNotebook(it) },
                        onOpenFiles = ::importNotebookFiles,
                        onProbe = { startActivity(Intent(this, ProbeActivity::class.java)) },
                        version = libraryVersion,
                    ) }
                } else {
                    // A fresh editor for each notebook: nothing of the last one carries over.
                    androidx.compose.runtime.key(current) { EditorScreen(
                        controller = current,
                        themeMode = themeMode,
                        onThemeModeChange = changeTheme,
                        onClose = {
                            current.flush()
                            editor = null
                        },
                    ) }
                }
                pendingImport?.let { (set, into) -> ImportConflictDialog(set, into) }
                if (splash) Splash(onDone = { splash = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with" (VIEW) and "Share" (SEND, SEND_MULTIPLE) of notebook files from another app. */
    private fun handleIntent(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) importNotebookFiles(uris, null)
    }

    private fun openNotebook(nb: dev.inknotes.data.Notebook, hit: SearchHit? = null) {
        libraryMessage = null
        // Another notebook may be open (an import finished meanwhile): its last strokes are saved first.
        editor?.flush()
        editor = EditorController(repo, nb, penButton, prefs, darkNow, handwriting, AppServices.get(this).math).also { it.pendingHit = hit }
    }

    /** `.inknote` and `.inknotes` files, added under library folder [into]. */
    private fun importNotebookFiles(uris: List<Uri>, into: String?) {
        editor?.let {
            it.flush()
            editor = null
        }
        libraryMessage = "Открываю " + if (uris.size == 1) "файл…" else "файлы (${uris.size})…"
        repo.readImportsAsync(uris.map { uri -> { contentResolver.openInputStream(uri) } }) { set ->
            when {
                set.items.isEmpty() -> libraryMessage = set.failures.firstOrNull() ?: "Не удалось открыть файл"
                set.conflicts > 0 -> pendingImport = set to into
                else -> commit(set, into, replace = false)
            }
        }
    }

    private fun commit(set: NotebookRepository.ImportSet, into: String?, replace: Boolean) {
        pendingImport = null
        repo.commitImportsAsync(set, replace, into) { added ->
            handwriting.indexAll()
            libraryVersion++
            val failed = if (set.failures.isNotEmpty()) " Не открылись файлы: ${set.failures.size}." else ""
            when {
                added.isEmpty() -> libraryMessage = "Не удалось добавить тетради.$failed"
                // One shared notebook opens right away, as before.
                added.size == 1 && set.folders.isEmpty() && failed.isEmpty() -> openNotebook(added[0])
                else -> libraryMessage = "Добавлено тетрадей: ${added.size}" + (if (set.folders.isNotEmpty()) ", папок: ${set.folders.size}" else "") + ".$failed"
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ImportConflictDialog(set: NotebookRepository.ImportSet, into: String?) {
        val single = set.items.singleOrNull()?.ready
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(if (single != null) "Тетрадь «${single.notebook.title}» уже есть" else "Некоторые тетради уже есть: ${set.conflicts} из ${set.items.size}")
            },
            text = {
                Text(
                    "Заменить ваши версии полученными (ваши несохранённые в файл изменения пропадут) " +
                        "или сохранить полученные как отдельные копии? Новые тетради добавятся в любом случае.",
                )
            },
            confirmButton = {
                Column {
                    Button(onClick = { commit(set, into, replace = true) }) { Text("Заменить") }
                    OutlinedButton(onClick = { commit(set, into, replace = false) }) { Text("Сохранить копии") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImport = null
                    libraryMessage = null
                    repo.discardImports(set)
                }) { Text("Отмена") }
            },
        )
    }

    override fun onPause() {
        editor?.flush()
        super.onPause()
    }

    // Leaving the app is when the day's notes are done: the daily copy, if one is due.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppServices.get(this).backup.run()
    }

    // Pen buttons on some tablets (Huawei M-Pencil) arrive as key events.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        editor?.handleKey(event) == true || super.dispatchKeyEvent(event)
}
