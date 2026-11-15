package dev.inknotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.inknotes.data.tr
import dev.inknotes.math.Answer
import dev.inknotes.math.Box as InkBox
import dev.inknotes.math.Correct
import dev.inknotes.math.MathNotes
import dev.inknotes.math.PageAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Unsure symbols are marked in amber, the same on light and dark paper. */
private val DOUBT = Color(0xFFD98C00)

/**
 * The correction window: the formula as written and as read, an answer that follows every
 * change, and a wheel per symbol to pick another reading of it and where it stands (an index,
 * on the line, an exponent). Done pins the corrected formula to its strokes.
 */
@Composable
fun FixDialog(c: EditorController, answer: PageAnswer, onDismiss: () -> Unit) {
    val fix = answer.fix ?: return onDismiss()
    var draft by remember(answer) { mutableStateOf<MathNotes.Draft?>(null) }
    var failed by remember(answer) { mutableStateOf(false) }
    LaunchedEffect(answer) {
        c.math.prepare(fix) { d -> if (d == null) failed = true else draft = d }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 980.dp),
        ) {
            val d = draft
            when {
                d != null -> FixContent(c, answer, d, onDismiss)
                failed -> Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(tr("Как прочитано", "How it was read"), style = MaterialTheme.typography.headlineSmall)
                    Text(tr("Эту формулу не удалось разобрать по символам. Попробуйте написать её чуть крупнее и ровнее.", "This formula could not be taken apart into symbols. Try writing it a little larger and straighter."))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Button(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } }
                }
                else -> Box(Modifier.padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

/** A symbol on a wheel: which side of "=" ([right]) and which of its symbols. */
private class Slot(val right: Boolean, val index: Int)

@Composable
private fun FixContent(c: EditorController, answer: PageAnswer, d: MathNotes.Draft, onDismiss: () -> Unit) {
    val fix = d.fix
    val leftPicks = remember(d) { mutableStateListOf(*d.left.initial.toTypedArray()) }
    val rightPicks = remember(d) { mutableStateListOf(*(d.right?.initial ?: emptyList()).toTypedArray()) }
    val slots = remember(d) { d.left.syms.indices.map { Slot(false, it) } + d.right?.syms?.indices?.map { Slot(true, it) }.orEmpty() }
    fun sym(s: Slot) = (if (s.right) d.right!! else d.left).syms[s.index]
    fun picks(s: Slot): SnapshotStateList<Correct.Pick> = if (s.right) rightPicks else leftPicks
    var focus by remember(d) { mutableIntStateOf(slots.indexOfFirst { sym(it).unsure }.coerceAtLeast(0)) }
    var typed by remember(d) { mutableStateOf<String?>(null) }
    var learn by remember { mutableStateOf(c.math.fixes.learn) }

    val leftText = Correct.text(d.left, leftPicks)
    val rightText = d.right?.let { Correct.text(it, rightPicks) }
    // Typed by hand: "left = right" for an equation, only the left for `expr =`.
    val (useLeft, useRight) = typed?.let { t ->
        if (d.right == null) t.substringBefore('=').trim() to null else t.substringBefore('=').trim() to t.substringAfter('=', "").trim()
    } ?: (leftText to rightText)
    val outcome by produceState<Correct.Outcome?>(null, useLeft, useRight) {
        value = withContext(Dispatchers.Default) { runCatching { Correct.outcome(useLeft, useRight, fix.env) }.getOrNull() }
    }

    val list = rememberLazyListState()
    LaunchedEffect(focus) { if (slots.isNotEmpty()) list.animateScrollToItem(max(0, focus - 2)) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Как прочитано", "How it was read"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            val before = when {
                answer.missing -> tr("Было: ничего", "Was: nothing")
                else -> (answer.answer as? Answer.Text)?.lines?.joinToString("; ")?.let { tr("Было: $it", "Was: $it") }
                    ?: if (answer.answer is Answer.Graph || answer.answer is Answer.Surface) tr("Было: график", "Was: a graph") else null
            }
            if (before != null) Text(before, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            if (answer.unsure || answer.missing) {
                Spacer(Modifier.width(8.dp))
                Text(
                    if (answer.missing) tr("не получилось", "did not work") else tr("под вопросом", "uncertain"),
                    color = Color(0xFF8A5300),
                    fontSize = 13.sp,
                    modifier = Modifier.background(Color(0xFFFFF1D6), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(tr("Ваша запись", "As written"), Modifier.weight(1f)) {
                Handwriting(fix, d, slots, focus, ::sym) { focus = it }
                Text(
                    tr("Нажмите на символ, чтобы перейти к его барабану. Жёлтым — в чём распознавание не уверено.", "Tap a symbol to go to its wheel. Amber: what recognition is not sure of."),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Panel(tr("Получается", "Reads as"), Modifier.weight(1f)) {
                Typeset(useLeft + "=" + (useRight ?: ""))
                Result(outcome)
            }
        }

        if (typed == null) {
            Text(
                tr("СИМВОЛЫ — ПРОКРУТИТЕ БАРАБАН ИЛИ НАЖМИТЕ ВАРИАНТ", "SYMBOLS — TURN A WHEEL OR TAP A CHOICE"),
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // As many wheels as symbols: a long formula scrolls sideways.
            LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(slots) { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.right && s.index == 0) {
                            Text("=", fontSize = 28.sp, modifier = Modifier.padding(end = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Wheel(sym(s), picks(s)[s.index], focused = i == focus, onFocus = { focus = i }) { p ->
                            picks(s)[s.index] = p
                            focus = i
                        }
                    }
                }
            }
            Text(
                tr("Под барабаном — где стоит символ: индекс, на строке или степень.", "Under a wheel: where the symbol stands — an index, on the line or an exponent."),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedTextField(
                value = typed ?: "",
                onValueChange = { typed = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                label = { Text(tr("Формула", "Formula")) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                tr("Как в LaTeX: x^{2}, x_{1}, \\frac{a}{b}, \\sqrt{x}, \\int x dx, \\pi. Картинка выше меняется сразу.", "As in LaTeX: x^{2}, x_{1}, \\frac{a}{b}, \\sqrt{x}, \\int x dx, \\pi. The picture above follows at once."),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                typed = if (typed == null) leftText + (rightText?.let { " = $it" } ?: "") else null
            }) {
                Text(if (typed == null) tr("Нет нужного — набрать формулу", "Not there — type the formula") else tr("Назад к барабанам", "Back to the wheels"))
            }
            Row(
                Modifier.weight(1f).clickable { learn = !learn; c.math.fixes.learn = learn },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = learn, onCheckedChange = { learn = it; c.math.fixes.learn = it })
                Text(tr("Учиться моему почерку", "Learn my handwriting"), fontSize = 14.sp)
            }
            if (c.isPinned(answer)) {
                TextButton(onClick = { c.unpinFix(answer); onDismiss() }) { Text(tr("Вернуть распознанное", "Back to recognised")) }
            }
            TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) }
            Button(onClick = {
                val t = typed
                if (t != null) {
                    c.applyFix(answer, useLeft, useRight, emptyList(), emptyList())
                } else {
                    val read = ArrayList<String>()
                    val chosen = ArrayList<String>()
                    for (s in slots) {
                        val sym = sym(s)
                        if (sym.sign) continue
                        read += sym.candidates[(if (s.right) d.right!! else d.left).initial[s.index].choice]
                        chosen += sym.candidates[picks(s)[s.index].choice]
                    }
                    val l = leftText.takeIf { it != Correct.text(d.left, d.left.initial) }
                    val r = d.right?.let { side -> rightText.takeIf { it != Correct.text(side, side.initial) } }
                    c.applyFix(answer, l, r, read, chosen)
                }
                onDismiss()
            }) { Text(tr("Готово", "Done")) }
        }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), fontSize = 12.sp, letterSpacing = 0.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** The strokes as written, symbols framed: the focused one in the accent, unsure ones dashed amber. */
@Composable
private fun Handwriting(fix: dev.inknotes.math.Fix, d: MathNotes.Draft, slots: List<Slot>, focus: Int, sym: (Slot) -> Correct.Sym, onTap: (Int) -> Unit) {
    val ink = fix.left + fix.equals + fix.right.orEmpty()
    val all = InkBox.of(ink)
    val pen = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val boxes = remember(d) { slots.map { sym(it).box } }
    val unsure = remember(d) { slots.map { sym(it).unsure } }
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).pointerInput(d) {
            detectTapGestures { p ->
                val scale = min((size.width - 16f) / max(all.width, 1f), (size.height - 16f) / max(all.height, 1f))
                val ox = (size.width - all.width * scale) / 2 - all.left * scale
                val oy = (size.height - all.height * scale) / 2 - all.top * scale
                val x = (p.x - ox) / scale
                val y = (p.y - oy) / scale
                val pad = 0.15f * fix.h
                val hit = boxes.indices.filter { i -> val b = boxes[i]; x in b.left - pad..b.right + pad && y in b.top - pad..b.bottom + pad }
                    .minByOrNull { i -> abs(boxes[i].cx - x) + abs(boxes[i].cy - y) }
                if (hit != null) onTap(hit)
            }
        },
    ) {
        val scale = min((size.width - 16f) / max(all.width, 1f), (size.height - 16f) / max(all.height, 1f))
        val ox = (size.width - all.width * scale) / 2 - all.left * scale
        val oy = (size.height - all.height * scale) / 2 - all.top * scale
        fun px(x: Float) = ox + x * scale
        fun py(y: Float) = oy + y * scale
        val pad = 0.08f * fix.h * scale
        for ((i, b) in boxes.withIndex()) {
            val tl = Offset(px(b.left) - pad, py(b.top) - pad)
            val sz = Size(b.width * scale + 2 * pad, b.height * scale + 2 * pad)
            val r = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
            when {
                i == focus -> {
                    drawRoundRect(accent.copy(alpha = 0.12f), tl, sz, r)
                    drawRoundRect(accent, tl, sz, r, style = Stroke(2.dp.toPx()))
                }
                unsure[i] -> {
                    drawRoundRect(DOUBT.copy(alpha = 0.16f), tl, sz, r)
                    drawRoundRect(DOUBT, tl, sz, r, style = Stroke(1.5f.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
                }
            }
        }
        val width = max(1.6f, 0.09f * fix.h * scale)
        for (s in ink) {
            val p = Path()
            p.moveTo(px(s.xs[0]), py(s.ys[0]))
            for (k in 1 until s.xs.size) p.lineTo(px(s.xs[k]), py(s.ys[k]))
            if (s.xs.size == 1) p.lineTo(px(s.xs[0]) + 0.5f, py(s.ys[0]))
            drawPath(p, pen, style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

/** The formula set in print. */
@Composable
private fun Typeset(source: String) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val letterPx = with(LocalDensity.current) { 30.dp.toPx() }
    val bmp = remember(source, color) { FormulaArt.bitmap(source, letterPx, color) }
    Box(Modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.CenterStart) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = source, modifier = Modifier.heightIn(max = 110.dp))
        } else {
            Text(source, fontSize = 22.sp, fontStyle = FontStyle.Italic)
        }
    }
}

/** The answer the corrected formula gives: a value, a small graph or surface, or why not. */
@Composable
private fun Result(o: Correct.Outcome?) {
    val accent = MaterialTheme.colorScheme.primary
    when (val a = o?.answer) {
        is Answer.Text -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Ответ", "Answer"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(a.lines.joinToString("; "), fontSize = 24.sp, fontWeight = FontWeight.Medium, color = accent)
        }
        is Answer.Grid -> Text(a.rows.joinToString("\n") { it.joinToString("   ") }, fontSize = 20.sp, color = accent)
        is Answer.Graph -> MiniGraph(a)
        is Answer.Surface -> {
            val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val px = with(LocalDensity.current) { 240.dp.toPx() }.toInt()
            val bmp = remember(a, dark) { dev.inknotes.editor.SurfaceArt.bitmap(a, px, accent.toArgb(), dark) }
            Image(bmp.asImageBitmap(), contentDescription = tr("Поверхность", "Surface"), modifier = Modifier.width(240.dp))
        }
        null -> {}
    }
    val note = o?.note
    if (note != null) Text(note, fontSize = 13.sp, color = if (o?.error == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** y = f(x) in a small box: axes where they fall in the window, and the curve. */
@Composable
private fun MiniGraph(g: Answer.Graph) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(
        Modifier.fillMaxWidth().height(150.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)),
    ) {
        val w = size.width
        val h = size.height
        fun gx(u: Float) = u * w
        fun gy(v: Float) = h - v * h
        if (g.x0 < 0 && g.x1 > 0) {
            val u = -g.x0 / (g.x1 - g.x0)
            drawLine(axis, Offset(gx(u), 0f), Offset(gx(u), h), 1.dp.toPx())
        }
        if (g.y0 < 0 && g.y1 > 0) {
            val v = -g.y0 / (g.y1 - g.y0)
            drawLine(axis, Offset(0f, gy(v)), Offset(w, gy(v)), 1.dp.toPx())
        }
        clipRect {
            for (seg in g.segments) {
                val p = Path()
                p.moveTo(gx(seg[0]), gy(seg[1]))
                for (k in 2 until seg.size step 2) p.lineTo(gx(seg[k]), gy(seg[k + 1]))
                drawPath(p, line, style = Stroke(2.5f.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
    Text(
        tr("${g.variable} от ${dev.inknotes.math.Printer.number(g.x0.toDouble())} до ${dev.inknotes.math.Printer.number(g.x1.toDouble())}", "${g.variable} from ${dev.inknotes.math.Printer.number(g.x0.toDouble())} to ${dev.inknotes.math.Printer.number(g.x1.toDouble())}"),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One symbol's wheel: the reading before and after the chosen one, turned by a swipe up or
 * down or the arrows, and under it the symbol's place.
 */
@Composable
private fun Wheel(sym: Correct.Sym, pick: Correct.Pick, focused: Boolean, onFocus: () -> Unit, onPick: (Correct.Pick) -> Unit) {
    val n = sym.candidates.size
    val accent = MaterialTheme.colorScheme.primary
    val border = when {
        focused -> BorderStroke(2.dp, accent)
        sym.unsure -> BorderStroke(1.dp, DOUBT)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    val back = if (sym.unsure) DOUBT.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
    val step = with(LocalDensity.current) { 36.dp.toPx() }
    fun turn(by: Int) {
        if (n > 1) onPick(pick.copy(choice = ((pick.choice + by) % n + n) % n))
    }
    Column(
        Modifier.width(92.dp)
            .background(back, RoundedCornerShape(16.dp))
            .border(border, RoundedCornerShape(16.dp))
            .pointerInput(sym, n) {
                var drag = 0f
                detectVerticalDragGestures(onDragStart = { drag = 0f; onFocus() }) { _, dy ->
                    drag += dy
                    while (drag > step) { drag -= step; turn(-1) }
                    while (drag < -step) { drag += step; turn(1) }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(onClick = { onFocus(); turn(-1) }, enabled = n > 1, modifier = Modifier.height(34.dp)) { Text("▲", fontSize = 12.sp) }
        for (k in -1..1) {
            val index = if (n == 1 && k != 0) -1 else ((pick.choice + k) % n + n) % n
            val chosen = k == 0
            Box(
                Modifier.size(width = 80.dp, height = if (chosen) 54.dp else 36.dp)
                    .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable(enabled = index >= 0) { onFocus(); onPick(pick.copy(choice = index)) },
                contentAlignment = Alignment.Center,
            ) {
                if (index >= 0) {
                    Text(
                        Correct.show(sym.candidates[index]),
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = if (chosen) 30.sp else 19.sp,
                        color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }
        TextButton(onClick = { onFocus(); turn(1) }, enabled = n > 1, modifier = Modifier.height(34.dp)) { Text("▼", fontSize = 12.sp) }
        Row(
            Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((level, mark) in listOf(Correct.DOWN to "□₂", Correct.LINE to "□", Correct.UP to "□²")) {
                val on = pick.level == level
                Box(
                    Modifier.size(width = 26.dp, height = 30.dp)
                        .background(if (on) accent else Color.Transparent, RoundedCornerShape(7.dp))
                        .clickable { onFocus(); onPick(pick.copy(level = level)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(mark, fontSize = 12.sp, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A small question over the page about a formula just written: check how it was read? */
@Composable
fun CheckOffer(c: EditorController, answer: PageAnswer, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (answer.missing) tr("Формулу не удалось посчитать — исправить?", "The formula could not be computed — correct it?")
                else tr("Правильно прочитана формула?", "Was the formula read right?"),
                fontSize = 14.sp,
            )
            TextButton(onClick = { c.startFix(answer) }) { Text(tr("Проверить", "Check")) }
            TextButton(onClick = { c.checkOffer = null }) { Text(tr("Не сейчас", "Not now")) }
        }
    }
}

/** The formula settings: learning the handwriting, asking whether it was read right. */
@Composable
fun FormulaFixSettings(fixes: dev.inknotes.math.FixStore) {
    var learn by remember { mutableStateOf(fixes.learn) }
    var ask by remember { mutableStateOf(fixes.ask) }
    var forgotten by remember { mutableStateOf(false) }
    Text(tr("Формулы", "Formulas"), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().clickable { learn = !learn; fixes.learn = learn }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Учиться моему почерку", "Learn my handwriting"))
            Text(
                tr("Исправления в окне «Как прочитано» запоминаются и подсказывают, как читать ваши символы. Хранится только на устройстве.", "Corrections in the “How it was read” window are kept and tell how to read your symbols. Kept on the device only."),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = learn, onCheckedChange = { learn = it; fixes.learn = it })
    }
    Text(tr("Спрашивать «всё ли верно?»", "Ask “is this right?”"), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((mode, label) in listOf(
            dev.inknotes.math.FixStore.Ask.UNSURE to tr("Только при сомнении", "Only when unsure"),
            dev.inknotes.math.FixStore.Ask.SOMETIMES to tr("Иногда, даже когда уверены", "Sometimes, even when sure"),
            dev.inknotes.math.FixStore.Ask.NEVER to tr("Никогда", "Never"),
        )) {
            androidx.compose.material3.FilterChip(selected = ask == mode, onClick = { ask = mode; fixes.ask = mode }, label = { Text(label) })
        }
    }
    OutlinedButton(enabled = !forgotten && !fixes.learned.isEmpty(), onClick = { fixes.forget(); forgotten = true }) {
        Text(if (forgotten) tr("Почерк забыт", "Handwriting forgotten") else tr("Забыть мой почерк", "Forget my handwriting"))
    }
}
