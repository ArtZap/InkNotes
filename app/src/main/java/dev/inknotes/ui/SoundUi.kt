package dev.inknotes.ui

import dev.inknotes.data.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import dev.inknotes.data.Recording
import dev.inknotes.speech.Speech
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
            Text(tr("Запись ${clock(now - since)}", "Recording ${clock(now - since)}"), fontWeight = FontWeight.Medium)
            Text(tr("Пишите — написанное запомнит момент", "Write — your writing remembers the moment"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Button(onClick = { c.stopRecording() }) { Text(tr("Стоп", "Stop")) }
        } else if (rec != null) {
            Text(if (c.playPaused) "❚❚" else "▶")
            Text("${clock(position)} / ${clock(rec.durationMs)}")
            Slider(
                value = position.toFloat().coerceIn(0f, rec.durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = { c.seekPlaying(it.toLong()); position = it.toLong() },
                valueRange = 0f..rec.durationMs.toFloat().coerceAtLeast(1f),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { c.seekPlaying(position - 10_000) }) { Text(tr("−10 с", "−10 s")) }
            TextButton(onClick = { c.togglePause() }) { Text(if (c.playPaused) tr("Дальше", "Resume") else tr("Пауза", "Pause")) }
            TextButton(onClick = { c.stopPlaying() }) { Text(tr("Стоп", "Stop")) }
        }
    }
}

/** The notebook's recordings: play, delete; listening by touching the writing. */
@Composable
fun RecordingsDialog(c: EditorController, onRecord: () -> Unit, onDismiss: () -> Unit) {
    val dates = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    val context = LocalContext.current
    var asking by remember { mutableStateOf<Recording?>(null) }
    var showing by remember { mutableStateOf<Recording?>(null) }
    asking?.let { rec -> TranscribeDialog(rec, onStart = { m -> c.transcribe(context, rec, m) }, onDismiss = { asking = null }) }
    showing?.let { rec -> TranscriptDialog(c, rec, onDismiss = { showing = null }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Записи звука", "Sound recordings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Слушать касанием написанного", "Listen by touching your writing"))
                        Text(
                            tr("Коснитесь пальцем слова — запись включится с момента, когда оно писалось. Такие места подчёркнуты.", "Touch a word with your finger — the recording plays from the moment it was written. Such places are underlined."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = c.listenByTouch, onCheckedChange = { c.toggleListenByTouch() })
                }
                HorizontalDivider()
                if (c.notebook.recordings.isEmpty()) {
                    Text(tr("Записей пока нет.", "No recordings yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(c.notebook.recordings.reversed()) { rec ->
                          Column {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(dates.format(Date(rec.startedAt)))
                                    Text(
                                        clock(rec.durationMs) + tr(" · отметок: ${c.notebook.audioMarks.count { it.file == rec.file }}", " · marks: ${c.notebook.audioMarks.count { it.file == rec.file }}"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { c.play(rec) }) { Text("▶") }
                                TextButton(onClick = { c.deleteRecording(rec) }) { Text("✕", color = MaterialTheme.colorScheme.error) }
                            }
                            TranscriptRow(c, rec, onAsk = { asking = rec }, onShow = { showing = rec })
                          }
                        }
                    }
                }
                SpeechModels()
            }
        },
        confirmButton = {
            Button(enabled = c.recordingSince == null, onClick = {
                onRecord()
                onDismiss()
            }) { Text(tr("● Записать", "● Record")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } },
    )
}

/** Under a recording: its transcript (open it), the transcription under way, or a way to make one. */
@Composable
private fun TranscriptRow(c: EditorController, rec: Recording, onAsk: () -> Unit, onShow: () -> Unit) {
    val task = Speech.task
    val t = c.transcripts[rec.file]
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (task != null && task.nbId == c.notebook.id && task.file == rec.file) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (task.downloading) tr("Скачиваю модель речи… ${(task.progress * 100).toInt()} %", "Downloading the speech model… ${(task.progress * 100).toInt()} %")
                    else tr("Расшифровка… ${(task.progress * 100).toInt()} %", "Transcribing… ${(task.progress * 100).toInt()} %"),
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = { Speech.cancel() }) { Text(tr("Стоп", "Stop")) }
        } else {
            Text(
                when {
                    t == null -> tr("Без текста", "No text")
                    t.complete -> tr("Текст: ${t.phrases.size} фраз", "Text: ${t.phrases.size} phrases")
                    else -> tr("Текст до ${clock(t.doneMs)}", "Text up to ${clock(t.doneMs)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (t != null && t.phrases.isNotEmpty()) TextButton(onClick = onShow) { Text(tr("Текст", "Text")) }
            if (t?.complete != true) TextButton(enabled = task == null, onClick = onAsk) {
                Text(if (t == null) tr("Расшифровать", "Transcribe") else tr("Продолжить", "Continue"))
            }
        }
    }
}

/** Which language, and what downloading the model means, before the first transcript. */
@Composable
private fun TranscribeDialog(rec: Recording, onStart: (Speech.Model) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var model by remember { mutableStateOf(if (dev.inknotes.data.Lang.english) Speech.Model.EN else Speech.Model.RU) }
    val ready = Speech.version.let { Speech.installed(context, model) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Расшифровать запись", "Transcribe the recording")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(
                    "Запись ${clock(rec.durationMs)} превратится в текст: касание фразы включит звук с неё. Всё делается на этом устройстве, звук никуда не отправляется.",
                    "The ${clock(rec.durationMs)} recording becomes text: touching a phrase plays the sound from it. All on this device: the sound is sent nowhere.",
                ))
                Text(tr("Язык лекции", "Language of the lecture"), fontWeight = FontWeight.Medium)
                for (m in Speech.Model.entries) {
                    Row(Modifier.fillMaxWidth().clickable { model = m }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model == m, onClick = { model = m })
                        Text(
                            m.title.replaceFirstChar { it.uppercaseChar() } +
                                if (Speech.installed(context, m)) tr(" · модель скачана", " · model downloaded") else tr(" · модель ${m.mb} МБ", " · model ${m.mb} MB"),
                        )
                    }
                }
                if (!ready) {
                    Text(
                        tr(
                            "Модель речи скачается один раз (${model.mb} МБ, нужен интернет) и дальше работает без него. Её можно удалить в «Записях звука».",
                            "The speech model is downloaded once (${model.mb} MB, needs the internet) and then works offline. It can be deleted in “Sound recordings”.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    tr(
                        "Расшифровка может идти долго — сравнимо с длиной записи. Если закрыть приложение, она продолжится с того же места.",
                        "Transcribing may take long, comparable to the length of the recording. If the app is closed, it goes on from the same place.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onStart(model)
                onDismiss()
            }) { Text(if (ready) tr("Расшифровать", "Transcribe") else tr("Скачать и расшифровать", "Download and transcribe")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}

/** The transcript: a phrase a line with its time; a touch plays the sound from it. */
@Composable
private fun TranscriptDialog(c: EditorController, rec: Recording, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val t = c.transcripts[rec.file]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Текст записи", "Recording text")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t == null || t.phrases.isEmpty()) {
                    Text(tr("Слов не распознано.", "No words recognized."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (!t.complete) Text(tr("Расшифровано до ${clock(t.doneMs)} из ${clock(rec.durationMs)}", "Transcribed up to ${clock(t.doneMs)} of ${clock(rec.durationMs)}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("Коснитесь фразы — звук включится с неё. Распознавание без интернета ошибается в терминах и формулах.", "Touch a phrase to play the sound from it. Offline recognition makes mistakes in terms and formulas."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(t.phrases) { p ->
                            Row(Modifier.fillMaxWidth().clickable { c.play(rec, p.startMs) }.padding(vertical = 4.dp)) {
                                Text(clock(p.startMs), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                                Text(p.text, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } },
        dismissButton = {
            if (t != null && t.phrases.isNotEmpty()) TextButton(onClick = { c.copyTranscript(context, rec) }) { Text(tr("Копировать", "Copy")) }
        },
    )
}

/** Downloaded speech models, with the space they take back on deleting. */
@Composable
private fun SpeechModels() {
    val context = LocalContext.current
    val installed = Speech.version.let { Speech.Model.entries.filter { Speech.installed(context, it) } }
    if (installed.isEmpty()) return
    HorizontalDivider()
    for (m in installed) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Модель речи: ${m.title} (${m.mb} МБ)", "Speech model: ${m.title} (${m.mb} MB)"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = Speech.task?.model != m, onClick = { Speech.deleteModel(context, m) }) { Text(tr("Удалить", "Delete")) }
        }
    }
}
