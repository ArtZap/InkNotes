package dev.inknotes.ui

import dev.inknotes.R
import dev.inknotes.data.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** A long, soft settle: quick start, very gentle landing (no visible stop). */
private val Smooth = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Even in and out: for the gleam and the fade. */
private val Glide = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * The start of the app. The system window already shows the pen of the icon in the middle of
 * the paper (themes.xml, splash_window.xml); the first frame here is exactly that picture, so
 * nothing jumps. Then the paper turns to the chosen style, the pen rises and shrinks, the name
 * comes in under it with a gleam running across; once nothing moves, [onRisen] lets what is
 * under it be made, and the start fades away by itself (a tap skips it).
 *
 * Every moving value is read only where it is drawn (graphicsLayer, drawBehind), never while
 * composing: a frame of the start is only drawn again, nothing is laid out, so it stays smooth.
 */
@Composable
fun Splash(onRisen: () -> Unit = {}, onDone: () -> Unit) {
    val style = LocalUiStyle.current
    val colors = MaterialTheme.colorScheme
    val startBg = colorResource(R.color.splash_bg)
    val startInk = colorResource(R.color.splash_ink)
    val rise = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val gleam = remember { Animatable(-0.3f) }
    val gone = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // One easing for everything that moves, the parts overlapping: no visible step.
        launch { words.animateTo(1f, tween(520, delayMillis = 200, easing = Smooth)) }
        launch { gleam.animateTo(1.3f, tween(900, delayMillis = 380, easing = Glide)) }
        rise.animateTo(1f, tween(640, easing = Smooth))
        // The page underneath is made only when nothing moves any more (making it holds the
        // main thread for a moment: a moving frame would stop there).
        kotlinx.coroutines.delay(660)
        onRisen()
        // Its first frames drawn, then the start fades away over it.
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        gone.animateTo(1f, tween(380, easing = Glide))
        onDone()
    }
    // Gold in the gilded styles, the accent in the classic one, graphite in the minimal.
    val (ink, shine) = when (style) {
        UiStyle.PREMIUM -> Color(0xFFB8914A) to Color(0xFFF6E7BE)
        UiStyle.MYSTIC -> Color(0xFFAD8C4C) to Color(0xFFEFDDAE)
        UiStyle.CLASSIC -> colors.primary to lerp(colors.primary, Color.White, 0.7f)
        UiStyle.MINIMAL -> colors.onBackground to colors.onSurfaceVariant
        UiStyle.GLASS -> colors.primary to Color.White
        UiStyle.NOTEBOOK -> colors.primary to Color(0xFFFFD6E8)
    }
    val paper = colors.background
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 1f - gone.value
                val s = 1f + 0.03f * gone.value
                scaleX = s
                scaleY = s
            }
            .drawBehind { drawRect(lerp(startBg, paper, rise.value)) }
            .clickable(remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        // The pen: 240dp in the middle, as in the start window, then up and smaller; its
        // colour turns from that of the start window to the style's (one over the other).
        val penLayer = Modifier
            .size(240.dp)
            .graphicsLayer {
                val k = 1f - 0.42f * rise.value
                scaleX = k
                scaleY = k
                translationY = -78.dp.toPx() * rise.value
            }
        Image(painterResource(R.drawable.splash_logo), contentDescription = null, colorFilter = ColorFilter.tint(startInk), modifier = penLayer)
        Image(
            painterResource(R.drawable.splash_logo),
            contentDescription = null,
            colorFilter = ColorFilter.tint(ink),
            modifier = penLayer.graphicsLayer { alpha = rise.value },
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = words.value
                translationY = (62f + 16f * (1f - words.value)).dp.toPx()
            },
        ) {
            // The gleam runs over the letters only: drawn over them in the draw phase (the
            // letters are not laid out again each frame).
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val g = gleam.value
                        if (g > -0.25f && g < 1.25f) {
                            val w = size.width
                            val x = g * w
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, shine, Color.Transparent),
                                    start = Offset(x - 0.16f * w, 0f),
                                    end = Offset(x + 0.16f * w, size.height * 0.6f),
                                ),
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                    },
            ) {
                Text(
                    "Ink",
                    color = ink,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = if (style == UiStyle.MINIMAL) FontWeight.Light else FontWeight.Normal,
                    fontSize = 72.sp,
                    letterSpacing = 1.sp,
                )
                Text(
                    "Notes",
                    color = ink,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Light,
                    fontSize = 72.sp,
                    letterSpacing = 3.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(90.dp).height(0.8.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, ink))))
                Canvas(Modifier.size(14.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = size.width / 2
                    val k = r * 0.22f
                    drawPath(
                        Path().apply {
                            moveTo(c.x, c.y - r)
                            quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
                            quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
                            quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
                            quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
                            close()
                        },
                        ink,
                    )
                }
                Box(Modifier.width(90.dp).height(0.8.dp).background(Brush.horizontalGradient(listOf(ink, Color.Transparent))))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                tr("РУКОПИСНЫЕ ТЕТРАДИ", "HANDWRITTEN NOTEBOOKS"),
                fontSize = 12.sp,
                letterSpacing = 6.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
