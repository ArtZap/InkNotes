package dev.inknotes.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.inknotes.data.ToolbarLayout
import dev.inknotes.data.Toolbars
import dev.inknotes.data.Zone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A button of the editor, wherever the user has put it: on the tool bar, the bar above, in the
 * "⋮" menu or among the hidden ones. [title] is its name in the menu, [hint] what it does.
 */
class BarAction(
    val id: String,
    val title: String,
    val hint: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val selected: Boolean? = null,
)

/** Names, icons and groups of the buttons, for the settings (where nothing can be pressed). */
object BarCatalog {
    class Entry(val title: String, val icon: () -> ImageVector, val group: Int)

    val entries: Map<String, Entry> = mapOf(
        "pen" to Entry("Ручка", { InkIcons.Pen }, 0),
        "highlighter" to Entry("Маркер", { InkIcons.Highlighter }, 0),
        "neon" to Entry("Неон", { InkIcons.Neon }, 0),
        "eraser" to Entry("Ластик", { InkIcons.Eraser }, 0),
        "lasso" to Entry("Лассо", { InkIcons.Lasso }, 0),
        "area" to Entry("Область", { InkIcons.Area }, 0),
        "spoiler" to Entry("Маркер подсказок", { InkIcons.Spoiler }, 0),
        "laser" to Entry("Указка", { InkIcons.Laser }, 0),
        "picture" to Entry("Картинка", { InkIcons.Picture }, 1),
        "table" to Entry("Таблица", { InkIcons.Table }, 1),
        "stamps" to Entry("Штампы", { InkIcons.Stamp }, 1),
        "ruler" to Entry("Линейка", { InkIcons.RulerTool }, 1),
        "protractor" to Entry("Транспортир", { InkIcons.Protractor }, 1),
        "compass" to Entry("Циркуль", { InkIcons.Compass }, 1),
        "zoom-window" to Entry("Окно увеличения", { InkIcons.ZoomWindow }, 1),
        "scan" to Entry("Сканировать документ", { InkIcons.Scan }, 1),
        "text" to Entry("Печатный текст", { InkIcons.TextBox }, 1),
        "space" to Entry("Вставить место", { InkIcons.InsertSpace }, 1),
        "undo" to Entry("Отменить", { InkIcons.Undo }, 2),
        "redo" to Entry("Повторить", { InkIcons.Redo }, 2),
        "pages" to Entry("Страницы", { InkIcons.Pages }, 3),
        "search" to Entry("Поиск", { InkIcons.Search }, 3),
        "page-nav" to Entry("Листать страницы", { InkIcons.PageNav }, 3),
        "add-page" to Entry("Новая страница", { InkIcons.AddPage }, 3),
        "toc" to Entry("Оглавление", { InkIcons.Toc }, 3),
        "page-text" to Entry("Текст страницы", { InkIcons.PageText }, 3),
        "math" to Entry("Ответы после «=»", { InkIcons.Calc }, 4),
        "fractions" to Entry("Дроби в ответах", { InkIcons.Fraction }, 4),
        "units" to Entry("Единицы измерения", { InkIcons.Units }, 4),
        "formula" to Entry("Формула печатной", { InkIcons.Formula }, 4),
        "layers" to Entry("Слои", { InkIcons.Layers }, 5),
        "cards" to Entry("Карточки", { InkIcons.Cards }, 5),
        "timer" to Entry("Таймер учёбы", { InkIcons.Timer }, 5),
        "record" to Entry("Записать звук", { InkIcons.Mic }, 6),
        "recordings" to Entry("Записи звука", { InkIcons.Recordings }, 6),
        "background" to Entry("Фон", { InkIcons.Paper }, 7),
        "page-size" to Entry("Размер страницы", { InkIcons.PageSize }, 7),
        "page-shift" to Entry("Отступ страницы", { InkIcons.PageShift }, 7),
        "share" to Entry("Поделиться", { InkIcons.Share }, 8),
        "save" to Entry("Сохранить файл", { InkIcons.Save }, 8),
        "pdf" to Entry("Экспорт: PDF, PNG", { InkIcons.Export }, 8),
        "pdf-share" to Entry("PDF сразу", { InkIcons.PdfShare }, 8),
        "print" to Entry("Печать", { InkIcons.Print }, 8),
        "theme" to Entry("Тема", { InkIcons.AutoTheme }, 9),
        "pen-button" to Entry("Кнопка пера", { InkIcons.PenButton }, 9),
        "pressure" to Entry("Нажим ручки", { InkIcons.Pressure }, 9),
        "scribble" to Entry("Стирание зачёркиванием", { InkIcons.Scribble }, 9),
        "help" to Entry("Справка", { InkIcons.Help }, 9),
    )

    fun title(id: String) = entries[id]?.title ?: id
    fun icon(id: String) = entries[id]?.icon?.invoke() ?: InkIcons.More
    fun group(id: String) = entries[id]?.group ?: 99

    /** Room a button takes on a bar (the page arrows with the number take three). */
    fun width(id: String) = if (id == "page-nav") PAGE_NAV_WIDTH else BUTTON_WIDTH

    val BUTTON_WIDTH = 48.dp
    val PAGE_NAV_WIDTH = 140.dp

    /** Room between groups on the bar above. */
    val GROUP_GAP = 8.dp
}

/**
 * The buttons of [ids] that fit into [width] on the bar above, the rest (they go to the top of
 * the menu): in order, a gap where the group changes.
 */
fun fitOnBar(ids: List<String>, width: androidx.compose.ui.unit.Dp): Pair<List<String>, List<String>> {
    var used = 0.dp
    var last = -1
    for ((i, id) in ids.withIndex()) {
        val gap = if (last >= 0 && BarCatalog.group(id) != last) BarCatalog.GROUP_GAP else 0.dp
        val w = BarCatalog.width(id) + gap
        if (used + w > width) return ids.take(i) to ids.drop(i)
        used += w
        last = BarCatalog.group(id)
    }
    return ids to emptyList()
}

/** What can be done with a button's place: offered on a long press. */
class Placing(val layout: ToolbarLayout, val place: (String, Zone) -> Unit, val customize: () -> Unit)

/**
 * A button on a bar: a tap does its job, a long press shows its name and where else it can go.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BarButton(a: BarAction, placing: Placing) {
    var menu by remember { mutableStateOf(false) }
    val selected = a.selected == true
    val base = LocalContentColor.current
    val color = when {
        !a.enabled -> base.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> base
    }
    Box {
        Box(
            Modifier
                .size(48.dp)
                .padding(4.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .combinedClickable(
                    role = Role.Button,
                    onClick = { if (a.enabled) a.onClick() },
                    onLongClick = { menu = true },
                )
                .semantics { contentDescription = a.hint },
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides color) { InkIcon(a.icon, contentDescription = null) }
        }
        PlaceMenu(menu, a.id, a.hint, placing, onDismiss = { menu = false })
    }
}

/** Where a button can go: the other bars, the menu, hidden; then the full settings. */
@Composable
fun PlaceMenu(expanded: Boolean, id: String, name: String, placing: Placing, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            name,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 280.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        val now = placing.layout.zoneOf(id)
        for (z in Zone.entries) {
            if (z == now || z !in Toolbars.allowed(id)) continue
            val label = when (z) {
                Zone.TOOLS -> "На панель инструментов"
                Zone.TOP -> "На верхнюю панель"
                Zone.MENU -> "Убрать в меню ⋮"
                Zone.HIDDEN -> "Скрыть"
            }
            DropdownMenuItem(text = { Text(label) }, onClick = {
                onDismiss()
                placing.place(id, z)
            })
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Настроить панели…") },
            onClick = {
                onDismiss()
                placing.customize()
            },
        )
    }
}

/** A line of the "⋮" menu: icon and name; a long press moves it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MenuRow(a: BarAction, placing: Placing?, close: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .widthIn(min = 260.dp, max = 380.dp)
                .combinedClickable(
                    enabled = a.enabled,
                    role = Role.Button,
                    onClick = {
                        close()
                        a.onClick()
                    },
                    onLongClick = if (placing != null) ({ menu = true }) else null,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (a.enabled) 1f else 0.38f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (a.selected == true) MaterialTheme.colorScheme.primary else LocalContentColor.current
            CompositionLocalProvider(LocalContentColor provides color) { InkIcon(a.icon, contentDescription = null) }
            Spacer(Modifier.width(14.dp))
            Text(a.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (a.selected == true) Text("✓", color = MaterialTheme.colorScheme.primary)
        }
        if (placing != null) PlaceMenu(menu, a.id, a.title, placing, onDismiss = { menu = false })
    }
}

/** The menu's lines in order, a divider wherever the group changes. */
@Composable
fun MenuRows(actions: List<BarAction>, placing: Placing?, close: () -> Unit) {
    var last = -1
    for (a in actions) {
        val g = BarCatalog.group(a.id)
        if (last >= 0 && g != last) HorizontalDivider()
        MenuRow(a, placing, close)
        last = g
    }
}

/** The hidden buttons, all at hand: a tap does the job, a long press brings it back on a bar. */
@Composable
fun AllFunctionsDialog(hidden: List<BarAction>, placing: Placing, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Все функции") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Скрытые с панелей и из меню. Долгое нажатие — вернуть на панель или в меню.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                MenuRows(hidden, placing, onDismiss)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                placing.customize()
            }) { Text("Настроить панели…") }
        },
    )
}

/**
 * The first time: which ready-made layout to start from (all of them can be changed later).
 */
@Composable
fun ToolbarIntroDialog(onPick: (Toolbars.Preset) -> Unit, onCustomize: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Какие инструменты показывать?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Выберите набор — потом любую кнопку можно передвинуть: долгое нажатие на неё или «⋮ → Настроить панели».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (p in Toolbars.Preset.entries) PresetCard(p, onClick = { onPick(p) })
            }
        },
        confirmButton = { TextButton(onClick = onCustomize) { Text("Настроить подробно…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Оставить как есть") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetCard(p: Toolbars.Preset, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(p.title, fontWeight = FontWeight.SemiBold)
            Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), maxLines = 1) {
                for (id in p.layout.tools + p.layout.top) InkIcon(BarCatalog.icon(id), contentDescription = null, Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Settings of the bars and the menu: a preview of both bars as they will look, then every
 * button by where it is. Drag a button (hold it, then move) into another place or order;
 * a tap offers the places too. Ready-made layouts at the top; "Отменить изменения" goes back
 * to how it was when the screen opened.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomizeBarsDialog(initial: ToolbarLayout, onChange: (ToolbarLayout) -> Unit, onDismiss: () -> Unit) {
    var layout by remember { mutableStateOf(initial) }
    fun set(l: ToolbarLayout) {
        layout = l
        onChange(l)
    }
    var message by remember { mutableStateOf<String?>(null) }
    // Where every chip and section is on screen, for dropping.
    val chips = remember { mutableStateMapOf<String, Rect>() }
    val sections = remember { mutableStateMapOf<Zone, Rect>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var rootInWindow by remember { mutableStateOf(Offset.Zero) }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // The bar above shares the width with the back button, the title and the menu button.
    val (fits, overflow) = fitOnBar(layout.top, screenWidth - TOP_BAR_RESERVED)

    fun drop() {
        val id = dragging ?: return
        dragging = null
        val zone = sections.entries.firstOrNull { it.value.contains(pointer) }?.key ?: return
        if (zone !in Toolbars.allowed(id)) {
            message = "«${BarCatalog.title(id)}» всегда остаётся на панели"
            return
        }
        // Chips before the drop point, row by row.
        val index = layout.list(zone).filter { it != id }.count { other ->
            val r = chips[other] ?: return@count false
            r.center.y < pointer.y - r.height / 2 || (abs(r.center.y - pointer.y) <= r.height / 2 && r.center.x < pointer.x)
        }
        set(layout.moved(id, zone, index))
        message = null
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { rootInWindow = it.positionInWindow() }) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Панели и меню", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { set(initial); message = null }) { Text("Отменить изменения") }
                        Button(onClick = onDismiss) { Text("Готово") }
                    }
                    Text(
                        "Удерживайте кнопку и перетащите её на другую панель, в меню или в «Скрыто»; так же меняется порядок. Касание кнопки — выбрать место из списка.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState(), enabled = dragging == null).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Готовые наборы", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (p in Toolbars.Preset.entries) {
                                Box(Modifier.weight(1f)) { PresetCard(p, selected = layout == p.layout, onClick = { set(p.layout) }) }
                            }
                        }
                        BarsPreview(layout, fits, overflow)
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        for (zone in listOf(Zone.TOP, Zone.TOOLS, Zone.MENU, Zone.HIDDEN)) {
                            val ids = layout.list(zone)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (dragging != null && sections[zone]?.contains(pointer) == true) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    )
                                    .onGloballyPositioned { sections[zone] = it.boundsInWindow() }
                                    .padding(12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(zone.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text(
                                        when (zone) {
                                            Zone.TOP -> if (overflow.isEmpty()) "всё помещается" else "не помещается: ${overflow.size} — будут в начале меню"
                                            Zone.TOOLS -> "перед толщиной и цветами"
                                            Zone.MENU -> "по группам, с разделителями"
                                            Zone.HIDDEN -> "доступны в «⋮ → Все функции…»"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FlowRow(
                                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (ids.isEmpty()) Text("Пусто — перетащите сюда", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                                    for (id in ids) {
                                        androidx.compose.runtime.key(id) {
                                            DraggableChip(
                                                id = id,
                                                faded = dragging == id || (zone == Zone.TOP && id in overflow),
                                                note = if (zone == Zone.TOP && id in overflow) "→ ⋮" else null,
                                                layout = layout,
                                                onPlace = { z ->
                                                    if (z in Toolbars.allowed(id)) set(layout.moved(id, z)) else message = "«${BarCatalog.title(id)}» всегда остаётся на панели"
                                                },
                                                onBounds = { chips[id] = it },
                                                onDragStart = { at ->
                                                    dragging = id
                                                    pointer = at
                                                },
                                                onDrag = { pointer += it },
                                                onDragEnd = ::drop,
                                                onDragCancel = { dragging = null },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // The chip under the finger while dragging.
                dragging?.let { id ->
                    val density = LocalDensity.current
                    val half = with(density) { Offset(60.dp.toPx(), 20.dp.toPx()) }
                    Box(Modifier.offset { IntOffset((pointer.x - rootInWindow.x - half.x).roundToInt(), (pointer.y - rootInWindow.y - half.y).roundToInt()) }) {
                        ChipFace(id, lifted = true, note = null)
                    }
                }
            }
        }
    }
}

/** A button in the settings: its icon and name. */
@Composable
private fun ChipFace(id: String, lifted: Boolean, note: String?) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (lifted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkIcon(BarCatalog.icon(id), contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(BarCatalog.title(id), fontSize = 14.sp, maxLines = 1)
        if (note != null) Text("  $note", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DraggableChip(
    id: String,
    faded: Boolean,
    note: String?,
    layout: ToolbarLayout,
    onPlace: (Zone) -> Unit,
    onBounds: (Rect) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier
            .onGloballyPositioned { bounds = it.boundsInWindow(); onBounds(bounds) }
            .pointerInput(id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { at -> onDragStart(bounds.topLeft + at) },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            }
            .alpha(if (faded) 0.45f else 1f),
    ) {
        Surface(onClick = { menu = true }, color = Color.Transparent) { ChipFace(id, lifted = false, note = note) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val now = layout.zoneOf(id)
            for (z in Zone.entries) {
                if (z == now) continue
                DropdownMenuItem(
                    text = { Text(z.title) },
                    enabled = z in Toolbars.allowed(id),
                    onClick = {
                        menu = false
                        onPlace(z)
                    },
                )
            }
        }
    }
}

/** Both bars as they will look: the bar above (with what spills into the menu) and the tool bar. */
@Composable
private fun BarsPreview(layout: ToolbarLayout, fits: List<String>, overflow: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp),
    ) {
        Text(
            "Так будет выглядеть",
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PreviewIcon(InkIcons.Back)
            Text("Тетрадь", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
            var last = -1
            for (id in fits) {
                if (last >= 0 && BarCatalog.group(id) != last) Spacer(Modifier.width(BarCatalog.GROUP_GAP))
                last = BarCatalog.group(id)
                if (id == "page-nav") {
                    PreviewIcon(InkIcons.Previous)
                    Text("1 / 9", fontSize = 13.sp)
                    PreviewIcon(InkIcons.Next)
                } else {
                    PreviewIcon(BarCatalog.icon(id))
                }
            }
            Box {
                PreviewIcon(InkIcons.More)
                if (overflow.isNotEmpty() || layout.menu.isNotEmpty()) {
                    Text(
                        "${overflow.size + layout.menu.size}",
                        Modifier.align(Alignment.TopEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (id in layout.tools) PreviewIcon(BarCatalog.icon(id))
            Spacer(Modifier.width(12.dp))
            Text("толщина и цвета", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreviewIcon(icon: ImageVector) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { InkIcon(icon, contentDescription = null, Modifier.size(22.dp)) }
}

/** Width of the bar above taken by the back button, a short title and the menu button. */
val TOP_BAR_RESERVED = 48.dp + 140.dp + 48.dp + 16.dp

/** Lines of the menu for the page arrows when they are not on a bar. */
fun pageNavMenu(c: EditorController): List<BarAction> = listOf(
    BarAction("page-nav", "Предыдущая страница", "Предыдущая страница", InkIcons.Previous, { c.goToPage(c.pageIndex - 1) }, enabled = c.pageIndex > 0),
    BarAction("page-nav", "Следующая страница", "Следующая страница", InkIcons.Next, { c.goToPage(c.pageIndex + 1) }, enabled = c.pageIndex < c.notebook.pageIds.lastIndex),
)
