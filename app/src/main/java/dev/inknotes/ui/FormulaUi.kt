package dev.inknotes.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.math.Typeset
import kotlin.math.ceil

/** A formula set in print drawn on a bitmap (serif letters, italic variables). */
object FormulaArt {
    private val upright = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val italic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    val measure = Typeset.Measure { text, size, it ->
        synchronized(textPaint) {
            textPaint.typeface = if (it) italic else upright
            textPaint.textSize = size
            textPaint.measureText(text)
        }
    }

    /** [source] at [letterPx] pixels a letter in [color] on a see-through picture; null when empty. */
    fun bitmap(source: String, letterPx: Float, color: Int): Bitmap? {
        val laid = runCatching { Typeset.layout(source, letterPx, measure) }.getOrNull() ?: return null
        if (laid.width <= 0f || laid.ops.isEmpty()) return null
        val pad = letterPx * 0.25f
        val w = ceil(laid.width + 2 * pad).toInt().coerceIn(1, MAX_SIDE)
        val h = ceil(laid.height + 2 * pad).toInt().coerceIn(1, MAX_SIDE)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(pad, pad + laid.ascent)
        draw(canvas, laid.ops, color)
        return bmp
    }

    fun draw(canvas: Canvas, ops: List<Typeset.Op>, color: Int) = synchronized(textPaint) {
        textPaint.color = color
        linePaint.color = color
        for (op in ops) {
            when (op) {
                is Typeset.Op.Text -> {
                    textPaint.typeface = if (op.italic) italic else upright
                    textPaint.textSize = op.size
                    canvas.drawText(op.text, op.x, op.y, textPaint)
                }
                is Typeset.Op.Line -> {
                    linePaint.strokeWidth = op.width
                    canvas.drawLine(op.x1, op.y1, op.x2, op.y2, linePaint)
                }
                is Typeset.Op.Poly -> {
                    linePaint.strokeWidth = op.width
                    val p = Path()
                    p.moveTo(op.points[0], op.points[1])
                    for (k in 2 until op.points.size step 2) p.lineTo(op.points[k], op.points[k + 1])
                    canvas.drawPath(p, linePaint)
                }
            }
        }
    }

    private const val MAX_SIDE = 4096
}

/**
 * A handwritten formula read and set in print: the picture, its LaTeX (which can be corrected
 * by hand: the picture follows), copied as LaTeX or as a picture, or put on the page in place
 * of the handwriting or under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormulaDialog(
    initial: String,
    readFailed: Boolean,
    onDismiss: () -> Unit,
    onCopyLatex: (String) -> Unit,
    onCopyPicture: (String) -> Unit,
    onReplace: (String) -> Unit,
    onBelow: (String) -> Unit,
) {
    var latex by remember { mutableStateOf(initial) }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val letterPx = with(LocalDensity.current) { 26.dp.toPx() }
    val preview = remember(latex, color) { FormulaArt.bitmap(latex, letterPx, color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Формула") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (readFailed) {
                    Text(
                        "Формулу прочитать не удалось. Напишите её в LaTeX сами — картинка появится сразу.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (preview != null) {
                        Image(preview.asImageBitmap(), contentDescription = "Формула: $latex")
                    } else {
                        Text("Пусто", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(
                    value = latex,
                    onValueChange = { latex = it },
                    label = { Text("LaTeX") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Проверьте: если буква прочиталась не так, исправьте её в строке LaTeX. \\frac{a}{b} — дробь, x^{2} — степень, \\sqrt{x} — корень.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = latex.isNotBlank(), onClick = { onCopyLatex(latex) }) { Text("Копировать LaTeX") }
                    OutlinedButton(enabled = preview != null, onClick = { onCopyPicture(latex) }) { Text("Копировать картинкой") }
                    OutlinedButton(enabled = preview != null, onClick = { onBelow(latex) }) { Text("Вставить под ней") }
                }
            }
        },
        confirmButton = { Button(enabled = preview != null, onClick = { onReplace(latex) }) { Text("Заменить почерк") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
