package dev.inknotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.tr
import dev.inknotes.math.GraphMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The view of a graph's window: x from [x0] to [x1], y from [y0] to [y1]. */
private data class View(val x0: Double, val x1: Double, val y0: Double, val y1: Double) {
    val w get() = x1 - x0
    val h get() = y1 - y0
}

/** Curve colours: the first in the answers' blue, the rest as on the page's graphs. */
private fun curveColor(k: Int, dark: Boolean): Color = Color(
    when (k) {
        0 -> if (dark) 0xFF82AAFF.toInt() else 0xFF1E5BD8.toInt()
        1 -> if (dark) 0xFFF0A23A.toInt() else 0xFFC77700.toInt()
        2 -> if (dark) 0xFF5BC98A.toInt() else 0xFF2E8B57.toInt()
        else -> if (dark) 0xFFE57AB0.toInt() else 0xFFB0306E.toInt()
    },
)

/**
 * A graph from the page in a window of its own, over the page: moved by its title, sized by
 * its corner. A tap on the graph lets the fingers work it — the page stays still; one finger
 * moves the view, two zoom it, a tap shows the value there, a long press (or a tap with
 * «Точка» on) puts a point. Where curves cross each other and the axes is marked and signed.
 */
@Composable
fun GraphWindow(c: EditorController, win: EditorController.GraphWin, modifier: Modifier = Modifier) {
    val g = win.graph
    val fns = g.fns
    val screen = LocalDensity.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val start = remember(win) { View(g.x0.toDouble(), g.x1.toDouble(), g.y0.toDouble(), g.y1.toDouble()) }
    var view by remember(win) { mutableStateOf(start) }
    var pos by remember(win) { mutableStateOf(Offset(with(screen) { 24.dp.toPx() }, with(screen) { 96.dp.toPx() })) }
    var wDp by remember(win) { mutableStateOf(460f) }
    var hDp by remember(win) { mutableStateOf(380f) }
    var active by remember(win) { mutableStateOf(false) }
    var pointMode by remember(win) { mutableStateOf(false) }
    // Where a finger asked the value: x and the curve.
    var probe by remember(win) { mutableStateOf<Pair<Double, Int>?>(null) }
    var points by remember(win) { mutableStateOf(c.graphPoints(win)) }
    var marks by remember(win) { mutableStateOf(emptyList<GraphMath.Mark>()) }
    var area by remember(win) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // Crossings for what is in view, once the view stops moving.
    LaunchedEffect(win, view.x0, view.x1) {
        delay(120)
        val v = view
        marks = withContext(Dispatchers.Default) { runCatching { GraphMath.marks(fns, v.x0, v.x1) }.getOrDefault(emptyList()) }
    }

    fun toX(px: Float) = view.x0 + px / area.width * view.w
    fun toY(py: Float) = view.y1 - py / area.height * view.h
    fun pxX(x: Double) = ((x - view.x0) / view.w * area.width).toFloat()
    fun pxY(y: Double) = ((view.y1 - y) / view.h * area.height).toFloat()
    val grab = with(screen) { 22.dp.toPx() }

    fun putPoint(o: Offset) {
        // On a curve when one is near: the point is exactly on it.
        val x = toX(o.x)
        val y = toY(o.y)
        val near = GraphMath.nearest(fns, x, y, grab / area.height * view.h)
        points = points + (x to (near?.second ?: y))
        c.setGraphPoints(win, points)
    }

    fun tap(o: Offset) {
        if (!active) {
            active = true
            return
        }
        val hit = points.indexOfFirst { (x, y) -> hypot(pxX(x) - o.x, pxY(y) - o.y) < grab }
        if (pointMode) {
            if (hit >= 0) points = points.filterIndexed { i, _ -> i != hit } else return putPoint(o)
            c.setGraphPoints(win, points)
            return
        }
        // A crossing near the finger: its exact place.
        val mark = marks.minByOrNull { hypot(pxX(it.x) - o.x, pxY(it.y) - o.y) }?.takeIf { hypot(pxX(it.x) - o.x, pxY(it.y) - o.y) < grab }
        if (mark != null) {
            probe = mark.x to mark.a
            return
        }
        val x = toX(o.x)
        // The curve under the finger, or the nearest one at that x.
        val near = GraphMath.nearest(fns, x, toY(o.y), grab / area.height * view.h)
            ?: GraphMath.nearest(fns, x, toY(o.y), Double.MAX_VALUE)
        probe = near?.let { x to it.first }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 10.dp,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
            .size(wDp.dp, hDp.dp),
    ) {
        Column {
            // The title bar: dragging it moves the window.
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(win) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            pos = Offset((pos.x + drag.x).coerceAtLeast(0f), (pos.y + drag.y).coerceAtLeast(0f))
                        }
                    }
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    g.titles.joinToString("; ").ifEmpty { tr("График", "Graph") },
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    TextButton(onClick = { pointMode = !pointMode }) {
                        Text(if (pointMode) tr("✓ Точка", "✓ Point") else tr("Точка", "Point"), fontSize = 13.sp)
                    }
                }
                TextButton(onClick = {
                    view = start
                    probe = null
                }) { Text(tr("Вписать", "Fit"), fontSize = 13.sp) }
                // Moved or sized on the page by hand: back where it was drawn.
                if (!active && c.graphPlaced(win)) {
                    TextButton(onClick = { c.resetGraphPlace(win) }) { Text(tr("На место", "Reset"), fontSize = 13.sp) }
                }
                if (active) {
                    TextButton(onClick = {
                        active = false
                        pointMode = false
                    }) { Text(tr("Готово", "Done"), fontSize = 13.sp) }
                }
                TextButton(onClick = { c.closeGraph() }) { Text("✕", fontSize = 15.sp) }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val surface = MaterialTheme.colorScheme.surface
                val ink = MaterialTheme.colorScheme.onSurface
                val faint = MaterialTheme.colorScheme.outlineVariant
                val accent = MaterialTheme.colorScheme.primary
                val text = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
                val textSize = with(screen) { 11.sp.toPx() }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { area = it }
                        .pointerInput(win, active) {
                            if (!active) return@pointerInput
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (area.width == 0 || area.height == 0) return@detectTransformGestures
                                val v = view
                                val cx = v.x0 + centroid.x / area.width * v.w
                                val cy = v.y1 - centroid.y / area.height * v.h
                                val k = zoom.coerceIn(0.5f, 2f).toDouble()
                                val w = (v.w / k).coerceIn(1e-6, 1e7)
                                val h = (v.h / k).coerceIn(1e-6, 1e7)
                                val dx = -pan.x / area.width * w
                                val dy = pan.y / area.height * h
                                val nx0 = cx - (cx - v.x0) / v.w * w + dx
                                val ny0 = cy - (cy - v.y0) / v.h * h + dy
                                view = View(nx0, nx0 + w, ny0, ny0 + h)
                            }
                        }
                        .pointerInput(win, active, pointMode, points, marks, view) {
                            detectTapGestures(
                                onTap = { tap(it) },
                                onLongPress = { if (active) putPoint(it) },
                            )
                        }

                ) {
                    val v = view
                    drawRect(surface)
                    val backing = surface.copy(alpha = 0.85f).toArgb()
                    text.textSize = textSize
                    text.color = ink.copy(alpha = 0.7f).toArgb()
                    // Grid and the numbers on it.
                    val sx = GraphMath.step(v.w, (size.width / (70 * screen.density)).roundToInt().coerceIn(3, 12))
                    val sy = GraphMath.step(v.h, (size.height / (50 * screen.density)).roundToInt().coerceIn(3, 10))
                    val axisY = pxY(0.0).coerceIn(0f, size.height - textSize - 2)
                    val axisX = pxX(0.0).coerceIn(0f, size.width - 4 * textSize)
                    for (x in GraphMath.ticks(v.x0, v.x1, sx)) {
                        val px = pxX(x)
                        drawLine(faint, Offset(px, 0f), Offset(px, size.height), 1f)
                        if (x != 0.0) drawIntoCanvas { it.nativeCanvas.drawText(GraphMath.label(x, sx), px + 3, axisY + textSize + 1, text) }
                    }
                    for (y in GraphMath.ticks(v.y0, v.y1, sy)) {
                        val py = pxY(y)
                        drawLine(faint, Offset(0f, py), Offset(size.width, py), 1f)
                        if (y != 0.0) drawIntoCanvas { it.nativeCanvas.drawText(GraphMath.label(y, sy), axisX + 4, py - 3, text) }
                    }
                    if (0.0 in v.y0..v.y1) drawLine(ink, Offset(0f, pxY(0.0)), Offset(size.width, pxY(0.0)), 2f)
                    if (0.0 in v.x0..v.x1) drawLine(ink, Offset(pxX(0.0), 0f), Offset(pxX(0.0), size.height), 2f)
                    // The curves.
                    val curves = runCatching { GraphMath.curves(fns, v.x0, v.x1, v.y0, v.y1, (size.width / 2).toInt().coerceIn(200, 900)) }.getOrDefault(emptyList())
                    for ((i, parts) in curves.withIndex()) {
                        val color = curveColor(i, dark)
                        for (seg in parts) {
                            val p = Path()
                            for (k in seg.indices step 2) {
                                val x = seg[k] * size.width
                                val y = (1 - seg[k + 1]) * size.height
                                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                            }
                            drawPath(p, color, style = Stroke(width = 2.5f * screen.density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    // Crossings and where the curves meet the axes, signed.
                    var signed = 0
                    for (m in marks) {
                        if (m.y !in v.y0..v.y1 || m.x !in v.x0..v.x1) continue
                        val p = Offset(pxX(m.x), pxY(m.y))
                        drawCircle(surface, 5f * screen.density, p)
                        drawCircle(if (m.kind == GraphMath.Kind.CROSS) ink else curveColor(m.a, dark), 5f * screen.density, p, style = Stroke(2f * screen.density))
                        if (signed++ < 12) label(text, "(${GraphMath.label(m.x, sx)}; ${GraphMath.label(m.y, sy)})", p, ink.toArgb(), backing)
                    }
                    // The writer's points.
                    for ((x, y) in points) {
                        if (x !in v.x0..v.x1 || y !in v.y0..v.y1) continue
                        val p = Offset(pxX(x), pxY(y))
                        drawCircle(accent, 5.5f * screen.density, p)
                        label(text, "(${GraphMath.label(x, sx)}; ${GraphMath.label(y, sy)})", p, accent.toArgb(), backing)
                    }
                    // The value a finger asked for.
                    probe?.let { (x, i) ->
                        val y = runCatching { fns[i](x) }.getOrDefault(Double.NaN)
                        if (!y.isFinite()) return@let
                        val p = Offset(pxX(x), pxY(y))
                        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        drawLine(ink.copy(alpha = 0.6f), Offset(p.x, 0f), Offset(p.x, size.height), 1.5f, pathEffect = dash)
                        drawLine(ink.copy(alpha = 0.6f), Offset(0f, p.y), Offset(size.width, p.y), 1.5f, pathEffect = dash)
                        drawCircle(curveColor(i, dark), 6f * screen.density, p)
                        label(text, "x = ${GraphMath.label(x, sx / 10)}, y = ${GraphMath.label(y, sy / 10)}", p, curveColor(i, dark).toArgb(), backing, bold = true)
                    }
                }
                if (!active) {
                    Text(
                        tr("Коснитесь, чтобы управлять графиком", "Tap to work the graph"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                } else if (pointMode) {
                    Text(
                        tr("Касание ставит точку, касание точки — убирает", "A tap puts a point, a tap on a point takes it away"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                }
                // The corner that sizes the window.
                val grip = MaterialTheme.colorScheme.outline
                Canvas(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .pointerInput(win) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                wDp = (wDp + drag.x / screen.density).coerceIn(260f, 1400f)
                                hDp = (hDp + drag.y / screen.density).coerceIn(200f, 1400f)
                            }
                        },
                ) {
                    for (k in 1..3) {
                        val d = k * size.width / 4
                        drawLine(grip, Offset(size.width - d, size.height - 3), Offset(size.width - 3, size.height - d), 2f)
                    }
                }
            }
        }
    }
}

/** A label beside a point, on a light backing so the grid does not cross it. */
private val labelBack = android.graphics.Paint()

private fun DrawScope.label(paint: android.graphics.Paint, s: String, at: Offset, color: Int, backing: Int, bold: Boolean = false) {
    paint.isFakeBoldText = bold
    val w = paint.measureText(s)
    val x = (at.x + 8f).coerceAtMost(size.width - w - 4f).coerceAtLeast(2f)
    val y = (at.y - 8f).coerceAtLeast(paint.textSize + 2f)
    val back = labelBack.apply { this.color = backing }
    drawIntoCanvas {
        it.nativeCanvas.drawRect(x - 2, y - paint.textSize, x + w + 2, y + 3, back)
        val keep = paint.color
        paint.color = color
        it.nativeCanvas.drawText(s, x, y, paint)
        paint.color = keep
    }
    paint.isFakeBoldText = false
}
