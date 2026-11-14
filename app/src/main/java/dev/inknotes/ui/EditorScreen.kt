package dev.inknotes.ui

import dev.inknotes.data.tr
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.graphics.ImageBitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import dev.inknotes.math.MathNotes
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageBackground
import dev.inknotes.data.PaperKind
import dev.inknotes.editor.EraserMode
import dev.inknotes.editor.InkColors
import dev.inknotes.editor.Neon
import dev.inknotes.editor.PageEditorView
import dev.inknotes.editor.PageRenderer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import dev.inknotes.editor.PaperStyle
import dev.inknotes.editor.Tool

@Composable
fun EditorScreen(
    controller: EditorController,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onClose: () -> Unit,
    tabs: List<EditorController> = emptyList(),
    onTab: (EditorController) -> Unit = {},
    onCloseTab: (EditorController) -> Unit = {},
    /** Opens a notebook beside this one, or closes the one beside (null: not offered). */
    onSide: (() -> Unit)? = null,
    sideOpen: Boolean = false,
    /** Beside another: the two change places. */
    onSwap: (() -> Unit)? = null,
    /**
     * Half of the screen or less (two notebooks side by side): the bars take as little height
     * as they can — the tools and their options in one line that scrolls instead of wrapping,
     * and a button that hides them altogether.
     */
    compact: Boolean = false,
    /** Tabs dragged out of the strip (to another place, beside, into the other half). */
    tabDrag: TabDragging? = null,
    /** This is the right half: its strip of tabs is told apart from the left one's. */
    rightPane: Boolean = false,
    /** Show the strip of tabs even with one tab (two halves: a tab can be dragged across). */
    alwaysTabs: Boolean = false,
    /** "+" in the strip: another notebook in this half (null: the library). */
    onAddTab: (() -> Unit)? = null,
    /** Closes every tab of this half (asked once more in the strip first). */
    onCloseAllTabs: (() -> Unit)? = null,
    /** The system back gesture (null: as the back arrow). */
    onBack: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack ?: onClose)
    // The view this screen made: only it is let go of when the screen goes.
    val shownView = remember { arrayOfNulls<PageEditorView>(1) }
    DisposableEffect(controller) {
        onDispose { controller.detach(shownView[0]) }
    }
    var showBackground by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var showHatch by remember { mutableStateOf(false) }
    var showTable by remember { mutableStateOf(false) }
    var showStamps by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showSelectionSize by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showShift by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    var showCustomize by remember { mutableStateOf(false) }
    var showAllFunctions by remember { mutableStateOf(false) }
    var textEdit by remember { mutableStateOf<String?>(null) }
    var textNew by remember { mutableStateOf(false) }
    var pageSizeOf by remember { mutableStateOf<Int?>(null) }
    var pageText by remember { mutableStateOf<List<dev.inknotes.search.TextLine>?>(null) }
    val context = LocalContext.current
    val resolver = context.contentResolver
    // The microphone, and on Android 13+ the notification that a recording is on (it shows how
    // long it has run and stops it; without it the recording still works).
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[android.Manifest.permission.RECORD_AUDIO] == true) controller.startRecording(context) else controller.showStatus(tr("Без доступа к микрофону записать звук нельзя", "Sound can't be recorded without microphone access"))
    }
    val record = {
        fun has(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notify = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.POST_NOTIFICATIONS else null
        val missing = listOfNotNull(android.Manifest.permission.RECORD_AUDIO, notify).filter { !has(it) }
        val prefs = AppServices.get(context).prefs
        // Asked once: refused, it is not asked before every recording.
        val askedNotify = notify != null && prefs.askedNotifications
        val ask = missing.filter { it != notify || !askedNotify }
        if (ask.isEmpty()) {
            controller.startRecording(context)
        } else {
            if (notify in ask) prefs.askedNotifications = true
            micPermission.launch(ask.toTypedArray())
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) controller.insertImage(uri, resolver)
    }
    // Export: what was chosen in the dialog waits for the system "save as" or folder dialog.
    var exportPending by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showExport by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showPdfPage by remember { mutableStateOf(false) }
    val pdfInsertLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.insertPdf(uri, resolver, context)
    }
    // Shortcut keys that open something on the screen.
    androidx.compose.runtime.LaunchedEffect(controller.uiRequest) {
        when (controller.uiRequest) {
            "search" -> showSearch = true
            "export" -> showExport = true
            "print" -> controller.print(context, controller.exportIndices(EditorController.ExportPages.All))
            "timer" -> showTimer = true
        }
        controller.uiRequest = null
    }
    androidx.compose.runtime.LaunchedEffect(StudyTimer.messageId) {
        if (StudyTimer.messageId > 0) StudyTimer.message?.let(controller::showStatus)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) controller.exportTo(uri, resolver, exportPending, EditorController.ExportFormat.PDF)
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) controller.exportTo(uri, resolver, exportPending, EditorController.ExportFormat.PNG)
    }
    val pngFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) controller.exportPngToFolder(uri, resolver, exportPending)
    }
    fun sendFiles(uris: List<Uri>, mime: String) {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, tr("Отправить", "Share")))
    }
    val shareExport = { format: EditorController.ExportFormat, pages: List<Int> ->
        controller.exportForSharing(context, pages, format) { uris -> sendFiles(uris, format.mime) }
    }
    // Scanner: the camera writes into a cache file, then the photo opens in the editor.
    val scanFile = remember { java.io.File(java.io.File(context.cacheDir, "camera").apply { mkdirs() }, "scan.jpg") }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) controller.scanned(Uri.fromFile(scanFile), resolver)
    }
    val scan = {
        runCatching {
            scanFile.delete()
            scanLauncher.launch(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", scanFile))
        }.onFailure { controller.showStatus(tr("Камера недоступна", "Camera unavailable")) }
        Unit
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) controller.saveNotebookFile(uri, resolver)
    }
    val share = {
        controller.shareNotebookFile(context) { uri ->
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, tr("Отправить тетрадь", "Share the notebook")))
        }
    }

    val placing = Placing(
        controller.toolbar,
        place = { id, zone -> controller.changeToolbar(controller.toolbar.moved(id, zone)) },
        customize = { showCustomize = true },
    )
    val actions = barActions(
        controller,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        pagesShown = showPages,
        onTogglePages = { showPages = !showPages },
        searchShown = showSearch,
        onToggleSearch = { showSearch = !showSearch },
        onInsertImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onInsertTable = { showTable = true },
        onInsertStamp = { showStamps = true },
        onText = { textNew = true },
        onPageText = { controller.currentPageText { pageText = it } },
        onToc = { showToc = true },
        onLayers = { showLayers = true },
        onRecord = { if (controller.recordingSince == null) record() else controller.stopRecording() },
        onRecordings = { showRecordings = true },
        onBackground = { showBackground = true },
        onPageSize = { pageSizeOf = controller.pageIndex },
        onPageShift = { showShift = true },
        onShare = { share() },
        onSave = { saveLauncher.launch("${controller.fileName(controller.notebook.title)}.inknote") },
        onPdf = { showExport = true },
        onPdfShare = { shareExport(EditorController.ExportFormat.PDF, controller.exportIndices(EditorController.ExportPages.All)) },
        onPrint = { controller.print(context, controller.exportIndices(EditorController.ExportPages.All)) },
        onScan = scan,
        onTimer = { showTimer = true },
        onPdfPage = { showPdfPage = true },
        onSide = onSide,
        sideOpen = sideOpen,
        onSwap = onSwap,
        onHelp = { showHelp = true },
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
    ) {
        if (tabs.size > 1 || (alwaysTabs || rightPane) && tabs.isNotEmpty()) {
            TabStrip(controller, tabs, onTab, onCloseTab, onAdd = onAddTab ?: onClose, drag = tabDrag, right = rightPane, onCloseAll = onCloseAllTabs)
        }
        val layout = controller.toolbar
        TopRow(
            c = controller,
            top = layout.top.mapNotNull(actions::get),
            menu = layout.menu.mapNotNull(actions::get),
            hiddenCount = layout.hidden.size,
            placing = placing,
            onClose = onClose,
            onAllFunctions = { showAllFunctions = true },
            onTimer = { showTimer = true },
            compact = compact,
        )
        SoundBar(controller)
        PdfPickBar(controller)
        // Hidden tools come back by themselves for a selection: its options are needed then.
        // They fold up towards the top bar (where the button that brings them back is) and
        // unfold from there, so it is seen where they went.
        AnimatedVisibility(
            !compact || !controller.toolsHidden || controller.selectionCount > 0,
            enter = expandVertically(tween(TOOLS_FOLD_MS), expandFrom = Alignment.Top) + fadeIn(tween(TOOLS_FOLD_MS)),
            exit = shrinkVertically(tween(TOOLS_FOLD_MS), shrinkTowards = Alignment.Top) + fadeOut(tween(TOOLS_FOLD_MS)),
        ) {
            ToolRow(
                controller,
                tools = layout.tools.mapNotNull(actions::get),
                placing = placing,
                onPickColor = { showPicker = true },
                onHatchSettings = { showHatch = true },
                onSelectionSize = { showSelectionSize = true },
                compact = compact,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxSize()) {
            // Slides in and out; the page beside it keeps its place (see PageEditorView.refit).
            AnimatedVisibility(
                visible = showPages,
                enter = expandHorizontally(tween(PANEL_MS), expandFrom = Alignment.Start) + fadeIn(tween(PANEL_MS)),
                exit = shrinkHorizontally(tween(PANEL_MS), shrinkTowards = Alignment.Start) + fadeOut(tween(PANEL_MS)),
            ) {
                Row {
                    PagesPanel(controller, onPageSize = { pageSizeOf = it })
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            AndroidView(
                factory = { ctx -> PageEditorView(ctx).also { shownView[0] = it; controller.attach(it) } },
                // Its own layer: a redrawn page (every frame of a scroll) then re-records only
                // the canvas, not the toolbars and panels around it.
                modifier = Modifier.weight(1f).fillMaxHeight().graphicsLayer(),
            )
            if (showSearch) {
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SearchPanel(controller)
            }
        }
    }

    controller.recognizedText?.let { RecognizedTextDialog(it, controller, onDismiss = { controller.recognizedText = null }) }
    controller.tappedAnswer?.let { AnswerDialog(controller, it, onDismiss = { controller.tappedAnswer = null }) }
    controller.studyCards?.let { CardsDialog(controller, it, onDismiss = { controller.closeCards() }) }
    if (showToc) TocDialog(controller, onDismiss = { showToc = false })
    if (showCustomize) CustomizeBarsDialog(controller.toolbar, onChange = controller::changeToolbar, onDismiss = { showCustomize = false })
    if (showAllFunctions) {
        AllFunctionsDialog(
            controller.toolbar.hidden.flatMap { id -> if (id == "page-nav") pageNavMenu(controller) else listOfNotNull(actions[id]) },
            placing,
            onDismiss = { showAllFunctions = false },
        )
    }
    if (controller.toolbarIntro) {
        ToolbarIntroDialog(
            onPick = {
                controller.changeToolbar(it.layout)
                controller.closeToolbarIntro()
            },
            onCustomize = {
                controller.closeToolbarIntro()
                showCustomize = true
            },
            onDismiss = controller::closeToolbarIntro,
        )
    }
    if (showRecordings) RecordingsDialog(controller, onRecord = { record() }, onDismiss = { showRecordings = false })
    if (showLayers || controller.showLayersDialog) LayersDialog(controller, onDismiss = { showLayers = false; controller.showLayersDialog = false })
    if (textNew) TypeTextDialog(tr("Печатный текст", "Typed text"), "", onDismiss = { textNew = false }) { controller.insertText(it) }
    if (controller.editingText) {
        TypeTextDialog(tr("Изменить текст", "Edit text"), controller.selectedText.orEmpty(), onDismiss = { controller.editingText = false }) { controller.editSelectedText(it) }
    }
    if (controller.showLinkDialog) LinkDialog(controller, onDismiss = { controller.showLinkDialog = false })
    if (controller.showAddBookmark) AddBookmarkDialog(controller, onDismiss = { controller.showAddBookmark = false })
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    if (showTimer) StudyTimerDialog(onDismiss = { showTimer = false })
    if (showPdfPage) {
        PdfPageDialog(
            controller,
            onInsertPdf = { runCatching { pdfInsertLauncher.launch(arrayOf("application/pdf")) }.onFailure { controller.showStatus(tr("Нет приложения для выбора файла", "No app to pick a file")) } },
            onDismiss = { showPdfPage = false },
        )
    }
    controller.formula?.let { f ->
        FormulaDialog(
            initial = f.latex,
            readFailed = f.failed,
            onDismiss = controller::closeFormula,
            onCopyLatex = { controller.copyFormulaLatex(context, it) },
            onCopyPicture = { controller.copyFormulaPicture(context, it) },
            onReplace = { controller.placeFormula(it, replace = true) },
            onBelow = { controller.placeFormula(it, replace = false) },
        )
    }
    if (showExport) {
        ExportDialog(
            pageCount = controller.notebook.pageIds.size,
            currentPage = controller.pageIndex,
            initialFormat = controller.exportFormat,
            initialPages = controller.exportPagesChoice,
            onDismiss = { showExport = false },
            onShare = { format, pages ->
                showExport = false
                shareExport(format, controller.exportIndices(pages))
            },
            onSave = { format, pages ->
                showExport = false
                val list = controller.exportIndices(pages)
                exportPending = list
                when {
                    format == EditorController.ExportFormat.PDF -> exportLauncher.launch(controller.exportName(list, format))
                    list.size == 1 -> pngLauncher.launch(controller.exportName(list, format))
                    else -> pngFolderLauncher.launch(null)
                }
            },
            onPrint = { pages ->
                showExport = false
                controller.print(context, controller.exportIndices(pages))
            },
            onRemember = { format, which ->
                controller.exportFormat = format
                controller.exportPagesChoice = which
            },
        )
    }
    controller.photoEdit?.let { edit ->
        PhotoEditorDialog(
            source = edit.bitmap,
            startMode = edit.mode,
            doneLabel = if (edit.target == null) tr("Вставить", "Insert") else tr("Готово", "Done"),
            onCancel = controller::cancelPhotoEdit,
            onDone = controller::finishPhotoEdit,
        )
    }
    if (showShift) PageShiftDialog(controller, onDismiss = { showShift = false })
    if (showBackground) BackgroundDialog(controller, onDismiss = { showBackground = false })
    if (showSelectionSize && controller.selectionCount > 0) SelectionSizeDialog(controller, onDismiss = { showSelectionSize = false })
    pageSizeOf?.let { PageSizeDialog(controller, it, onDismiss = { pageSizeOf = null }) }
    if (showHatch) HatchDialog(controller, onDismiss = { showHatch = false })
    if (showTable) {
        TableDialog(
            onDismiss = { showTable = false },
            onInsert = { rows, cols ->
                showTable = false
                controller.insertTable(rows, cols)
            },
        )
    }
    if (showStamps) {
        StampDialog(
            onDismiss = { showStamps = false },
            onPick = {
                showStamps = false
                controller.insertStamp(it)
            },
        )
    }
    pageText?.let { lines -> PageTextDialog(controller, lines, onDismiss = { pageText = null }) }
    if (showPicker) {
        ColorPickerDialog(
            initial = controller.currentColor(),
            onPick = {
                controller.pickCustomColor(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * Every button of the editor as an action (see [Toolbars]): what it does, its icon and name,
 * whether it is on or available now. Where each goes is the user's layout.
 */
@Composable
private fun barActions(
    c: EditorController,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    pagesShown: Boolean,
    onTogglePages: () -> Unit,
    searchShown: Boolean,
    onToggleSearch: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertTable: () -> Unit,
    onInsertStamp: () -> Unit,
    onText: () -> Unit,
    onPageText: () -> Unit,
    onToc: () -> Unit,
    onLayers: () -> Unit,
    onRecord: () -> Unit,
    onRecordings: () -> Unit,
    onBackground: () -> Unit,
    onPageSize: () -> Unit,
    onPageShift: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onPdf: () -> Unit,
    onPdfShare: () -> Unit,
    onPrint: () -> Unit,
    onScan: () -> Unit,
    onTimer: () -> Unit,
    onPdfPage: () -> Unit,
    onSide: (() -> Unit)?,
    sideOpen: Boolean,
    onSwap: (() -> Unit)?,
    onHelp: () -> Unit,
): Map<String, BarAction> {
    val math = c.math.state
    fun a(id: String, hint: String, onClick: () -> Unit, enabled: Boolean = true, selected: Boolean? = null, title: String = BarCatalog.title(id), icon: ImageVector = BarCatalog.icon(id)) =
        id to BarAction(id, title, hint, icon, onClick, enabled, selected)
    fun tool(id: String, t: Tool, hint: String) = a(id, hint, { c.selectTool(t) }, selected = c.tool == t)
    val fountain = c.penIsFountain
    val layer = c.notebook.layerList().getOrNull(c.notebook.activeLayer)?.name ?: tr("Основной", "Main")
    return mapOf(
        a(
            "pen",
            if (fountain) tr("Перьевая ручка (вид — в «Ручка ▾»)", "Fountain pen (kind — in “Pen ▾”)") else tr("Ручка (вид — в «Ручка ▾»)", "Pen (kind — in “Pen ▾”)"),
            { c.selectTool(if (fountain) Tool.FOUNTAIN else Tool.PEN) },
            selected = c.tool == Tool.PEN || c.tool == Tool.FOUNTAIN,
            title = if (fountain) tr("Перьевая ручка", "Fountain pen") else tr("Ручка", "Pen"),
            icon = if (fountain) InkIcons.Fountain else InkIcons.Pen,
        ),
        tool("highlighter", Tool.HIGHLIGHTER, tr("Маркер", "Highlighter")),
        tool("neon", Tool.NEON, tr("Неон", "Neon")),
        tool("eraser", Tool.ERASER, tr("Ластик", "Eraser")),
        tool("lasso", Tool.LASSO, tr("Лассо: выделить и двигать", "Lasso: select and move")),
        tool("area", Tool.AREA, tr("Область: заштриховать", "Area: hatch")),
        tool("spoiler", Tool.SPOILER, tr("Маркер подсказок: прячет текст, касание пальцем открывает", "Hint highlighter: hides text, a finger touch reveals it")),
        tool("laser", Tool.LASER, tr("Указка: след гаснет сам", "Pointer: the trail fades by itself")),
        a("picture", tr("Вставить картинку", "Insert picture"), onInsertImage, enabled = !c.importingImage, title = tr("Вставить картинку", "Insert picture")),
        a("table", tr("Вставить таблицу", "Insert table"), onInsertTable, title = tr("Вставить таблицу", "Insert table")),
        a("stamps", tr("Штампы: готовые рисунки", "Stamps: ready-made drawings"), onInsertStamp),
        a("ruler", tr("Линейка: положить на лист", "Ruler: lay it on the page"), c::toggleRuler, selected = c.rulerShown),
        a("text", tr("Вставить печатный текст", "Insert typed text"), onText, title = tr("Вставить печатный текст…", "Insert typed text…")),
        a(
            "space",
            tr("Вставить место: штрих вниз раздвигает строки, вверх — убирает пустое", "Insert space: a stroke down spreads lines apart, up removes empty space"),
            c::toggleInsertSpace,
            selected = c.insertingSpace,
            title = if (c.insertingSpace) tr("Вставить место: отменить", "Insert space: cancel") else tr("Вставить место (раздвинуть строки)", "Insert space (spread lines)"),
        ),
        a("undo", tr("Отменить", "Undo"), c::undo, enabled = c.canUndo),
        a("redo", tr("Повторить", "Redo"), c::redo, enabled = c.canRedo),
        a("pages", tr("Страницы", "Pages"), onTogglePages, selected = pagesShown),
        a("search", tr("Поиск в тетради", "Search the notebook"), onToggleSearch, selected = searchShown, title = tr("Поиск в тетради", "Search the notebook")),
        a("page-nav", tr("Листать страницы", "Turn pages"), {}),
        a("add-page", tr("Добавить страницу после текущей", "Add a page after the current one"), c::addPageAfterCurrent, title = tr("Добавить страницу", "Add page")),
        a("toc", tr("Оглавление тетради", "Notebook contents"), onToc, title = tr("Оглавление…", "Contents…")),
        a("page-text", tr("Распознанный текст страницы", "Recognised text of the page"), onPageText, title = tr("Распознанный текст страницы", "Recognised text of the page")),
        a(
            "math",
            tr("Ответы после «=»: вычисления, переменные, системы", "Answers after “=”: calculations, variables, systems"),
            c::toggleMath,
            selected = math == MathNotes.State.Ready || math == MathNotes.State.Downloading,
            title = tr("Ответы после «=»", "Answers after “=”"),
        ),
        a(
            "fractions",
            tr("Ответы обыкновенными или десятичными дробями", "Answers as fractions or decimals"),
            c::toggleFractionAnswers,
            title = if (c.fractionAnswers) tr("Ответы: обыкновенные дроби (1/3)", "Answers: fractions (1/3)") else tr("Ответы: десятичные дроби (0,333…)", "Answers: decimals (0.333…)"),
        ),
        a(
            "units",
            tr("Единицы измерения и физические константы в формулах", "Units and physical constants in formulas"),
            c::toggleMathUnits,
            selected = c.mathUnits,
            title = tr("Единицы и константы в формулах", "Units and constants in formulas"),
        ),
        a("layers", tr("Слои (сейчас: $layer)", "Layers (now: $layer)"), onLayers, title = tr("Слои (сейчас: $layer)…", "Layers (now: $layer)…")),
        a("cards", tr("Повторение: карточки из маркера подсказок", "Review: cards from the hint highlighter"), c::openCards, title = tr("Повторение: карточки", "Review: cards")),
        a(
            "record",
            if (c.recordingSince == null) tr("Записать звук", "Record sound") else tr("Остановить запись", "Stop recording"),
            onRecord,
            selected = c.recordingSince != null,
            title = if (c.recordingSince == null) tr("Записать звук", "Record sound") else tr("Остановить запись", "Stop recording"),
        ),
        a("recordings", tr("Записи звука", "Sound recordings"), onRecordings, title = tr("Записи звука…", "Sound recordings…")),
        a("background", tr("Фон страниц", "Page background"), onBackground),
        a("page-size", tr("Размер и ориентация страницы", "Page size and orientation"), onPageSize, title = tr("Размер и ориентация страницы…", "Page size and orientation…")),
        a("page-shift", tr("Отступ страницы слева или справа", "Page margin on the left or right"), onPageShift, title = tr("Отступ страницы слева или справа…", "Page margin on the left or right…")),
        a("share", tr("Поделиться файлом тетради", "Share the notebook file"), onShare, title = tr("Поделиться файлом тетради", "Share the notebook file")),
        a("save", tr("Сохранить файл тетради", "Save the notebook file"), onSave, title = tr("Сохранить файл тетради…", "Save the notebook file…")),
        a("pdf", tr("Экспорт: PDF или PNG, все страницы, эта или диапазон", "Export: PDF or PNG, all pages, this one or a range"), onPdf, title = tr("Экспорт: PDF, PNG…", "Export: PDF, PNG…")),
        a("pdf-share", tr("Отправить тетрадь как PDF одним нажатием", "Share the notebook as a PDF in one tap"), onPdfShare, title = tr("Отправить как PDF", "Share as PDF")),
        a("print", tr("Печать тетради", "Print the notebook"), onPrint, title = tr("Печать…", "Print…")),
        a("zoom-window", tr("Окно увеличения: пишите крупно, на странице мелко", "Zoom window: write large, it lands small on the page"), c::toggleZoomPad, selected = c.zoomPadOpen),
        a("formula", tr("Формула печатной: выделите формулу лассо и нажмите", "Typeset formula: select a formula with the lasso and press"), c::formulaFromSelection, title = tr("Формула печатной (выделенная)", "Typeset formula (selection)")),
        a("protractor", tr("Транспортир: углы и дуги", "Protractor: angles and arcs"), c::toggleProtractor, selected = c.protractorShown),
        a("compass", tr("Циркуль: окружности и дуги", "Compass: circles and arcs"), c::toggleCompass, selected = c.compassOn),
        a("timer", tr("Таймер учёбы: учёба и перерывы", "Study timer: study and breaks"), onTimer, selected = StudyTimer.phase != null, title = tr("Таймер учёбы…", "Study timer…")),
        a("pdf-text", tr("Текст PDF: проведите пером по словам — скопировать или выделить маркером", "PDF text: run the pen over words — copy or highlight them"), c::togglePdfText, selected = c.pdfTextOn, title = tr("Текст PDF", "PDF text")),
        a("pdf-page", tr("Страница PDF: повернуть, поля для заметок, вставить страницы", "PDF page: rotate, note margins, insert pages"), onPdfPage, title = tr("Страница PDF…", "PDF page…")),
        a("pdf-dark", tr("Тёмный PDF: светлый текст на тёмном", "Dark PDF: light text on dark"), c::togglePdfDark, selected = c.pdfDark),
        a(
            "side",
            if (sideOpen) tr("Закрыть тетрадь рядом", "Close the notebook beside") else tr("Открыть рядом: вторая тетрадь или PDF на том же экране", "Open side by side: a second notebook or PDF on the same screen"),
            { onSide?.invoke() },
            enabled = onSide != null,
            selected = sideOpen,
            title = if (sideOpen) tr("Закрыть рядом", "Close side by side") else tr("Открыть рядом…", "Open side by side…"),
        ),
        a("swap", tr("Поменять тетради местами", "Swap the notebooks"), { onSwap?.invoke() }, enabled = onSwap != null, title = tr("Поменять местами", "Swap")),
        a("scan", tr("Сканировать документ камерой", "Scan a document with the camera"), onScan, title = tr("Сканировать документ", "Scan document")),
        a(
            "pressure",
            if (c.penPressure) tr("Ручка чувствует нажим (нажмите, чтобы линия была одной толщины)", "The pen responds to pressure (tap for lines of one width)") else tr("Ручка без нажима: линия одной толщины", "Pen without pressure: lines of one width"),
            c::togglePenPressure,
            selected = c.penPressure,
            title = if (c.penPressure) tr("Нажим ручки: включён", "Pen pressure: on") else tr("Нажим ручки: выключен", "Pen pressure: off"),
        ),
        a(
            "theme",
            tr("Тема: ${themeMode.title.lowercase()} (нажмите, чтобы сменить)", "Theme: ${themeMode.title.lowercase()} (tap to change)"),
            { onThemeModeChange(ThemeMode.entries[(themeMode.ordinal + 1) % ThemeMode.entries.size]) },
            title = tr("Тема: ${themeMode.title.lowercase()}", "Theme: ${themeMode.title.lowercase()}"),
            icon = themeIcon(themeMode),
        ),
        a("pen-button", tr("Кнопка пера: ${c.penButtonLabel}", "Pen button: ${c.penButtonLabel}"), { c.learningPenButton = true }, title = tr("Кнопка пера: ${c.penButtonLabel}", "Pen button: ${c.penButtonLabel}")),
        a("scribble", tr("Стирание зачёркиванием", "Scribble to erase"), c::toggleScribbleErase, selected = c.scribbleErase, title = tr("Стирание зачёркиванием", "Scribble to erase")),
        a("help", tr("Справка: все инструменты", "Help: all tools"), onHelp, title = tr("Справка: все инструменты", "Help: all tools")),
    )
}

/** The page arrows with the page number between them, as one group on a bar. */
@Composable
private fun PageNav(c: EditorController, placing: Placing) {
    BarButton(BarAction("page-nav", "", tr("Предыдущая страница", "Previous page"), InkIcons.Previous, { c.goToPage(c.pageIndex - 1) }, enabled = c.pageIndex > 0), placing)
    Text("${c.pageIndex + 1} / ${c.notebook.pageIds.size}", maxLines = 1)
    BarButton(BarAction("page-nav", "", tr("Следующая страница", "Next page"), InkIcons.Next, { c.goToPage(c.pageIndex + 1) }, enabled = c.pageIndex < c.notebook.pageIds.lastIndex), placing)
}

/** Buttons of a bar in order, with a small gap where the group changes. */
@Composable
private fun BarButtons(c: EditorController, actions: List<BarAction>, placing: Placing, gap: androidx.compose.ui.unit.Dp) {
    var last = -1
    for (a in actions) {
        val g = BarCatalog.group(a.id)
        if (last >= 0 && g != last && gap > 0.dp) Spacer(Modifier.width(gap))
        last = g
        if (a.id == "page-nav") PageNav(c, placing) else BarButton(a, placing)
    }
}

@Composable
private fun TopRow(
    c: EditorController,
    top: List<BarAction>,
    menu: List<BarAction>,
    hiddenCount: Int,
    placing: Placing,
    onClose: () -> Unit,
    onAllFunctions: () -> Unit,
    onTimer: () -> Unit,
    compact: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // What does not fit goes to the top of the menu, in order (compact: room for the tools button).
        // In a narrow pane undo and redo come first: they are the last to go into the menu.
        val order = if (compact) top.sortedBy { if (it.id == "undo" || it.id == "redo") 0 else 1 } else top
        val (fits, spill) = fitOnBar(order.map { it.id }, maxWidth - TOP_BAR_RESERVED - (if (compact) 48.dp else 0.dp))
        val shown = top.filter { it.id in fits }
        val spilled = top.filter { it.id in spill }.flatMap { if (it.id == "page-nav") pageNavMenu(c) else listOf(it) }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(InkIcons.Back, tr("К тетрадям", "To notebooks"), onClose)
            val math = c.math.state
            val status = c.status ?: when (math) {
                MathNotes.State.Downloading -> tr("Загружаю модель формул…", "Downloading the formula model…")
                is MathNotes.State.Failed -> math.message
                else -> null
            }
            // Title with the status under it: they share the room left by the buttons. The line
            // under the title opens and closes smoothly, and one message fades into the next.
            Column(Modifier.weight(1f).animateContentSize(Motion.fast())) {
                Text(
                    c.notebook.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (compact) 16.sp else 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    c.learningPenButton -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Нажмите кнопку пера…", "Press the pen button…"), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, maxLines = 1)
                        TextButton(onClick = { c.learningPenButton = false }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            Text(tr("Отмена", "Cancel"), fontSize = 13.sp)
                        }
                    }
                    status != null -> androidx.compose.animation.AnimatedContent(
                        status,
                        transitionSpec = { fadeIn(Motion.fast()) togetherWith fadeOut(Motion.fast()) },
                        label = "status",
                    ) { shown ->
                        Text(
                            shown,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            StudyTimerChip(onClick = onTimer)
            Row(verticalAlignment = Alignment.CenterVertically) { BarButtons(c, shown, placing, BarCatalog.GROUP_GAP) }
            if (compact) ToolsToggle(c)
            Box {
                IconAction(InkIcons.More, tr("Ещё", "More"), { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val close = { menuOpen = false }
                    // Settings of the bars first: a long menu would hide them at its end.
                    MenuRow(BarAction("customize", tr("Настроить панели…", "Customise bars…"), tr("Какие кнопки где", "Which buttons go where"), InkIcons.Tune, placing.customize), null, close)
                    if (hiddenCount > 0) {
                        MenuRow(BarAction("all", tr("Все функции… ($hiddenCount скрыто)", "All functions… ($hiddenCount hidden)"), tr("Скрытые функции", "Hidden functions"), InkIcons.AllFunctions, onAllFunctions), null, close)
                    }
                    HorizontalDivider()
                    if (spilled.isNotEmpty()) {
                        MenuRows(spilled, placing, close)
                        HorizontalDivider()
                    }
                    MenuRows(menu.flatMap { if (it.id == "page-nav") pageNavMenu(c) else listOf(it) }, placing, close)
                }
            }
        }
    }
}

/** The icon of [tool] on its button. */
private fun toolIcon(tool: Tool) = when (tool) {
    Tool.PEN -> InkIcons.Pen
    Tool.FOUNTAIN -> InkIcons.Fountain
    Tool.HIGHLIGHTER -> InkIcons.Highlighter
    Tool.ERASER -> InkIcons.Eraser
    Tool.LASSO -> InkIcons.Lasso
    Tool.AREA -> InkIcons.Area
    Tool.NEON -> InkIcons.Neon
    Tool.LASER -> InkIcons.Laser
    Tool.SPOILER -> InkIcons.Spoiler
}

/** How long the pages panel takes to slide in or out. */
private const val PANEL_MS = 220

/** Cycles system → light → dark; the icon shows the current mode. */
@Composable
fun ThemeButton(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    IconAction(
        icon = themeIcon(mode),
        label = tr("Тема: ${mode.title.lowercase()} (нажмите, чтобы сменить)", "Theme: ${mode.title.lowercase()} (tap to change)"),
        onClick = { onChange(ThemeMode.entries[(mode.ordinal + 1) % ThemeMode.entries.size]) },
    )
}

fun themeIcon(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> InkIcons.AutoTheme
    ThemeMode.LIGHT -> InkIcons.Light
    ThemeMode.DARK -> InkIcons.Dark
}

/** Side column: search in this notebook's titles and handwriting; a tap shows the line. */
@Composable
private fun SearchPanel(c: EditorController) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<dev.inknotes.search.SearchHit>>(emptyList()) }
    LaunchedEffect(query, c.handwriting.version) {
        kotlinx.coroutines.delay(250)
        c.searchHere(query) { hits = it }
    }
    Column(
        Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(tr("Найти в тетради", "Find in the notebook")) },
        )
        HandwritingStatus(c.handwriting)
        if (query.isNotBlank()) {
            SearchResults(hits, emptyText = tr("Ничего не найдено", "Nothing found"), showNotebook = false, onClick = c::showHit)
        } else if (c.handwriting.state == dev.inknotes.search.HandwritingIndex.State.Ready) {
            Text(
                tr("Ищет по распознанному рукописному тексту. Нажмите на результат — строка подсветится на странице.", "Searches the recognised handwriting. Tap a result — the line lights up on the page."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Recognized text of the current page, selectable and copyable. */
@Composable
private fun PageTextDialog(c: EditorController, lines: List<dev.inknotes.search.TextLine>, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val text = lines.joinToString("\n") { it.text }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Текст страницы ${c.pageIndex + 1}", "Text of page ${c.pageIndex + 1}")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    c.handwriting.state != dev.inknotes.search.HandwritingIndex.State.Ready -> HandwritingStatus(c.handwriting)
                    lines.isEmpty() -> Text(
                        tr("Текст ещё не распознан или на странице нет рукописного текста. Распознавание идёт через пару секунд после того, как вы перестали писать.", "The text is not recognised yet or the page has no handwriting. Recognition runs a couple of seconds after you stop writing."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> androidx.compose.foundation.text.selection.SelectionContainer { Text(text) }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } },
        dismissButton = {
            if (lines.isNotEmpty()) {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    onDismiss()
                }) { Text(tr("Копировать", "Copy")) }
            }
        },
    )
}

/**
 * Side column of page thumbnails: tap to jump; hold and drag to move a page; hold and let go
 * for its menu (duplicate, move, delete…); a button at the end adds a page.
 */
@Composable
private fun PagesPanel(c: EditorController, onPageSize: (Int) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    // While a thumbnail is dragged the list shows this order; the notebook changes on release.
    // One state for the panel's life: the drag handlers below hold on to it.
    var order by remember { mutableStateOf(c.notebook.pageIds) }
    var dragged by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(c.notebook.pageIds) {
        if (dragged == null) order = c.notebook.pageIds
    }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragTravel by remember { mutableFloatStateOf(0f) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    val edgePx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(c.pageIndex) {
        if (dragged == null && c.pageIndex !in listState.firstVisibleItemIndex until listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size) {
            listState.animateScrollToItem(c.pageIndex)
        }
    }

    /** The dragged thumbnail moved by [dy]: it trades places with the one its middle is over. */
    fun dragBy(dy: Float) {
        val id = dragged ?: return
        dragOffset += dy
        dragTravel += kotlin.math.abs(dy)
        val items = listState.layoutInfo.visibleItemsInfo
        val cur = items.firstOrNull { it.key == id } ?: return
        val middle = cur.offset + dragOffset + cur.size / 2
        val target = items.firstOrNull { it.key != id && it.key is String && middle >= it.offset && middle <= it.offset + it.size }
        if (target != null) {
            val from = order.indexOf(id)
            val to = order.indexOf(target.key as String)
            if (from >= 0 && to >= 0) {
                order = order.toMutableList().apply { add(to, removeAt(from)) }
                // Where the thumbnail is laid out now: keep it under the finger.
                val newTop = if (to > from) target.offset + target.size - cur.size else target.offset
                dragOffset += cur.offset - newTop
            }
        }
        // Near the ends of the column, the list scrolls along.
        val info = listState.layoutInfo
        val top = cur.offset + dragOffset
        when {
            top < info.viewportStartOffset + edgePx -> scope.launch { listState.scrollBy(-gapPx) }
            top + cur.size > info.viewportEndOffset - edgePx -> scope.launch { listState.scrollBy(gapPx) }
        }
    }

    fun endDrag() {
        val id = dragged ?: return
        dragged = null
        dragOffset = 0f
        val from = c.notebook.pageIds.indexOf(id)
        val to = order.indexOf(id)
        when {
            from >= 0 && to >= 0 && from != to -> c.movePage(from, to)
            // Held without moving: the page's menu.
            dragTravel < gapPx -> menuFor = id
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .width(150.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val widest = c.notebook.pageIds.indices.maxOfOrNull(c::pageWidth) ?: PageEditorView.PAGE_W
        itemsIndexed(order, key = { _, id -> id }) { position, pageId ->
            val index = c.notebook.pageIds.indexOf(pageId)
            // A page deleted a moment ago is still in the order for a frame.
            if (index >= 0) {
                LaunchedEffect(pageId, c.thumbnailTick, c.dark) { c.requestThumbnail(pageId) }
                val isDragged = pageId == dragged
                PageThumbnail(
                    c = c,
                    index = index,
                    position = position,
                    image = c.thumbnails[pageId],
                    aspect = c.pageHeight(index) / c.pageWidth(index),
                    // Thumbnails keep the pages' sizes relative to each other.
                    widthShare = c.pageWidth(index) / widest,
                    current = index == c.pageIndex,
                    menuOpen = menuFor == pageId,
                    onMenuClose = { menuFor = null },
                    onDelete = { confirmDelete = index },
                    onPageSize = { onPageSize(index) },
                    modifier = if (isDragged) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer {
                                translationY = dragOffset
                                scaleX = 1.06f
                                scaleY = 1.06f
                                shadowElevation = 12.dp.toPx()
                            }
                    } else {
                        Modifier.animateItem()
                    },
                    dragHandle = Modifier.pointerInput(pageId) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragged = pageId
                                dragOffset = 0f
                                dragTravel = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragBy(amount.y)
                            },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() },
                        )
                    },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { c.addPageAt(c.notebook.pageIds.size) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                InkIcon(InkIcons.AddPage, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("В конец", "To the end"), maxLines = 1, softWrap = false)
            }
        }
    }
    confirmDelete?.let { index ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Удалить страницу ${index + 1}?", "Delete page ${index + 1}?")) },
            text = { Text(tr("Страницу нельзя будет вернуть кнопкой «Отменить».", "The page can't be brought back with “Undo”.")) },
            confirmButton = {
                Button(onClick = {
                    c.deletePage(index)
                    confirmDelete = null
                }) { Text(tr("Удалить", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** A page's thumbnail; [position] is its place in the column (it differs while one is dragged). */
@Composable
private fun PageThumbnail(
    c: EditorController,
    index: Int,
    position: Int,
    image: ImageBitmap?,
    aspect: Float,
    widthShare: Float,
    current: Boolean,
    menuOpen: Boolean,
    onMenuClose: () -> Unit,
    onDelete: () -> Unit,
    onPageSize: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth(widthShare.coerceIn(0.2f, 1f))
                .aspectRatio(1f / aspect)
                .clip(shape)
                .background(Color(PaperStyle.of(c.dark, c.notebook.background(c.dark).color).paper))
                .border(
                    if (current) 3.dp else 1.dp,
                    if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape,
                )
                .clickable(onClickLabel = tr("Перейти к странице ${index + 1}", "Go to page ${index + 1}")) { c.goToPage(index) }
                // After the click handler: it sees events first and takes the release of a hold,
                // so letting go after dragging (or for the menu) does not also open the page.
                .then(dragHandle)
        ) {
            if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuClose) {
                DropdownMenuItem(text = { Text(tr("Дублировать", "Duplicate")) }, onClick = {
                    onMenuClose()
                    c.duplicatePage(index)
                })
                DropdownMenuItem(text = { Text(tr("Переместить выше", "Move up")) }, enabled = index > 0, onClick = {
                    onMenuClose()
                    c.movePage(index, index - 1)
                })
                DropdownMenuItem(text = { Text(tr("Переместить ниже", "Move down")) }, enabled = index < c.notebook.pageIds.lastIndex, onClick = {
                    onMenuClose()
                    c.movePage(index, index + 1)
                })
                DropdownMenuItem(text = { Text(tr("Вставить пустую после", "Insert a blank one after")) }, onClick = {
                    onMenuClose()
                    c.addPageAt(index + 1)
                })
                DropdownMenuItem(text = { Text(tr("Размер и ориентация…", "Size and orientation…")) }, onClick = {
                    onMenuClose()
                    onPageSize()
                })
                DropdownMenuItem(text = { Text(tr("Удалить", "Delete"), color = MaterialTheme.colorScheme.error) }, enabled = c.notebook.pageIds.size > 1, onClick = {
                    onMenuClose()
                    onDelete()
                })
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("${position + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Tools, then the options of the current one, on one line: the strip of colours takes the
 * room left over (and scrolls), so the line fits a tablet held sideways. While something is
 * selected the line is the selection's (what can be done with it) instead of the tools.
 * When even that does not fit (a tablet held upright) the options wrap onto lines of their
 * own under the tools. The marker's options (the widest of the pens) decide it too, so the
 * bar keeps its height when switching tools.
 */
@Composable
private fun ToolRow(
    c: EditorController,
    tools: List<BarAction>,
    placing: Placing,
    onPickColor: () -> Unit,
    onHatchSettings: () -> Unit,
    onSelectionSize: () -> Unit,
    compact: Boolean = false,
) {
    val selection = c.selectionCount > 0
    val toolButtons = tools
    val tools: @Composable () -> Unit = { BarButtons(c, toolButtons, placing, 0.dp) }
    val divider: @Composable () -> Unit = {
        VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
    val options: @Composable () -> Unit = { ToolOptions(c, onPickColor, onHatchSettings, onSelectionSize) }
    val line: @Composable () -> Unit = if (selection) options else ({ tools(); divider(); options() })
    if (compact) {
        // One line that scrolls sideways: the page keeps the height that wrapping would take.
        CompositionLocalProvider(LocalColorStrip provides COMPACT_COLOR_STRIP, LocalCompactBar provides true) {
            ArrowRow { line() }
        }
        return
    }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        // The line laid out at its natural width with the narrowest colour strip, never shown.
        fun natural(slot: String, content: @Composable () -> Unit) = subcompose(slot) {
            CompositionLocalProvider(LocalColorStrip provides MIN_COLOR_STRIP) {
                BarRow(Modifier.clearAndSetSemantics { }, scroll = false) { content() }
            }
        }.maxOfOrNull { it.naturalWidth(constraints) } ?: 0
        val width = natural("probe", line)
        val oneLine = width <= constraints.maxWidth &&
            (selection || natural("reference") { tools(); divider(); InkOptions(c, onPickColor, Tool.HIGHLIGHTER) } <= constraints.maxWidth)
        val strip = if (oneLine) (MIN_COLOR_STRIP.toPx() + constraints.maxWidth - width).coerceAtMost(MAX_COLOR_STRIP.toPx()).toDp() else null
        val shown = subcompose(if (oneLine) "line" else if (selection) "wrapped" else "two") {
            CompositionLocalProvider(LocalColorStrip provides strip) {
                when {
                    oneLine -> BarRow { line() }
                    selection -> WrapRow { options() }
                    else -> Column {
                        BarRow { tools() }
                        WrapRow { options() }
                    }
                }
            }
        }.map { it.measure(constraints.copy(minHeight = 0)) }
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) { shown.forEach { it.placeRelative(0, 0) } }
    }
}

/** Width the colour strip may take; null for its own default. */
private val LocalColorStrip = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.unit.Dp?> { null }
private val MIN_COLOR_STRIP = 112.dp
private val MAX_COLOR_STRIP = 336.dp
private val COMPACT_COLOR_STRIP = 236.dp

/** The bar is the one line of a narrow pane: sliders are shorter there. */
private val LocalCompactBar = androidx.compose.runtime.compositionLocalOf { false }

/** Options that wrap onto more lines rather than run off the edge. */
@Composable
private fun WrapRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Width of a probe at no width limit (measured, so it works for any content). */
private fun androidx.compose.ui.layout.Measurable.naturalWidth(constraints: androidx.compose.ui.unit.Constraints): Int =
    measure(androidx.compose.ui.unit.Constraints(maxHeight = constraints.maxHeight)).width

@Composable
private fun BarRow(modifier: Modifier = Modifier, scroll: Boolean = true, vertical: androidx.compose.ui.unit.Dp = 4.dp, content: @Composable () -> Unit) {
    Row(
        modifier
            .let { if (scroll) it.fillMaxWidth().horizontalScroll(rememberScrollState()) else it }
            .padding(horizontal = if (vertical == 0.dp) 8.dp else 16.dp, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** What the bar offers for the current tool, or for the selection while there is one. */
@Composable
private fun ToolOptions(c: EditorController, onPickColor: () -> Unit, onHatchSettings: () -> Unit, onSelectionSize: () -> Unit) {
    // A finger tap selects in any tool: then the bar is the selection's.
    if (c.selectionCount > 0) SelectionOptions(c, onPickColor, onHatchSettings, onSelectionSize) else when (c.tool) {
        Tool.PEN, Tool.FOUNTAIN, Tool.HIGHLIGHTER, Tool.NEON, Tool.SPOILER -> InkOptions(c, onPickColor)
        Tool.LASER -> Text(
            tr("Указка: след гаснет сам и не записывается", "Pointer: the trail fades by itself and is not saved"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Tool.ERASER -> {
            SizeSlider(c)
            FilterChip(
                selected = c.eraserMode == EraserMode.WHOLE,
                onClick = { c.selectEraserMode(EraserMode.WHOLE) },
                label = { Text(tr("Штрих целиком", "Whole stroke")) },
            )
            FilterChip(
                selected = c.eraserMode == EraserMode.PARTIAL,
                onClick = { c.selectEraserMode(EraserMode.PARTIAL) },
                label = { Text(tr("Частично", "Partly")) },
            )
            EraserTargetButton(c)
        }
        Tool.LASSO -> Text(
            tr("Обведите пером то, что нужно выделить, или коснитесь объекта пальцем", "Circle what to select with the pen, or touch an object with a finger"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Tool.AREA -> {
            // One swatch with the current pattern: patterns and settings are in its dialog,
            // so the bar fits on screen with any number of colours.
            PatternSwatch(
                c.fillPattern,
                c.fillStyle,
                InkColors.forPaper(c.fillColor, c.paperDark),
                selected = true,
                label = tr("Узор: ${c.fillPattern.title}. Выбрать узор и настроить", "Pattern: ${c.fillPattern.title}. Choose and adjust the pattern"),
                onClick = onHatchSettings,
            )
            ColorChoices(c, selected = c.fillColor, onPickColor)
            Spacer(Modifier.width(8.dp))
            if (c.hasPendingArea) {
                Button(onClick = { c.applyFill() }) {
                    InkIcon(InkIcons.Area, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Заштриховать", "Hatch"))
                }
                IconAction(InkIcons.Close, tr("Отменить область", "Cancel the area"), { c.cancelArea() })
            } else {
                Text(tr("Коснитесь внутри фигуры", "Tap inside a shape"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Open notebooks as tabs: tap to switch, ✕ to close; the library is one tap away. */
@Composable
private fun TabStrip(
    current: EditorController,
    tabs: List<EditorController>,
    onTab: (EditorController) -> Unit,
    onCloseTab: (EditorController) -> Unit,
    onAdd: () -> Unit,
    drag: TabDragging?,
    right: Boolean,
    onCloseAll: (() -> Unit)? = null,
) {
    // Where the strip and each tab are (in the window's root): a dragged tab is placed by them,
    // in this strip or in the other half's.
    val info = remember { TabDragging.Strip() }
    if (drag != null) {
        androidx.compose.runtime.DisposableEffect(drag, right) {
            drag.strips[right] = info
            onDispose { if (drag.strips[right] === info) drag.strips.remove(right) }
        }
    }
    val bounds = info.tabs
    info.tabs.keys.retainAll(tabs.toSet())
    // A tab of this strip being dragged when the strip goes (its half closed): the drag ends,
    // the overlay does not stay on screen.
    if (drag != null) {
        val mine = tabs.toSet()
        androidx.compose.runtime.DisposableEffect(drag, mine) {
            onDispose { if (drag.tab != null && drag.tab in mine) drag.cancel() }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dragging = drag?.tab
    // The gap where the dragged tab would land, as wide as that tab; the other tabs slide.
    val gapAt = drag?.target?.takeIf { it.first == right }?.second
    val gapWidth = with(density) { ((dragging?.let { drag.strips.values.firstNotNullOfOrNull { s -> s.tabs[it] } }?.width ?: 0f)).toDp() }
    val others = tabs.filter { it !== dragging }
    // Tabs being closed: they fold away first, then go.
    val closing = remember { androidx.compose.runtime.mutableStateMapOf<EditorController, Boolean>() }
    val scope = rememberCoroutineScope()
    fun closeSmoothly(list: List<EditorController>, then: () -> Unit) {
        list.forEach { closing[it] = true }
        scope.launch {
            kotlinx.coroutines.delay(Motion.FAST.toLong())
            list.forEach { closing.remove(it) }
            then()
        }
    }
    @Composable
    fun gap(at: Int) {
        val w by androidx.compose.animation.core.animateDpAsState(
            if (gapAt == at) gapWidth else 0.dp,
            androidx.compose.animation.core.tween(TAB_SLIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "tab gap",
        )
        if (w > 0.5.dp) {
            Box(
                Modifier
                    .width(w)
                    .height(TAB_HEIGHT)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(6.dp)),
            )
        }
    }
    // Many tabs scroll sideways, with the same arrows at the ends as the tool line.
    ArrowRow(
        Modifier.onGloballyPositioned { info.rect = it.boundsInRoot() },
        height = TAB_HEIGHT,
        spacing = 4.dp,
        padding = PaddingValues(horizontal = 6.dp, vertical = 1.dp),
    ) {
        for (t in tabs) {
            val selected = t === current
            val dragged = dragging === t
            if (!dragged) gap(others.indexOf(t))
            // The dragged tab folds away where it was (it stays composed: the drag goes on in it).
            val folded by androidx.compose.animation.core.animateFloatAsState(
                if (dragged || closing[t] == true) 0f else 1f,
                androidx.compose.animation.core.tween(if (closing[t] == true) Motion.FAST else TAB_SLIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                label = "tab fold",
            )
            // A new tab unfolds into the strip.
            val appear = remember(t) { androidx.compose.animation.core.Animatable(if (bounds.isEmpty()) 1f else 0f) }
            LaunchedEffect(t) { appear.animateTo(1f, Motion.fast()) }
            val fold = folded * appear.value
            Row(
                Modifier
                    .height(TAB_HEIGHT)
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        // Only its laid-out width folds; where it was measured stays for the gap.
                        layout((p.width * fold).toInt(), p.height) { p.placeRelative(0, 0) }
                    }
                    .onGloballyPositioned { if (!dragged) bounds[t] = it.boundsInRoot() }
                    .alpha(fold)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .let { m ->
                        if (drag == null) {
                            m
                        } else {
                            // A long press picks the tab up; it follows the finger or pen.
                            m.pointerInput(t) {
                                var at = Offset.Zero
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { local ->
                                        at = (bounds[t]?.topLeft ?: Offset.Zero) + local
                                        drag.start(t, at)
                                    },
                                    onDragEnd = { drag.drop(t, at) },
                                    onDragCancel = { drag.cancel() },
                                ) { change, amount ->
                                    change.consume()
                                    at += amount
                                    drag.move(at)
                                }
                            }
                        }
                    }
                    .clickable { onTab(t) }
                    .padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t.notebook.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                // Wider than it looks: easy to hit with a finger, the strip stays thin.
                Box(
                    Modifier.width(36.dp).height(TAB_HEIGHT).clip(RoundedCornerShape(6.dp)).clickable { if (closing[t] != true) closeSmoothly(listOf(t)) { onCloseTab(t) } }.semantics { contentDescription = tr("Закрыть вкладку", "Close tab") },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        gap(others.size)
        Box(
            Modifier.height(TAB_HEIGHT).clip(RoundedCornerShape(6.dp)).clickable(onClick = onAdd).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text(if (right) "+" else tr("+ Тетрадь", "+ Notebook"), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
        if (onCloseAll != null && tabs.size >= 2) CloseAllTabs(tabs.size) { closeSmoothly(tabs, onCloseAll) }
    }
}

/**
 * A line of buttons that scrolls sideways, with arrows at the ends while something is out of
 * sight: a tap on one moves the line, by finger or pen, and shows that there is more.
 */
@Composable
private fun ArrowRow(
    modifier: Modifier = Modifier,
    /** Height of the arrows: that of the line. */
    height: androidx.compose.ui.unit.Dp = 48.dp,
    spacing: androidx.compose.ui.unit.Dp = 8.dp,
    padding: PaddingValues = PaddingValues(horizontal = 8.dp),
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var width by remember { mutableStateOf(0) }
    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).padding(padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) { content() }
        val bg = MaterialTheme.colorScheme.surface
        @Composable
        fun arrow(forward: Boolean, align: Alignment) {
            Box(
                Modifier
                    .align(align)
                    .width(if (height < 40.dp) 36.dp else 44.dp)
                    .height(height)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            if (forward) listOf(bg.copy(alpha = 0f), bg, bg) else listOf(bg, bg, bg.copy(alpha = 0f)),
                        ),
                    )
                    .clickable {
                        // An unhurried glide that eases in and out, to the next part of the line.
                        scope.launch {
                            scroll.animateScrollBy(
                                (if (forward) 1 else -1) * width * 0.7f,
                                androidx.compose.animation.core.tween(ARROW_SCROLL_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            )
                        }
                    }
                    .semantics { contentDescription = if (forward) tr("Дальше по панели", "Further along the bar") else tr("Назад по панели", "Back along the bar") },
                contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart,
            ) { InkIcon(if (forward) InkIcons.Next else InkIcons.Previous, contentDescription = null, modifier = Modifier.size(if (height < 40.dp) 16.dp else 20.dp)) }
        }
        // The arrows fade in and out as the line reaches its ends, instead of popping.
        AnimatedVisibility(scroll.canScrollBackward, Modifier.align(Alignment.CenterStart), enter = fadeIn(tween(ARROW_FADE_MS)), exit = fadeOut(tween(ARROW_FADE_MS))) {
            Box { arrow(forward = false, Alignment.CenterStart) }
        }
        AnimatedVisibility(scroll.canScrollForward, Modifier.align(Alignment.CenterEnd), enter = fadeIn(tween(ARROW_FADE_MS)), exit = fadeOut(tween(ARROW_FADE_MS))) {
            Box { arrow(forward = true, Alignment.CenterEnd) }
        }
    }
}

private const val ARROW_SCROLL_MS = 450
private const val TOOLS_FOLD_MS = Motion.NORMAL

/**
 * Hides the tools of a narrow pane, and brings them back. Shown: a plain "fold up" button.
 * Hidden: a tinted chip with the tool in hand and an arrow down, that pulses as the tools fold
 * into it — it is where they went, and a tap brings them out again.
 */
@Composable
private fun ToolsToggle(c: EditorController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hidden = c.toolsHidden
    val pulse = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(hidden) {
        if (hidden) {
            kotlinx.coroutines.delay(TOOLS_FOLD_MS.toLong() - 60)
            pulse.animateTo(1.18f, tween(140))
            pulse.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 500f))
        }
    }
    val toggle = {
        c.toolsHidden = !c.toolsHidden
        if (c.toolsHidden) {
            val prefs = AppServices.get(context).prefs
            if (prefs.toolsHiddenHints < 3) {
                prefs.toolsHiddenHints = prefs.toolsHiddenHints + 1
                c.showStatus(tr("Инструменты спрятаны в кнопку справа сверху — коснитесь её, чтобы вернуть", "The tools are tucked into the button at the top right — tap it to bring them back"))
            }
        }
    }
    if (!hidden) {
        IconAction(InkIcons.ToolsHide, tr("Скрыть инструменты (больше места для страницы)", "Hide tools (more room for the page)"), toggle)
        return
    }
    Row(
        Modifier
            .padding(horizontal = 4.dp)
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = toggle)
            .semantics { contentDescription = tr("Показать инструменты", "Show tools") }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        InkIcon(toolIcon(c.tool), contentDescription = null, modifier = Modifier.size(20.dp))
        InkIcon(InkIcons.ToolsShow, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}
private const val TAB_SLIDE_MS = 220
private const val ARROW_FADE_MS = 180

/**
 * "Закрыть все" at the end of a strip of tabs. Two taps: the first turns it into a red
 * "Закрыть N вкладок?" for a few seconds, the second (only then) closes them; left alone it
 * turns back. Easy on purpose, never by a stray touch.
 */
@Composable
private fun CloseAllTabs(count: Int, onCloseAll: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            kotlinx.coroutines.delay(CLOSE_ALL_ARMED_MS)
            armed = false
        }
    }
    val bg by androidx.compose.animation.animateColorAsState(
        if (armed) MaterialTheme.colorScheme.error else Color.Transparent,
        tween(180),
        label = "close all",
    )
    Box(
        Modifier
            .height(TAB_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable {
                if (armed) {
                    armed = false
                    onCloseAll()
                } else {
                    armed = true
                }
            }
            .semantics { contentDescription = if (armed) tr("Подтвердить: закрыть все вкладки", "Confirm: close all tabs") else tr("Закрыть все вкладки", "Close all tabs") }
            .animateContentSize(tween(180))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (armed) tr("Закрыть $count ${tabsWord(count)}?", "Close $count ${if (count == 1) "tab" else "tabs"}?") else tr("Закрыть все", "Close all"),
            fontSize = 13.sp,
            fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal,
            color = if (armed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "вкладку", "вкладки", "вкладок" after [n]. */
private fun tabsWord(n: Int): String = when {
    n % 100 in 11..14 -> "вкладок"
    n % 10 == 1 -> "вкладку"
    n % 10 in 2..4 -> "вкладки"
    else -> "вкладок"
}

private const val CLOSE_ALL_ARMED_MS = 3000L

/** Height of a tab: a thin strip, the page keeps the room. */
private val TAB_HEIGHT = 28.dp

/**
 * A tab being dragged, told to whoever shows the tabs: [move] and [drop] with the point in the
 * window's root. [strips]: the strips of tabs on screen (true: the right half's), where they
 * and their tabs are, so a tab can be dropped among the tabs of either.
 */
class TabDragging(
    val start: (EditorController, Offset) -> Unit,
    val move: (Offset) -> Unit,
    val drop: (EditorController, Offset) -> Unit,
    val cancel: () -> Unit,
) {
    /** The tab being dragged now (null: none). */
    var tab by mutableStateOf<EditorController?>(null)

    /**
     * Where it would land now: the strip (true: the right half's) and its place among the other
     * tabs there. That strip opens a gap there (the others slide aside); null: none does.
     */
    var target by mutableStateOf<Pair<Boolean, Int>?>(null)

    class Strip {
        var rect = androidx.compose.ui.geometry.Rect.Zero
        val tabs = HashMap<EditorController, androidx.compose.ui.geometry.Rect>()
    }

    val strips = HashMap<Boolean, Strip>()
}

/** Typing text for the page (new, or editing a text box). */
@Composable
private fun TypeTextDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
                placeholder = { Text(tr("Текст", "Text")) },
            )
        },
        confirmButton = {
            Button(onClick = {
                onDone(text)
                onDismiss()
            }) { Text(tr("Готово", "Done")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}

/** Less frequent things to do with the selection: contents, link. */
@Composable
private fun SelectionMoreButton(c: EditorController) {
    Box {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }) { Text(tr("Ещё ▾", "More ▾")) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(tr("В оглавление…", "To contents…")) }, onClick = {
                open = false
                c.showAddBookmark = true
            })
            DropdownMenuItem(text = { Text(tr("Ссылка на страницу…", "Link to a page…")) }, onClick = {
                open = false
                c.showLinkDialog = true
            })
            DropdownMenuItem(text = { Text(tr("На слой…", "To a layer…")) }, onClick = {
                open = false
                c.showLayersDialog = true
            })
        }
    }
}

/** What the eraser takes: everything, or only the pen, the marker or hatching. */
@Composable
private fun EraserTargetButton(c: EditorController) {
    Box {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }) { Text(tr("Стирает: ${c.eraserTarget.title.lowercase().removePrefix("только ")} ▾", "Erases: ${c.eraserTarget.title.lowercase().removeSuffix(" only")} ▾")) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (t in dev.inknotes.editor.EraserTarget.entries) {
                DropdownMenuItem(
                    text = { Text(t.title) },
                    leadingIcon = { if (t == c.eraserTarget) Text("✓") },
                    onClick = {
                        open = false
                        c.selectEraserTarget(t)
                    },
                )
            }
        }
    }
}

/** What can be done with the selection: colour, mirror, size, text, copy, delete. */
@Composable
private fun SelectionOptions(c: EditorController, onPickColor: () -> Unit, onHatchSettings: () -> Unit, onSelectionSize: () -> Unit) {
    IconAction(InkIcons.Close, tr("Снять выделение", "Deselect"), { c.clearSelection() })
    Text(tr("Выделено: ${c.selectionCount}", "Selected: ${c.selectionCount}"), fontWeight = FontWeight.Medium)
    ColorChoices(c, selected = null, onPickColor)
    if (c.selectionLineKinds.isNotEmpty()) LineKindButton(c)
    SelectionWidthButton(c)
    SelectionDashButton(c)
    if (c.selectionIsTable) {
        IconAction(InkIcons.TableAddRow, tr("Добавить строку снизу", "Add a row below"), { c.addToTable(row = true) })
        IconAction(InkIcons.TableAddColumn, tr("Добавить столбец справа", "Add a column on the right"), { c.addToTable(row = false) })
        IconAction(InkIcons.TableRemoveRow, tr("Убрать последнюю строку", "Remove the last row"), { c.removeFromTable(row = true) })
        IconAction(InkIcons.TableRemoveColumn, tr("Убрать последний столбец", "Remove the last column"), { c.removeFromTable(row = false) })
    }
    if (c.selectedFills > 0) IconAction(InkIcons.Tune, tr("Штриховка выделенного", "Hatching of the selection"), onHatchSettings)
    IconAction(InkIcons.FlipHorizontal, tr("Отразить слева направо", "Flip left to right"), { c.flipSelection(horizontal = true) })
    IconAction(InkIcons.FlipVertical, tr("Отразить сверху вниз", "Flip top to bottom"), { c.flipSelection(horizontal = false) })
    IconAction(InkIcons.Ruler, tr("Размер в миллиметрах", "Size in millimetres"), onSelectionSize)
    if (c.selectedPicture != null) IconAction(InkIcons.PhotoEdit, tr("Изменить картинку: обрезать, выпрямить, перспектива, фон", "Edit picture: crop, straighten, perspective, background"), c::editSelectedPicture)
    if (c.selectedText != null) {
        TextButton(onClick = { c.editingText = true }) { Text(tr("Изменить текст", "Edit text")) }
        TextButton(onClick = { c.scaleSelectedText(1f / 1.2f) }) { Text(tr("А−", "A−")) }
        TextButton(onClick = { c.scaleSelectedText(1.2f) }) { Text(tr("А+", "A+")) }
    } else {
        TextButton(onClick = { c.recognizeSelection() }) { Text(tr("Текст", "Text")) }
        if (c.selectionLineKinds.isNotEmpty()) TextButton(onClick = { c.formulaFromSelection() }) { Text(tr("Формула", "Formula")) }
    }
    SelectionMoreButton(c)
    IconAction(InkIcons.Duplicate, tr("Дублировать", "Duplicate"), { c.duplicateSelection() })
    IconAction(InkIcons.Delete, tr("Удалить выделенное", "Delete the selection"), { c.deleteSelection() })
}

private val LINE_KINDS = listOf(
    InkKind.PEN to tr("Ручка", "Pen"),
    InkKind.LINE to tr("Ровная", "Even"),
    InkKind.PENCIL to tr("Карандаш", "Pencil"),
    InkKind.DASHED to tr("Пунктир", "Dashed"),
    InkKind.FOUNTAIN to tr("Перо", "Nib"),
    InkKind.NEON to tr("Неон", "Neon"),
)

/** Type of the selected lines: a neon line made an ordinary one, a pen line pencil… */
@Composable
private fun LineKindButton(c: EditorController) {
    Box {
        var open by remember { mutableStateOf(false) }
        val kinds = c.selectionLineKinds
        val title = if (kinds.size == 1) LINE_KINDS.firstOrNull { it.first == kinds.first() }?.second ?: tr("Линия", "Line") else tr("Тип линии", "Line type")
        TextButton(onClick = { open = true }) { Text("$title ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((kind, name) in LINE_KINDS) {
                DropdownMenuItem(
                    text = { Text((if (kinds.size == 1 && kind in kinds) "✓ " else "") + name) },
                    onClick = {
                        c.restyleSelection(kind)
                        open = false
                    },
                )
            }
        }
    }
}

private val PEN_KINDS = listOf(InkKind.PEN to tr("Ручка", "Pen"), InkKind.PENCIL to tr("Карандаш", "Pencil"), InkKind.DASHED to tr("Пунктир", "Dashed"))

/** Pen, pencil, dashes or the fountain pen for the pen button. */
@Composable
private fun PenKindButton(c: EditorController) {
    Box {
        var open by remember { mutableStateOf(false) }
        val fountain = c.tool == Tool.FOUNTAIN
        val title = if (fountain) tr("Перо", "Nib") else PEN_KINDS.firstOrNull { it.first == c.penKind }?.second ?: tr("Ручка", "Pen")
        TextButton(onClick = { open = true }) { Text("$title ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((kind, name) in PEN_KINDS) {
                DropdownMenuItem(
                    text = { Text((if (!fountain && kind == c.penKind) "✓ " else "") + name) },
                    onClick = {
                        c.selectPenKind(kind)
                        c.selectTool(Tool.PEN)
                        open = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text((if (fountain) "✓ " else "") + tr("Перо", "Nib")) },
                onClick = {
                    c.selectTool(Tool.FOUNTAIN)
                    open = false
                },
            )
        }
    }
}

@Composable
private fun InkOptions(c: EditorController, onPickColor: () -> Unit, tool: Tool = c.tool) {
    val highlighter = tool == Tool.HIGHLIGHTER
    val color = when (tool) {
        Tool.HIGHLIGHTER -> c.highlighterColor
        Tool.FOUNTAIN -> c.fountainColor
        Tool.NEON -> c.neonColor
        Tool.SPOILER -> c.spoilerColor
        else -> c.penColor
    }
    if (tool == Tool.PEN || tool == Tool.FOUNTAIN) PenKindButton(c)
    if (tool == Tool.PEN && c.penKind == InkKind.DASHED) DashSettingsButton(c)
    // The slider first: it stays on screen however many recent colours there are.
    SizeSlider(c, tool)
    Spacer(Modifier.width(4.dp))
    ColorChoices(c, selected = color, onPickColor, tool)
    if (highlighter || tool == Tool.SPOILER) {
        FilterChip(
            selected = c.straightHighlighter,
            onClick = c::toggleStraightHighlighter,
            label = { Text(tr("Ровно по тексту", "Along the text")) },
        )
    }
}

/**
 * Size of the current tool: a preview dot (the eraser shows its circle) and a slider.
 * Pen sizes are page units, so the dot is drawn at roughly the size the line will have.
 */
@Composable
private fun SizeSlider(c: EditorController, tool: Tool = c.tool) {
    val (range, value) = when (tool) {
        Tool.HIGHLIGHTER -> EditorController.HIGHLIGHTER_RANGE to c.highlighterSize
        Tool.FOUNTAIN -> EditorController.FOUNTAIN_RANGE to c.fountainSize
        Tool.NEON -> EditorController.NEON_RANGE to c.neonSize
        Tool.SPOILER -> EditorController.SPOILER_RANGE to c.spoilerSize
        Tool.ERASER -> EditorController.ERASER_RANGE to c.eraserSize
        else -> EditorController.PEN_RANGE to c.penSize
    }
    val eraser = tool == Tool.ERASER
    val neon = tool == Tool.NEON
    val ink = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier
                .size(40.dp)
                .semantics { contentDescription = tr("Толщина", "Width") }
        ) {
            // Eraser: radius in dp; ink: page units at about the on-screen page scale.
            val diameter = if (eraser) value * 2 * density else value * 1.4f * density
            val d = diameter.coerceIn(2f, size.minDimension)
            if (neon) {
                // The tube and its light core at the size the line will have; the glow follows.
                val glow = Color(c.neonColor)
                val tube = d / 2
                val halo = (tube + Neon.haloWidth(value) * 1.4f * density).coerceAtMost(size.minDimension / 2)
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(glow.copy(alpha = 0.75f), glow.copy(alpha = 0f)), radius = halo), halo)
                drawCircle(glow, tube)
                drawCircle(Color(Neon.core(c.neonColor)), tube * Neon.CORE)
            } else if (eraser) {
                drawCircle(ink, d / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * density))
            } else {
                drawCircle(ink, d / 2)
            }
        }
        InkSlider(
            value = value.coerceIn(range.min, range.max),
            onValueChange = { c.selectSize(it) },
            valueRange = range.min..range.max,
            modifier = Modifier.width(if (LocalCompactBar.current) 112.dp else 160.dp),
            valueLabel = { "%.1f".format(it) },
        )
    }
}

/**
 * Preset colours, then the user's own in the order they were added (a long press removes one),
 * in a strip that scrolls; the picker and the eyedropper stay after it.
 */
@Composable
private fun ColorChoices(c: EditorController, selected: Int?, onPickColor: () -> Unit, tool: Tool = c.tool) {
    val highlighter = tool == Tool.HIGHLIGHTER && c.selectionCount == 0
    val custom = c.customChoices()
    val scroll = rememberScrollState()
    // A colour just added is at the end: bring it into view.
    val shown = remember { intArrayOf(custom.size) }
    LaunchedEffect(custom.size) {
        if (custom.size > shown[0]) scroll.animateScrollTo(scroll.maxValue)
        shown[0] = custom.size
    }
    Row(
        // Narrower beside the pen-kind button, so the picker and eyedropper stay on screen.
        Modifier.widthIn(max = LocalColorStrip.current ?: if (tool == Tool.PEN) 236.dp else 336.dp).horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (color in c.presetColors() + custom) {
            val own = color in custom
            val value = if (highlighter && own) (color and 0x00FFFFFF) or 0x80000000.toInt() else color
            Swatch(
                Color(InkColors.forPaper(value, c.paperDark) or 0xFF000000.toInt()),
                selected = selected != null && sameRgb(selected, value),
                onLongClick = if (own) ({ c.removeCustomColor(color) }) else null,
            ) { c.selectColor(value) }
        }
    }
    PickerSwatch(onPickColor)
    IconAction(InkIcons.Eyedropper, tr("Пипетка: взять цвет со страницы или фото", "Eyedropper: take a colour from the page or a photo"), { c.toggleColorPick() }, selected = c.pickingColor)
}

private fun sameRgb(a: Int, b: Int) = (a and 0xFFFFFF) == (b and 0xFFFFFF)


@Composable
private fun Swatch(color: Color, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
            .padding(5.dp)
            .background(color, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    )
}

/** Rainbow circle that opens the spectrum/palette picker. */
@Composable
private fun PickerSwatch(onClick: () -> Unit) {
    val rainbow = remember { (0..6).map { Color.hsv(it * 60f % 360f, 0.85f, 1f) } }
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Brush.sweepGradient(rainbow))
            .clickable(onClickLabel = tr("Другой цвет", "Another colour"), onClick = onClick)
            .semantics { contentDescription = tr("Другой цвет", "Another colour") },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}

// ------------------------------------------------------------------ background

@Composable
private fun BackgroundDialog(c: EditorController, onDismiss: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    var photoForDark by remember { mutableStateOf(false) }
    var customFor by remember { mutableStateOf<Boolean?>(null) }
    customFor?.let { darkTheme ->
        val current = c.notebook.background(darkTheme)
        ColorPickerDialog(
            initial = current.color ?: PaperStyle.of(darkTheme).paper,
            onPick = {
                c.setBackground(darkTheme, current.copy(color = it or 0xFF000000.toInt()))
                customFor = null
            },
            onDismiss = { customFor = null },
        )
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) c.importPhoto(photoForDark, uri, resolver)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Фон тетради", "Notebook background")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                for (darkTheme in listOf(false, true)) {
                    ThemeBackgrounds(
                        c = c,
                        darkTheme = darkTheme,
                        onPickPhoto = {
                            photoForDark = darkTheme
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onCustomColor = { customFor = darkTheme },
                    )
                }
                if (c.importingPhoto) Text(tr("Загружаю фото…", "Loading the photo…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Готово", "Done")) } },
    )
}

@Composable
private fun ThemeBackgrounds(c: EditorController, darkTheme: Boolean, onPickPhoto: () -> Unit, onCustomColor: () -> Unit) {
    val current = c.notebook.background(darkTheme)
    var lines by remember(current.lines) { mutableFloatStateOf(current.lines) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (darkTheme) tr("Тёмная тема", "Dark theme") else tr("Светлая тема", "Light theme"), fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (kind in PaperKind.entries) {
                val isPhoto = kind == PaperKind.PHOTO
                PaperPreview(
                    kind = kind,
                    dark = darkTheme,
                    color = current.color,
                    photo = if (isPhoto) c.photo(current.photo) else null,
                    selected = current.kind == kind,
                    lines = lines,
                    onClick = {
                        if (isPhoto) onPickPhoto() else c.setBackground(darkTheme, PageBackground(kind, color = current.color, lines = lines))
                    },
                )
            }
        }
        // Paper colour: the theme's own, ready shades or any colour.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Цвет листа", "Paper colour"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val swatches: List<Int?> = listOf(null) + if (darkTheme) DARK_PAPERS else LIGHT_PAPERS
            for (color in swatches) {
                val shown = color ?: PaperStyle.of(darkTheme).paper
                val selected = current.color == color
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color(shown))
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(15.dp))
                        .clickable(onClickLabel = if (color == null) tr("Цвет темы", "Theme colour") else tr("Цвет листа", "Paper colour")) {
                            c.setBackground(darkTheme, current.copy(color = color))
                        },
                )
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                    .clickable(onClickLabel = tr("Любой цвет", "Any colour"), onClick = onCustomColor),
            )
        }
        if (current.kind != PaperKind.BLANK && current.kind != PaperKind.PHOTO) {
            LinesSlider(lines, onChange = { lines = it }, onDone = { c.setBackground(darkTheme, current.copy(lines = lines)) })
        }
    }
}

/** How strongly the ruling (lines, grid, dots) shows, from invisible to full. */
@Composable
fun LinesSlider(value: Float, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Линии фона: ${Math.round(value * 100)}%", "Background lines: ${Math.round(value * 100)}%"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        InkSlider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = 0.1f..1f,
            modifier = Modifier.width(240.dp).semantics { contentDescription = tr("Прозрачность линий фона", "Background line opacity") },
        )
    }
}

private val LIGHT_PAPERS = listOf(0xFFFFF8E7, 0xFFFFF9C4, 0xFFE8F5E9, 0xFFE3F2FD, 0xFFFCE4EC, 0xFFF3E5F5, 0xFFECEFF1).map { it.toInt() }
private val DARK_PAPERS = listOf(0xFF000000, 0xFF263238, 0xFF1B2A1E, 0xFF1A237E, 0xFF3E2723, 0xFF2D1B33, 0xFF37474F).map { it.toInt() }

/** Miniature page in the theme's paper colours; tapping the photo tile opens the picker. */
@Composable
fun PaperPreview(
    kind: PaperKind,
    dark: Boolean,
    color: Int?,
    photo: android.graphics.Bitmap?,
    selected: Boolean,
    lines: Float = 1f,
    /** Width to height of the page shown. */
    aspect: Float = 210f / 297f,
    onClick: () -> Unit,
) {
    val style = PaperStyle.of(dark, color)
    val shape = RoundedCornerShape(6.dp)
    val side = 84.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = if (aspect >= 1f) side else side * aspect, height = if (aspect >= 1f) side / aspect else side)
                .clip(shape)
                .background(Color(style.paper))
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape,
                )
                .clickable(onClickLabel = kind.title, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (kind == PaperKind.PHOTO) {
                if (photo != null) {
                    Image(
                        photo.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text("+", fontSize = 24.sp, color = Color(style.line))
                }
            } else {
                // The page's own drawing, scaled down, with lines a pixel wide.
                val renderer = remember(kind, dark, color, lines) {
                    PageRenderer().apply {
                        paper = PageBackground(kind, color = color, lines = lines)
                        this.dark = dark
                        hairline = true
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    // Fine patterns are shown close up (a corner of the page): whole, they blur into grey.
                    val zoom = if (kind == PaperKind.DOTS || kind == PaperKind.GRID || kind == PaperKind.DASH_GRID || kind == PaperKind.MILLIMETER) 2.5f else 1f
                    val k = size.width / PageEditorView.PAGE_W * zoom
                    renderer.pageWidth = size.width / k
                    renderer.pageHeight = size.height / k
                    renderer.dotRadius = 0.8f / k
                    drawIntoCanvas { c ->
                        c.nativeCanvas.save()
                        c.nativeCanvas.scale(k, k)
                        renderer.drawBackground(c.nativeCanvas, edgeWidth = 0f)
                        c.nativeCanvas.restore()
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(kind.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
