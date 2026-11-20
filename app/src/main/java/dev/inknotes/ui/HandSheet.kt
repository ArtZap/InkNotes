package dev.inknotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.inknotes.data.tr
import dev.inknotes.math.FixStore
import dev.inknotes.math.HandGlyphs

/** What each asked symbol is called, so a dot is not taken for a speck. */
private fun nameOf(c: Char): String = when (c) {
    ',' -> tr("запятая", "comma")
    '.' -> tr("точка", "point")
    '−' -> tr("минус", "minus")
    '+' -> tr("плюс", "plus")
    '/' -> tr("дробная черта", "slash")
    '(' -> tr("скобка", "bracket")
    ')' -> tr("скобка", "bracket")
    '=' -> tr("равно", "equals")
    '√' -> tr("знак корня", "root sign")
    'π' -> tr("пи", "pi")
    '°' -> tr("градус", "degree")
    '∞' -> tr("бесконечность", "infinity")
    '·' -> tr("умножение", "times")
    else -> ""
}

/** The top guide and the base line, as parts of the writing box's height. */
private const val TOP = 0.3f
private const val BASE = 0.72f

/**
 * «Мой почерк для ответов»: the digits and signs answers are written with, one at a time, in a
 * box with two guides (how tall a digit is). Each can be written up to three times; answers
 * then take the ways in turn. Kept on the device only.
 */
@Composable
fun HandSheet(fixes: FixStore, onClose: () -> Unit) {
    val asked = HandGlyphs.ASKED
    var index by remember { mutableIntStateOf(asked.indexOfFirst { !fixes.hand.has(it) }.coerceAtLeast(0)) }
    // Bumped on every change of the hand: what has been written is shown again.
    var version by remember { mutableIntStateOf(0) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val live = remember { mutableStateListOf<Offset>() }
    var boxH by remember { mutableStateOf(1f) }
    val c = asked[index]
    val written = remember(version) { asked.filter { fixes.hand.has(it) }.toSet() }
    val variants = remember(version, index) { fixes.hand.variants(c) }

    fun save() {
        if (strokes.isEmpty()) return
        val g = HandGlyphs.glyph(strokes.map { s -> FloatArray(s.size * 2) { i -> if (i % 2 == 0) s[i / 2].x else s[i / 2].y } }, boxH * TOP, boxH * BASE) ?: return
        fixes.hand.add(c, g)
        fixes.saveHand()
        strokes.clear()
        version++
        index = (index + 1) % asked.length
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 560.dp).padding(16.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(tr("Мой почерк для ответов", "My handwriting for answers"), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("Напишите каждый знак так, как пишете обычно: высотой от верхней линии до нижней. Ответы после «=» будут нарисованы вашими цифрами.", "Write each sign the way you usually do: from the upper line to the lower one. Answers after “=” will be drawn with your digits."),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                // Every asked symbol: written ones marked; a tap goes to it.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((i, ch) in asked.withIndex()) {
                        val done = ch in written
                        Box(
                            Modifier
                                .size(34.dp)
                                .border(BorderStroke(if (i == index) 2.dp else 1.dp, if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp))
                                .background(if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .clickable {
                                    strokes.clear()
                                    index = i
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(ch.toString(), fontSize = 17.sp) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.toString(), fontSize = 34.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.size(10.dp))
                    Column {
                        nameOf(c).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 14.sp) }
                        Text(
                            tr("записано: $variants из ${HandGlyphs.VARIANTS}", "written: $variants of ${HandGlyphs.VARIANTS}"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val ink = MaterialTheme.colorScheme.onSurface
                val guide = MaterialTheme.colorScheme.outline
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .onSizeChanged { boxH = it.height.toFloat() }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                live.clear()
                                live += down.position
                                down.consume()
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                    for (h in ch.historical) live += h.position
                                    live += ch.position
                                    ch.consume()
                                    if (!ch.pressed) break
                                }
                                if (live.isNotEmpty()) strokes += live.toList()
                                live.clear()
                            }
                        },
                ) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                    drawLine(guide, Offset(0f, size.height * TOP), Offset(size.width, size.height * TOP), strokeWidth = 2f, pathEffect = dash)
                    drawLine(guide, Offset(0f, size.height * BASE), Offset(size.width, size.height * BASE), strokeWidth = 3f)
                    val pen = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    for (s in strokes + listOf(live.toList())) {
                        if (s.isEmpty()) continue
                        val p = Path().apply {
                            moveTo(s[0].x, s[0].y)
                            if (s.size == 1) lineTo(s[0].x + 0.5f, s[0].y)
                            for (k in 1 until s.size) lineTo(s[k].x, s[k].y)
                        }
                        drawPath(p, ink, style = pen)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text(tr("Стереть", "Clear")) }
                    TextButton(onClick = {
                        strokes.clear()
                        index = (index + 1) % asked.length
                    }) { Text(tr("Пропустить", "Skip")) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        save()
                        if (!fixes.hand.isEmpty()) fixes.inHand = true
                        onClose()
                    }) { Text(tr("Готово", "Done")) }
                    Button(onClick = { save() }, enabled = strokes.isNotEmpty()) { Text(tr("Дальше", "Next")) }
                }
            }
        }
    }
}
