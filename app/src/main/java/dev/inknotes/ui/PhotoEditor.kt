package dev.inknotes.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.inknotes.editor.PhotoMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Picture edits on bitmaps: each returns a new bitmap and leaves the given one as it was. */
object PhotoEdits {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    /** The picture drawn so that the points [from] land on [to] (four x, y pairs each). */
    fun warp(src: Bitmap, from: FloatArray, to: FloatArray, outW: Int, outH: Int): Bitmap {
        val m = Matrix()
        m.setPolyToPoly(from, 0, to, 0, 4)
        val out = Bitmap.createBitmap(max(1, outW), max(1, outH), Bitmap.Config.ARGB_8888)
        AndroidCanvas(out).drawBitmap(src, m, paint)
        return out
    }

    private fun rect(w: Float, h: Float) = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)

    /** A sheet photographed at a slant: its [corners] (pixels: top left, top right, bottom right, bottom left) made a rectangle. */
    fun straighten(src: Bitmap, corners: FloatArray): Bitmap {
        var (w, h) = PhotoMath.straightSize(corners)
        val k = min(1f, MAX_SIDE.toFloat() / max(w, h))
        w = max(1, (w * k).roundToInt())
        h = max(1, (h * k).roundToInt())
        return warp(src, corners, rect(w.toFloat(), h.toFloat()), w, h)
    }

    /** Turned in perspective about the horizontal ([aroundX]) and vertical ([aroundY]) axes, degrees. */
    fun tilt(src: Bitmap, aroundX: Float, aroundY: Float): Bitmap {
        val to = PhotoMath.tiltCorners(src.width.toFloat(), src.height.toFloat(), aroundX, aroundY)
        val (w, h) = PhotoMath.boundsSize(to)
        val out = warp(src, rect(src.width.toFloat(), src.height.toFloat()), to, ceil(w).toInt(), ceil(h).toInt())
        out.setHasAlpha(true)
        return out
    }

    /** The part [l], [t], [r], [b] (shares of the width and height). */
    fun crop(src: Bitmap, l: Float, t: Float, r: Float, b: Float): Bitmap {
        val x = (min(l, r) * src.width).roundToInt().coerceIn(0, src.width - 1)
        val y = (min(t, b) * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = ((max(l, r) - min(l, r)) * src.width).roundToInt().coerceIn(1, src.width - x)
        val h = ((max(t, b) - min(t, b)) * src.height).roundToInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h).let { if (it === src) it.copy(Bitmap.Config.ARGB_8888, true) else it }
    }

    fun rotate(src: Bitmap, clockwise: Boolean): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { setRotate(if (clockwise) 90f else -90f) }, true)

    private inline fun pixels(src: Bitmap, edit: (IntArray) -> IntArray): Bitmap {
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        out.setPixels(edit(px), 0, src.width, 0, 0, src.width, src.height)
        return out
    }

    fun clearBackground(src: Bitmap, tolerance: Float): Bitmap =
        pixels(src) { PhotoMath.clearBackground(it, src.width, src.height, tolerance) }.also { it.setHasAlpha(true) }

    fun document(src: Bitmap, color: Boolean): Bitmap = pixels(src) { PhotoMath.documentLevels(it, color) }.also { if (!src.hasAlpha()) it.setHasAlpha(false) }

    /** A smaller copy for quick previews, at most [side] px on the longer side. */
    fun scaled(src: Bitmap, side: Int): Bitmap {
        val k = side.toFloat() / max(src.width, src.height)
        if (k >= 1f) return src
        return Bitmap.createScaledBitmap(src, max(1, (src.width * k).roundToInt()), max(1, (src.height * k).roundToInt()), true)
    }

    const val MAX_SIDE = 2048
}

enum class PhotoMode(val title: String) {
    CROP("Обрезать"),
    CORNERS("Выпрямить лист"),
    TILT("Перспектива"),
    BACKGROUND("Убрать фон"),
    DOCUMENT("Документ"),
    ROTATE("Повернуть"),
}

/**
 * Full-screen editor of a picture: crop, a sheet's four corners made a rectangle, a turn in
 * perspective about the axes along the screen, the background taken away, a page made crisp,
 * quarter turns. Each step can be taken back; [onDone] gets the result.
 */
@Composable
fun PhotoEditorDialog(
    source: Bitmap,
    startMode: PhotoMode,
    doneLabel: String,
    onCancel: () -> Unit,
    onDone: (Bitmap) -> Unit,
) {
    var current by remember { mutableStateOf(source) }
    val history = remember { mutableStateListOf<Bitmap>() }
    var mode by remember { mutableStateOf(startMode) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Per-step settings; reset when the picture changes.
    var generation by remember { mutableIntStateOf(0) }
    val crop = remember(generation) { mutableStateListOf(0.05f, 0.05f, 0.95f, 0.95f) }
    val corners = remember(generation) { mutableStateListOf(0.08f, 0.08f, 0.92f, 0.08f, 0.92f, 0.92f, 0.08f, 0.92f) }
    var aroundX by remember(generation) { mutableFloatStateOf(0f) }
    var aroundY by remember(generation) { mutableFloatStateOf(0f) }
    var tolerance by remember(generation) { mutableFloatStateOf(0.18f) }
    var toleranceShown by remember(generation) { mutableFloatStateOf(0.18f) }

    val small = remember(current) { PhotoEdits.scaled(current, PREVIEW_SIDE) }
    val shown: Bitmap = remember(small, mode, aroundX, aroundY, toleranceShown) {
        when (mode) {
            PhotoMode.TILT -> if (aroundX != 0f || aroundY != 0f) PhotoEdits.tilt(small, aroundX, aroundY) else small
            PhotoMode.BACKGROUND -> PhotoEdits.clearBackground(small, toleranceShown)
            else -> small
        }
    }

    fun apply(op: (Bitmap) -> Bitmap) {
        if (busy) return
        busy = true
        val before = current
        scope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { op(before) } }.getOrNull()
            if (result != null) {
                history += before
                current = result
                generation++
            }
            busy = false
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF1C1F23)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Отмена", color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = history.isNotEmpty() && !busy, onClick = {
                        current = history.removeAt(history.lastIndex)
                        generation++
                    }) { Text("↶ Шаг назад", color = if (history.isNotEmpty()) Color.White else Color.Gray) }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy, onClick = { onDone(current) }) { Text(doneLabel) }
                }
                var area by remember { mutableStateOf(IntSize.Zero) }
                val fit = fitRect(area, shown.width, shown.height)
                val handle = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .onSizeChanged { area = it }
                        .pointerInput(mode, fit, generation) {
                            if (mode != PhotoMode.CROP && mode != PhotoMode.CORNERS) return@pointerInput
                            var grabbed = -1
                            var last = Offset.Zero
                            detectDragGestures(
                                onDragStart = { p ->
                                    last = p
                                    grabbed = if (mode == PhotoMode.CROP) {
                                        val pts = listOf(crop[0] to crop[1], crop[2] to crop[1], crop[2] to crop[3], crop[0] to crop[3])
                                        val near = pts.indexOfFirst { (fx, fy) -> hypot(fit.left + fx * fit.width - p.x, fit.top + fy * fit.height - p.y) < handle }
                                        when {
                                            near >= 0 -> near
                                            p.x in fit.left + crop[0] * fit.width..fit.left + crop[2] * fit.width &&
                                                p.y in fit.top + crop[1] * fit.height..fit.top + crop[3] * fit.height -> 4
                                            else -> -1
                                        }
                                    } else {
                                        (0 until 4).minByOrNull { hypot(fit.left + corners[2 * it] * fit.width - p.x, fit.top + corners[2 * it + 1] * fit.height - p.y) }
                                            ?.takeIf { hypot(fit.left + corners[2 * it] * fit.width - p.x, fit.top + corners[2 * it + 1] * fit.height - p.y) < handle * 2 } ?: -1
                                    }
                                },
                                onDrag = { change, _ ->
                                    val p = change.position
                                    val fx = ((p.x - fit.left) / fit.width).coerceIn(0f, 1f)
                                    val fy = ((p.y - fit.top) / fit.height).coerceIn(0f, 1f)
                                    if (mode == PhotoMode.CROP) {
                                        val minSize = 0.05f
                                        when (grabbed) {
                                            0 -> { crop[0] = min(fx, crop[2] - minSize); crop[1] = min(fy, crop[3] - minSize) }
                                            1 -> { crop[2] = max(fx, crop[0] + minSize); crop[1] = min(fy, crop[3] - minSize) }
                                            2 -> { crop[2] = max(fx, crop[0] + minSize); crop[3] = max(fy, crop[1] + minSize) }
                                            3 -> { crop[0] = min(fx, crop[2] - minSize); crop[3] = max(fy, crop[1] + minSize) }
                                            4 -> {
                                                val dx = (p.x - last.x) / fit.width
                                                val dy = (p.y - last.y) / fit.height
                                                val w = crop[2] - crop[0]
                                                val h = crop[3] - crop[1]
                                                crop[0] = (crop[0] + dx).coerceIn(0f, 1f - w)
                                                crop[1] = (crop[1] + dy).coerceIn(0f, 1f - h)
                                                crop[2] = crop[0] + w
                                                crop[3] = crop[1] + h
                                            }
                                        }
                                    } else if (grabbed >= 0) {
                                        corners[2 * grabbed] = fx
                                        corners[2 * grabbed + 1] = fy
                                    }
                                    last = p
                                    change.consume()
                                },
                            )
                        },
                ) {
                    val image = remember(shown) { shown.asImageBitmap() }
                    Canvas(Modifier.fillMaxSize()) {
                        if (fit.width <= 0f) return@Canvas
                        // Squares behind see-through parts.
                        if (shown.hasAlpha()) checker(fit)
                        drawImage(
                            image,
                            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                            dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                        )
                        val accent = Color(0xFF4FC3F7)
                        when (mode) {
                            PhotoMode.CROP -> {
                                val r = Rect(fit.left + crop[0] * fit.width, fit.top + crop[1] * fit.height, fit.left + crop[2] * fit.width, fit.top + crop[3] * fit.height)
                                val shade = Color(0x99000000)
                                drawRect(shade, Offset(fit.left, fit.top), Size(fit.width, r.top - fit.top))
                                drawRect(shade, Offset(fit.left, r.bottom), Size(fit.width, fit.bottom - r.bottom))
                                drawRect(shade, Offset(fit.left, r.top), Size(r.left - fit.left, r.height))
                                drawRect(shade, Offset(r.right, r.top), Size(fit.right - r.right, r.height))
                                drawRect(accent, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                                for (c in listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)) drawCircle(accent, 9.dp.toPx(), c)
                            }
                            PhotoMode.CORNERS -> {
                                val pts = (0 until 4).map { Offset(fit.left + corners[2 * it] * fit.width, fit.top + corners[2 * it + 1] * fit.height) }
                                val path = Path().apply {
                                    moveTo(pts[0].x, pts[0].y)
                                    for (k in 1 until 4) lineTo(pts[k].x, pts[k].y)
                                    close()
                                }
                                drawPath(path, accent.copy(alpha = 0.18f))
                                drawPath(path, accent, style = Stroke(2.dp.toPx()))
                                for (c in pts) drawCircle(accent, 11.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                            }
                            else -> Unit
                        }
                    }
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                // The step's own controls.
                Column(Modifier.fillMaxWidth().background(Color(0xFF263238)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hint = Color(0xFFB0BEC5)
                    when (mode) {
                        PhotoMode.CROP -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Тяните углы рамки или саму рамку.", color = hint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Button(enabled = !busy, onClick = {
                                val (l, t, r, b) = crop.toList()
                                apply { PhotoEdits.crop(it, l, t, r, b) }
                            }) { Text("Обрезать") }
                        }
                        PhotoMode.CORNERS -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Поставьте кружки на углы листа: он станет ровным прямоугольником.", color = hint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            val pts = corners.toFloatArray()
                            Button(enabled = !busy && PhotoMath.isConvex(pts), onClick = {
                                apply { bmp -> PhotoEdits.straighten(bmp, FloatArray(8) { i -> pts[i] * if (i % 2 == 0) bmp.width else bmp.height }) }
                            }) { Text("Выпрямить") }
                        }
                        PhotoMode.TILT -> {
                            AngleRow("Наклон вперёд-назад", aroundX) { aroundX = it }
                            AngleRow("Поворот влево-вправо", aroundY) { aroundY = it }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Повороты вокруг осей вдоль экрана: картинка уходит вглубь.", color = hint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = { aroundX = 0f; aroundY = 0f }) { Text("Сброс", color = Color.White) }
                                Spacer(Modifier.width(8.dp))
                                Button(enabled = !busy && (aroundX != 0f || aroundY != 0f), onClick = {
                                    val ax = aroundX
                                    val ay = aroundY
                                    apply { PhotoEdits.tilt(it, ax, ay) }
                                }) { Text("Применить") }
                            }
                        }
                        PhotoMode.BACKGROUND -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Похожесть цвета", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(150.dp))
                                InkSlider(
                                    value = tolerance,
                                    onValueChange = { tolerance = it },
                                    onValueChangeFinished = { toleranceShown = tolerance },
                                    valueRange = 0.02f..0.5f,
                                    modifier = Modifier.weight(1f),
                                    valueLabel = { "${(it * 100).roundToInt()}%" },
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Убирается фон, который касается края картинки: лист, стена, стол.", color = hint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Button(enabled = !busy, onClick = {
                                    val tol = tolerance
                                    apply { PhotoEdits.clearBackground(it, tol) }
                                }) { Text("Убрать фон") }
                            }
                        }
                        PhotoMode.DOCUMENT -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Бумага станет белой, текст — чётким.", color = hint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            OutlinedButton(enabled = !busy, onClick = { apply { PhotoEdits.document(it, color = true) } }) { Text("В цвете", color = Color.White) }
                            Spacer(Modifier.width(8.dp))
                            Button(enabled = !busy, onClick = { apply { PhotoEdits.document(it, color = false) } }) { Text("Чёрно-белый") }
                        }
                        PhotoMode.ROTATE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Поворот на четверть оборота.", color = hint, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            OutlinedButton(enabled = !busy, onClick = { apply { PhotoEdits.rotate(it, clockwise = false) } }) { Text("↺ 90°", color = Color.White) }
                            Button(enabled = !busy, onClick = { apply { PhotoEdits.rotate(it, clockwise = true) } }) { Text("↻ 90°") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in PhotoMode.entries) {
                            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.title, color = if (mode == m) MaterialTheme.colorScheme.onSecondaryContainer else Color.White) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AngleRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(190.dp))
        InkSlider(
            value = value,
            onValueChange = { onChange((it * 2).roundToInt() / 2f) },
            valueRange = -60f..60f,
            modifier = Modifier.weight(1f),
            valueLabel = { "${it.roundToInt()}°" },
        )
        Text("${value.roundToInt()}°", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(48.dp).padding(start = 8.dp))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.checker(r: Rect) {
    val cell = 12.dp.toPx()
    drawRect(Color(0xFFEEEEEE), r.topLeft, r.size)
    var y = r.top
    var row = 0
    while (y < r.bottom) {
        var x = r.left + if (row % 2 == 0) 0f else cell
        while (x < r.right) {
            drawRect(Color(0xFFCCCCCC), Offset(x, y), Size(min(cell, r.right - x), min(cell, r.bottom - y)))
            x += 2 * cell
        }
        y += cell
        row++
    }
}

/** Where a [w]×[h] picture sits, as large as fits and centred, in an [area]. */
private fun fitRect(area: IntSize, w: Int, h: Int): Rect {
    if (area.width <= 0 || area.height <= 0 || w <= 0 || h <= 0) return Rect.Zero
    val k = min(area.width.toFloat() / w, area.height.toFloat() / h)
    val dw = w * k
    val dh = h * k
    val left = (area.width - dw) / 2
    val top = (area.height - dh) / 2
    return Rect(left, top, left + dw, top + dh)
}

private const val PREVIEW_SIDE = 900
