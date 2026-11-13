package dev.inknotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.ui.EditorController.ExportFormat
import dev.inknotes.ui.EditorController.ExportPages

/**
 * Export: PDF or PNG pictures; all pages, this one or a range; sent on (share), saved to a
 * file or printed. The format and the kind of page choice are remembered.
 */
@Composable
fun ExportDialog(
    pageCount: Int,
    currentPage: Int,
    initialFormat: ExportFormat,
    initialPages: String,
    onDismiss: () -> Unit,
    onShare: (ExportFormat, ExportPages) -> Unit,
    onSave: (ExportFormat, ExportPages) -> Unit,
    onPrint: (ExportPages) -> Unit,
    onRemember: (ExportFormat, String) -> Unit,
) {
    var format by remember { mutableStateOf(initialFormat) }
    var which by remember { mutableStateOf(if (pageCount <= 1 && initialPages == "range") "all" else initialPages) }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf(pageCount.toString()) }
    val fromN = from.toIntOrNull()
    val toN = to.toIntOrNull()
    val rangeOk = fromN != null && toN != null && fromN in 1..pageCount && toN in 1..pageCount
    val pages: ExportPages? = when (which) {
        "page" -> ExportPages.Current
        "range" -> if (rangeOk) ExportPages.Range(fromN!!, toN!!) else null
        else -> ExportPages.All
    }
    val count = when (val p = pages) {
        ExportPages.All -> pageCount
        ExportPages.Current -> 1
        is ExportPages.Range -> kotlin.math.abs(p.to - p.from) + 1
        null -> 0
    }
    fun go(block: (ExportPages) -> Unit) {
        val p = pages ?: return
        onRemember(format, which)
        block(p)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(InkIcons.Export, null) },
        title = { Text("Экспорт") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Формат", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = format == ExportFormat.PDF, onClick = { format = ExportFormat.PDF }, label = { Text("PDF") })
                    FilterChip(selected = format == ExportFormat.PNG, onClick = { format = ExportFormat.PNG }, label = { Text("PNG, картинки") })
                }
                Text("Страницы", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = which == "all", onClick = { which = "all" }, label = { Text("Все ($pageCount)") })
                    FilterChip(selected = which == "page", onClick = { which = "page" }, label = { Text("Эта (${currentPage + 1})") })
                    if (pageCount > 1) FilterChip(selected = which == "range", onClick = { which = "range" }, label = { Text("Диапазон") })
                }
                if (which == "range") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(from, { from = it.filter(Char::isDigit).take(4) }, Modifier.width(96.dp), label = { Text("с") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(to, { to = it.filter(Char::isDigit).take(4) }, Modifier.width(96.dp), label = { Text("по") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    if (!rangeOk) Text("Номера страниц от 1 до $pageCount", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Text(
                    when {
                        pages == null -> ""
                        format == ExportFormat.PDF -> "Один PDF, страниц: $count."
                        count == 1 -> "Одна картинка PNG."
                        else -> "Картинок PNG: $count, по одной на страницу. Сохраняются в выбранную папку."
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = pages != null, onClick = { go(onPrint) }) {
                        Icon(InkIcons.Print, null)
                        Text("  Печать")
                    }
                    OutlinedButton(enabled = pages != null, onClick = { go { onSave(format, it) } }) {
                        Icon(InkIcons.Save, null)
                        Text("  Сохранить…")
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = pages != null, onClick = { go { onShare(format, it) } }) {
                Icon(InkIcons.Share, null)
                Text("  Отправить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
