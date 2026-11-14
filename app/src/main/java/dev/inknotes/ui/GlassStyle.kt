package dev.inknotes.ui

import dev.inknotes.data.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GlassCardShape = RoundedCornerShape(26.dp)

/**
 * A pane of liquid glass: translucent fill, a light edge brighter at the top left where the
 * light falls, a soft shadow; [tint] colours the glass.
 */
fun Modifier.liquidGlass(shape: Shape, dark: Boolean, tint: Color = Color.Unspecified, elevation: Dp = 10.dp): Modifier =
    this
        .shadow(elevation, shape, ambientColor = Color(0x33203060), spotColor = Color(0x44203060))
        .clip(shape)
        .background(
            if (tint.isSpecified) {
                Brush.linearGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.32f)))
            } else if (dark) {
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f)))
            } else {
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.72f), Color.White.copy(alpha = 0.42f)))
            },
        )
        .drawWithContent {
            drawContent()
            // Specular light: a glow in the top left and a bright rim fading round the edge.
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = if (dark) 0.14f else 0.35f), Color.Transparent), Offset(size.width * 0.15f, 0f), size.maxDimension * 0.7f))
        }
        .border(
            1.2.dp,
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.5f else 0.95f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = if (dark) 0.25f else 0.55f)),
                start = Offset.Zero,
                end = Offset.Infinite,
            ),
            shape,
        )

private val Color.isSpecified get() = this != Color.Unspecified

/**
 * Liquid glass notebook: a slab of tinted glass; through it the first page, frosted, when
 * glass covers are on. The title sits in a small glass capsule at the bottom.
 */
@Composable
fun GlassCover(color: Int, title: String, dark: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, glass: Glass? = null, overlay: @Composable () -> Unit = {}) {
    val tint = Color(color)
    Box(modifier.liquidGlass(if (compact) RoundedCornerShape(8.dp) else GlassCardShape, dark, elevation = if (compact) 4.dp else 14.dp)) {
        glass?.page?.let {
            Image(it, null, Modifier.fillMaxSize().blur(6.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, alpha = 0.9f)
        }
        // The tint, thicker towards the bottom like the edge of a real slab of glass.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = if (glass != null) 0.28f else 0.42f), tint.copy(alpha = if (glass != null) 0.5f else 0.72f))))
                .drawWithContent {
                    drawContent()
                    // A diagonal streak of reflected light.
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Transparent,
                            0.38f to Color.Transparent,
                            0.45f to Color.White.copy(alpha = 0.22f),
                            0.52f to Color.Transparent,
                            1f to Color.Transparent,
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                    drawRoundRect(
                        Color.White.copy(alpha = 0.35f),
                        topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(if (compact) 6.dp.toPx() else 23.dp.toPx()),
                        style = Stroke(0.6.dp.toPx()),
                    )
                },
        )
        if (!compact) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .liquidGlass(RoundedCornerShape(50), dark, elevation = 0.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (dark) Color.White else Color(0xFF10131A),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        overlay()
    }
}

/** Liquid glass folder: a glass pane with a tab, the count large. */
@Composable
fun GlassFolder(count: Int, dark: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 6.dp)) {
        Box(Modifier.fillMaxWidth(0.42f).padding(bottom = 0.dp).liquidGlass(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp), dark, elevation = 0.dp).padding(9.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).liquidGlass(RoundedCornerShape(topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 22.dp), dark),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$count", fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp, color = MaterialTheme.colorScheme.onSurface)
                Text(tr("тетрадей", "notebooks"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A capsule of glass holding a row of toolbar buttons (the library's actions). */
@Composable
fun GlassCapsule(dark: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.liquidGlass(RoundedCornerShape(50), dark, elevation = 8.dp).padding(horizontal = 6.dp)) { content() }
}
