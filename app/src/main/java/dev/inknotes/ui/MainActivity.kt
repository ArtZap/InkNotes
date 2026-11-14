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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    /**
     * Tabs of the half beside (0.39), [side] the one shown there; empty when there is no half
     * beside. A notebook is open in one place only: never in both [tabs] and these.
     */
    private var sideTabs by mutableStateOf<List<EditorController>>(emptyList())
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
        // The sync asks what is open before each step (held only weakly there: no leak).
        AppServices.get(this).cloud.watchOpen(SYNC_KEY, openForSync)
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
                            compact = side != null,
                            tabDrag = tabDragging,
                            // Beside another half its strip shows even with one tab: a tab can be dragged across.
                            alwaysTabs = side != null,
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
                            onFocus = { left ->
                                val pane = if (left) current else other
                                focused = pane
                                // New writing goes onto the active layer of the notebook touched,
                                // not of the one attached last (the layer is one for the app).
                                pane.activateLayer()
                            },
                            first = main,
                            second = {
                                androidx.compose.runtime.key(other) { EditorScreen(
                                    controller = other,
                                    themeMode = themeMode,
                                    onThemeModeChange = changeTheme,
                                    // The arrow closes this tab (the half goes with its last one).
                                    onClose = { closeSideTab(other) },
                                    // Back acts on the half last touched: here this tab, there the library.
                                    onBack = {
                                        if (focused === other) {
                                            closeSideTab(other)
                                        } else {
                                            current.flush()
                                            editor = null
                                        }
                                    },
                                    tabs = sideTabs,
                                    onTab = ::switchSide,
                                    onCloseTab = ::closeSideTab,
                                    onSide = ::closeSide,
                                    sideOpen = true,
                                    onSwap = ::swapSides,
                                    compact = true,
                                    tabDrag = tabDragging,
                                    rightPane = true,
                                    onAddTab = { pickingSide = true },
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
                TabDropOverlay()
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
        if (uris.isNotEmpty()) importFiles(uris, null, outside = true)
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
        // Open in the half beside: shown there (one notebook is never open twice). With the
        // library on screen (no notebook on the left) it comes over to the left to be seen.
        sideTabs.firstOrNull { it.notebook.id == nb.id }?.let { s ->
            s.pendingHit = hit
            if (editor == null) {
                val rest = sideTabs - s
                sideTabs = rest
                if (side === s) side = rest.lastOrNull()
                tabs = tabs + s
                editor = s
            } else {
                side = s
            }
            focused = s
            return
        }
        tabs.firstOrNull { it.notebook.id == nb.id }?.let { open ->
            open.pendingHit = hit
            editor = open
            return
        }
        val c = EditorController(repo, nb, penButton, prefs, darkNow, handwriting, AppServices.get(this).math).also { it.pendingHit = hit }
        // The oldest tab gives way past the limit (of this half, or of both together).
        val kept = if (tabs.size >= MAX_TABS || tabs.isNotEmpty() && tabs.size + sideTabs.size >= MAX_OPEN) tabs.drop(1).also { tabs.first().close() } else tabs
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
        sideTabs.firstOrNull { it.notebook.id == nb.id }?.let { side = it; return }
        val tab = tabs.firstOrNull { it.notebook.id == nb.id }
        if (tab != null) {
            tab.flush()
            tabs = tabs - tab
        }
        val c = tab ?: EditorController(repo, nb, penButton, prefs, darkNow, handwriting, AppServices.get(this).math)
        // The oldest tab of that half gives way past the limit.
        val kept = if (sideTabs.size >= MAX_TABS || sideTabs.isNotEmpty() && tabs.size + sideTabs.size >= MAX_OPEN) sideTabs.drop(1).also { sideTabs.first().close() } else sideTabs
        sideTabs = kept + c
        side = c
    }

    /** The half beside closes: its tabs are saved and closed. */
    private fun closeSide() {
        val all = sideTabs
        if (all.isEmpty()) return
        sideTabs = emptyList()
        side = null
        focused = editor
        editor?.activateLayer()
        for (t in all) {
            t.flush()
            t.close()
        }
    }

    /** A tab of the half beside shown there. */
    private fun switchSide(c: EditorController) {
        if (c === side || c !in sideTabs) return
        side?.flush()
        side = c
        focused = c
        c.activateLayer()
    }

    /** Closes a tab of the half beside; with its last one the half goes. */
    private fun closeSideTab(c: EditorController) {
        val i = sideTabs.indexOf(c)
        if (i < 0) return
        val rest = sideTabs - c
        c.flush()
        if (rest.isEmpty()) {
            closeSide()
            return
        }
        if (side === c) side = rest.getOrNull(i) ?: rest.last()
        sideTabs = rest
        c.close()
    }

    /** The two halves change places, with their tabs. */
    private fun swapSides() {
        val s = side ?: return
        val m = editor ?: return
        m.flush()
        s.flush()
        val left = tabs
        tabs = sideTabs
        sideTabs = left
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
        // The last tab on the left closed while a half beside is open: that half takes its place.
        if (rest.isEmpty() && sideTabs.isNotEmpty()) {
            tabs = sideTabs
            editor = side
            sideTabs = emptyList()
            side = null
            focused = editor
        }
    }

    private suspend fun dropStaleTabs() {
        if (tabs.isEmpty() && sideTabs.isEmpty()) return
        val live = repo.io { list() }.associateBy { it.id }
        fun stale(t: EditorController): Boolean {
            val now = live[t.notebook.id]
            // Gone, or replaced by an imported version: the tab would write over it.
            return now == null || now.pageIds != t.notebook.pageIds && now.updatedAt > t.notebook.updatedAt
        }
        for (t in tabs.filter(::stale)) {
            tabs = tabs - t
            if (editor === t) editor = null
            t.close()
        }
        for (t in sideTabs.filter(::stale)) closeSideTab(t)
    }

    /** `.inknote` and `.inknotes` files picked in the library, added under library folder [into]. */
    private fun importNotebookFiles(uris: List<Uri>, into: String?) = importFiles(uris, into, outside = false)

    /**
     * Reads [uris] first; nothing open is touched before that, so a file that is not a
     * notebook changes nothing. [outside]: sent by another app ("Открыть с помощью",
     * "Поделиться"): asked before anything is added, as the app is offered for any binary file.
     */
    private fun importFiles(uris: List<Uri>, into: String?, outside: Boolean) {
        libraryMessage = "Открываю " + if (uris.size == 1) "файл…" else "файлы (${uris.size})…"
        repo.readImportsAsync(uris.map { uri -> { contentResolver.openInputStream(uri) } }) { set ->
            when {
                set.items.isEmpty() -> (set.failures.firstOrNull() ?: "Не удалось открыть файл").let { m ->
                    libraryMessage = m
                    editor?.showStatus(m)
                }
                set.conflicts > 0 || outside -> {
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
        // Notebooks replaced by the import must not stay open: the tabs would write over them.
        // They are saved and closed (with the notebook beside them); new ones and copies touch nothing open.
        val replaced = if (replace) set.items.mapNotNull { it.ready.existing?.id }.toSet() else emptySet()
        if (replaced.isNotEmpty()) {
            for (t in sideTabs.filter { it.notebook.id in replaced }) closeSideTab(t)
            for (t in tabs.filter { it.notebook.id in replaced }) closeTab(t)
        }
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
        if (set.conflicts == 0) {
            // From another app, nothing to replace: only "add or not".
            AlertDialog(
                onDismissRequest = {},
                title = { Text(if (single != null) "Добавить тетрадь «${single.notebook.title}»?" else "Добавить тетради: ${set.items.size}?") },
                text = { Text("Файл открыт из другого приложения. Тетради добавятся в библиотеку, ваши не изменятся.") },
                confirmButton = { Button(onClick = { commit(set, into, replace = false) }) { Text("Добавить") } },
                dismissButton = {
                    TextButton(onClick = {
                        pendingImport = null
                        libraryMessage = null
                        repo.discardImports(set)
                    }) { Text("Отмена") }
                },
            )
            return
        }
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
            // The half beside with its tabs, if they still exist.
            if (editor != null) {
                sideTabs = plan.sideIds.mapNotNull(all::get).map { nb -> EditorController(repo, nb, penButton, prefs, darkNow, handwriting, AppServices.get(this@MainActivity).math) }
                side = sideTabs.firstOrNull { it.notebook.id == plan.side } ?: sideTabs.lastOrNull()
            }
        }
    }

    /** What is open now, kept for the next start. */
    private fun rememberTabs() {
        if (pendingTabs != null) return
        prefs.openTabs = TabsMemory.encode(tabs.map { it.notebook.id }, editor?.notebook?.id, side?.notebook?.id, sideTabs.map { it.notebook.id })
    }

    /** When a quick note was last written, as this activity knows it. */
    private var quickNoteSeen = 0L

    // ------------------------------------------------------------ dragging tabs

    /** Where a dragged tab is now (in the root of the window), and the size of the window. */
    private var tabDragAt by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)
    private var rootSize = androidx.compose.ui.unit.IntSize.Zero

    private val tabDragging: TabDragging = TabDragging(
        start = { t, at ->
            startTabDrag(t)
            tabDragAt = at
        },
        move = { at ->
            tabDragAt = at
            tabDragging.tab?.let { t -> tabDragging.target = tabTarget(t, at) }
        },
        drop = { t, at -> dropTab(t, at) },
        cancel = ::endTabDrag,
    )

    private fun startTabDrag(t: EditorController) {
        tabDragging.tab = t
    }

    private fun endTabDrag() {
        tabDragging.tab = null
        tabDragging.target = null
        tabDragAt = null
    }

    /** The half a tab let go at [at] goes to: true the right one, false the left, null neither. */
    private fun tabDropHalf(at: androidx.compose.ui.geometry.Offset): Boolean? {
        val w = rootSize.width.toFloat()
        if (w <= 0f) return null
        return when {
            side != null -> at.x > w * sideShare
            at.x > w * (1f - BESIDE_EDGE) -> true
            else -> null
        }
    }

    /**
     * A tab let go. Over a strip of tabs (either half) it takes its place among them there;
     * elsewhere over the other half it goes to the end of that half's tabs; at the right edge,
     * with one half only, it opens a half beside.
     */
    private fun dropTab(t: EditorController, at: androidx.compose.ui.geometry.Offset) {
        val target = tabTarget(t, at)
        endTabDrag()
        if (t !in sideTabs && t !in tabs) return
        if (target != null) {
            moveTab(t, target.first, target.second)
            return
        }
        // At the right edge with one half only: a half beside opens with it.
        if (side == null && tabDropHalf(at) == true) moveTab(t, true, null)
    }

    /**
     * Where tab [t] would land if let go at [at]: the strip (true: the right half's) and its
     * place among the other tabs there. Over a strip: where it is pointed at; elsewhere over the
     * other half: at the end of that half's tabs. Null: nowhere (it stays where it was).
     */
    private fun tabTarget(t: EditorController, at: androidx.compose.ui.geometry.Offset): Pair<Boolean, Int>? {
        val fromRight = t in sideTabs
        val band = 24 * resources.displayMetrics.density
        for ((right, strip) in tabDragging.strips) {
            val r = strip.rect
            if (at.x in r.left..r.right && at.y in r.top - band..r.bottom + band) {
                return right to strip.tabs.filterKeys { it !== t }.values.count { it.center.x < at.x }
            }
        }
        val right = tabDropHalf(at) ?: return null
        if (right == fromRight || side == null) return null
        return right to (if (right) sideTabs else tabs).count { it !== t }
    }

    /**
     * Tab [t] to place [index] (null: the end) among the tabs of the right half ([right]) or the
     * left one, shown there. A half left without tabs goes: the left one takes the right one's.
     */
    private fun moveTab(t: EditorController, right: Boolean, index: Int?) {
        val fromRight = t in sideTabs
        val target = if (right) sideTabs else tabs
        if (fromRight == right) {
            // Only a new place in its own strip.
            val rest = target - t
            val moved = rest.toMutableList().apply { add((index ?: rest.size).coerceIn(0, rest.size), t) }
            if (right) sideTabs = moved else tabs = moved
            rememberTabs()
            return
        }
        if (target.size >= MAX_TABS) {
            (focused ?: editor)?.showStatus("В той половине уже $MAX_TABS вкладок — закройте одну, чтобы перенести сюда ещё")
            return
        }
        t.flush()
        if (fromRight) {
            val rest = sideTabs - t
            sideTabs = rest
            if (side === t) side = rest.lastOrNull()
        } else {
            val rest = tabs - t
            tabs = rest
            if (editor === t) editor = rest.lastOrNull()
        }
        val into = (if (right) sideTabs else tabs).toMutableList().apply { add((index ?: size).coerceIn(0, size), t) }
        if (right) {
            sideTabs = into
            side = t
        } else {
            tabs = into
            editor = t
        }
        // The left half emptied: the right one becomes the only one.
        if (tabs.isEmpty()) {
            tabs = sideTabs
            editor = side
            sideTabs = emptyList()
            side = null
        }
        focused = t
        t.activateLayer()
        rememberTabs()
    }

    /** The places a dragged tab can go, lit under it, and the tab itself following the pen. */
    @androidx.compose.runtime.Composable
    private fun TabDropOverlay() {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .fillMaxSize()
                .onSizeChanged { rootSize = it },
        ) {
            val t = tabDragging.tab ?: return@Box
            val at = tabDragAt ?: return@Box
            val fromRight = t in sideTabs
            // The other half lights up (or the edge where one opens); not the half it came from.
            val lit = tabDropHalf(at)?.let { it != fromRight } == true
            val density = androidx.compose.ui.platform.LocalDensity.current
            val w = rootSize.width.toFloat()
            val zone = when {
                side == null -> (1f - BESIDE_EDGE) to 1f
                fromRight -> 0f to sideShare
                else -> sideShare to 1f
            }
            val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
            with(density) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .offset(x = (w * zone.first).toDp())
                        .width((w * (zone.second - zone.first)).toDp())
                        .fillMaxHeight()
                        .background(accent.copy(alpha = if (lit) 0.18f else 0.06f)),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text(
                        if (side != null) "Отпустите — вкладка переедет сюда" else "Отпустите — тетрадь откроется рядом",
                        color = accent,
                        modifier = androidx.compose.ui.Modifier.padding(16.dp),
                    )
                }
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.offset(x = (at.x - 60.dp.toPx()).toDp(), y = (at.y - 16.dp.toPx()).toDp()),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        t.notebook.title,
                        maxLines = 1,
                        fontSize = 13.sp,
                        modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 200.dp),
                    )
                }
            }
        }
    }

    /** Notebooks open here: the sync does not replace them under the editor. */
    private fun openIds() = (tabs + sideTabs).map { it.notebook.id }.toSet()

    /** Kept by this activity: the sync holds it only weakly. */
    private val openForSync: () -> Set<String> = { openIds() }

    override fun onDestroy() {
        AppServices.get(this).cloud.unwatchOpen(SYNC_KEY)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppServices.get(this).cloud.autoSync(openIds())
        // A quick note was written meanwhile (over the lock screen, from the tile): an open tab
        // of that notebook has the old pages and is closed, so it can't write over the note.
        val at = prefs.quickNoteUsedAt
        if (at > quickNoteSeen) {
            quickNoteSeen = at
            val id = prefs.quickNoteId
            (tabs + sideTabs).filter { it.notebook.id == id }.forEach { t ->
                if (t in sideTabs) closeSideTab(t) else closeTab(t)
            }
        }
    }

    override fun onPause() {
        rememberTabs()
        for (t in tabs) t.flush()
        for (t in sideTabs) t.flush()
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
        const val MAX_TABS = 20

        /**
         * Notebooks open in both halves together. A tab out of sight keeps only the page it was
         * left on (EditorController.sleep), so many fit; this is only a bound.
         */
        const val MAX_OPEN = 30

        /** Share of the width at the right edge where a dragged tab opens beside. */
        const val BESIDE_EDGE = 0.25f
        const val SYNC_KEY = "main"
    }
}
