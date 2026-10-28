package dev.inknotes.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Colour picker with two modes: a continuous spectrum (saturation/value square plus hue
 * strip) and a fixed palette of tints and shades.
 */
@Composable
fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var tab by remember { mutableIntStateOf(0) }
    val color = Color.hsv(hue, sat, value)

    fun setColor(c: Color) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), out)
        // Greys have no hue; keep the current one so the strip doesn't jump.
        if (out[1] > 0f) hue = out[0]
        sat = out[1]
        value = out[2]
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Narrower and scrolling on small screens, so «Выбрать» is always reachable.
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f)) {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Цвет", style = MaterialTheme.typography.titleLarge)
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Спектр") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Палитра") })
                }
                if (tab == 0) {
                    SaturationValueSquare(hue, sat, value) { s, v ->
                        sat = s
                        value = v
                    }
                    HueStrip(hue) { hue = it }
                } else {
                    PaletteGrid(selected = color) { setColor(it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "#%06X".format(color.toArgb() and 0xFFFFFF),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onPick(color.toArgb()) }) { Text("Выбрать") }
                }
            }
        }
    }
}

@Composable
private fun SaturationValueSquare(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    fun update(p: Offset, size: IntSize) =
        onChange((p.x / size.width).coerceIn(0f, 1f), (1f - p.y / size.height).coerceIn(0f, 1f))

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { update(it, size) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> update(change.position, size) } }
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(sat * size.width, (1f - value) * size.height)
        drawCircle(Color.Black.copy(alpha = 0.5f), 11.dp.toPx(), c, style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, 10.dp.toPx(), c, style = Stroke(2.5.dp.toPx()))
    }
}

@Composable
private fun HueStrip(hue: Float, onChange: (Float) -> Unit) {
    val rainbow = remember { (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) } }
    fun update(p: Offset, size: IntSize) = onChange((p.x / size.width).coerceIn(0f, 1f) * 359.9f)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures { update(it, size) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> update(change.position, size) } }
    ) {
        drawRect(Brush.horizontalGradient(rainbow))
        val x = hue / 360f * size.width
        drawCircle(Color.White, size.height / 2 - 2.dp.toPx(), Offset(x, size.height / 2), style = Stroke(3.dp.toPx()))
    }
}

private val PALETTE_HUES = listOf(0f, 14f, 28f, 42f, 54f, 75f, 110f, 145f, 175f, 195f, 215f, 235f, 260f, 285f, 320f)

/** (saturation, value) per row: light tints at the top, deep shades at the bottom. */
private val PALETTE_ROWS = listOf(0.22f to 1f, 0.45f to 1f, 0.8f to 0.95f, 0.85f to 0.75f, 0.85f to 0.5f)

@Composable
private fun PaletteGrid(selected: Color, onPick: (Color) -> Unit) {
    val greys = remember { (0..14).map { Color.hsv(0f, 0f, 1f - it / 14f) } }
    Column(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((s, v) in PALETTE_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (h in PALETTE_HUES) PaletteCell(Color.hsv(h, s, v), selected, onPick)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (g in greys) PaletteCell(g, selected, onPick)
        }
    }
}

@Composable
private fun PaletteCell(color: Color, selected: Color, onPick: (Color) -> Unit) {
    val isSelected = color.toArgb() == selected.toArgb()
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                if (isSelected) 3.dp else 1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            )
            .clickable { onPick(color) }
    )
}
