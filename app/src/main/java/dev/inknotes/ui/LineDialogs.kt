package dev.inknotes.ui

import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import dev.inknotes.data.DashPattern
import dev.inknotes.data.DashStyle
import dev.inknotes.data.InkKind
import dev.inknotes.data.Notebook
import dev.inknotes.editor.InkColors
import dev.inknotes.editor.PageEditorView
import dev.inknotes.editor.PaperStyle
import kotlin.math.roundToInt

/** Page units per dp in the dash previews, about a page fitted to a tablet screen. */
private const val DASH_DP_PER_UNIT = 1.4f

/** Lengths of dashes and gaps offered, in millimetres. */
private const val MIN_MM = 0.5f
private const val MAX_MM = 20f

private fun mm(units: Float) = units * Notebook.MM_PER_UNIT
private fun units(mm: Float) = mm / Notebook.MM_PER_UNIT

/** A straight line [width] page units wide in [style] across the draw area. */
private fun DrawScope.drawDashSample(style: DashStyle, width: Float, color: Int, paint: Paint, dpPerUnit: Float = DASH_DP_PER_UNIT) {
    val k = dpPerUnit.dp.toPx()
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.scale(k, k)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        paint.strokeWidth = width
        paint.pathEffect = DashPathEffect(style.intervals(width), 0f)
        val y = size.height / k / 2
        native.drawLine(width, y, size.width / k - width, y, paint)
        native.restore()
    }
}

/**
 * Pattern of dashed lines: marks, dash length and gap, with a preview at the pen's width.
 * [onApply] gets the chosen style; [applyLabel] names what it does.
 */
@Composable
fun DashDialog(c: EditorController, applyLabel: String, onApply: (DashStyle) -> Unit, onDismiss: () -> Unit) {
    var style by remember { mutableStateOf(c.dashStyle) }
    val color = InkColors.forPaper(c.penColor, c.paperDark)
    val width = c.penSize
    val paint = remember { Paint() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пунктир") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val paper = PaperStyle.of(c.dark, c.notebook.background(c.dark).color).paper
                val shape = RoundedCornerShape(8.dp)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(shape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                ) {
                    drawRect(androidx.compose.ui.graphics.Color(paper))
                    drawDashSample(style, width, color, paint)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (p in DashPattern.entries) {
                        FilterChip(
                            selected = style.pattern == p,
                            onClick = { style = style.copy(pattern = p) },
                            label = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Canvas(Modifier.width(64.dp).height(14.dp)) {
                                        drawDashSample(style.copy(pattern = p), 2.4f, color, paint, dpPerUnit = 0.9f)
                                    }
                                    Text(p.title)
                                }
                            },
                        )
                    }
                }
                SettingSlider(
                    "Длина штриха",
                    "%.1f мм".format(mm(style.dash)),
                    mm(style.dash),
                    MIN_MM..MAX_MM,
                    enabled = style.pattern != DashPattern.DOT,
                ) { style = style.copy(dash = units(it)) }
                SettingSlider(
                    "Промежуток",
                    "%.1f мм".format(mm(style.gap)),
                    mm(style.gap),
                    MIN_MM..MAX_MM,
                    enabled = true,
                ) { style = style.copy(gap = units(it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(style.sane())
                onDismiss()
            }) { Text(applyLabel) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { style = DashStyle.DEFAULT }) { Text("Сбросить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** Pen tool with dashes: opens the dash pattern for new lines. */
@Composable
fun DashSettingsButton(c: EditorController) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Узор: ${c.dashStyle.pattern.title}") }
    if (open) DashDialog(c, "Готово", onApply = c::changeDashStyle, onDismiss = { open = false })
}

/** Selection with dashed lines: gives them a pattern (it becomes the pen's too). */
@Composable
fun SelectionDashButton(c: EditorController) {
    if (InkKind.DASHED !in c.selectionLineKinds) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Пунктир…") }
    if (open) {
        DashDialog(c, "Применить", onApply = {
            c.changeDashStyle(it)
            c.applyDashToSelection()
        }, onDismiss = { open = false })
    }
}

/** Width of the selected lines: a slider in a drop-down, one undo step per drag. */
@Composable
fun SelectionWidthButton(c: EditorController) {
    val width = c.selectionLineWidth ?: return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Толщина ${"%.1f".format(width)} ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.width(40.dp).height(40.dp)) {
                    val d = (width * 1.4f * density).coerceIn(2f, size.minDimension)
                    drawCircle(ink, d / 2)
                }
                InkSlider(
                    value = width.coerceIn(PageEditorView.MIN_LINE_WIDTH, MAX_SELECTION_WIDTH),
                    onValueChange = { c.setSelectionLineWidth((it * 10).roundToInt() / 10f) },
                    onValueChangeFinished = { c.endSelectionLineWidth() },
                    valueRange = PageEditorView.MIN_LINE_WIDTH..MAX_SELECTION_WIDTH,
                    modifier = Modifier.width(220.dp),
                )
            }
        }
    }
}

/** The slider's top: lines this wide or wider are rare; the view takes up to its own limit. */
private const val MAX_SELECTION_WIDTH = 30f
