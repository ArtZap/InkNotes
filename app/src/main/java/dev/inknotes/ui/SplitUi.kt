package dev.inknotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.Notebook
import dev.inknotes.data.NotebookRepository

/**
 * Two notebooks side by side, [share] of the width to the first; the line between them is
 * dragged to change it. A touch in a pane makes it the one the keyboard and pen button act on.
 */
@Composable
fun SplitPanes(
    share: Float,
    onShare: (Float) -> Unit,
    onFocus: (first: Boolean) -> Unit,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(share).fillMaxHeight().noticeTouch { onFocus(true) }) { first() }
            // The handle: a thin line with a wider strip to grab.
            Box(
                Modifier
                    .width(12.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(total) {
                        var at = share
                        detectHorizontalDragGestures { _, dx ->
                            at = (at + dx / total).coerceIn(MIN_SHARE, 1f - MIN_SHARE)
                            onShare(at)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(4.dp).heightIn(min = 48.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
            }
            Box(Modifier.weight(1f - share).fillMaxHeight().noticeTouch { onFocus(false) }) { second() }
        }
    }
}

private const val MIN_SHARE = 0.25f

/** Calls [onTouch] at every touch inside, before the content handles it (nothing is taken from it). */
private fun Modifier.noticeTouch(onTouch: () -> Unit) = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.type == PointerEventType.Press) onTouch()
        }
    }
}

/** A notebook to show beside the one open: the latest first, with a filter by name. */
@Composable
fun SidePickerDialog(repo: NotebookRepository, except: String, onPick: (Notebook) -> Unit, onDismiss: () -> Unit) {
    val library by repo.library.collectAsState()
    LaunchedEffect(Unit) { repo.refreshLibrary() }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(InkIcons.SideBySide, null) },
        title = { Text("Открыть рядом") },
        text = {
            Column {
                Text(
                    "Вторая тетрадь встанет справа: например, PDF и конспект. Линию между ними можно двигать.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Найти по названию") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                val all = library?.notebooks
                if (all == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                } else {
                    val shown = all.filter { it.id != except && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
                        .sortedByDescending { it.updatedAt }
                    if (shown.isEmpty()) Text("Нет других тетрадей", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.id }) { nb ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(nb) }.padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (nb.pdfPages.isNotEmpty()) InkIcons.PdfPage else InkIcons.Notebook, null, Modifier.size(20.dp))
                                Text(nb.title, Modifier.padding(start = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${nb.pageIds.size} стр.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
