package dev.inknotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.inknotes.data.Backup
import dev.inknotes.data.Notebook
import dev.inknotes.data.NotebookRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Deleted notebooks: restore one, delete one for good, or empty the trash. */
@Composable
fun TrashDialog(repo: NotebookRepository, onRestored: (Notebook) -> Unit, onDismiss: () -> Unit) {
    // Read off the main thread: null while it is read.
    var loaded by remember { mutableStateOf<List<NotebookRepository.Trashed>?>(null) }
    val items = loaded.orEmpty()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { loaded = repo.io { trash() } }
    var confirmEmpty by remember { mutableStateOf(false) }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val dayMs = 24L * 3600 * 1000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmEmpty) "Очистить корзину?" else "Корзина") },
        text = {
            when {
                loaded == null -> Text("Открываю корзину…")
                confirmEmpty -> Text("Тетради в корзине (${items.size}) будут удалены без возможности восстановления.")
                items.isEmpty() -> Text("Корзина пуста. Удалённые тетради хранятся здесь ${repo.trashDays} дней.")
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Через ${repo.trashDays} дней после удаления тетрадь удаляется насовсем.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(items, key = { it.notebook.id }) { t ->
                            val left = (repo.trashDays - (System.currentTimeMillis() - t.deletedAt) / dayMs).coerceAtLeast(0)
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.notebook.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${t.notebook.pageIds.size} стр. · удалена ${date.format(Date(t.deletedAt))} · осталось дней: $left",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        repo.io { restore(t.notebook) }?.let(onRestored)
                                        loaded = repo.io { trash() }
                                    }
                                }) { Text("Восстановить") }
                                TextButton(onClick = {
                                    repo.purge(t.notebook)
                                    loaded = items - t
                                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (confirmEmpty) {
                Button(onClick = {
                    repo.emptyTrash()
                    loaded = emptyList()
                    confirmEmpty = false
                }) { Text("Очистить") }
            } else {
                Button(onClick = onDismiss) { Text("Готово") }
            }
        },
        dismissButton = {
            if (confirmEmpty) {
                TextButton(onClick = { confirmEmpty = false }) { Text("Отмена") }
            } else if (items.isNotEmpty()) {
                TextButton(onClick = { confirmEmpty = true }) { Text("Очистить корзину…", color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

/** Where the daily copies go, when the last one was made, and "copy now". */
@Composable
fun BackupDialog(backup: Backup, prefs: AppPrefs, onDismiss: () -> Unit) {
    var folderName by remember { mutableStateOf(backup.folderName()) }
    var on by remember { mutableStateOf(backup.folder != null) }
    var status by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf(prefs.lastBackupAt) }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val report = { r: Backup.Result ->
        status = when (r) {
            Backup.Result.Done -> "Копия сохранена"
            Backup.Result.Skipped -> null
            is Backup.Result.Failed -> r.reason
        }
        last = prefs.lastBackupAt
        on = backup.folder != null
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        status = "Сохраняю копию…"
        backup.setFolder(tree, report)
        // On only if the folder was really taken (a failure has already said why).
        on = backup.folder != null
        folderName = backup.folderName()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Автокопия") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Раз в день, когда вы выходите из приложения, все тетради и папки сохраняются одним файлом " +
                        ".inknotes в выбранную папку (можно Google Диск). Хранятся ${Backup.KEEP} последних копий " +
                        "(для каждого устройства выберите свою папку). " +
                        "Восстановить: «Импорт» → файл копии.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (on) {
                    Text("Папка: ${folderName ?: "выбрана"}")
                    Text(if (last > 0) "Последняя копия: ${date.format(Date(last))}" else "Копий ещё не было")
                } else {
                    Text("Выключена")
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pick.launch(null) }) { Text(if (on) "Другая папка…" else "Выбрать папку…") }
                    if (on) {
                        OutlinedButton(onClick = {
                            status = "Сохраняю копию…"
                            backup.run(force = true, onDone = report)
                        }) { Text("Копия сейчас") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Готово") } },
        dismissButton = {
            if (on) {
                TextButton(onClick = {
                    backup.turnOff()
                    on = false
                    status = null
                }) { Text("Выключить") }
            }
        },
    )
}
