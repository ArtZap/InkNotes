package dev.inknotes.ui

import dev.inknotes.data.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inknotes.data.Bookmark

/** The notebook's table of contents: go to an entry, rename or remove it, add one here. */
@Composable
fun TocDialog(c: EditorController, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    val toc = c.notebook.toc
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Оглавление", "Contents")) },
        text = {
            if (toc.isEmpty()) {
                Text(
                    tr("Пока пусто. Выделите заголовок лассо и выберите «Ещё → В оглавление» или добавьте текущую страницу кнопкой ниже.", "Empty so far. Select a heading with the lasso and choose “More → To contents”, or add the current page with the button below."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(toc) { b ->
                        val page = c.notebook.pageIds.indexOf(b.pageId)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                c.goToBookmark(b)
                                onDismiss()
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(b.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${page + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                            TextButton(onClick = { renaming = b }) { Text(tr("Изм.", "Edit")) }
                            TextButton(onClick = { c.deleteBookmark(b) }) { Text("✕") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { adding = true }) { Text(if (c.selectionCount > 0) tr("Добавить выделенное", "Add the selection") else tr("Добавить эту страницу", "Add this page")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } },
    )
    if (adding) AddBookmarkDialog(c, onDismiss = { adding = false })
    renaming?.let { b ->
        TitleDialog(tr("Название", "Title"), b.title, onDismiss = { renaming = null }) {
            c.renameBookmark(b, it)
            renaming = null
        }
    }
}

/** Title for a new entry at the selection (its handwriting read as the suggested title) or the page in view. */
@Composable
fun AddBookmarkDialog(c: EditorController, onDismiss: () -> Unit) {
    val (page, rect) = remember { c.selectionPlace() }
    var suggested by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(rect == null) }
    LaunchedEffect(Unit) {
        if (rect != null) c.selectionText { suggested = it; ready = true }
    }
    if (!ready) return
    TitleDialog(tr("В оглавление", "To contents"), suggested ?: tr("Страница ${page + 1}", "Page ${page + 1}"), onDismiss = onDismiss) { title ->
        c.addBookmark(title, page, rect?.top ?: 0f)
        onDismiss()
    }
}

@Composable
private fun TitleDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(tr("Название", "Title")) }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { onDone(text) }) { Text(tr("Готово", "Done")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}

/** Makes the selection a link: to an entry of the contents or to a page by number. */
@Composable
fun LinkDialog(c: EditorController, onDismiss: () -> Unit) {
    var number by remember { mutableStateOf("") }
    val existing = remember { c.selectionLinks() }
    val pages = c.notebook.pageIds.size
    val target = number.toIntOrNull()?.takeIf { it in 1..pages }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Ссылка", "Link")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Касание выделенного пальцем откроет страницу.", "Touching the selection with a finger opens the page."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    number,
                    { number = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    label = { Text(tr("Номер страницы (1–$pages)", "Page number (1–$pages)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (c.notebook.toc.isNotEmpty()) {
                    Text(tr("Или раздел оглавления:", "Or a contents entry:"), style = MaterialTheme.typography.labelLarge)
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(c.notebook.toc) { b ->
                            val page = c.notebook.pageIds.indexOf(b.pageId)
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    c.linkSelection(page, b.y)
                                    onDismiss()
                                }.padding(vertical = 8.dp),
                            ) {
                                Text(b.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${page + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (existing.isNotEmpty()) {
                    TextButton(onClick = {
                        c.removeLinks(existing)
                        onDismiss()
                    }) { Text(tr("Убрать ссылку отсюда", "Remove the link from here"), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(enabled = target != null, onClick = {
                c.linkSelection(target!! - 1)
                onDismiss()
            }) { Text(tr("Ссылка", "Link")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}
