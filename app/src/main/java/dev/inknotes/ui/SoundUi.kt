package dev.inknotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Date

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A slim bar under the toolbar while sound is being recorded or played. */
@Composable
fun SoundBar(c: EditorController) {
    val since = c.recordingSince
    val rec = c.playing
    if (since == null && rec == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(since, rec) {
        while (true) {
            now = System.currentTimeMillis()
            position = c.playPosition()
            delay(250)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (since != null) {
            Text("●", color = Color(0xFFE53935))
            Text("Запись ${clock(now - since)}", fontWeight = FontWeight.Medium)
            Text("Пишите — написанное запомнит момент", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Button(onClick = { c.stopRecording() }) { Text("Стоп") }
        } else if (rec != null) {
            Text(if (c.playPaused) "❚❚" else "▶")
            Text("${clock(position)} / ${clock(rec.durationMs)}")
            Slider(
                value = position.toFloat().coerceIn(0f, rec.durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = { c.seekPlaying(it.toLong()); position = it.toLong() },
                valueRange = 0f..rec.durationMs.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { c.seekPlaying(position - 10_000) }) { Text("−10 с") }
            TextButton(onClick = { c.togglePause() }) { Text(if (c.playPaused) "Дальше" else "Пауза") }
            TextButton(onClick = { c.stopPlaying() }) { Text("Стоп") }
        }
    }
}

/** The notebook's recordings: play, delete; listening by touching the writing. */
@Composable
fun RecordingsDialog(c: EditorController, onRecord: () -> Unit, onDismiss: () -> Unit) {
    val dates = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Записи звука") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Слушать касанием написанного")
                        Text(
                            "Коснитесь пальцем слова — запись включится с момента, когда оно писалось. Такие места подчёркнуты.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = c.listenByTouch, onCheckedChange = { c.toggleListenByTouch() })
                }
                HorizontalDivider()
                if (c.notebook.recordings.isEmpty()) {
                    Text("Записей пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(c.notebook.recordings.reversed()) { rec ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(dates.format(Date(rec.startedAt)))
                                    Text(
                                        clock(rec.durationMs) + " · отметок: ${c.notebook.audioMarks.count { it.file == rec.file }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { c.play(rec) }) { Text("▶") }
                                TextButton(onClick = { c.deleteRecording(rec) }) { Text("✕", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = c.recordingSince == null, onClick = {
                onRecord()
                onDismiss()
            }) { Text("● Записать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
