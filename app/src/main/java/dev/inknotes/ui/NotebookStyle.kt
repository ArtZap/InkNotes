package dev.inknotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The plastic covers of the notebook style, in the order of the cover picker. */
enum class PlasticKind(val title: String) {
    FROSTED("Матовая на пружине"),
    SCHOOL("Школьная под плёнкой"),
    HOLO("Голограмма на кольцах"),
    ;

    companion object {
        fun of(index: Int): PlasticKind = entries[index.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * Notebook-style cover, one of the three kinds of the references: frosted pastel plastic on
 * a spiral with index tabs; a school exercise book under coloured film; a holographic binder
 * on rings with a snap strap. With glass covers on, the plastic shows the first page itself
 * (the school book loses its printed label then: only the film stays).
 */
@Composable
fun PlasticCover(
    color: Int,
    title: String,
    pages: Int,
    kind: PlasticKind,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    glass: Glass? = null,
    overlay: @Composable () -> Unit = {},
) {
    val c = Color(color)
    val shape = RoundedCornerShape(if (kind == PlasticKind.HOLO) 12.dp else if (kind == PlasticKind.SCHOOL) 3.dp else 8.dp)
    Box(modifier.shadow(if (compact) 3.dp else 10.dp, shape, ambientColor = c, spotColor = c.copy(alpha = 0.6f)).clip(shape)) {
        // What is under the plastic: the first page, or plain paper.
        Box(Modifier.fillMaxSize().background(Color.White).drawWithCache { onDrawBehind { if (glass == null && kind != PlasticKind.SCHOOL) ruled(this) } })
        glass?.page?.let {
            Image(
                it,
                null,
                Modifier.fillMaxSize().then(if (kind == PlasticKind.FROSTED) Modifier.blur(2.5.dp) else Modifier),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = maxWidth
            when (kind) {
                PlasticKind.FROSTED -> Frosted(c, title, pages, compact, glass != null, w.value)
                PlasticKind.SCHOOL -> School(c, title, pages, compact, glass != null, w.value)
                PlasticKind.HOLO -> Holo(c, title, compact, glass != null, w.value)
            }
        }
        overlay()
    }
}

/** Faint ruled paper under the plastic when the page itself is not shown. */
private fun ruled(s: DrawScope) = with(s) {
    val step = size.height / 26f
    var y = step * 3
    while (y < size.height) {
        drawLine(Color(0xFFBFD3EA), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

@Composable
private fun Frosted(c: Color, title: String, pages: Int, compact: Boolean, see: Boolean, w: Float) {
    val pastel = lerp(c, Color.White, 0.4f)
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val holes = Path()
                val r = size.width * 0.017f
                val x = size.width * 0.07f
                var y = size.height * 0.035f
                while (y < size.height * 0.97f) {
                    holes.addOval(Rect(Offset(x, y), r))
                    y += size.height * 0.037f
                }
                val frost = Brush.linearGradient(
                    listOf(lerp(pastel, Color.White, 0.35f).copy(alpha = if (see) 0.55f else 0.8f), pastel.copy(alpha = if (see) 0.7f else 0.92f)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                onDrawBehind {
                    drawRect(frost)
                    // A soft white haze in the middle, as frosted plastic scatters light.
                    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), Offset(size.width * 0.6f, size.height * 0.45f), size.maxDimension * 0.6f))
                    // Index tabs peeking out on the right.
                    val tabW = size.width * 0.045f
                    for ((i, tab) in listOf(lerp(c, Color.White, 0.65f), Color(0xFFBFD9F2), Color(0xFFE6D3F5)).withIndex()) {
                        drawRect(tab.copy(alpha = 0.95f), Offset(size.width - tabW, size.height * (0.1f + i * 0.13f)), Size(tabW, size.height * 0.1f))
                    }
                    // Spiral punch holes, white, with a little shade inside.
                    drawPath(holes, Color.White.copy(alpha = 0.95f))
                    drawPath(holes, lerp(c, Color.Black, 0.2f).copy(alpha = 0.25f), style = Stroke(r * 0.35f))
                    // The edge of the plastic.
                    drawRect(Color.White.copy(alpha = 0.5f), style = Stroke(size.width * 0.012f))
                }
            },
    ) {
        if (!compact) {
            Column(Modifier.padding(start = (w * 0.2f).dp, top = (w * 0.16f).dp, end = (w * 0.08f).dp)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = (w * 0.12f).coerceIn(16f, 34f).sp,
                    lineHeight = (w * 0.13f).coerceIn(18f, 38f).sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp,
                    style = TextStyle(shadow = Shadow(lerp(c, Color.Black, 0.3f).copy(alpha = 0.35f), Offset(0f, 2f), 6f)),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("лист бумаги белой", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, letterSpacing = 0.2.sp)
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(end = (w * 0.09f).dp, bottom = (w * 0.08f).dp), horizontalAlignment = Alignment.End) {
                Text("СТРАНИЦ: $pages", color = Color.White.copy(alpha = 0.95f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("всё получится", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun School(c: Color, title: String, pages: Int, compact: Boolean, see: Boolean, w: Float) {
    val ink = Color(0xFF223077)
    Box(Modifier.fillMaxSize()) {
        if (!see) {
            // The printed cover under the film: a brand mark, the label "ТЕТРАДЬ" and lines for the name.
            Box(
                Modifier.fillMaxSize().drawWithCache {
                    onDrawBehind {
                        val x0 = size.width * 0.34f
                        for (k in 0..2) {
                            val y = size.height * (0.4f + k * 0.075f)
                            drawLine(Color(0xFF9A9AA8), Offset(x0, y), Offset(size.width * 0.9f, y), strokeWidth = size.width * 0.004f)
                        }
                        drawRect(Color(0xFF1C1C22), Offset(size.width * 0.34f, size.height * 0.1f), Size(size.width * 0.2f, size.height * 0.035f))
                    }
                },
            )
            if (!compact) {
                Text(
                    "ТЕТРАДЬ",
                    modifier = Modifier.offset(x = (w * 0.34f).dp, y = (w * 0.38f).dp),
                    color = Color(0xFF3A3A48),
                    fontSize = (w * 0.07f).coerceIn(10f, 18f).sp,
                    letterSpacing = 2.sp,
                )
                // Written on the lines: the line height is the ruling's step (7.5 % of the
                // height), and the first baseline sits just above the first line.
                val step = w / 0.72f * 0.075f
                val firstLine = w / 0.72f * 0.4f
                Text(
                    title,
                    modifier = Modifier.offset(x = (w * 0.36f).dp, y = (firstLine - step * 0.86f).dp).width((w * 0.55f).dp),
                    color = ink,
                    fontFamily = FontFamily.Cursive,
                    fontStyle = FontStyle.Italic,
                    fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (step * 0.68f).dp.toSp() },
                    maxLines = 2,
                    lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { step.dp.toSp() },
                    style = TextStyle(lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Bottom, androidx.compose.ui.text.style.LineHeightStyle.Trim.None)),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // The coloured film, its corner lifted at the top right, and the gridded strip at the spine.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val wv = size.width
                val hv = size.height
                val a = Offset(wv * 0.68f, 0f)
                val b = Offset(wv, hv * 0.2f)
                val corner = Offset(wv, 0f)
                // The corner folded back over the line AB.
                val ab = b - a
                val t = ((corner - a).x * ab.x + (corner - a).y * ab.y) / (ab.x * ab.x + ab.y * ab.y)
                val foot = a + ab * t
                val folded = foot * 2f - corner
                val cut = Path().apply {
                    moveTo(a.x, a.y)
                    lineTo(corner.x, corner.y)
                    lineTo(b.x, b.y)
                    close()
                }
                val film = Path().apply {
                    op(Path().apply { addRect(Rect(0f, 0f, wv, hv)) }, cut, PathOperation.Difference)
                }
                val flap = Path().apply {
                    moveTo(a.x, a.y)
                    quadraticTo(folded.x + (a.x - folded.x) * 0.15f, folded.y, folded.x, folded.y)
                    quadraticTo(folded.x, folded.y + (b.y - folded.y) * 0.3f, b.x, b.y)
                    close()
                }
                val strip = wv * 0.26f
                val grid = Path().apply {
                    val step = wv * 0.032f
                    var x = step
                    while (x < strip) {
                        moveTo(x, 0f); lineTo(x, hv)
                        x += step
                    }
                    var y = step
                    while (y < hv) {
                        moveTo(0f, y); lineTo(strip, y)
                        y += step
                    }
                }
                onDrawBehind {
                    drawPath(film, c.copy(alpha = if (see) 0.4f else 0.38f))
                    drawRect(Brush.horizontalGradient(listOf(c.copy(alpha = 0.78f), c.copy(alpha = 0.62f))), size = Size(strip, hv))
                    drawPath(grid, Color.White.copy(alpha = 0.45f), style = Stroke(wv * 0.003f))
                    drawLine(lerp(c, Color.Black, 0.2f).copy(alpha = 0.6f), Offset(strip, 0f), Offset(strip, hv), strokeWidth = wv * 0.005f)
                    // A sheen across the film.
                    drawPath(film, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent, Color.White.copy(alpha = 0.12f)), Offset.Zero, Offset(wv, hv)))
                    // The lifted flap and its shadow on the page.
                    drawPath(flap, Color.Black.copy(alpha = 0.12f))
                    drawPath(flap, Brush.linearGradient(listOf(lerp(c, Color.White, 0.45f).copy(alpha = 0.85f), c.copy(alpha = 0.55f)), a, folded))
                    drawPath(flap, Color.White.copy(alpha = 0.6f), style = Stroke(wv * 0.004f))
                    // The box with the page count, as printed on the strip.
                    drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(wv * 0.04f, hv * 0.72f), Size(strip - wv * 0.08f, hv * 0.14f), CornerRadius(wv * 0.01f), style = Stroke(wv * 0.006f))
                }
            },
        )
        if (!compact) {
            // Inside the printed box on the strip (72–86 % of the height).
            Column(
                Modifier.offset(y = (w / 0.72f * 0.735f).dp).width((w * 0.26f).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("$pages", color = Color.White, fontSize = (w * 0.085f).coerceIn(11f, 22f).sp, fontWeight = FontWeight.ExtraBold, lineHeight = (w * 0.09f).coerceIn(12f, 24f).sp)
                Text("СТР.", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            if (see) {
                Text(
                    title,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp).width((w * 0.6f).dp),
                    color = Color.White,
                    textAlign = TextAlign.End,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = (w * 0.08f).coerceIn(12f, 20f).sp,
                    style = TextStyle(shadow = Shadow(lerp(c, Color.Black, 0.4f).copy(alpha = 0.7f), Offset(0f, 1f), 5f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Holo(c: Color, title: String, compact: Boolean, see: Boolean, w: Float) {
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val wv = size.width
            val hv = size.height
            val holo = Brush.linearGradient(
                listOf(Color(0xFFFFB3DA), Color(0xFFDAB8FF), Color(0xFFA6E2FF), Color(0xFFB8F5E0), Color(0xFFFFE3B3), Color(0xFFFFB8D8)),
                start = Offset.Zero,
                end = Offset(wv, hv),
            )
            // Liquid streaks of the foil, like the sky-blue flames on the reference binder.
            val streaks = Path().apply {
                moveTo(0f, hv * 0.05f)
                cubicTo(wv * 0.3f, hv * 0.0f, wv * 0.2f, hv * 0.3f, wv * 0.45f, hv * 0.28f)
                cubicTo(wv * 0.6f, hv * 0.27f, wv * 0.55f, hv * 0.1f, wv * 0.72f, 0f)
                lineTo(0f, 0f)
                close()
                moveTo(0f, hv * 0.55f)
                cubicTo(wv * 0.15f, hv * 0.5f, wv * 0.12f, hv * 0.75f, wv * 0.3f, hv * 0.8f)
                cubicTo(wv * 0.4f, hv * 0.83f, wv * 0.35f, hv * 0.95f, wv * 0.5f, hv)
                lineTo(0f, hv)
                close()
                moveTo(wv, hv * 0.7f)
                cubicTo(wv * 0.85f, hv * 0.75f, wv * 0.9f, hv * 0.9f, wv * 0.75f, hv)
                lineTo(wv, hv)
                close()
            }
            val rings = listOf(0.13f, 0.21f, 0.29f, 0.71f, 0.79f, 0.87f)
            onDrawBehind {
                drawRect(holo, alpha = if (see) 0.58f else 0.82f)
                drawRect(c.copy(alpha = 0.18f))
                drawPath(streaks, Color(0xFF8FD3FF).copy(alpha = 0.5f))
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent, Color.White.copy(alpha = 0.25f)), Offset.Zero, Offset(wv, hv)))
                drawRoundRect(Color.White.copy(alpha = 0.7f), Offset(wv * 0.06f, hv * 0.03f), Size(wv * 0.88f, hv * 0.94f), CornerRadius(wv * 0.03f), style = Stroke(wv * 0.006f))
                // Binder rings with their red-tipped posts.
                for (y in rings) {
                    val cy = hv * y
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xFFE9EDF2), Color(0xFF8D96A3), Color(0xFFD9DEE5)), cy - wv * 0.012f, cy + wv * 0.012f),
                        Offset(0f, cy - wv * 0.012f),
                        Size(wv * 0.07f, wv * 0.024f),
                        CornerRadius(wv * 0.012f),
                    )
                    drawCircle(Color(0xFFE0603A), wv * 0.013f, Offset(wv * 0.075f, cy))
                }
                // The snap strap across the right edge.
                val top = hv * 0.47f
                val sh = hv * 0.12f
                drawRoundRect(holo, Offset(wv * 0.74f, top), Size(wv * 0.3f, sh), CornerRadius(sh / 2), alpha = 0.9f)
                drawRoundRect(Color.White.copy(alpha = 0.8f), Offset(wv * 0.74f, top), Size(wv * 0.3f, sh), CornerRadius(sh / 2), style = Stroke(wv * 0.006f))
                val btn = Offset(wv * 0.83f, top + sh / 2)
                drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF9AA3AF), Color(0xFF5A626D)), btn + Offset(-wv * 0.01f, -wv * 0.01f), wv * 0.05f), wv * 0.04f, btn)
            }
        },
    ) {
        if (!compact) {
            Text(
                title,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = (w * 0.14f).dp, bottom = (w * 0.1f).dp, end = (w * 0.1f).dp),
                color = Color.White,
                fontSize = (w * 0.1f).coerceIn(14f, 28f).sp,
                lineHeight = (w * 0.11f).coerceIn(16f, 32f).sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                style = TextStyle(shadow = Shadow(Color(0xFF6A5A9A).copy(alpha = 0.6f), Offset(0f, 1.5f), 6f)),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Notebook-style folder: a clear plastic document envelope with a snap button. */
@Composable
fun PlasticFolder(count: Int, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .shadow(6.dp, shape, ambientColor = tint, spotColor = tint.copy(alpha = 0.5f))
            .clip(shape)
            .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.82f), lerp(tint, Color.White, 0.65f))))
            .border(1.dp, Color.White.copy(alpha = 0.8f), shape)
            .drawWithCache {
                val flap = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height * 0.22f)
                    quadraticTo(size.width / 2, size.height * 0.34f, 0f, size.height * 0.22f)
                    close()
                }
                onDrawBehind {
                    drawPath(flap, Color.White.copy(alpha = 0.45f))
                    drawPath(flap, Color.White.copy(alpha = 0.9f), style = Stroke(1.dp.toPx()))
                    val btn = Offset(size.width / 2, size.height * 0.28f)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFB0B7C2), Color(0xFF6C7480)), btn - Offset(3f, 3f), 14.dp.toPx()), 9.dp.toPx(), btn)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = lerp(tint, Color.Black, 0.35f))
            Text("тетрадей", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = lerp(tint, Color.Black, 0.2f))
        }
    }
}

/** A strip of washi tape: the divider under the library title in the notebook style. */
@Composable
fun WashiTape(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(modifier.width(180.dp).height(22.dp).rotate(-2f)) {
        val w = size.width
        val h = size.height
        val tooth = h / 6
        val tape = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            var y = 0f
            var out = true
            while (y < h) {
                y += tooth
                lineTo(if (out) w - tooth * 0.6f else w, y.coerceAtMost(h))
                out = !out
            }
            lineTo(0f, h)
            y = h
            out = true
            while (y > 0f) {
                y -= tooth
                lineTo(if (out) tooth * 0.6f else 0f, y.coerceAtLeast(0f))
                out = !out
            }
            close()
        }
        drawPath(tape, lerp(tint, Color.White, 0.55f).copy(alpha = 0.9f))
        // Diagonal stripes printed on the tape.
        clipPathStripes(this, tape, w, h)
    }
}

private fun clipPathStripes(s: DrawScope, tape: Path, w: Float, h: Float) = with(s) {
    val stripes = Path()
    var x = -h
    while (x < w) {
        stripes.moveTo(x, h)
        stripes.lineTo(x + h, 0f)
        x += h * 0.7f
    }
    val clipped = Path().apply { op(tape, Path().apply { addRect(Rect(0f, 0f, w, h)) }, PathOperation.Intersect) }
    clipPath(clipped) {
        drawPath(stripes, Color.White.copy(alpha = 0.5f), style = Stroke(h * 0.18f))
    }
}
