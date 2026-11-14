package dev.inknotes.ui

import dev.inknotes.data.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A cover seen as tinted glass over the notebook's first page ([page] null while it renders). */
class Glass(val page: ImageBitmap?, val dark: Boolean)

/** How much of the cover colour stays on a glass cover: the colour reads, the page shows. */
const val GLASS_ALPHA = 0.56f

/** Paper behind a glass cover until (and under) the first page. */
fun glassPaper(dark: Boolean) = if (dark) Color(0xFF232427) else Color(0xFFFBFAF7)

/** The cover colour as it looks on glass, for choosing a legible title colour. */
fun glassSeen(cover: Color, glass: Glass?): Color = if (glass == null) cover else lerp(glassPaper(glass.dark), cover, GLASS_ALPHA)

/** Shadow that keeps a title readable over the ink of the first page. */
fun glassShadow(glass: Glass?, light: Boolean) =
    if (glass == null) null else Shadow(if (light) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.7f), blurRadius = 6f)

/** The first page under a glass cover; fills its parent. */
@Composable
fun GlassPage(glass: Glass) {
    Box(Modifier.fillMaxSize().background(glassPaper(glass.dark))) {
        glass.page?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) }
    }
}

/** Old gold of the mystic style: dimmer than the premium champagne. */
object OldGold {
    val Line = Color(0xFFCDAE6E)
    val Foil = Brush.linearGradient(listOf(Color(0xFFF3DA8F), Color(0xFFC99A3C), Color(0xFFEBC871), Color(0xFFAA7E2D)))
}

private val MysticShape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)

/**
 * Mystic notebook cover: one of the six designs of the reference set ([art]) drawn by
 * [MysticArt] in the cover colour, the title set in its panel.
 */
@Composable
fun MysticCover(
    color: Int,
    title: String,
    art: CoverArt,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    glass: Glass? = null,
    overlay: @Composable () -> Unit = {},
) {
    val see = glass != null
    // The designs are kept in the colours of the reference set.
    val color = art.color
    Box(modifier.shadow(if (compact) 3.dp else 10.dp, MysticShape).clip(MysticShape)) {
        if (glass != null) GlassPage(glass)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val picture = MysticArt.picture(art, color, size.width.toInt(), size.height.toInt(), see)
                    onDrawBehind { drawImage(picture) }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (!compact) {
                val w = maxWidth
                val onGold = art == CoverArt.MOON
                // A long word is set smaller rather than broken in the middle.
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val size = remember(title, w, art) {
                    val room = with(density) { (w * art.titleWidth).toPx() } * 0.85f
                    val words = title.split(' ').filter { it.isNotEmpty() }
                    var sp = (w.value * if (onGold) 0.062f else 0.078f).coerceIn(9f, 24f)
                    while (sp > 9f && words.any { measurer.measure(it, TextStyle(fontFamily = FontFamily.Serif, fontSize = sp.sp, letterSpacing = 0.6.sp)).size.width > room }) sp -= 0.5f
                    sp
                }
                val c = Color(color)
                Text(
                    title,
                    modifier = Modifier.offset(y = maxHeight * (art.titleY - 0.5f)).width(w * art.titleWidth),
                    style = when (art) {
                        CoverArt.MOON -> TextStyle(color = lerp(c, Color.Black, 0.55f))
                        CoverArt.OVAL -> TextStyle(color = lerp(c, Color(0xFFF4B19C), 0.62f), shadow = glassShadow(glass, light = false))
                        else -> TextStyle(brush = OldGold.Foil, shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 1.5f), 3f))
                    },
                    fontFamily = FontFamily.Serif,
                    fontSize = size.sp,
                    lineHeight = (size * 1.28f).sp,
                    letterSpacing = 0.6.sp,
                    textAlign = TextAlign.Center,
                    maxLines = art.titleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            overlay()
        }
    }
}

/** A leaf: a pointed oval from [at] towards [degrees], [len] long and [half] wide. */
private fun Path.leaf(at: Offset, degrees: Float, len: Float, half: Float) {
    val t = Math.toRadians(degrees.toDouble())
    val d = Offset(cos(t).toFloat(), sin(t).toFloat())
    val n = Offset(-d.y, d.x)
    val tip = at + d * len
    val mid = at + d * (len / 2)
    moveTo(at.x, at.y)
    quadraticTo(mid.x + n.x * half * 2, mid.y + n.y * half * 2, tip.x, tip.y)
    quadraticTo(mid.x - n.x * half * 2, mid.y - n.y * half * 2, at.x, at.y)
    close()
}

/** A four-pointed star of radius [r]. */
private fun sparkle(c: Offset, r: Float): Path = Path().apply {
    val k = r * 0.22f
    moveTo(c.x, c.y - r)
    quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
    quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
    quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
    quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
    close()
}

/** Mystic folder: a dark panel framed in old gold with stars at the corners and the count in gold. */
@Composable
fun MysticFolder(count: Int, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier
            .shadow(4.dp, MysticShape)
            .clip(MysticShape)
            .background(surface)
            .drawWithCache {
                val u = size.width / 100f
                val stars = Path().apply {
                    for (p in listOf(Offset(10f * u, 10f * u), Offset(size.width - 10f * u, 10f * u), Offset(10f * u, size.height - 10f * u), Offset(size.width - 10f * u, size.height - 10f * u))) {
                        addPath(sparkle(p, 3f * u))
                    }
                }
                val flap = size.height * 0.24f
                onDrawBehind {
                    drawRect(OldGold.Line.copy(alpha = 0.8f), Offset(4f * u, 4f * u), Size(size.width - 8f * u, size.height - 8f * u), style = Stroke(0.6f * u))
                    drawRect(OldGold.Line.copy(alpha = 0.4f), Offset(6.5f * u, 6.5f * u), Size(size.width - 13f * u, size.height - 13f * u), style = Stroke(0.3f * u))
                    drawLine(OldGold.Line.copy(alpha = 0.6f), Offset(16f * u, flap), Offset(size.width - 16f * u, flap), strokeWidth = 0.4f * u)
                    drawPath(stars, OldGold.Line)
                    drawPath(sparkle(Offset(size.width / 2, flap), 2.6f * u), OldGold.Line)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", style = TextStyle(brush = OldGold.Foil), fontFamily = FontFamily.Serif, fontSize = 44.sp)
            Text(tr("ТЕТРАДЕЙ", "NOTEBOOKS"), fontSize = 10.sp, letterSpacing = 3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A gold rule with a star in the middle, under the library title. */
@Composable
fun MysticRule(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(0.6.dp).background(color))
        Canvas(Modifier.size(14.dp)) { drawPath(sparkle(Offset(size.width / 2, size.height / 2), size.width / 2), color) }
        Box(Modifier.weight(1f).height(0.6.dp).background(color))
    }
}
