package dev.inknotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.inknotes.editor.PageEditorView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Room beside the pages: on the left, on the right or none, and how much. The page moves
 * as the slider does, so the result is seen at once.
 */
@Composable
fun PageShiftDialog(c: EditorController, onDismiss: () -> Unit) {
    val shift = c.pageShift
    val max = PageEditorView.MAX_PAGE_SHIFT
    val percent = (abs(shift) * 100).roundToInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отступ страницы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Свободное место сбоку от страницы — например, чтобы положить руку. Страница уменьшается и встаёт в оставшуюся часть экрана.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = shift > 0f, onClick = { c.changePageShift(abs(shift).takeIf { it > 0f } ?: DEFAULT_SHIFT) }, label = { Text("Слева") })
                    FilterChip(selected = shift == 0f, onClick = { c.changePageShift(0f) }, label = { Text("Нет") })
                    FilterChip(selected = shift < 0f, onClick = { c.changePageShift(-(abs(shift).takeIf { it > 0f } ?: DEFAULT_SHIFT)) }, label = { Text("Справа") })
                }
                // The page goes the way the slider does: to the right leaves room on the left.
                InkSlider(
                    value = shift,
                    onValueChange = { v -> c.changePageShift(((v * 20).roundToInt() / 20f).let { if (abs(it) < 0.025f) 0f else it }) },
                    valueRange = -max..max,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when {
                        shift > 0f -> "Слева: $percent% ширины экрана"
                        shift < 0f -> "Справа: $percent% ширины экрана"
                        else -> "Без отступа: страница по ширине экрана"
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

private const val DEFAULT_SHIFT = 0.25f
