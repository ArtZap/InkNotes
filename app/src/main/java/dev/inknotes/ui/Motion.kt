package dev.inknotes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * How the app moves: quick enough never to make anyone wait, smooth enough to show where
 * things come from and go. One place, so every animation feels the same.
 */
object Motion {
    /** Small things: a button's colour, a tab folding, a message changing. */
    const val FAST = 160

    /** Screens and panels: a notebook appearing, the half beside sliding in. */
    const val NORMAL = 220

    fun <T> fast() = tween<T>(FAST, easing = FastOutSlowInEasing)

    fun <T> normal() = tween<T>(NORMAL, easing = FastOutSlowInEasing)
}

/**
 * Fades in (and, with [fromX], slides in by that share of its width) when first shown: a new
 * screen or notebook does not just pop up. Only drawing changes: layout and touch are at once.
 */
fun Modifier.appear(fromX: Float = 0f, durationMs: Int = Motion.NORMAL): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = p.value
        translationX = (1f - p.value) * fromX * size.width
    }
}
