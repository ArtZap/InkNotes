package dev.inknotes.ui

import dev.inknotes.data.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.PdfGeometry.Margins

/** Room for notes round a PDF page, as offered. */
enum class PdfMargins(val title: String, val margins: Margins) {
    NONE(tr("Без полей", "No margins"), Margins.NONE),
    RIGHT(tr("Справа", "Right"), Margins(right = 0.6f)),
    BOTTOM(tr("Снизу", "Bottom"), Margins(bottom = 0.7f)),
    AROUND(tr("Вокруг", "All around"), Margins(0.15f, 0.15f, 0.15f, 0.15f)),
    WIDE(tr("Справа и снизу", "Right and bottom"), Margins(right = 0.5f, bottom = 0.5f));

    companion object {
        fun of(m: Margins) = entries.firstOrNull { it.margins == m }
    }
}

/**
 * The PDF page shown: turned, room for notes round it, dark; other PDF's pages put in after
 * it; the PDF's own contents added to the notebook's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfPageDialog(c: EditorController, onInsertPdf: () -> Unit, onDismiss: () -> Unit) {
    var all by remember { mutableStateOf(false) }
    val ref = c.pdfRef()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(InkIcons.PdfPage, null) },
        title = { Text(tr("Страница PDF", "PDF page")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ref == null) {
                    Text(
                        if (c.hasPdf) tr("Эта страница — не из PDF. Повернуть и добавить поля можно у страниц PDF.", "This page is not from a PDF. PDF pages can be rotated and given margins.") else tr("В тетради нет страниц PDF. Их можно вставить из файла.", "This notebook has no PDF pages. They can be inserted from a file."),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Ко всем страницам PDF", "To all PDF pages"), Modifier.weight(1f))
                        Switch(checked = all, onCheckedChange = { all = it })
                    }
                    Text(tr("Повернуть", "Rotate"), fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { c.setPdfLayout(all, rotateBy = -90) }) { Text("↺ 90°") }
                        OutlinedButton(onClick = { c.setPdfLayout(all, rotateBy = 90) }) { Text("↻ 90°") }
                        OutlinedButton(onClick = { c.setPdfLayout(all, rotateBy = 180) }) { Text("180°") }
                    }
                    Text(tr("Поля для заметок", "Margins for notes"), fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val now = PdfMargins.of(ref.margins)
                        for (m in PdfMargins.entries) {
                            FilterChip(selected = now == m, onClick = { c.setPdfLayout(all, margins = m.margins) }, label = { Text(m.title) })
                        }
                    }
                    Text(
                        tr("Написанное на странице поворачивается и сдвигается вместе с PDF. Поля видны и в экспорте.", "Writing on the page turns and moves with the PDF. Margins also appear in exports."),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Тёмный PDF", "Dark PDF"))
                        Text(tr("Светлый текст на тёмном, цвета сохраняются", "Light text on dark, colours kept"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = c.pdfDark, onCheckedChange = { c.togglePdfDark() })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !c.insertingPdf, onClick = onInsertPdf) { Text(tr("Вставить страницы из PDF…", "Insert pages from a PDF…")) }
                    if (c.hasPdf) OutlinedButton(onClick = { c.readPdfOutline() }) { Text(tr("Оглавление из PDF", "Contents from the PDF")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Готово", "Done")) } },
    )
}

/** Words of a PDF picked with the pen: what they say, copy them or mark them with the highlighter. */
@Composable
fun PdfPickBar(c: EditorController) {
    val pick = c.pdfPick ?: return
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(InkIcons.PdfText, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            "«" + pick.text.replace('\n', ' ') + "»",
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = {
            copyText(context, pick.text)
            c.showStatus(tr("Текст скопирован", "Text copied"))
            c.closePdfPick()
        }) { Text(tr("Копировать", "Copy")) }
        OutlinedButton(onClick = c::highlightPdfPick) { Text(tr("Маркером", "Highlight")) }
        TextButton(onClick = c::closePdfPick) { Text("✕") }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(tr("Текст PDF", "PDF text"), text))
}
