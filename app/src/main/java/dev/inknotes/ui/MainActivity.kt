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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.NotebookRepository.ArchiveImport
import dev.inknotes.editor.PenButtonBinding
import dev.inknotes.search.HandwritingIndex
import dev.inknotes.search.SearchHit

class MainActivity : ComponentActivity() {

    private lateinit var repo: NotebookRepository
    private lateinit var penButton: PenButtonBinding
    private lateinit var prefs: AppPrefs
    private lateinit var handwriting: HandwritingIndex
    private var editor by mutableStateOf<EditorController?>(null)

    /** Open notebooks, as tabs over the editor; [editor] is the one shown (null: the library). */
    private var tabs by mutableStateOf<List<EditorController>>(emptyList())
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var uiStyle by mutableStateOf(UiStyle.CLASSIC)
    private var darkNow = false

    /**
     * A second notebook beside the one shown (PDF on one side, notes on the other); it is not
     * among the tabs while it is there. [focused] gets the keyboard and the pen button.
     */
    private var side by mutableStateOf<EditorController?>(null)
    private var focused: EditorController? = null
    private var sideShare by mutableStateOf(0.5f)
    private var pickingSide by mutableStateOf(false)

    /** Imported notebooks some of which already exist here: waiting for "replace or copy". */
    private var pendingImport by mutableStateOf<Pair<NotebookRepository.ImportSet, String?>?>(null)

    /** Bumped when notebooks were added, so the library reloads. */
    private var libraryVersion by mutableStateOf(0)
    private var libraryMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppServices.get(this).cloud.openNow = ::openIds
        val services = AppServices.get(this)
        repo = services.repo
        handwriting = services.handwriting
        penButton = PenButtonBinding(this)
        prefs = services.prefs
        themeMode = prefs.themeMode
        uiStyle = prefs.uiStyle
        if (savedInstanceState == null) handleIntent(intent)
        // The tabs open when the app was last stopped (or closed by the system) come back.
        // Not when a shared file is being opened: an import closes the tabs anyway.
        if (tabs.isEmpty() && pendingTabs == null && (intent?.action == Intent.ACTION_MAIN || intent?.action == null)) restoreTabs()
        quickNoteSeen = prefs.quickNoteUsedAt
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
                side?.setDarkTheme(dark)
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
                        // The probe is in debug builds only (src/debug): started by name.
                        onProbe = { runCatching { startActivity(Intent().setClassName(this, "dev.inknotes.probe.ProbeActivity")) } },
                        version = libraryVersion,
                    ) }
                } else {
                    val main = @androidx.compose.runtime.Composable {
                        // A fresh editor for each notebook: nothing of the last one carries over.
                        androidx.compose.runtime.key(current) { EditorScreen(
                            controller = current,
                            themeMode = themeMode,
                            onThemeModeChange = changeTheme,
                            onClose = {
                                current.flush()
                                editor = null
                            },
                            tabs = tabs,
                            onTab = ::switchTo,
                            onCloseTab = ::closeTab,
                            onSide = { if (side == null) pickingSide = true else closeSide() },
                            sideOpen = side != null,
                        ) }
                    }
                    val other = side
                    if (other == null) {
                        focused = current
                        main()
                    } else {
                        SplitPanes(
                            share = sideShare,
                            onShare = { sideShare = it },
                            onFocus = { left -> focused = if (left) current else other },
                            first = main,
                            second = {
                                androidx.compose.runtime.key(other) { EditorScreen(
                                    controller = other,
                                    themeMode = themeMode,
                                    onThemeModeChange = changeTheme,
                                    onClose = ::closeSide,
                                    onSide = ::closeSide,
                                    sideOpen = true,
                                    onSwap = ::swapSides,
                                ) }
                            },
                        )
                    }
                    if (pickingSide) {
                        SidePickerDialog(
                            repo = repo,
                            except = current.notebook.id,
                            onPick = { nb ->
                                pickingSide = false
                                openSide(nb)
                            },
                            onDismiss = { pickingSide = false },
                        )
                    }
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
        // Back from Google's sign-in page in the browser: the app only comes to the front.
        if (intent?.data?.scheme == "inknotes") return
        // A notebook asked for from the home screen widget.
        if (intent?.action == QuickWidget.ACTION_OPEN) {
            val id = intent.getStringExtra(QuickNotes.EXTRA_NOTEBOOK) ?: return
            lifecycleScope.launch { repo.io { list() }.firstOrNull { it.id == id }?.let { openNotebook(it) } }
            return
        }
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
        // Tabs of notebooks deleted or replaced in the library meanwhile are closed: the library
        // is read off the main thread, then the notebook opens.
        lifecycleScope.launch {
            dropStaleTabs()
            showNotebook(nb, hit)
        }
    }

    private fun showNotebook(nb: dev.inknotes.data.Notebook, hit: SearchHit? = null) {
        // Shown beside: it becomes the main one (one notebook is never open twice).
        side?.takeIf { it.notebook.id == nb.id }?.let { s ->
            side = null
            s.pendingHit = hit
            tabs = tabs + s
            editor = s
            return
        }
        tabs.firstOrNull { it.notebook.id == nb.id }?.let { open ->
            open.pendingHit = hit
            editor = open
            return
        }
        val c = EditorController(repo, nb, penButton, prefs, darkNow, handwriting, AppServices.get(this).math).also { it.pendingHit = hit }
        // The oldest tab gives way past the limit.
        val kept = if (tabs.size >= MAX_TABS) tabs.drop(1).also { tabs.first().close() } else tabs
        tabs = kept + c
        editor = c
    }

    private fun switchTo(c: EditorController) {
        if (c === editor) return
        editor?.flush()
        lifecycleScope.launch {
            dropStaleTabs()
            if (c in tabs) editor = c
        }
    }

    /** [nb] beside the notebook shown; taken out of the tabs if it is one. */
    private fun openSide(nb: dev.inknotes.data.Notebook) {
        val main = editor ?: return
        if (nb.id == main.notebook.id) return
        side?.let { closeSide() }
        val tab = tabs.firstOrNull { it.notebook.id == nb.id }
        if (tab != null) {
            tab.flush()
            tabs = tabs - tab
        }
        side = tab ?: EditorController(repo, nb, penButton, prefs, darkNow, handwriting, AppServices.get(this).math)
    }

    private fun closeSide() {
        val s = side ?: return
        s.flush()
        side = null
        focused = editor
        s.close()
    }

    /** The two notebooks change places. */
    private fun swapSides() {
        val s = side ?: return
        val m = editor ?: return
        m.flush()
        s.flush()
        tabs = tabs.map { if (it === m) s else it }
        editor = s
        side = m
        focused = s
    }

    /** Closes a tab; the next one (or the library) is shown if it was the current one. */
    private fun closeTab(c: EditorController) {
        val i = tabs.indexOf(c)
        if (i < 0) return
        val rest = tabs - c
        if (editor === c) editor = rest.getOrNull(i) ?: rest.lastOrNull()
        tabs = rest
        c.close()
    }

    private suspend fun dropStaleTabs() {
        if (tabs.isEmpty()) return
        val live = repo.io { list() }.associateBy { it.id }
        for (t in tabs) {
            val now = live[t.notebook.id]
            // Gone, or replaced by an imported version: the tab would write over it.
            if (now == null || now.pageIds != t.notebook.pageIds && now.updatedAt > t.notebook.updatedAt) {
                tabs = tabs - t
                if (editor === t) editor = null
                t.close()
            }
        }
    }

    /** `.inknote` and `.inknotes` files, added under library folder [into]. */
    private fun importNotebookFiles(uris: List<Uri>, into: String?) {
        editor?.let {
            it.flush()
            editor = null
        }
        // An import may replace an open notebook: every tab is closed (they are saved first).
        for (t in tabs) t.close()
        tabs = emptyList()
        libraryMessage = "Открываю " + if (uris.size == 1) "файл…" else "файлы (${uris.size})…"
        repo.readImportsAsync(uris.map { uri -> { contentResolver.openInputStream(uri) } }) { set ->
            when {
                set.items.isEmpty() -> libraryMessage = set.failures.firstOrNull() ?: "Не удалось открыть файл"
                set.conflicts > 0 -> {
                    // A second import while the first one's question is open: the first one's
                    // unpacked files go, it is replaced by the new one.
                    pendingImport?.let { (old, _) -> repo.discardImports(old) }
                    pendingImport = set to into
                }
                else -> commit(set, into, replace = false)
            }
        }
    }

    private fun commit(set: NotebookRepository.ImportSet, into: String?, replace: Boolean) {
        pendingImport?.let { (old, _) -> if (old !== set) repo.discardImports(old) }
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

    /** Set while the tabs of the last run are being opened again. */
    private var pendingTabs: TabsMemory.Saved? = null

    private fun restoreTabs() {
        val saved = TabsMemory.decode(prefs.openTabs)
        if (saved.ids.isEmpty()) return
        pendingTabs = saved
        lifecycleScope.launch {
            val all = repo.io { list() }.associateBy { it.id }
            val plan = TabsMemory.restore(saved, all.keys, MAX_TABS)
            pendingTabs = null
            // Opened meanwhile by the user (or a shared file): theirs wins.
            if (tabs.isNotEmpty()) return@launch
            tabs = plan.ids.mapNotNull(all::get).map { nb -> EditorController(repo, nb, penButton, prefs, darkNow, handwriting, AppServices.get(this@MainActivity).math) }
            editor = tabs.firstOrNull { it.notebook.id == plan.active }
            // The notebook that was beside it, if it still exists.
            plan.side?.let(all::get)?.takeIf { editor != null && it.id != editor?.notebook?.id }?.let(::openSide)
        }
    }

    /** What is open now, kept for the next start. */
    private fun rememberTabs() {
        if (pendingTabs != null) return
        prefs.openTabs = TabsMemory.encode(tabs.map { it.notebook.id }, editor?.notebook?.id, side?.notebook?.id)
    }

    /** When a quick note was last written, as this activity knows it. */
    private var quickNoteSeen = 0L

    /** Notebooks open here: the sync does not replace them under the editor. */
    private fun openIds() = (tabs + listOfNotNull(side)).map { it.notebook.id }.toSet()

    override fun onResume() {
        super.onResume()
        AppServices.get(this).cloud.autoSync(openIds())
        // A quick note was written meanwhile (over the lock screen, from the tile): an open tab
        // of that notebook has the old pages and is closed, so it can't write over the note.
        val at = prefs.quickNoteUsedAt
        if (at > quickNoteSeen) {
            quickNoteSeen = at
            val id = prefs.quickNoteId
            (tabs + listOfNotNull(side)).filter { it.notebook.id == id }.forEach { t ->
                if (t === side) closeSide() else closeTab(t)
            }
        }
    }

    override fun onPause() {
        rememberTabs()
        for (t in tabs) t.flush()
        side?.flush()
        QuickWidget.update(this)
        // What was written goes to the Drive (open notebooks are saved just above).
        AppServices.get(this).cloud.autoSync(openIds())
        super.onPause()
    }

    // Leaving the app is when the day's notes are done: the daily copy, if one is due.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppServices.get(this).backup.run()
    }

    // Pen buttons on some tablets (Huawei M-Pencil) arrive as key events. Keyboard shortcuts
    // come after the screen: a text field being typed in keeps its letters.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (focused ?: editor)?.handleKey(event) == true || super.dispatchKeyEvent(event) || (focused ?: editor)?.handleShortcut(event) == true

    private companion object {
        const val MAX_TABS = 5
    }
}
