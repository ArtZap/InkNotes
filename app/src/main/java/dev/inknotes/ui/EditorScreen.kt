package dev.inknotes.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
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
) {
    BackHandler(onBack = onClose)
    DisposableEffect(controller) {
        onDispose { controller.detach() }
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
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.startRecording(context) else controller.showStatus("Без доступа к микрофону записать звук нельзя")
    }
    val record = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            controller.startRecording(context)
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
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
        context.startActivity(Intent.createChooser(send, "Отправить"))
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
        }.onFailure { controller.showStatus("Камера недоступна") }
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
            context.startActivity(Intent.createChooser(send, "Отправить тетрадь"))
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
        if (tabs.size > 1) TabStrip(controller, tabs, onTab, onCloseTab, onLibrary = onClose)
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
        )
        SoundBar(controller)
        PdfPickBar(controller)
        ToolRow(
            controller,
            tools = layout.tools.mapNotNull(actions::get),
            placing = placing,
            onPickColor = { showPicker = true },
            onHatchSettings = { showHatch = true },
            onSelectionSize = { showSelectionSize = true },
        )
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
                factory = { ctx -> PageEditorView(ctx).also(controller::attach) },
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
    if (textNew) TypeTextDialog("Печатный текст", "", onDismiss = { textNew = false }) { controller.insertText(it) }
    if (controller.editingText) {
        TypeTextDialog("Изменить текст", controller.selectedText.orEmpty(), onDismiss = { controller.editingText = false }) { controller.editSelectedText(it) }
    }
    if (controller.showLinkDialog) LinkDialog(controller, onDismiss = { controller.showLinkDialog = false })
    if (controller.showAddBookmark) AddBookmarkDialog(controller, onDismiss = { controller.showAddBookmark = false })
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    if (showTimer) StudyTimerDialog(onDismiss = { showTimer = false })
    if (showPdfPage) {
        PdfPageDialog(
            controller,
            onInsertPdf = { runCatching { pdfInsertLauncher.launch(arrayOf("application/pdf")) }.onFailure { controller.showStatus("Нет приложения для выбора файла") } },
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
            doneLabel = if (edit.target == null) "Вставить" else "Готово",
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
    val layer = c.notebook.layerList().getOrNull(c.notebook.activeLayer)?.name ?: "Основной"
    return mapOf(
        a(
            "pen",
            if (fountain) "Перьевая ручка (вид — в «Ручка ▾»)" else "Ручка (вид — в «Ручка ▾»)",
            { c.selectTool(if (fountain) Tool.FOUNTAIN else Tool.PEN) },
            selected = c.tool == Tool.PEN || c.tool == Tool.FOUNTAIN,
            title = if (fountain) "Перьевая ручка" else "Ручка",
            icon = if (fountain) InkIcons.Fountain else InkIcons.Pen,
        ),
        tool("highlighter", Tool.HIGHLIGHTER, "Маркер"),
        tool("neon", Tool.NEON, "Неон"),
        tool("eraser", Tool.ERASER, "Ластик"),
        tool("lasso", Tool.LASSO, "Лассо: выделить и двигать"),
        tool("area", Tool.AREA, "Область: заштриховать"),
        tool("spoiler", Tool.SPOILER, "Маркер подсказок: прячет текст, касание пальцем открывает"),
        tool("laser", Tool.LASER, "Указка: след гаснет сам"),
        a("picture", "Вставить картинку", onInsertImage, enabled = !c.importingImage, title = "Вставить картинку"),
        a("table", "Вставить таблицу", onInsertTable, title = "Вставить таблицу"),
        a("stamps", "Штампы: готовые рисунки", onInsertStamp),
        a("ruler", "Линейка: положить на лист", c::toggleRuler, selected = c.rulerShown),
        a("text", "Вставить печатный текст", onText, title = "Вставить печатный текст…"),
        a(
            "space",
            "Вставить место: штрих вниз раздвигает строки, вверх — убирает пустое",
            c::toggleInsertSpace,
            selected = c.insertingSpace,
            title = if (c.insertingSpace) "Вставить место: отменить" else "Вставить место (раздвинуть строки)",
        ),
        a("undo", "Отменить", c::undo, enabled = c.canUndo),
        a("redo", "Повторить", c::redo, enabled = c.canRedo),
        a("pages", "Страницы", onTogglePages, selected = pagesShown),
        a("search", "Поиск в тетради", onToggleSearch, selected = searchShown, title = "Поиск в тетради"),
        a("page-nav", "Листать страницы", {}),
        a("add-page", "Добавить страницу после текущей", c::addPageAfterCurrent, title = "Добавить страницу"),
        a("toc", "Оглавление тетради", onToc, title = "Оглавление…"),
        a("page-text", "Распознанный текст страницы", onPageText, title = "Распознанный текст страницы"),
        a(
            "math",
            "Ответы после «=»: вычисления, переменные, системы",
            c::toggleMath,
            selected = math == MathNotes.State.Ready || math == MathNotes.State.Downloading,
            title = "Ответы после «=»",
        ),
        a(
            "fractions",
            "Ответы обыкновенными или десятичными дробями",
            c::toggleFractionAnswers,
            title = if (c.fractionAnswers) "Ответы: обыкновенные дроби (1/3)" else "Ответы: десятичные дроби (0,333…)",
        ),
        a(
            "units",
            "Единицы измерения и физические константы в формулах",
            c::toggleMathUnits,
            selected = c.mathUnits,
            title = "Единицы и константы в формулах",
        ),
        a("layers", "Слои (сейчас: $layer)", onLayers, title = "Слои (сейчас: $layer)…"),
        a("cards", "Повторение: карточки из маркера подсказок", c::openCards, title = "Повторение: карточки"),
        a(
            "record",
            if (c.recordingSince == null) "Записать звук" else "Остановить запись",
            onRecord,
            selected = c.recordingSince != null,
            title = if (c.recordingSince == null) "Записать звук" else "Остановить запись",
        ),
        a("recordings", "Записи звука", onRecordings, title = "Записи звука…"),
        a("background", "Фон страниц", onBackground),
        a("page-size", "Размер и ориентация страницы", onPageSize, title = "Размер и ориентация страницы…"),
        a("page-shift", "Отступ страницы слева или справа", onPageShift, title = "Отступ страницы слева или справа…"),
        a("share", "Поделиться файлом тетради", onShare, title = "Поделиться файлом тетради"),
        a("save", "Сохранить файл тетради", onSave, title = "Сохранить файл тетради…"),
        a("pdf", "Экспорт: PDF или PNG, все страницы, эта или диапазон", onPdf, title = "Экспорт: PDF, PNG…"),
        a("pdf-share", "Отправить тетрадь как PDF одним нажатием", onPdfShare, title = "Отправить как PDF"),
        a("print", "Печать тетради", onPrint, title = "Печать…"),
        a("zoom-window", "Окно увеличения: пишите крупно, на странице мелко", c::toggleZoomPad, selected = c.zoomPadOpen),
        a("formula", "Формула печатной: выделите формулу лассо и нажмите", c::formulaFromSelection, title = "Формула печатной (выделенная)"),
        a("protractor", "Транспортир: углы и дуги", c::toggleProtractor, selected = c.protractorShown),
        a("compass", "Циркуль: окружности и дуги", c::toggleCompass, selected = c.compassOn),
        a("timer", "Таймер учёбы: учёба и перерывы", onTimer, selected = StudyTimer.phase != null, title = "Таймер учёбы…"),
        a("pdf-text", "Текст PDF: проведите пером по словам — скопировать или выделить маркером", c::togglePdfText, selected = c.pdfTextOn, title = "Текст PDF"),
        a("pdf-page", "Страница PDF: повернуть, поля для заметок, вставить страницы", onPdfPage, title = "Страница PDF…"),
        a("pdf-dark", "Тёмный PDF: светлый текст на тёмном", c::togglePdfDark, selected = c.pdfDark),
        a(
            "side",
            if (sideOpen) "Закрыть тетрадь рядом" else "Открыть рядом: вторая тетрадь или PDF на том же экране",
            { onSide?.invoke() },
            enabled = onSide != null,
            selected = sideOpen,
            title = if (sideOpen) "Закрыть рядом" else "Открыть рядом…",
        ),
        a("swap", "Поменять тетради местами", { onSwap?.invoke() }, enabled = onSwap != null, title = "Поменять местами"),
        a("scan", "Сканировать документ камерой", onScan, title = "Сканировать документ"),
        a(
            "pressure",
            if (c.penPressure) "Ручка чувствует нажим (нажмите, чтобы линия была одной толщины)" else "Ручка без нажима: линия одной толщины",
            c::togglePenPressure,
            selected = c.penPressure,
            title = if (c.penPressure) "Нажим ручки: включён" else "Нажим ручки: выключен",
        ),
        a(
            "theme",
            "Тема: ${themeMode.title.lowercase()} (нажмите, чтобы сменить)",
            { onThemeModeChange(ThemeMode.entries[(themeMode.ordinal + 1) % ThemeMode.entries.size]) },
            title = "Тема: ${themeMode.title.lowercase()}",
            icon = themeIcon(themeMode),
        ),
        a("pen-button", "Кнопка пера: ${c.penButtonLabel}", { c.learningPenButton = true }, title = "Кнопка пера: ${c.penButtonLabel}"),
        a("scribble", "Стирание зачёркиванием", c::toggleScribbleErase, selected = c.scribbleErase, title = "Стирание зачёркиванием"),
        a("help", "Справка: все инструменты", onHelp, title = "Справка: все инструменты"),
    )
}

/** The page arrows with the page number between them, as one group on a bar. */
@Composable
private fun PageNav(c: EditorController, placing: Placing) {
    BarButton(BarAction("page-nav", "", "Предыдущая страница", InkIcons.Previous, { c.goToPage(c.pageIndex - 1) }, enabled = c.pageIndex > 0), placing)
    Text("${c.pageIndex + 1} / ${c.notebook.pageIds.size}", maxLines = 1)
    BarButton(BarAction("page-nav", "", "Следующая страница", InkIcons.Next, { c.goToPage(c.pageIndex + 1) }, enabled = c.pageIndex < c.notebook.pageIds.lastIndex), placing)
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // What does not fit goes to the top of the menu, in order.
        val (fits, spill) = fitOnBar(top.map { it.id }, maxWidth - TOP_BAR_RESERVED)
        val shown = top.filter { it.id in fits }
        val spilled = top.filter { it.id in spill }.flatMap { if (it.id == "page-nav") pageNavMenu(c) else listOf(it) }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(InkIcons.Back, "К тетрадям", onClose)
            val math = c.math.state
            val status = c.status ?: when (math) {
                MathNotes.State.Downloading -> "Загружаю модель формул…"
                is MathNotes.State.Failed -> math.message
                else -> null
            }
            // Title with the status under it: they share the room left by the buttons.
            Column(Modifier.weight(1f)) {
                Text(
                    c.notebook.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    c.learningPenButton -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Нажмите кнопку пера…", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, maxLines = 1)
                        TextButton(onClick = { c.learningPenButton = false }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            Text("Отмена", fontSize = 13.sp)
                        }
                    }
                    status != null -> Text(
                        status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StudyTimerChip(onClick = onTimer)
            Row(verticalAlignment = Alignment.CenterVertically) { BarButtons(c, shown, placing, BarCatalog.GROUP_GAP) }
            Box {
                IconAction(InkIcons.More, "Ещё", { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val close = { menuOpen = false }
                    // Settings of the bars first: a long menu would hide them at its end.
                    MenuRow(BarAction("customize", "Настроить панели…", "Какие кнопки где", InkIcons.Tune, placing.customize), null, close)
                    if (hiddenCount > 0) {
                        MenuRow(BarAction("all", "Все функции… ($hiddenCount скрыто)", "Скрытые функции", InkIcons.AllFunctions, onAllFunctions), null, close)
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

/** How long the pages panel takes to slide in or out. */
private const val PANEL_MS = 220

/** Cycles system → light → dark; the icon shows the current mode. */
@Composable
fun ThemeButton(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    IconAction(
        icon = themeIcon(mode),
        label = "Тема: ${mode.title.lowercase()} (нажмите, чтобы сменить)",
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
            placeholder = { Text("Найти в тетради") },
        )
        HandwritingStatus(c.handwriting)
        if (query.isNotBlank()) {
            SearchResults(hits, emptyText = "Ничего не найдено", showNotebook = false, onClick = c::showHit)
        } else if (c.handwriting.state == dev.inknotes.search.HandwritingIndex.State.Ready) {
            Text(
                "Ищет по распознанному рукописному тексту. Нажмите на результат — строка подсветится на странице.",
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
        title = { Text("Текст страницы ${c.pageIndex + 1}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    c.handwriting.state != dev.inknotes.search.HandwritingIndex.State.Ready -> HandwritingStatus(c.handwriting)
                    lines.isEmpty() -> Text(
                        "Текст ещё не распознан или на странице нет рукописного текста. Распознавание идёт через пару секунд после того, как вы перестали писать.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> androidx.compose.foundation.text.selection.SelectionContainer { Text(text) }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = {
            if (lines.isNotEmpty()) {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    onDismiss()
                }) { Text("Копировать") }
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
                Text("В конец", maxLines = 1, softWrap = false)
            }
        }
    }
    confirmDelete?.let { index ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить страницу ${index + 1}?") },
            text = { Text("Страницу нельзя будет вернуть кнопкой «Отменить».") },
            confirmButton = {
                Button(onClick = {
                    c.deletePage(index)
                    confirmDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
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
                .clickable(onClickLabel = "Перейти к странице ${index + 1}") { c.goToPage(index) }
                // After the click handler: it sees events first and takes the release of a hold,
                // so letting go after dragging (or for the menu) does not also open the page.
                .then(dragHandle)
        ) {
            if (image != null) Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuClose) {
                DropdownMenuItem(text = { Text("Дублировать") }, onClick = {
                    onMenuClose()
                    c.duplicatePage(index)
                })
                DropdownMenuItem(text = { Text("Переместить выше") }, enabled = index > 0, onClick = {
                    onMenuClose()
                    c.movePage(index, index - 1)
                })
                DropdownMenuItem(text = { Text("Переместить ниже") }, enabled = index < c.notebook.pageIds.lastIndex, onClick = {
                    onMenuClose()
                    c.movePage(index, index + 1)
                })
                DropdownMenuItem(text = { Text("Вставить пустую после") }, onClick = {
                    onMenuClose()
                    c.addPageAt(index + 1)
                })
                DropdownMenuItem(text = { Text("Размер и ориентация…") }, onClick = {
                    onMenuClose()
                    onPageSize()
                })
                DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, enabled = c.notebook.pageIds.size > 1, onClick = {
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
) {
    val selection = c.selectionCount > 0
    val toolButtons = tools
    val tools: @Composable () -> Unit = { BarButtons(c, toolButtons, placing, 0.dp) }
    val divider: @Composable () -> Unit = {
        VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
    val options: @Composable () -> Unit = { ToolOptions(c, onPickColor, onHatchSettings, onSelectionSize) }
    val line: @Composable () -> Unit = if (selection) options else ({ tools(); divider(); options() })
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
private fun BarRow(modifier: Modifier = Modifier, scroll: Boolean = true, content: @Composable () -> Unit) {
    Row(
        modifier
            .let { if (scroll) it.fillMaxWidth().horizontalScroll(rememberScrollState()) else it }
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
            "Указка: след гаснет сам и не записывается",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Tool.ERASER -> {
            SizeSlider(c)
            FilterChip(
                selected = c.eraserMode == EraserMode.WHOLE,
                onClick = { c.selectEraserMode(EraserMode.WHOLE) },
                label = { Text("Штрих целиком") },
            )
            FilterChip(
                selected = c.eraserMode == EraserMode.PARTIAL,
                onClick = { c.selectEraserMode(EraserMode.PARTIAL) },
                label = { Text("Частично") },
            )
            EraserTargetButton(c)
        }
        Tool.LASSO -> Text(
            "Обведите пером то, что нужно выделить, или коснитесь объекта пальцем",
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
                label = "Узор: ${c.fillPattern.title}. Выбрать узор и настроить",
                onClick = onHatchSettings,
            )
            ColorChoices(c, selected = c.fillColor, onPickColor)
            Spacer(Modifier.width(8.dp))
            if (c.hasPendingArea) {
                Button(onClick = { c.applyFill() }) {
                    InkIcon(InkIcons.Area, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Заштриховать")
                }
                IconAction(InkIcons.Close, "Отменить область", { c.cancelArea() })
            } else {
                Text("Коснитесь внутри фигуры", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onLibrary: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in tabs) {
            val selected = t === current
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onTab(t) }
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t.notebook.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                TextButton(onClick = { onCloseTab(t) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✕", fontSize = 13.sp) }
            }
        }
        TextButton(onClick = onLibrary) { Text("+ Тетрадь") }
    }
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
                placeholder = { Text("Текст") },
            )
        },
        confirmButton = {
            Button(onClick = {
                onDone(text)
                onDismiss()
            }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Less frequent things to do with the selection: contents, link. */
@Composable
private fun SelectionMoreButton(c: EditorController) {
    Box {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }) { Text("Ещё ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("В оглавление…") }, onClick = {
                open = false
                c.showAddBookmark = true
            })
            DropdownMenuItem(text = { Text("Ссылка на страницу…") }, onClick = {
                open = false
                c.showLinkDialog = true
            })
            DropdownMenuItem(text = { Text("На слой…") }, onClick = {
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
        TextButton(onClick = { open = true }) { Text("Стирает: ${c.eraserTarget.title.lowercase().removePrefix("только ")} ▾") }
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
    IconAction(InkIcons.Close, "Снять выделение", { c.clearSelection() })
    Text("Выделено: ${c.selectionCount}", fontWeight = FontWeight.Medium)
    ColorChoices(c, selected = null, onPickColor)
    if (c.selectionLineKinds.isNotEmpty()) LineKindButton(c)
    SelectionWidthButton(c)
    SelectionDashButton(c)
    if (c.selectionIsTable) {
        IconAction(InkIcons.TableAddRow, "Добавить строку снизу", { c.addToTable(row = true) })
        IconAction(InkIcons.TableAddColumn, "Добавить столбец справа", { c.addToTable(row = false) })
        IconAction(InkIcons.TableRemoveRow, "Убрать последнюю строку", { c.removeFromTable(row = true) })
        IconAction(InkIcons.TableRemoveColumn, "Убрать последний столбец", { c.removeFromTable(row = false) })
    }
    if (c.selectedFills > 0) IconAction(InkIcons.Tune, "Штриховка выделенного", onHatchSettings)
    IconAction(InkIcons.FlipHorizontal, "Отразить слева направо", { c.flipSelection(horizontal = true) })
    IconAction(InkIcons.FlipVertical, "Отразить сверху вниз", { c.flipSelection(horizontal = false) })
    IconAction(InkIcons.Ruler, "Размер в миллиметрах", onSelectionSize)
    if (c.selectedPicture != null) IconAction(InkIcons.PhotoEdit, "Изменить картинку: обрезать, выпрямить, перспектива, фон", c::editSelectedPicture)
    if (c.selectedText != null) {
        TextButton(onClick = { c.editingText = true }) { Text("Изменить текст") }
        TextButton(onClick = { c.scaleSelectedText(1f / 1.2f) }) { Text("А−") }
        TextButton(onClick = { c.scaleSelectedText(1.2f) }) { Text("А+") }
    } else {
        TextButton(onClick = { c.recognizeSelection() }) { Text("Текст") }
        if (c.selectionLineKinds.isNotEmpty()) TextButton(onClick = { c.formulaFromSelection() }) { Text("Формула") }
    }
    SelectionMoreButton(c)
    IconAction(InkIcons.Duplicate, "Дублировать", { c.duplicateSelection() })
    IconAction(InkIcons.Delete, "Удалить выделенное", { c.deleteSelection() })
}

private val LINE_KINDS = listOf(
    InkKind.PEN to "Ручка",
    InkKind.LINE to "Ровная",
    InkKind.PENCIL to "Карандаш",
    InkKind.DASHED to "Пунктир",
    InkKind.FOUNTAIN to "Перо",
    InkKind.NEON to "Неон",
)

/** Type of the selected lines: a neon line made an ordinary one, a pen line pencil… */
@Composable
private fun LineKindButton(c: EditorController) {
    Box {
        var open by remember { mutableStateOf(false) }
        val kinds = c.selectionLineKinds
        val title = if (kinds.size == 1) LINE_KINDS.firstOrNull { it.first == kinds.first() }?.second ?: "Линия" else "Тип линии"
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

private val PEN_KINDS = listOf(InkKind.PEN to "Ручка", InkKind.PENCIL to "Карандаш", InkKind.DASHED to "Пунктир")

/** Pen, pencil, dashes or the fountain pen for the pen button. */
@Composable
private fun PenKindButton(c: EditorController) {
    Box {
        var open by remember { mutableStateOf(false) }
        val fountain = c.tool == Tool.FOUNTAIN
        val title = if (fountain) "Перо" else PEN_KINDS.firstOrNull { it.first == c.penKind }?.second ?: "Ручка"
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
                text = { Text((if (fountain) "✓ " else "") + "Перо") },
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
            label = { Text("Ровно по тексту") },
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
                .semantics { contentDescription = "Толщина" }
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
            modifier = Modifier.width(160.dp),
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
    IconAction(InkIcons.Eyedropper, "Пипетка: взять цвет со страницы или фото", { c.toggleColorPick() }, selected = c.pickingColor)
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
            .clickable(onClickLabel = "Другой цвет", onClick = onClick)
            .semantics { contentDescription = "Другой цвет" },
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
        title = { Text("Фон тетради") },
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
                if (c.importingPhoto) Text("Загружаю фото…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun ThemeBackgrounds(c: EditorController, darkTheme: Boolean, onPickPhoto: () -> Unit, onCustomColor: () -> Unit) {
    val current = c.notebook.background(darkTheme)
    var lines by remember(current.lines) { mutableFloatStateOf(current.lines) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (darkTheme) "Тёмная тема" else "Светлая тема", fontWeight = FontWeight.Medium)
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
            Text("Цвет листа", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        .clickable(onClickLabel = if (color == null) "Цвет темы" else "Цвет листа") {
                            c.setBackground(darkTheme, current.copy(color = color))
                        },
                )
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                    .clickable(onClickLabel = "Любой цвет", onClick = onCustomColor),
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
        Text("Линии фона: ${Math.round(value * 100)}%", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        InkSlider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = 0.1f..1f,
            modifier = Modifier.width(240.dp).semantics { contentDescription = "Прозрачность линий фона" },
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
