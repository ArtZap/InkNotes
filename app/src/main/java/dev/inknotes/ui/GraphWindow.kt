package dev.inknotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.tr
import dev.inknotes.editor.GraphPainter
import dev.inknotes.math.GraphMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A graph in a window of its own, over the page: moved by its title, sized by its corner.
 * A tap on the graph lets the fingers work it — the page stays still; one finger moves the
 * view, two zoom it, a tap shows the value there, a long press (or a tap with «Точка» on)
 * puts a point. Where curves cross each other and the axes is marked and signed. «В тетрадь»
 * puts what the window shows into the notebook as a picture; a tap on that picture, once it
 * is selected, opens this window again («Сохранить» then updates it).
 */
@Composable
fun GraphWindow(c: EditorController, win: EditorController.GraphWin, modifier: Modifier = Modifier) {
    val spec = win.spec
    val fns = remember(win) { spec.fns() }
    val screen = LocalDensity.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val start = remember(win) { GraphPainter.View(spec.x0, spec.x1, spec.y0, spec.y1) }
    var view by remember(win) { mutableStateOf(start) }
    var pos by remember(win) { mutableStateOf(Offset(with(screen) { 24.dp.toPx() }, with(screen) { 96.dp.toPx() })) }
    var wDp by remember(win) { mutableStateOf(460f) }
    var hDp by remember(win) { mutableStateOf(380f) }
    var active by remember(win) { mutableStateOf(false) }
    var pointMode by remember(win) { mutableStateOf(false) }
    // Where a finger asked the value: x and the curve.
    var probe by remember(win) { mutableStateOf<Pair<Double, Int>?>(null) }
    var points by remember(win) { mutableStateOf(spec.points) }
    var marks by remember(win) { mutableStateOf(emptyList<GraphMath.Mark>()) }
    var area by remember(win) { mutableStateOf(IntSize.Zero) }
    val painter = remember(screen) { GraphPainter(screen.density) }
    // The writer's point a finger picked (to remove it), or -1.
    var picked by remember(win) { mutableStateOf(-1) }
    // A surface: how it is turned and tilted, and how wide a square it covers.
    var azimuth by remember(win) { mutableStateOf(spec.azimuth) }
    var elevation by remember(win) { mutableStateOf(spec.elevation) }
    var range by remember(win) { mutableStateOf(spec.x1) }
    // One to one: cells square, both axes stretch alike.
    var square by remember(win) { mutableStateOf(spec.square) }
    val surface = remember(win, range) { if (spec.isSurface) runCatching { spec.surface(range) }.getOrNull() else null }

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

    fun pointAt(o: Offset) = points.indexOfFirst { (x, y) -> hypot(pxX(x) - o.x, pxY(y) - o.y) < grab }

    fun removePoint(i: Int) {
        points = points.filterIndexed { k, _ -> k != i }
        picked = -1
    }

    fun putPoint(o: Offset) {
        // A point already there: a long press takes it away.
        val at = pointAt(o)
        if (at >= 0) return removePoint(at)
        // On a curve when one is near: the point is exactly on it.
        val x = toX(o.x)
        val y = toY(o.y)
        val near = GraphMath.nearest(fns, x, y, grab / area.height * view.h)
        points = points + (x to (near?.second ?: y))
    }

    fun tap(o: Offset) {
        if (!active) {
            active = true
            return
        }
        if (spec.isSurface) return
        val hit = pointAt(o)
        if (pointMode) {
            if (hit >= 0) removePoint(hit) else putPoint(o)
            return
        }
        // One's own point: picked, to remove it.
        if (hit >= 0) {
            picked = hit
            probe = null
            return
        }
        picked = -1
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

    /** The view with a unit on y as long as a unit on x, around the middle of y. */
    fun squared(v: GraphPainter.View): GraphPainter.View {
        if (area.width == 0 || area.height == 0) return v
        val h = v.w / area.width * area.height
        val mid = (v.y0 + v.y1) / 2
        return GraphPainter.View(v.x0, v.x1, mid - h / 2, mid + h / 2)
    }

    /**
     * The fingers moved by [pan] and spread [kx] times along x, [ky] along y, around [at]: the
     * view follows them (a surface turns and comes nearer instead).
     */
    fun move(pan: Offset, kx: Double, ky: Double, at: Offset) {
        if (area.width == 0 || area.height == 0) return
        if (spec.isSurface) {
            azimuth = (azimuth - pan.x * 0.4f) % 360f
            elevation = (elevation + pan.y * 0.3f).coerceIn(5f, 85f)
            range = (range / kx.coerceIn(0.5, 2.0)).coerceIn(0.05, 1e4)
            return
        }
        val v = view
        val cx = v.x0 + at.x / area.width * v.w
        val cy = v.y1 - at.y / area.height * v.h
        val w = (v.w / kx.coerceIn(0.5, 2.0)).coerceIn(1e-6, 1e7)
        val h = (v.h / ky.coerceIn(0.5, 2.0)).coerceIn(1e-6, 1e7)
        val dx = -pan.x / area.width * w
        val dy = pan.y / area.height * h
        val nx0 = cx - (cx - v.x0) / v.w * w + dx
        val ny0 = cy - (cy - v.y0) / v.h * h + dy
        val next = GraphPainter.View(nx0, nx0 + w, ny0, ny0 + h)
        view = if (square) squared(next) else next
    }

    // At 1:1 a window sized anew keeps its cells square.
    LaunchedEffect(win, area, square) {
        if (square && !spec.isSurface) view = squared(view)
    }

    fun save() {
        val v = view
        val kept = if (spec.isSurface) spec.turned(azimuth, elevation, range) else spec.with(v.x0, v.x1, v.y0, v.y1, points, square)
        c.saveGraph(win, kept, area.width.toFloat() / area.height.coerceAtLeast(1))
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
                    spec.titles.joinToString("; ").ifEmpty { tr("График", "Graph") },
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    TextButton(onClick = {
                        active = false
                        pointMode = false
                        picked = -1
                    }) { Text(tr("Готово", "Done"), fontSize = 13.sp) }
                }
                TextButton(onClick = {
                    view = if (square) squared(start) else start
                    probe = null
                    azimuth = spec.azimuth
                    elevation = spec.elevation
                    range = spec.x1
                }) { Text(tr("Исходный вид", "First view"), fontSize = 13.sp) }
                TextButton(onClick = { save() }) {
                    Text(if (win.image != null) tr("Сохранить", "Save") else tr("В тетрадь", "To the page"), fontSize = 13.sp)
                }
                TextButton(onClick = { c.closeGraph() }) { Text("✕", fontSize = 15.sp) }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val colors = GraphPainter.Colors(
                    MaterialTheme.colorScheme.surface.toArgb(),
                    MaterialTheme.colorScheme.onSurface.toArgb(),
                    MaterialTheme.colorScheme.outlineVariant.toArgb(),
                    MaterialTheme.colorScheme.primary.toArgb(),
                    dark,
                )
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { area = it }
                        .pointerInput(win, active) {
                            if (!active) return@pointerInput
                            // One finger moves (turns a surface); two fingers apart along x
                            // stretch x, along y stretch y, slantwise both — or both alike at 1:1.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val down = event.changes.filter { it.pressed && it.previousPressed }
                                    if (down.size == 1) {
                                        val d = down[0].position - down[0].previousPosition
                                        if (d != Offset.Zero) move(d, 1.0, 1.0, down[0].position)
                                    } else if (down.size >= 2) {
                                        val a = down[0]
                                        val b = down[1]
                                        val mid = (a.position + b.position) / 2f
                                        val was = (a.previousPosition + b.previousPosition) / 2f
                                        val spanX = abs(a.position.x - b.position.x)
                                        val spanY = abs(a.position.y - b.position.y)
                                        val wasX = abs(a.previousPosition.x - b.previousPosition.x)
                                        val wasY = abs(a.previousPosition.y - b.previousPosition.y)
                                        val dist = (a.position - b.position).getDistance()
                                        val wasDist = (a.previousPosition - b.previousPosition).getDistance()
                                        val min = 48 * screen.density
                                        val k = if (wasDist > 1f) (dist / wasDist).toDouble() else 1.0
                                        // An axis stretches only when the fingers are apart along it.
                                        val kx = if (wasX > min && spanX > min) (spanX / wasX).toDouble() else 1.0
                                        val ky = if (wasY > min && spanY > min) (spanY / wasY).toDouble() else 1.0
                                        if (spec.isSurface || square) move(mid - was, k, k, mid) else move(mid - was, kx, ky, mid)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(win, active, pointMode, points, marks, view, picked) {
                            detectTapGestures(
                                onTap = { tap(it) },
                                onLongPress = { if (active) putPoint(it) },
                            )
                        },
                ) {
                    drawIntoCanvas {
                        if (spec.isSurface) {
                            surface?.let { s -> painter.drawSurface(it.nativeCanvas, size.width, size.height, s, azimuth, elevation, spec.titles.firstOrNull().orEmpty(), colors) }
                        } else {
                            painter.draw(it.nativeCanvas, size.width, size.height, view, fns, spec.titles, marks, points, probe, colors, picked, square)
                        }
                    }
                }
                if (!active) {
                    Text(
                        if (spec.isSurface) tr("Коснитесь, чтобы вращать поверхность", "Tap to turn the surface") else tr("Коснитесь, чтобы управлять графиком", "Tap to work the graph"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                } else if (!spec.isSurface) {
                    // The points: put them, take one or all away.
                    Row(Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            square = !square
                            if (square) view = squared(view)
                        }) { Text(if (square) "✓ 1:1" else "1:1", fontSize = 12.sp) }
                        TextButton(onClick = {
                            pointMode = !pointMode
                            picked = -1
                        }) { Text(if (pointMode) tr("✓ Ставить точки", "✓ Put points") else tr("Ставить точки", "Put points"), fontSize = 12.sp) }
                        if (picked in points.indices) {
                            TextButton(onClick = { removePoint(picked) }) { Text(tr("Удалить точку", "Remove point"), fontSize = 12.sp) }
                        }
                        if (points.isNotEmpty()) {
                            TextButton(onClick = {
                                points = emptyList()
                                picked = -1
                            }) { Text(tr("Убрать все", "Remove all"), fontSize = 12.sp) }
                        }
                    }
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
