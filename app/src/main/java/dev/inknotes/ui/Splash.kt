package dev.inknotes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The name of the app over the start: "InkNotes" set in a serif with a gleam running across
 * it, a rule with a star and a line under it; fades away by itself (a tap skips it). Drawn in
 * the colours of the chosen style, over the library that loads underneath.
 */
@Composable
fun Splash(onDone: () -> Unit) {
    val style = LocalUiStyle.current
    val colors = MaterialTheme.colorScheme
    val shown = remember { Animatable(0f) }
    val gleam = remember { Animatable(-0.4f) }
    val gone = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { gleam.animateTo(1.4f, tween(1300, delayMillis = 250, easing = LinearEasing)) }
        shown.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(900)
        gone.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
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
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - gone.value }
            .background(colors.background)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = shown.value
                val s = 0.94f + 0.06f * shown.value
                scaleX = s
                scaleY = s
                translationY = (1f - shown.value) * 18.dp.toPx()
            },
        ) {
            val brush = Brush.linearGradient(
                0f to ink,
                (gleam.value - 0.15f).coerceIn(0f, 1f) to ink,
                gleam.value.coerceIn(0f, 1f) to shine,
                (gleam.value + 0.15f).coerceIn(0f, 1f) to ink,
                1f to ink,
                start = Offset(0f, 0f),
                end = Offset(900f, 120f),
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "Ink",
                    style = TextStyle(brush = brush),
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = if (style == UiStyle.MINIMAL) FontWeight.Light else FontWeight.Normal,
                    fontSize = 72.sp,
                    letterSpacing = 1.sp,
                )
                Text(
                    "Notes",
                    style = TextStyle(brush = brush),
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
                "РУКОПИСНЫЕ ТЕТРАДИ",
                fontSize = 12.sp,
                letterSpacing = 6.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
