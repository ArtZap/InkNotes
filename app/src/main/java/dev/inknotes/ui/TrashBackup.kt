package dev.inknotes.ui

import dev.inknotes.data.tr
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
        title = { Text(if (confirmEmpty) tr("Очистить корзину?", "Empty the trash?") else tr("Корзина", "Trash")) },
        text = {
            when {
                loaded == null -> Text(tr("Открываю корзину…", "Opening the trash…"))
                confirmEmpty -> Text(tr("Тетради в корзине (${items.size}) будут удалены без возможности восстановления.", "The notebooks in the trash (${items.size}) will be deleted for good."))
                items.isEmpty() -> Text(tr("Корзина пуста. Удалённые тетради хранятся здесь ${repo.trashDays} дней.", "The trash is empty. Deleted notebooks are kept here for ${repo.trashDays} days."))
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("Через ${repo.trashDays} дней после удаления тетрадь удаляется насовсем.", "${repo.trashDays} days after deletion a notebook is deleted for good."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(items, key = { it.notebook.id }) { t ->
                            val left = (repo.trashDays - (System.currentTimeMillis() - t.deletedAt) / dayMs).coerceAtLeast(0)
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.notebook.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        tr("${t.notebook.pageIds.size} стр. · удалена ${date.format(Date(t.deletedAt))} · осталось дней: $left", "${t.notebook.pageIds.size} p. · deleted ${date.format(Date(t.deletedAt))} · days left: $left"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        repo.io { restore(t.notebook) }?.let(onRestored)
                                        loaded = repo.io { trash() }
                                    }
                                }) { Text(tr("Восстановить", "Restore")) }
                                TextButton(onClick = {
                                    repo.purge(t.notebook)
                                    loaded = items - t
                                }) { Text(tr("Удалить", "Delete"), color = MaterialTheme.colorScheme.error) }
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
                }) { Text(tr("Очистить", "Empty")) }
            } else {
                Button(onClick = onDismiss) { Text(tr("Готово", "Done")) }
            }
        },
        dismissButton = {
            if (confirmEmpty) {
                TextButton(onClick = { confirmEmpty = false }) { Text(tr("Отмена", "Cancel")) }
            } else if (items.isNotEmpty()) {
                TextButton(onClick = { confirmEmpty = true }) { Text(tr("Очистить корзину…", "Empty the trash…"), color = MaterialTheme.colorScheme.error) }
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
            Backup.Result.Done -> tr("Копия сохранена", "Copy saved")
            Backup.Result.Skipped -> null
            is Backup.Result.Failed -> r.reason
        }
        last = prefs.lastBackupAt
        on = backup.folder != null
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        status = tr("Сохраняю копию…", "Saving a copy…")
        backup.setFolder(tree, report)
        // On only if the folder was really taken (a failure has already said why).
        on = backup.folder != null
        folderName = backup.folderName()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Автокопия", "Auto backup")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("Раз в день, когда вы выходите из приложения, все тетради и папки сохраняются одним файлом ", "Once a day, when you leave the app, all notebooks and folders are saved as one ") +
                        tr(".inknotes в выбранную папку (можно Google Диск). Хранятся ${Backup.KEEP} последних копий ", ".inknotes file in the chosen folder (Google Drive works too). The last ${Backup.KEEP} copies are kept ") +
                        tr("(для каждого устройства выберите свою папку). ", "(choose a separate folder for each device). ") +
                        tr("Восстановить: «Импорт» → файл копии.", "To restore: “Import” → the backup file."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (on) {
                    Text(tr("Папка: ${folderName ?: "выбрана"}", "Folder: ${folderName ?: "chosen"}"))
                    Text(if (last > 0) tr("Последняя копия: ${date.format(Date(last))}", "Last copy: ${date.format(Date(last))}") else tr("Копий ещё не было", "No copies yet"))
                } else {
                    Text(tr("Выключена", "Off"))
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pick.launch(null) }) { Text(if (on) tr("Другая папка…", "Another folder…") else tr("Выбрать папку…", "Choose a folder…")) }
                    if (on) {
                        OutlinedButton(onClick = {
                            status = tr("Сохраняю копию…", "Saving a copy…")
                            backup.run(force = true, onDone = report)
                        }) { Text(tr("Копия сейчас", "Back up now")) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(tr("Готово", "Done")) } },
        dismissButton = {
            if (on) {
                TextButton(onClick = {
                    backup.turnOff()
                    on = false
                    status = null
                }) { Text(tr("Выключить", "Turn off")) }
            }
        },
    )
}
