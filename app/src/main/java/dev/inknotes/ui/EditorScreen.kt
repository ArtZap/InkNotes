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
    var pageSizeOf by remember { mutableStateOf<Int?>(null) }
    var pageText by remember { mutableStateOf<List<dev.inknotes.search.TextLine>?>(null) }
    val context = LocalContext.current
    val resolver = context.contentResolver
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) controller.insertImage(uri, resolver)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) controller.exportPdf(uri, resolver)
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
    ) {
        TopRow(
            c = controller,
            pagesShown = showPages,
            onTogglePages = { showPages = !showPages },
            searchShown = showSearch,
            onToggleSearch = { showSearch = !showSearch },
            onInsertImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onInsertTable = { showTable = true },
            onInsertStamp = { showStamps = true },
            onClose = onClose,
            menu = {
                MenuItem("Фон") { showBackground = true }
                MenuItem("Размер и ориентация страницы…", InkIcons.PageSize) { pageSizeOf = controller.pageIndex }
                MenuItem("Отступ страницы слева или справа…") { showShift = true }
                MenuItem("Распознанный текст страницы") { controller.currentPageText { pageText = it } }
                MenuItem("Поделиться файлом тетради", InkIcons.Share) { share() }
                MenuItem("Сохранить файл тетради…") { saveLauncher.launch("${controller.fileName(controller.notebook.title)}.inknote") }
                MenuItem("Экспорт в PDF…") { exportLauncher.launch("${controller.fileName(controller.notebook.title)}.pdf") }
                MenuItem(if (controller.fractionAnswers) "Ответы: обыкновенные дроби (1/3)" else "Ответы: десятичные дроби (0,333…)") {
                    controller.toggleFractionAnswers()
                }
                MenuItem("Тема: ${themeMode.title.lowercase()}", themeIcon(themeMode)) {
                    onThemeModeChange(ThemeMode.entries[(themeMode.ordinal + 1) % ThemeMode.entries.size])
                }
                MenuItem("Кнопка пера: ${controller.penButtonLabel}") { controller.learningPenButton = true }
                MenuItem(if (controller.scribbleErase) "Стирание зачёркиванием: вкл." else "Стирание зачёркиванием: выкл.") { controller.toggleScribbleErase() }
                MenuItem("Справка: все инструменты") { showHelp = true }
            },
        )
        ToolRow(controller, onPickColor = { showPicker = true }, onHatchSettings = { showHatch = true }, onSelectionSize = { showSelectionSize = true })
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
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
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

/** Entry of the "Ещё" menu; the menu closes itself after a click. */
class MenuScope(private val close: () -> Unit) {
    class Item(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector?, val action: () -> Unit)

    val items = ArrayList<Item>()

    @Suppress("FunctionName")
    fun MenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
        items += Item(label, icon) {
            close()
            onClick()
        }
    }
}

@Composable
private fun TopRow(
    c: EditorController,
    pagesShown: Boolean,
    onTogglePages: () -> Unit,
    searchShown: Boolean,
    onToggleSearch: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertTable: () -> Unit,
    onInsertStamp: () -> Unit,
    onClose: () -> Unit,
    menu: MenuScope.() -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // On a narrow screen (a tablet held upright) the insert buttons move into the menu, so
    // the page buttons and the menu itself stay on screen.
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_TOP_BAR_DP
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
        // The buttons scroll when even they do not fit; the menu after them always stays.
        Row(
            Modifier.weight(2.5f, fill = false).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        val gap = if (compact) 0.dp else 12.dp
        // Tools used now and then: up here, so the tool bar below has room for colours.
        IconAction(InkIcons.Spoiler, "Маркер подсказок: прячет текст, касание пальцем открывает", { c.selectTool(Tool.SPOILER) }, selected = c.tool == Tool.SPOILER)
        IconAction(InkIcons.Laser, "Указка: след гаснет сам", { c.selectTool(Tool.LASER) }, selected = c.tool == Tool.LASER)
        Spacer(Modifier.width(gap))
        if (!compact) {
            IconAction(InkIcons.Picture, "Вставить картинку", onInsertImage, enabled = !c.importingImage)
            IconAction(InkIcons.Table, "Вставить таблицу", onInsertTable)
            IconAction(InkIcons.Stamp, "Штампы: готовые рисунки", onInsertStamp)
            IconAction(InkIcons.RulerTool, "Линейка: положить на лист", c::toggleRuler, selected = c.rulerShown)
        }
        Spacer(Modifier.width(gap))
        IconAction(InkIcons.Undo, "Отменить", c::undo, enabled = c.canUndo)
        IconAction(InkIcons.Redo, "Повторить", c::redo, enabled = c.canRedo)
        Spacer(Modifier.width(gap))
        IconAction(InkIcons.Pages, "Страницы", onTogglePages, selected = pagesShown)
        IconAction(InkIcons.Search, "Поиск в тетради", onToggleSearch, selected = searchShown)
        IconAction(
            InkIcons.Calc,
            "Ответы после «=»: вычисления, переменные, системы",
            c::toggleMath,
            selected = math == MathNotes.State.Ready || math == MathNotes.State.Downloading,
        )
        Spacer(Modifier.width(gap))
        IconAction(InkIcons.Previous, "Предыдущая страница", { c.goToPage(c.pageIndex - 1) }, enabled = c.pageIndex > 0)
        Text("${c.pageIndex + 1} / ${c.notebook.pageIds.size}")
        IconAction(
            InkIcons.Next,
            "Следующая страница",
            { c.goToPage(c.pageIndex + 1) },
            enabled = c.pageIndex < c.notebook.pageIds.lastIndex,
        )
        IconAction(InkIcons.AddPage, "Добавить страницу после текущей", c::addPageAfterCurrent)
        }
        Box {
            IconAction(InkIcons.More, "Ещё", { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (compact) {
                    @Composable
                    fun item(label: String, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
                        text = { Text(label) },
                        enabled = enabled,
                        onClick = {
                            menuOpen = false
                            action()
                        },
                        leadingIcon = { InkIcon(icon, contentDescription = null) },
                    )
                    item("Вставить картинку", InkIcons.Picture, enabled = !c.importingImage, action = onInsertImage)
                    item("Вставить таблицу", InkIcons.Table, action = onInsertTable)
                    item("Штампы", InkIcons.Stamp, action = onInsertStamp)
                    item(if (c.rulerShown) "Убрать линейку" else "Линейка", InkIcons.RulerTool, action = c::toggleRuler)
                    HorizontalDivider()
                }
                val scope = MenuScope { menuOpen = false }.apply(menu)
                for (item in scope.items) {
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        onClick = item.action,
                        leadingIcon = item.icon?.let { icon -> { InkIcon(icon, contentDescription = null) } },
                    )
                }
            }
        }
    }
}

/** How long the pages panel takes to slide in or out. */
private const val PANEL_MS = 220

/** Narrower than this (dp), the editor's top bar keeps only the essentials; the rest is in its menu. */
private const val COMPACT_TOP_BAR_DP = 1000

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
private fun ToolRow(c: EditorController, onPickColor: () -> Unit, onHatchSettings: () -> Unit, onSelectionSize: () -> Unit) {
    val selection = c.selectionCount > 0
    val tools: @Composable () -> Unit = {
        for ((t, icon, label) in TOOLS) {
            if (t == Tool.PEN) {
                // One button for the pen, pencil, dashes and the fountain pen (chosen in its menu).
                val fountain = c.penIsFountain
                IconAction(
                    if (fountain) InkIcons.Fountain else icon,
                    if (fountain) "Перьевая ручка" else label,
                    { c.selectTool(if (fountain) Tool.FOUNTAIN else Tool.PEN) },
                    selected = c.tool == Tool.PEN || c.tool == Tool.FOUNTAIN,
                )
            } else {
                IconAction(icon, label, { c.selectTool(t) }, selected = c.tool == t)
            }
        }
    }
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
    TextButton(onClick = { c.recognizeSelection() }) { Text("Текст") }
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

private val TOOLS = listOf(
    Triple(Tool.PEN, InkIcons.Pen, "Ручка"),
    Triple(Tool.HIGHLIGHTER, InkIcons.Highlighter, "Маркер"),
    Triple(Tool.NEON, InkIcons.Neon, "Неон"),
    Triple(Tool.ERASER, InkIcons.Eraser, "Ластик"),
    Triple(Tool.LASSO, InkIcons.Lasso, "Лассо: выделить и двигать"),
    Triple(Tool.AREA, InkIcons.Area, "Область: заштриховать"),
)

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
