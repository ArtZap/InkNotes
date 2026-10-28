package dev.inknotes.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.editor.Stamps

/** Rows × columns with a live preview of the grid. */
@Composable
fun TableDialog(onDismiss: () -> Unit, onInsert: (rows: Int, cols: Int) -> Unit) {
    var rows by remember { mutableIntStateOf(4) }
    var cols by remember { mutableIntStateOf(3) }
    val line = MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Таблица") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper("Строк", rows, 1..30) { rows = it }
                Stepper("Столбцов", cols, 1..10) { cols = it }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    val cw = size.width / cols
                    val rh = minOf(size.height / rows, cw * 0.6f)
                    val stroke = 1.5.dp.toPx()
                    for (r in 0..rows) drawLine(line, androidx.compose.ui.geometry.Offset(0f, r * rh), androidx.compose.ui.geometry.Offset(size.width, r * rh), stroke)
                    for (c in 0..cols) drawLine(line, androidx.compose.ui.geometry.Offset(c * cw, 0f), androidx.compose.ui.geometry.Offset(c * cw, rows * rh), stroke)
                }
                Text(
                    "Таблица рисуется линиями текущего цвета и толщины ручки. После вставки она выделена: перетащите её на место, уголком измените размер.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onInsert(rows, cols) }) { Text("Вставить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(110.dp))
        IconAction(InkIcons.Minus, "Меньше", { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first)
        Text("$value", Modifier.width(40.dp), textAlign = TextAlign.Center, fontSize = 18.sp)
        IconAction(InkIcons.Add, "Больше", { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last)
    }
}

/** Grid of ready-made drawings; a tap places the stamp on the page. */
@Composable
fun StampDialog(onDismiss: () -> Unit, onPick: (Stamps.Stamp) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Штампы") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.height(520.dp),
            ) {
                for ((category, stamps) in Stamps.CATEGORIES) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = category) {
                        Text(category, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(stamps, key = { it.title }) { stamp ->
                    Column(
                        Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = stamp.title) { onPick(stamp) }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Canvas(
                            Modifier
                                .size(72.dp)
                                .aspectRatio(1f)
                        ) {
                            val k = size.width / 100f
                            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            for (p in stamp.paths) {
                                val path = Path().apply {
                                    moveTo(p[0] * k, p[1] * k)
                                    for (i in 2 until p.size step 2) lineTo(p[i] * k, p[i + 1] * k)
                                }
                                drawPath(path, ink, style = stroke)
                            }
                            for (p in stamp.solids) {
                                val path = Path().apply {
                                    moveTo(p[0] * k, p[1] * k)
                                    for (i in 2 until p.size step 2) lineTo(p[i] * k, p[i + 1] * k)
                                    close()
                                }
                                drawPath(path, ink)
                            }
                        }
                        Text(
                            stamp.title,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
