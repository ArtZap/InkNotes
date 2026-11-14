package dev.inknotes.ui

import dev.inknotes.data.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.inknotes.math.Answer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import dev.inknotes.math.PageAnswer
import dev.inknotes.math.Printer

/** A tapped answer: the result written out, how it was found, and copying it. */
@Composable
fun AnswerDialog(c: EditorController, a: PageAnswer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = when (val ans = a.answer) {
        is Answer.Text -> ans.lines.joinToString("\n")
        is Answer.Grid -> ans.rows.joinToString("\n") { it.joinToString("   ") }
        is Answer.Graph -> tr("График: ${ans.variable} от ${Printer.number(ans.x0.toDouble())} до ${Printer.number(ans.x1.toDouble())}, ", "Graph: ${ans.variable} from ${Printer.number(ans.x0.toDouble())} to ${Printer.number(ans.x1.toDouble())}, ") +
            tr("y от ${Printer.number(ans.y0.toDouble())} до ${Printer.number(ans.y1.toDouble())}", "y from ${Printer.number(ans.y0.toDouble())} to ${Printer.number(ans.y1.toDouble())}")
        is Answer.Surface -> tr("Поверхность z(${ans.xv}, ${ans.yv}): ${ans.xv}, ${ans.yv} от ${Printer.number(ans.x0.toDouble())} до ${Printer.number(ans.x1.toDouble())}, ", "Surface z(${ans.xv}, ${ans.yv}): ${ans.xv}, ${ans.yv} from ${Printer.number(ans.x0.toDouble())} to ${Printer.number(ans.x1.toDouble())}, ") +
            tr("z от ${Printer.number(ans.z0.toDouble())} до ${Printer.number(ans.z1.toDouble())}", "z from ${Printer.number(ans.z0.toDouble())} to ${Printer.number(ans.z1.toDouble())}")
    }
    var reporting by remember { mutableStateOf(false) }
    var expected by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (a.error) tr("Не получилось", "Did not work") else tr("Ответ", "Answer")) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (a.answer as? Answer.Surface)?.let { SurfaceView(it) }
                if (reporting) {
                    OutlinedTextField(
                        expected,
                        { expected = it },
                        singleLine = true,
                        label = { Text(tr("Каким должен быть ответ", "What the answer should be")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        tr("Страница сохранится как пример для проверки распознавания: штрихи, прочтения и верный ответ. Файл можно отправить разработчику.", "The page is saved as an example for testing recognition: strokes, readings and the right answer. The file can be sent to the developer."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(enabled = expected.isNotBlank(), onClick = {
                        c.reportWrongAnswer(context, a, expected.trim()) { uri ->
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("application/json")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(android.content.Intent.createChooser(send, tr("Отправить пример", "Send example")))
                        }
                        onDismiss()
                    }) { Text(tr("Сохранить пример", "Save example")) }
                }
                Text(text, style = MaterialTheme.typography.titleMedium)
                val steps = a.steps
                if (!steps.isNullOrEmpty()) {
                    HorizontalDivider()
                    Text(tr("Решение", "Solution"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    steps.forEachIndexed { i, s -> Text("${i + 1}. $s") }
                } else if (a.answer !is Answer.Graph && a.answer !is Answer.Surface && !a.error) {
                    Text(
                        tr("Ход решения показывается для линейных и квадратных уравнений и линейных систем; ", "Steps are shown for linear and quadratic equations and linear systems; ") +
                            tr("остальное считается численно.", "everything else is computed numerically."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(tr("Закрыть", "Close")) } },
        dismissButton = {
            Column {
                if (!reporting && a.answer !is Answer.Graph && a.answer !is Answer.Surface) {
                    TextButton(onClick = { reporting = true }) { Text(tr("Неверно…", "Wrong…")) }
                }
                TextButton(onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText(tr("Ответ", "Answer"), (listOf(text) + a.steps.orEmpty()).joinToString("\n")))
                    c.showStatus(tr("Ответ скопирован", "Answer copied"))
                    onDismiss()
                }) { Text(tr("Копировать", "Copy")) }
                if (a.answer !is Answer.Graph && a.answer !is Answer.Surface) {
                    TextButton(onClick = {
                        c.toggleFractionAnswers()
                        onDismiss()
                    }) { Text(if (c.fractionAnswers) tr("Показывать десятичными", "Show as decimals") else tr("Показывать дробями", "Show as fractions")) }
                }
            }
        },
    )
}

/** Handwriting read from a lasso selection: editable, to copy elsewhere. */
@Composable
fun RecognizedTextDialog(initial: String, c: EditorController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Распознанный текст", "Recognised text")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp),
                placeholder = { Text(tr("Ничего не распознано", "Nothing recognised")) },
            )
        },
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText(tr("Текст", "Text"), text))
                c.showStatus(tr("Текст скопирован", "Text copied"))
                onDismiss()
            }) { Text(tr("Копировать", "Copy")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(tr("Закрыть", "Close")) }
                TextButton(enabled = text.isNotBlank() && c.selectionCount > 0, onClick = { c.replaceSelectionWithText(text) }) { Text(tr("Заменить почерк текстом", "Replace handwriting with text")) }
            }
        },
    )
}


/** A surface that turns under the finger: sideways around the vertical, up and down to look from above. */
@Composable
private fun SurfaceView(s: Answer.Surface) {
    var azimuth by remember { mutableStateOf(dev.inknotes.math.Plot3D.AZIMUTH) }
    var elevation by remember { mutableStateOf(dev.inknotes.math.Plot3D.ELEVATION) }
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().aspectRatio(dev.inknotes.math.Plot.WIDTH / dev.inknotes.math.Plot.HEIGHT)
            .pointerInput(s) {
                detectDragGestures { _, drag ->
                    azimuth = (azimuth - drag.x * 0.4f) % 360f
                    elevation = (elevation + drag.y * 0.3f).coerceIn(-10f, 85f)
                }
            },
    ) {
        drawIntoCanvas { canvas ->
            dev.inknotes.editor.SurfaceArt.draw(canvas.nativeCanvas, s, size.width, color, dark, azimuth, elevation)
        }
    }
    Text(tr("Поверните пальцем", "Turn with a finger"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
