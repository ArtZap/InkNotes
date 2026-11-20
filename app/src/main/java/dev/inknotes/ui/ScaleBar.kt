package dev.inknotes.ui

import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.tr
import dev.inknotes.math.Printer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/** [v] rounded to whole [step]s, without the binary tail (0.30000000000000004). */
internal fun roundToStep(v: Double, step: Double): Double {
    val places = max(0, -floor(log10(step) + 1e-9).toInt())
    return BigDecimal(Math.rint(v / step) * step).setScale(places, RoundingMode.HALF_UP).toDouble()
}

private fun shown(v: Double) = runCatching { Printer.number(v) }.getOrDefault("?")

/**
 * The scale of a number given to a letter (a = 3), under the page: drag the ruler (it runs on
 * by itself after a flick) or tap − / +; the answers and graphs that use the letter change at
 * once. The step is the last written digit; ×10 / ÷10 change it.
 */
@Composable
fun ScaleBar(c: EditorController, s: EditorController.Scaling, modifier: Modifier = Modifier) {
    val knob = s.knob
    var step by remember(knob.key) { mutableStateOf(knob.step) }
    // Turned back to the written number, or the step changed: the ruler starts again from here.
    var epoch by remember(knob.key) { mutableIntStateOf(0) }
    val base = remember(knob.key, step, epoch) { s.value }
    var pos by remember(knob.key, step, epoch) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val tick = with(LocalDensity.current) { 14.dp.toPx() }
    var running by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(knob.key, step, epoch) {
        snapshotFlow { pos.roundToInt() }.collect { n ->
            val v = roundToStep(base + n * step, step)
            if (v != c.scaling?.value) {
                c.turnTo(v)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    fun moveBy(marks: Int) {
        running?.cancel()
        running = scope.launch {
            animate(pos, (pos.roundToInt() + marks).toFloat(), animationSpec = spring()) { v, _ -> pos = v }
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 620.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${knob.name} = ${shown(s.value)}", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                if (s.value != knob.written) {
                    Text(
                        tr("  написано ${shown(knob.written)}", "  written ${shown(knob.written)}"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { moveBy(-1) }) { Text("−", fontSize = 20.sp) }
                TextButton(onClick = { moveBy(1) }) { Text("+", fontSize = 20.sp) }
            }
            val onSurface = MaterialTheme.colorScheme.onSurface
            val faint = MaterialTheme.colorScheme.onSurfaceVariant
            val accent = MaterialTheme.colorScheme.primary
            val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }
            val labelSize = with(LocalDensity.current) { 11.sp.toPx() }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { d -> pos -= d / tick },
                        onDragStarted = { running?.cancel() },
                        onDragStopped = { velocity ->
                            running = scope.launch {
                                var at = pos
                                animateDecay(pos, -velocity / tick, FloatExponentialDecaySpec(frictionMultiplier = 2.5f)) { v, _ ->
                                    pos = v
                                    at = v
                                }
                                animate(at, at.roundToInt().toFloat(), animationSpec = spring()) { v, _ -> pos = v }
                            }
                        },
                    ),
            ) {
                val cx = size.width / 2
                val half = size.width / 2 / tick
                val first = floor(pos - half).toInt()
                val last = ceil(pos + half).toInt()
                labelPaint.textSize = labelSize
                labelPaint.color = faint.toArgb()
                for (i in first..last) {
                    val x = cx + (i - pos) * tick
                    val major = Math.floorMod(i, 10) == 0
                    val mid = Math.floorMod(i, 5) == 0
                    val h = when {
                        major -> size.height * 0.45f
                        mid -> size.height * 0.32f
                        else -> size.height * 0.2f
                    }
                    drawLine(if (major) onSurface else faint, Offset(x, 0f), Offset(x, h), strokeWidth = if (major) 2f else 1.2f)
                    if (major) {
                        val label = shown(roundToStep(base + i * step, step))
                        drawIntoCanvas { it.nativeCanvas.drawText(label, x, size.height - 4f, labelPaint) }
                    }
                }
                // Where the value is read: a mark in the middle.
                drawLine(accent, Offset(cx, 0f), Offset(cx, size.height * 0.7f), strokeWidth = 5f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Шаг ${shown(step)}", "Step ${shown(step)}"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { if (step < 1e6) step = roundToStep(step * 10, step) }) { Text("×10") }
                TextButton(onClick = { if (step > 1e-6) step = roundToStep(step / 10, step / 10) }) { Text("÷10") }
                Spacer(Modifier.weight(1f))
                if (s.value != knob.written) {
                    TextButton(onClick = {
                        running?.cancel()
                        c.resetScaling()
                        epoch++
                    }) { Text(tr("Как написано", "As written")) }
                }
                TextButton(onClick = { c.endScaling() }) { Text(tr("Готово", "Done")) }
            }
        }
    }
}
