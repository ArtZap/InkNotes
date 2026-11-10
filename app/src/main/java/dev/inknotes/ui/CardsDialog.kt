package dev.inknotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.inknotes.data.Cards

/**
 * Review of the notebook's flash cards: the line around a band of the hint marker with the
 * band closed, then, on "Показать", opened; the answer is graded and the card comes back
 * after a while (spaced repetition). Cards forgotten come back in the same session.
 */
@Composable
fun CardsDialog(c: EditorController, cards: List<EditorController.StudyCard>, onDismiss: () -> Unit) {
    val today = remember { Cards.today() }
    // The session: due cards in page order; "Не помню" puts one back at the end.
    val queue = remember(cards) { mutableStateListOf<EditorController.StudyCard>().apply { addAll(cards.filter { Cards.isDue(c.cardStates[it.key], today) }) } }
    var done by remember(cards) { mutableStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(24.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Повторение", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (cards.isNotEmpty()) {
                        Text(
                            "Сегодня: ${queue.size} · пройдено $done · всего карточек ${cards.size}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                val card = queue.firstOrNull()
                when {
                    cards.isEmpty() -> Message(
                        "В тетради нет карточек",
                        "Закройте маркером подсказок (кнопка с перечёркнутым глазом) то, что нужно запомнить: " +
                            "определение, формулу, ответ. Каждая полоса станет карточкой — вопросом будет строка вокруг неё.",
                    )
                    card == null -> {
                        val next = cards.mapNotNull { c.cardStates[it.key]?.due }.filter { it > today }.minOrNull()
                        Message(
                            "На сегодня всё",
                            (if (done > 0) "Пройдено карточек: $done. " else "") +
                                (next?.let { "Следующие — через ${it - today} дн." } ?: ""),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            OutlinedButton(onClick = {
                                queue.addAll(cards)
                                shown = false
                            }) { Text("Повторить все сейчас") }
                        }
                    }
                    else -> {
                        CardImage(c, card, shown, Modifier.weight(1f).fillMaxWidth())
                        Text(
                            "Страница ${card.pageIndex + 1}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!shown) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                                TextButton(onClick = { c.showCard(card) }) { Text("Открыть страницу") }
                                Button(onClick = { shown = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Показать") }
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (g in Cards.Grade.entries) {
                                    val action = {
                                        c.gradeCard(card, g)
                                        queue.removeAt(0)
                                        if (g == Cards.Grade.AGAIN) queue.add(card) else done++
                                        shown = false
                                    }
                                    val label: @Composable () -> Unit = {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(g.title, fontWeight = FontWeight.Medium)
                                            Text(Cards.interval(c.cardStates[card.key], g, today), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (g == Cards.Grade.GOOD) {
                                        Button(onClick = action, modifier = Modifier.weight(1f)) { label() }
                                    } else {
                                        OutlinedButton(onClick = action, modifier = Modifier.weight(1f)) { label() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The card's part of the page, rendered to fit the width. */
@Composable
private fun CardImage(c: EditorController, card: EditorController.StudyCard, revealed: Boolean, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(200, 2400)
        var image by remember(card) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(card, revealed, px) { c.renderCard(card, revealed, px) { image = it } }
        val img = image
        if (img == null) {
            CircularProgressIndicator()
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Image(img, contentDescription = if (revealed) "Ответ" else "Вопрос", contentScale = ContentScale.Fit)
            }
        }
    }
}
