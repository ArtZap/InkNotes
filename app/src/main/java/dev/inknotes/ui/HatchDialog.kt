package dev.inknotes.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import dev.inknotes.data.FillPattern
import dev.inknotes.data.HatchStyle
import dev.inknotes.data.LineType
import dev.inknotes.editor.Hatch
import dev.inknotes.editor.InkColors
import dev.inknotes.editor.PaperStyle
import kotlin.math.roundToInt

/** Page units per dp in previews, close to a page fitted to a tablet screen. */
private const val PREVIEW_DP_PER_UNIT = 1.4f
private const val SWATCH_DP_PER_UNIT = 0.6f

/** Draws [pattern] with [style] over the whole draw area, clipped to [clip] if given. */
private fun DrawScope.drawPattern(pattern: FillPattern, style: HatchStyle, color: Int, paint: Paint, clip: Path? = null, dpPerUnit: Float = PREVIEW_DP_PER_UNIT) {
    val k = dpPerUnit.dp.toPx()
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        clip?.let(native::clipPath)
        native.scale(k, k)
        val bounds = RectF(0f, 0f, size.width / k, size.height / k)
        Hatch.draw(native, bounds, 0f, 0f, pattern, style, color, 255, paint)
        native.restore()
    }
}

/** Small square preview of a fill pattern drawn with the current settings. */
@Composable
fun PatternSwatch(pattern: FillPattern, style: HatchStyle, color: Int, selected: Boolean, label: String = pattern.title, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val paint = remember { Paint() }
    Canvas(
        Modifier
            .size(36.dp)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape,
            )
            .padding(4.dp)
            .clip(shape)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        // Smaller than the preview so a few repeats of the pattern fit in the swatch.
        drawPattern(pattern, style, color, paint, dpPerUnit = SWATCH_DP_PER_UNIT)
    }
}

/**
 * Pattern and hatch settings with a live preview. Changes apply at once to new fills;
 * when [selectedFills] > 0 the dialog also offers to restyle the lasso selection.
 */
@Composable
fun HatchDialog(c: EditorController, onDismiss: () -> Unit) {
    val style = c.fillStyle
    // On the notebook's own paper, which may be dark in the light theme or the other way round.
    val color = InkColors.forPaper(c.fillColor, c.paperDark)
    val paint = remember { Paint() }
    val set = { s: HatchStyle -> c.changeFillStyle(s) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Штриховка") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val paper = PaperStyle.of(c.dark, c.notebook.background(c.dark).color).paper
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                ) {
                    drawRect(androidx.compose.ui.graphics.Color(paper))
                    val blob = Path().apply {
                        addOval(RectF(size.width * 0.06f, size.height * 0.1f, size.width * 0.94f, size.height * 0.9f), Path.Direction.CW)
                    }
                    drawPattern(c.fillPattern, style, color, paint, blob)
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (p in FillPattern.entries) {
                        PatternSwatch(p, style, color, selected = c.fillPattern == p) { c.selectFillPattern(p) }
                    }
                }
                Text(c.fillPattern.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val lined = c.fillPattern != FillPattern.SOLID && c.fillPattern != FillPattern.INK
                val withLines = c.fillPattern in LINE_PATTERNS
                Text("Тип линии", color = if (withLines) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (t in LineType.entries) {
                        LineTypeChip(t, selected = style.lineType == t, enabled = withLines, color = color, paint = paint) {
                            set(style.copy(lineType = t))
                        }
                    }
                }
                SettingSlider("Толщина", "%.1f".format(style.width), style.width, HatchStyle.MIN_WIDTH..HatchStyle.MAX_WIDTH, lined && c.fillPattern != FillPattern.CHECKER) {
                    set(style.copy(width = it))
                }
                SettingSlider("Расстояние", "%.0f".format(style.spacing), style.spacing, HatchStyle.MIN_SPACING..HatchStyle.MAX_SPACING, lined) {
                    set(style.copy(spacing = it))
                }
                SettingSlider("Угол", "${style.angle.roundToInt()}°", style.angle, 0f..179f, lined) {
                    set(style.copy(angle = it.roundToInt().toFloat()))
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in listOf(0, 30, 45, 60, 90, 120, 135, 150)) {
                        FilterChip(
                            selected = style.angle.roundToInt() == a,
                            enabled = lined,
                            onClick = { set(style.copy(angle = a.toFloat())) },
                            label = { Text("$a°") },
                        )
                    }
                }
                SettingSlider("Масштаб", "×%.1f".format(style.scale), style.scale, HatchStyle.MIN_SCALE..HatchStyle.MAX_SCALE, lined) {
                    set(style.copy(scale = it))
                }
                SettingSlider("Непрозрачность", "${(style.opacity * 100).roundToInt()}%", style.opacity, HatchStyle.MIN_OPACITY..1f, true) {
                    set(style.copy(opacity = it))
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.selectedFills > 0) {
                    TextButton(onClick = {
                        c.restyleSelection()
                        onDismiss()
                    }) { Text("К выделенному (${c.selectedFills})") }
                }
                Button(onClick = onDismiss) { Text("Готово") }
            }
        },
        dismissButton = { TextButton(onClick = { set(HatchStyle.DEFAULT) }) { Text("Сбросить") } },
    )
}

/** Patterns made of lines, where the line type applies. */
private val LINE_PATTERNS = setOf(FillPattern.LINES, FillPattern.CROSS, FillPattern.WAVES, FillPattern.ZIGZAG, FillPattern.BRICKS)

/** Line type option: a few horizontal sample rows in that style above its name. */
@Composable
private fun LineTypeChip(type: LineType, selected: Boolean, enabled: Boolean, color: Int, paint: Paint, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .width(96.dp)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape,
            )
            .clip(shape)
            .clickable(enabled = enabled, onClickLabel = type.title, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val sample = HatchStyle(width = 1.4f, spacing = 9f, angle = 0f, lineType = type)
            drawPattern(FillPattern.LINES, sample, if (enabled) color else android.graphics.Color.GRAY, paint, dpPerUnit = 0.8f)
        }
        Text(
            type.title,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SettingSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(130.dp))
        InkSlider(
            value = current.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(value, Modifier.width(56.dp).padding(start = 8.dp))
    }
}
