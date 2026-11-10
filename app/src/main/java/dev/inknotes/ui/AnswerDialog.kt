package dev.inknotes.ui

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
        is Answer.Graph -> "График: ${ans.variable} от ${Printer.number(ans.x0.toDouble())} до ${Printer.number(ans.x1.toDouble())}, " +
            "y от ${Printer.number(ans.y0.toDouble())} до ${Printer.number(ans.y1.toDouble())}"
        is Answer.Surface -> "Поверхность z(${ans.xv}, ${ans.yv}): ${ans.xv}, ${ans.yv} от ${Printer.number(ans.x0.toDouble())} до ${Printer.number(ans.x1.toDouble())}, " +
            "z от ${Printer.number(ans.z0.toDouble())} до ${Printer.number(ans.z1.toDouble())}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (a.error) "Не получилось" else "Ответ") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (a.answer as? Answer.Surface)?.let { SurfaceView(it) }
                Text(text, style = MaterialTheme.typography.titleMedium)
                val steps = a.steps
                if (!steps.isNullOrEmpty()) {
                    HorizontalDivider()
                    Text("Решение", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    steps.forEachIndexed { i, s -> Text("${i + 1}. $s") }
                } else if (a.answer !is Answer.Graph && a.answer !is Answer.Surface && !a.error) {
                    Text(
                        "Ход решения показывается для линейных и квадратных уравнений и линейных систем; " +
                            "остальное считается численно.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = {
            Column {
                TextButton(onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("Ответ", (listOf(text) + a.steps.orEmpty()).joinToString("\n")))
                    c.showStatus("Ответ скопирован")
                    onDismiss()
                }) { Text("Копировать") }
                if (a.answer !is Answer.Graph && a.answer !is Answer.Surface) {
                    TextButton(onClick = {
                        c.toggleFractionAnswers()
                        onDismiss()
                    }) { Text(if (c.fractionAnswers) "Показывать десятичными" else "Показывать дробями") }
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
        title = { Text("Распознанный текст") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp),
                placeholder = { Text("Ничего не распознано") },
            )
        },
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Текст", text))
                c.showStatus("Текст скопирован")
                onDismiss()
            }) { Text("Копировать") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Закрыть") }
                TextButton(enabled = text.isNotBlank() && c.selectionCount > 0, onClick = { c.replaceSelectionWithText(text) }) { Text("Заменить почерк текстом") }
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
    Text("Поверните пальцем", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
