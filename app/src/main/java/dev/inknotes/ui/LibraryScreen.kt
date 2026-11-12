package dev.inknotes.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.PaperFormat
import dev.inknotes.data.PageSize
import dev.inknotes.data.Backup
import dev.inknotes.data.Folder
import dev.inknotes.data.FolderTree
import dev.inknotes.data.Notebook
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PaperKind
import dev.inknotes.data.PageBackground
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.luminance
import dev.inknotes.search.HandwritingIndex
import dev.inknotes.search.SearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

@Composable
fun LibraryScreen(
    repo: NotebookRepository,
    handwriting: HandwritingIndex,
    backup: Backup? = null,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    uiStyle: UiStyle = UiStyle.CLASSIC,
    onUiStyleChange: (UiStyle) -> Unit = {},
    message: String?,
    onOpen: (Notebook) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onOpenFiles: (List<Uri>, String?) -> Unit,
    onProbe: () -> Unit,
    version: Int = 0,
) {
    // The library is read off the main thread: a big one would freeze the screen.
    val library by repo.library.collectAsState()
    var notebooks by remember { mutableStateOf(library?.notebooks.orEmpty()) }
    var folders by remember { mutableStateOf(library?.folders.orEmpty()) }
    LaunchedEffect(library) {
        library?.let {
            notebooks = it.notebooks
            folders = it.folders
        }
    }
    val scope = rememberCoroutineScope()
    val reload: () -> Unit = { scope.launch { repo.refreshLibrary() } }
    /** Repository work off the main thread, then the library read anew. */
    fun change(block: NotebookRepository.() -> Unit) {
        scope.launch {
            repo.io(block)
            repo.refreshLibrary()
        }
    }
    // On first show, and when notebooks were added from files.
    LaunchedEffect(version) { repo.refreshLibrary() }

    // Kept while a notebook is open (see MainActivity), so closing it comes back here.
    var folderId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val folder = folders.find { it.id == folderId }
    var creating by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<Folder?>(null) }
    var editing by remember { mutableStateOf<Notebook?>(null) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    // A damaged folder list was set aside: say so once.
    LaunchedEffect(library) {
        repo.foldersProblem?.let {
            importStatus = it
            repo.foldersProblem = null
        }
    }
    var settings by remember { mutableStateOf(false) }
    var trashOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var backupOpen by remember { mutableStateOf(false) }
    val premium = uiStyle == UiStyle.PREMIUM
    val context = LocalContext.current
    val prefs = remember { AppServices.get(context).prefs }
    var sort by remember { mutableStateOf(prefs.librarySort) }
    var glass by remember { mutableStateOf(prefs.glassCovers) }
    val dark = MaterialTheme.colorScheme.onBackground.red > 0.5f
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onOpenFiles(uris, folderId)
    }
    // Choosing notebooks and folders to save together in one file.
    var selecting by remember { mutableStateOf(false) }
    var selNotebooks by remember { mutableStateOf(setOf<String>()) }
    var selFolders by remember { mutableStateOf(setOf<String>()) }
    var moving by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<Pair<List<Notebook>, List<Folder>>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val what = exporting
        exporting = null
        if (uri == null || what == null) return@rememberLauncherForActivityResult
        importStatus = "Сохраняю тетради…"
        repo.exportBundleAsync(what.first, what.second, { context.contentResolver.openOutputStream(uri, "wt") }) { ok ->
            importStatus = if (ok) "Сохранено тетрадей: ${what.first.size}" + (if (what.second.isNotEmpty()) ", папок: ${what.second.size}" else "") else "Не удалось сохранить файл"
        }
    }
    /** Starts saving: the folders with everything inside them, plus single notebooks. */
    fun export(folderIds: Set<String>, notebookIds: Set<String>, name: String) {
        val inFolders = folderIds.flatMap { FolderTree.subtree(folders, it) }.toSet()
        val fs = folders.filter { it.id in inFolders }
        val nbs = notebooks.filter { it.id in notebookIds || it.folder in inFolders }
        if (nbs.isEmpty() && fs.isEmpty()) return
        exporting = nbs to fs
        exportLauncher.launch("${name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(60).ifEmpty { "Тетради" }}.inknotes")
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importStatus = "Импортирую PDF…"
        val into = folderId
        repo.importPdfAsync(displayName(context, uri), uri, context.contentResolver, outline = { dev.inknotes.data.PdfText.outlineOf(context, it) }) { nb ->
            if (nb == null) {
                importStatus = "Не удалось открыть PDF"
            } else {
                importStatus = null
                onOpen(if (into != null) repo.moveToFolder(nb, into) else nb)
            }
        }
    }
    // Dates as the device's language writes them.
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    // Some systems (Huawei) put the first text field in focus on start: the screen takes the
    // focus itself, so the keyboard comes only when the search field is tapped.
    val screenFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        repeat(3) {
            runCatching { screenFocus.requestFocus() }
            keyboard?.hide()
            delay(250)
        }
    }

    LaunchedEffect(query, handwriting.version) {
        delay(250)
        handwriting.search(query) { hits = it }
    }
    BackHandler(enabled = folderId != null || query.isNotEmpty()) {
        if (query.isNotEmpty()) query = "" else folderId = folder?.parent?.takeIf { p -> folders.any { it.id == p } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(screenFocus)
            .focusable()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Breadcrumbs(FolderTree.path(folders, folderId), onOpen = { folderId = it }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            val actions: @Composable () -> Unit = {
                ThemeButton(themeMode, onThemeModeChange)
                IconAction(InkIcons.Settings, "Настройки: стиль интерфейса и тема", { settings = true })
                Box {
                    var importMenu by remember { mutableStateOf(false) }
                    IconAction(InkIcons.Import, "Импорт: файл тетради или PDF", { importMenu = true })
                    DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                        DropdownMenuItem(text = { Text("Файлы тетрадей (.inknote, .inknotes)…") }, onClick = {
                            importMenu = false
                            fileLauncher.launch(arrayOf("*/*"))
                        })
                        DropdownMenuItem(text = { Text("PDF…") }, onClick = {
                            importMenu = false
                            importLauncher.launch(arrayOf("application/pdf"))
                        })
                    }
                }
                IconAction(InkIcons.NewFolder, "Новая папка", { creatingFolder = true })
                Box {
                    var moreMenu by remember { mutableStateOf(false) }
                    IconAction(InkIcons.More, "Ещё", { moreMenu = true })
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("Справка: все инструменты") }, onClick = {
                            moreMenu = false
                            helpOpen = true
                        })
                        DropdownMenuItem(text = { Text("Выбрать несколько (переместить, экспорт)") }, onClick = {
                            moreMenu = false
                            selecting = true
                            selNotebooks = emptySet()
                            selFolders = emptySet()
                        })
                        DropdownMenuItem(text = { Text("Экспорт всех тетрадей…") }, onClick = {
                            moreMenu = false
                            export(folders.filter { it.parent == null }.map { it.id }.toSet(), notebooks.map { it.id }.toSet(), "Все тетради")
                        })
                        DropdownMenuItem(text = { Text("Корзина…") }, onClick = {
                            moreMenu = false
                            trashOpen = true
                        })
                        if (backup != null) {
                            DropdownMenuItem(text = { Text("Автокопия…") }, onClick = {
                                moreMenu = false
                                backupOpen = true
                            })
                        }
                        // A developer tool: only in debug builds.
                        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                            DropdownMenuItem(text = { Text("Зонд пера") }, onClick = {
                                moreMenu = false
                                onProbe()
                            })
                        }
                    }
                }
            }
            if (uiStyle == UiStyle.GLASS) {
                GlassCapsule(dark) { Row(verticalAlignment = Alignment.CenterVertically) { actions() } }
            } else {
                actions()
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { creating = true }) {
                InkIcon(InkIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Тетрадь")
            }
        }
        if (premium) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(thickness = 0.6.dp, color = Gold.line(MaterialTheme.colorScheme.background.red < 0.3f))
        } else if (uiStyle == UiStyle.MYSTIC) {
            Spacer(Modifier.height(12.dp))
            MysticRule()
            Spacer(Modifier.height(4.dp))
        } else if (uiStyle == UiStyle.NOTEBOOK) {
            Spacer(Modifier.height(6.dp))
            WashiTape()
            Spacer(Modifier.height(10.dp))
        } else if (uiStyle == UiStyle.GLASS) {
            Spacer(Modifier.height(16.dp))
        } else if (uiStyle == UiStyle.MINIMAL) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
        }
        if (selecting) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Выбрано: тетрадей ${selNotebooks.size}, папок ${selFolders.size}",
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val folderIds = folders.map(Folder::id).toSet()
                    selNotebooks = selNotebooks + notebooks.filter { it.folder == folderId || (folderId == null && it.folder !in folderIds) }.map { it.id }
                    selFolders = selFolders + folders.filter { it.parent == folderId }.map { it.id }
                }) { Text("Выбрать все здесь") }
                Button(enabled = selNotebooks.isNotEmpty() || selFolders.isNotEmpty(), onClick = {
                    val name = if (selNotebooks.isEmpty() && selFolders.size == 1) folders.first { it.id in selFolders }.name else "Тетради"
                    export(selFolders, selNotebooks, name)
                    selecting = false
                }) { Text("Экспорт…") }
                Button(enabled = selNotebooks.isNotEmpty() || selFolders.isNotEmpty(), onClick = { moving = true }) { Text("Переместить…") }
                TextButton(onClick = { selecting = false }) { Text("Отмена") }
            }
        }
        (message ?: importStatus)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Поиск по названиям и рукописному тексту") },
            leadingIcon = { InkIcon(InkIcons.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconAction(InkIcons.Close, "Очистить поиск", { query = "" }) },
        )
        HandwritingStatus(handwriting)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box {
                var sortMenu by remember { mutableStateOf(false) }
                TextButton(onClick = { sortMenu = true }) { Text("Сортировка: ${sort.title}") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    for (option in LibrarySort.entries) {
                        DropdownMenuItem(
                            text = { Text((if (option == sort) "✓ " else "") + option.title.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                sort = option
                                prefs.librarySort = option
                                sortMenu = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        if (query.isNotBlank()) {
            SearchResults(hits, emptyText = "Ничего не найдено", showNotebook = true, onClick = onOpenHit)
            return@Column
        }
        val folderIds = folders.map(Folder::id).toSet()
        val shown = notebooks.filter { it.folder == folderId || (folderId == null && it.folder !in folderIds) }
            .sortedWith(
                compareByDescending<Notebook> { it.favorite }.then(
                    when (sort) {
                        LibrarySort.CHANGED -> compareByDescending { it.updatedAt }
                        LibrarySort.CREATED -> compareByDescending { it.createdAt }
                        LibrarySort.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                    },
                ),
            )
        val subfolders = folders.filter { it.parent == folderId }.sortedBy { it.name.lowercase() }
        if (library == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Загружаю тетради…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (shown.isEmpty() && subfolders.isEmpty()) {
            Text(
                if (folderId != null) "В этой папке пока нет тетрадей." else "Пока нет ни одной тетради.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(180.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(subfolders, key = { "folder-" + it.id }) { f ->
                FolderCard(
                    folder = f,
                    notebooks = notebooks.count { it.folder == f.id },
                    subfolders = folders.count { it.parent == f.id },
                    onClick = { folderId = f.id },
                    onLongClick = { editingFolder = f },
                    selected = if (selecting) f.id in selFolders else null,
                    onToggle = { selFolders = if (f.id in selFolders) selFolders - f.id else selFolders + f.id },
                )
            }
            itemsIndexed(shown, key = { _, it -> it.id }) { i, nb ->
                NotebookCard(
                    number = i + 1,
                    nb = nb,
                    subtitle = "${nb.pageIds.size} стр. · ${dateFormat.format(Date(nb.updatedAt))}",
                    onClick = { if (selecting) selNotebooks = if (nb.id in selNotebooks) selNotebooks - nb.id else selNotebooks + nb.id else onOpen(nb) },
                    onLongClick = { editing = nb },
                    selected = if (selecting) nb.id in selNotebooks else null,
                    glass = glass,
                )
            }
        }
    }

    if (creating) {
        CreateNotebookDialog(
            onDismiss = { creating = false },
            onCreate = { title, background, size, cover, art, plastic ->
                creating = false
                onOpen(repo.setPlasticKind(repo.create(title, background.kind, cover, folderId, art, background, size), plastic))
            },
        )
    }
    if (creatingFolder) {
        NameDialog(
            title = "Новая папка",
            initial = "",
            confirm = "Создать",
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                val parent = folderId
                change { createFolder(name, parent) }
                creatingFolder = false
            },
        )
    }
    if (settings) {
        SettingsDialog(
            style = uiStyle,
            themeMode = themeMode,
            dark = MaterialTheme.colorScheme.background.red < 0.3f,
            onStyle = onUiStyleChange,
            onTheme = onThemeModeChange,
            onDismiss = { settings = false },
            glass = glass,
            onGlass = {
                glass = it
                prefs.glassCovers = it
            },
        )
    }
    if (helpOpen) HelpDialog(onDismiss = { helpOpen = false })
    if (trashOpen) {
        TrashDialog(
            repo = repo,
            onRestored = { nb ->
                importStatus = "Восстановлена «${nb.title}»"
                reload()
            },
            onDismiss = { trashOpen = false },
        )
    }
    if (backupOpen && backup != null) {
        BackupDialog(backup, AppServices.get(context).prefs, onDismiss = { backupOpen = false })
    }
    if (moving) {
        MoveDialog(
            folders = folders,
            current = folderId,
            exclude = selFolders.flatMap { FolderTree.subtree(folders, it) }.toSet(),
            count = selNotebooks.size + selFolders.size,
            onCreateFolder = { name, parent ->
                // Shown at once; written off the main thread.
                val f = Folder(java.util.UUID.randomUUID().toString(), name, parent)
                folders = folders + f
                change { addFolder(f) }
                f.id
            },
            onDismiss = { moving = false },
            onMove = { into ->
                val chosen = notebooks.filter { it.id in selNotebooks }
                val chosenFolders = selFolders
                moving = false
                selecting = false
                val where = folders.find { it.id == into }?.name?.let { "в «$it»" } ?: "на верхний уровень"
                scope.launch {
                    val (moved, movedFolders) = repo.io { moveAll(chosen, chosenFolders, into) }
                    importStatus = "Перемещено $where: тетрадей ${moved.size}" + (if (movedFolders > 0) ", папок $movedFolders" else "")
                    repo.refreshLibrary()
                }
            },
        )
    }
    editingFolder?.let { f ->
        FolderDialog(
            folder = f,
            folders = folders,
            onDismiss = { editingFolder = null },
            onSave = { name, parent ->
                change {
                    if (name != f.name) renameFolder(f, name)
                    if (parent != f.parent) moveFolder(f, parent)
                }
                editingFolder = null
            },
            onDelete = {
                change { deleteFolder(f) }
                editingFolder = null
            },
            onExport = {
                editingFolder = null
                export(setOf(f.id), emptySet(), f.name)
            },
        )
    }
    editing?.let { nb ->
        EditNotebookDialog(
            nb = nb,
            folders = folders,
            onDismiss = { editing = null },
            onSave = { title, cover, newFolder, favorite, art, plastic ->
                var updated = nb
                if (plastic.ordinal != nb.plasticKindOrDefault()) updated = repo.setPlasticKind(updated, plastic.ordinal)
                if (art.ordinal != nb.coverArtOrDefault()) updated = repo.setCoverArt(updated, art.ordinal)
                if (favorite != nb.favorite) updated = repo.setFavorite(updated, favorite)
                if (title != nb.title) updated = repo.rename(updated, title)
                if (cover != nb.coverColor()) updated = repo.setCover(updated, cover)
                if (newFolder != nb.folder) updated = repo.moveToFolder(updated, newFolder)
                notebooks = notebooks.map { if (it.id == nb.id) updated else it }
                editing = null
            },
            onDelete = {
                repo.delete(nb)
                notebooks = notebooks.filter { it.id != nb.id }
                editing = null
                importStatus = "«${nb.title}» в корзине: меню «Ещё» → «Корзина…», чтобы вернуть"
            },
        )
    }
}

/** Whether handwriting search is on, being set up, or failed, with the button to turn it on. */
@Composable
fun HandwritingStatus(handwriting: HandwritingIndex) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (val state = handwriting.state) {
        HandwritingIndex.State.Off -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Поиск по рукописному тексту выключен.", color = muted, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = handwriting::enable) { Text("Включить (загрузит модель ≈ 15 МБ)") }
        }
        HandwritingIndex.State.Downloading -> Text("Загружаю модель распознавания почерка…", color = muted, modifier = Modifier.padding(vertical = 8.dp))
        is HandwritingIndex.State.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = handwriting::enable) { Text("Повторить") }
        }
        HandwritingIndex.State.Ready -> if (handwriting.pendingPages > 0) {
            Text("Распознаю почерк: осталось страниц ${handwriting.pendingPages}", color = muted, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

/** Search hits: title matches and recognized lines, each with its notebook and page. */
@Composable
fun SearchResults(hits: List<SearchHit>, emptyText: String, showNotebook: Boolean, onClick: (SearchHit) -> Unit, modifier: Modifier = Modifier) {
    if (hits.isEmpty()) {
        Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(vertical = 8.dp))
        return
    }
    LazyColumn(modifier) {
        items(hits) { hit ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onClick(hit) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showNotebook) {
                    Box(
                        Modifier
                            .size(width = 22.dp, height = 30.dp)
                            .background(Color(hit.notebook.coverColor()), RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    val where = if (hit.line == null) "название тетради" else "стр. ${hit.pageIndex + 1}"
                    Text(
                        if (showNotebook) "${hit.notebook.title} · $where" else where,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(hit.line?.text ?: hit.notebook.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** "Тетради › Учёба › Математика": every part opens that level. */
@Composable
private fun Breadcrumbs(path: List<Folder>, onOpen: (String?) -> Unit, modifier: Modifier = Modifier) {
    val style = LocalUiStyle.current
    val premium = style.serif
    val minimal = style == UiStyle.MINIMAL
    Column(modifier) {
    if (style != UiStyle.CLASSIC && style != UiStyle.GLASS) Monogram()
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        @Composable
        fun title(text: String, last: Boolean, id: String?) {
            Text(
                text,
                fontSize = when {
                    style == UiStyle.GLASS -> if (last) 36.sp else 24.sp
                    style == UiStyle.NOTEBOOK -> if (last) 38.sp else 24.sp
                    premium -> if (last) 34.sp else 24.sp
                    minimal -> if (last) 38.sp else 24.sp
                    else -> if (last) 28.sp else 22.sp
                },
                fontWeight = titleWeight(style),
                fontFamily = if (premium) androidx.compose.ui.text.font.FontFamily.Serif else null,
                letterSpacing = when {
                    style == UiStyle.GLASS -> (-0.8).sp
                    style == UiStyle.NOTEBOOK -> (-1.2).sp
                    premium -> 0.8.sp
                    minimal -> (-0.5).sp
                    else -> androidx.compose.ui.unit.TextUnit.Unspecified
                },
                maxLines = 1,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                modifier = if (last) Modifier else Modifier.clickable { onOpen(id) },
            )
        }
        title("Тетради", path.isEmpty(), null)
        path.forEachIndexed { i, f ->
            Text("  ›  ", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            title(f.name, i == path.lastIndex, f.id)
        }
    }
    }
}

/** Folder tree with a "top level" entry; [exclude] (a folder and its subfolders) can't be chosen. */
@Composable
private fun FolderPicker(folders: List<Folder>, selected: String?, exclude: Set<String> = emptySet(), onSelect: (String?) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState())
    ) {
        val row = @Composable { label: String, depth: Int, id: String?, enabled: Boolean ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onSelect(id) }
                    .background(if (selected == id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .padding(start = (12 + depth * 20).dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (if (id == null) "" else "▸ ") + label,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        row("Верхний уровень (без папки)", 0, null, true)
        for ((f, depth) in FolderTree.flatten(folders)) row(f.name, depth + 1, f.id, f.id !in exclude)
    }
}

@Composable
private fun FolderCard(
    folder: Folder,
    notebooks: Int,
    subfolders: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean? = null,
    onToggle: () -> Unit = {},
) {
    val count = notebooks
    Column(Modifier.combinedClickable(onClick = if (selected != null) onToggle else onClick, onLongClick = onLongClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f),
            contentAlignment = Alignment.Center,
        ) {
            if (LocalUiStyle.current == UiStyle.GLASS) {
                GlassFolder(count, MaterialTheme.colorScheme.onBackground.red > 0.5f, Modifier.fillMaxSize().padding(vertical = 10.dp))
            } else if (LocalUiStyle.current == UiStyle.NOTEBOOK) {
                PlasticFolder(count, Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp))
            } else if (LocalUiStyle.current == UiStyle.MYSTIC) {
                MysticFolder(count, Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp))
            } else if (LocalUiStyle.current == UiStyle.PREMIUM) {
                PortfolioFolder(count, Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp))
            } else if (LocalUiStyle.current == UiStyle.MINIMAL) {
                LineFolder(count, Modifier.fillMaxSize())
            } else
            // A folder: tab on top, body below.
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.45f)
                        .height(18.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.1f)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(topEnd = 10.dp, bottomStart = 10.dp, bottomEnd = 10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$count", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (selected != null) SelectMark(selected, Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(6.dp))
        CardTitle(folder.name)
        Text(
            "тетрадей: $notebooks" + if (subfolders > 0) " · папок: $subfolders" else "",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotebookCard(nb: Notebook, subtitle: String, onClick: () -> Unit, onLongClick: () -> Unit, selected: Boolean? = null, number: Int = 0, glass: Boolean = false) {
    val dark = MaterialTheme.colorScheme.background.red < 0.3f
    val see = if (glass) Glass(rememberFirstPage(nb, dark), dark) else null
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        if (LocalUiStyle.current == UiStyle.GLASS) {
            GlassCover(nb.coverColor(), nb.title, dark, Modifier.fillMaxWidth().aspectRatio(0.72f), glass = see) {
                if (selected != null) Box(Modifier.fillMaxSize()) { SelectMark(selected, Modifier.align(Alignment.TopEnd)) }
            }
        } else if (LocalUiStyle.current == UiStyle.NOTEBOOK) {
            PlasticCover(nb.coverColor(), nb.title, nb.pageIds.size, PlasticKind.of(nb.plasticKindOrDefault()), Modifier.fillMaxWidth().aspectRatio(0.72f), glass = see) {
                if (selected != null) Box(Modifier.fillMaxSize()) { SelectMark(selected, Modifier.align(Alignment.TopEnd)) }
            }
        } else if (LocalUiStyle.current == UiStyle.MYSTIC) {
            MysticCover(nb.coverColor(), nb.title, CoverArt.of(nb.coverArtOrDefault()), Modifier.fillMaxWidth().aspectRatio(0.72f), glass = see) {
                if (selected != null) Box(Modifier.fillMaxSize()) { SelectMark(selected, Modifier.align(Alignment.TopEnd)) }
            }
        } else if (LocalUiStyle.current == UiStyle.PREMIUM) {
            LeatherCover(nb.coverColor(), nb.title, Modifier.fillMaxWidth().aspectRatio(0.72f), glass = see) {
                if (selected != null) Box(Modifier.fillMaxSize()) { SelectMark(selected, Modifier.align(Alignment.TopEnd)) }
            }
        } else if (LocalUiStyle.current == UiStyle.MINIMAL) {
            MatteCover(nb.coverColor(), nb.title, number, Modifier.fillMaxWidth().aspectRatio(0.72f), glass = see) {
                if (selected != null) Box(Modifier.fillMaxSize()) { SelectMark(selected, Modifier.align(Alignment.TopEnd)) }
            }
        } else
        Card(shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp)) {
            val cover = Color(nb.coverColor())
            val light = isLight(glassSeen(cover, see).toArgb())
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f),
            ) {
            if (see != null) GlassPage(see)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (see != null) cover.copy(alpha = GLASS_ALPHA) else cover),
                contentAlignment = Alignment.BottomStart,
            ) {
                if (selected != null) SelectMark(selected, Modifier.align(Alignment.TopEnd))
                Text(
                    nb.title,
                    modifier = Modifier.padding(14.dp),
                    style = androidx.compose.ui.text.TextStyle(shadow = glassShadow(see, light = light)),
                    color = if (light) Color(0xFF1A1A1A) else Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            }
        }
        Spacer(Modifier.height(if (LocalUiStyle.current == UiStyle.CLASSIC) 6.dp else 10.dp))
        CardTitle(if (nb.favorite) "★ ${nb.title}" else nb.title)
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Name under a card: serif in the premium style. */
@Composable
private fun CardTitle(text: String) {
    val style = LocalUiStyle.current
    val premium = style.serif
    Text(
        text,
        fontWeight = when (style) {
            UiStyle.CLASSIC -> FontWeight.Medium
            UiStyle.PREMIUM, UiStyle.MYSTIC -> FontWeight.Normal
            UiStyle.GLASS -> FontWeight.SemiBold
            UiStyle.NOTEBOOK -> FontWeight.Bold
            UiStyle.MINIMAL -> FontWeight.Normal
        },
        fontFamily = if (premium) androidx.compose.ui.text.font.FontFamily.Serif else null,
        fontSize = if (premium) 17.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        letterSpacing = if (style == UiStyle.CLASSIC) androidx.compose.ui.unit.TextUnit.Unspecified else 0.4.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun isLight(color: Int): Boolean {
    val r = (color shr 16 and 0xFF) / 255f
    val g = (color shr 8 and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    return 0.299f * r + 0.587f * g + 0.114f * b > 0.65f
}

/** Cover colours plus the spectrum/palette picker for any other colour. */
@Composable
private fun CoverChoices(selected: Int, onSelect: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val choices = Notebook.COVERS + listOf(selected).filter { it !in Notebook.COVERS }
        for (c in choices) {
            Box(
                Modifier
                    .size(width = 30.dp, height = 40.dp)
                    .border(
                        if (c == selected) 3.dp else 0.dp,
                        if (c == selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(5.dp),
                    )
                    .padding(3.dp)
                    .background(Color(c), RoundedCornerShape(3.dp))
                    .clickable { onSelect(c) }
            )
        }
        Box(
            Modifier
                .size(width = 30.dp, height = 40.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                .clickable { picking = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(16.dp).background(androidx.compose.ui.graphics.Brush.sweepGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.85f, 1f) }), CircleShape))
        }
    }
    if (picking) {
        ColorPickerDialog(
            initial = selected,
            onPick = {
                onSelect(it or 0xFF000000.toInt())
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun CreateNotebookDialog(onDismiss: () -> Unit, onCreate: (String, PageBackground, PageSize, Int, Int, Int) -> Unit) {
    var title by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(PaperFormat.A4) }
    var landscape by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf(PaperKind.LINED) }
    var lines by remember { mutableFloatStateOf(1f) }
    val darkUi = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    var cover by remember { mutableStateOf(Notebook.COVERS.random()) }
    var art by remember { mutableStateOf(CoverArt.entries.random()) }
    var plastic by remember { mutableStateOf(PlasticKind.entries.random()) }
    var more by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая тетрадь") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                )
                Text("Цвет тетради")
                CoverChoices(cover) { cover = it }
                CoverArtChoices(cover, art, plastic, { art = it }, { plastic = it })
                // Paper, format and ruling: most notebooks keep the defaults, so they wait behind a button.
                TextButton(onClick = { more = !more }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)) {
                    Text(if (more) "Скрыть дополнительные настройки ▴" else "Дополнительные настройки ▾")
                }
                if (!more) {
                    Text(
                        "${format.title}, ${if (landscape) "альбомная" else "книжная"} · ${template.title}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                } else {
                    PaperSizeChoices(format, landscape, onFormat = { format = it }, onLandscape = { landscape = it })
                    Text("Фон страниц")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Photo backgrounds are set from inside the notebook ("Фон").
                        for (t in PaperKind.entries - PaperKind.PHOTO) {
                            PaperPreview(kind = t, dark = darkUi, color = null, photo = null, selected = t == template, lines = lines, aspect = PageSize(format, landscape).let { it.widthMm / it.heightMm }, onClick = { template = t })
                        }
                    }
                    if (template != PaperKind.BLANK) LinesSlider(lines, onChange = { lines = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(title.trim().ifEmpty { "Без названия" }, PageBackground(template, lines = lines), PageSize(format, landscape), cover, art.ordinal, plastic.ordinal) }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun EditNotebookDialog(
    nb: Notebook,
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onSave: (title: String, cover: Int, folder: String?, favorite: Boolean, art: CoverArt, plastic: PlasticKind) -> Unit,
    onDelete: () -> Unit,
) {
    var favorite by remember { mutableStateOf(nb.favorite) }
    var title by remember { mutableStateOf(nb.title) }
    var cover by remember { mutableStateOf(nb.coverColor()) }
    var art by remember { mutableStateOf(CoverArt.of(nb.coverArtOrDefault())) }
    var plastic by remember { mutableStateOf(PlasticKind.of(nb.plasticKindOrDefault())) }
    var folder by remember { mutableStateOf(nb.folder?.takeIf { id -> folders.any { it.id == id } }) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmDelete) "Удалить «${nb.title}»?" else "Тетрадь") },
        text = {
            if (confirmDelete) {
                Text("Тетрадь попадёт в корзину: её можно восстановить в течение 30 дней (меню «Ещё» → «Корзина…»).")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название") },
                        singleLine = true,
                    )
                    FilterChip(selected = favorite, onClick = { favorite = !favorite }, label = { Text(if (favorite) "★ В избранном" else "☆ В избранное") })
                    Text("Обложка")
                    CoverChoices(cover) { cover = it }
                    CoverArtChoices(cover, art, plastic, { art = it }, { plastic = it })
                    if (folders.isNotEmpty()) {
                        Text("Папка")
                        FolderPicker(folders, folder) { folder = it }
                    }
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                Button(onClick = onDelete) { Text("Удалить") }
            } else {
                Button(onClick = { onSave(title.trim().ifEmpty { nb.title }, cover, folder, favorite, art, plastic) }) { Text("Сохранить") }
            }
        },
        dismissButton = {
            Row {
                if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("Удалить…", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** The kinds of cover of the current style (mystic designs or plastic notebooks), in the chosen colour. */
@Composable
private fun CoverArtChoices(color: Int, art: CoverArt, plastic: PlasticKind, onArt: (CoverArt) -> Unit, onPlastic: (PlasticKind) -> Unit) {
    val style = LocalUiStyle.current
    if (style != UiStyle.MYSTIC && style != UiStyle.NOTEBOOK) return
    Text(if (style == UiStyle.MYSTIC) "Вид обложки (цвета — как у оригинала)" else "Вид обложки")
    @Composable
    fun choice(title: String, on: Boolean, onClick: () -> Unit, cover: @Composable (Modifier) -> Unit) {
        Column(Modifier.width(80.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .border(if (on) 2.5.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .padding(4.dp),
            ) { cover(Modifier.size(width = 62.dp, height = 86.dp)) }
            Text(
                title,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (style == UiStyle.MYSTIC) {
            for (a in CoverArt.entries) choice(a.title, a == art, { onArt(a) }) { MysticCover(color, "", a, it, compact = true) }
        } else {
            for (k in PlasticKind.entries) choice(k.title, k == plastic, { onPlastic(k) }) { PlasticCover(color, "", 0, k, it, compact = true) }
        }
    }
}

@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier) {
    Box(
        modifier
            .padding(10.dp)
            .size(28.dp)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FolderDialog(
    folder: Folder,
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var name by remember { mutableStateOf(folder.name) }
    var parent by remember { mutableStateOf(folder.parent) }
    var confirmDelete by remember { mutableStateOf(false) }
    val parentName = folders.find { it.id == folder.parent }?.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmDelete) "Удалить папку «${folder.name}»?" else "Папка") },
        text = {
            if (confirmDelete) {
                Text(
                    "Тетради и вложенные папки не удалятся, а переместятся " +
                        (if (parentName != null) "в папку «$parentName»." else "на верхний уровень."),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                    Text("Находится в")
                    FolderPicker(folders, parent, exclude = FolderTree.subtree(folders, folder.id)) { parent = it }
                }
            }
        },
        confirmButton = {
            if (confirmDelete) {
                Button(onClick = onDelete) { Text("Удалить папку") }
            } else {
                Button(onClick = { onSave(name.trim().ifEmpty { folder.name }, parent) }) { Text("Сохранить") }
            }
        },
        dismissButton = {
            Row {
                if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("Удалить…", color = MaterialTheme.colorScheme.error) }
                if (!confirmDelete) TextButton(onClick = onExport) { Text("Экспорт…") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** Where to move the chosen notebooks and folders; a new folder can be made on the way. */
@Composable
private fun MoveDialog(
    folders: List<Folder>,
    current: String?,
    exclude: Set<String>,
    count: Int,
    onCreateFolder: (String, String?) -> String,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    var target by remember { mutableStateOf(current?.takeIf { it !in exclude }) }
    var naming by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переместить ($count)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Куда")
                FolderPicker(folders, target, exclude = exclude) { target = it }
                TextButton(onClick = { naming = true }) { Text("Новая папка здесь…") }
            }
        },
        confirmButton = { Button(onClick = { onMove(target) }) { Text("Переместить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (naming) {
        NameDialog(
            title = "Новая папка",
            initial = "",
            confirm = "Создать",
            onDismiss = { naming = false },
            onConfirm = { name ->
                target = onCreateFolder(name, target)
                naming = false
            },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(name.trim().ifEmpty { "Папка" }) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** File name without the .pdf extension, used as the new notebook's title. */
private fun displayName(context: Context, uri: Uri): String {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
    return name?.removeSuffix(".pdf")?.removeSuffix(".PDF")?.takeIf { it.isNotBlank() } ?: "PDF"
}
