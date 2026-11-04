package dev.inknotes.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * The app's slider: the stock one without the gaps cut into the track beside the thumb,
 * which near either end hid the short part of the line the thumb runs on. With [valueLabel],
 * the value shows in a bubble over the thumb while it is held or dragged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueLabel: ((Float) -> String)? = null,
) {
    val colors = SliderDefaults.colors()
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        thumb = {
            Box(contentAlignment = Alignment.Center) {
                SliderDefaults.Thumb(interaction, colors = colors, enabled = enabled)
                if (valueLabel != null && (dragged || pressed)) ValueBubble(valueLabel(value))
            }
        },
        track = { state ->
            SliderDefaults.Track(state, enabled = enabled, colors = colors, drawStopIndicator = null, thumbTrackGapSize = 0.dp)
        },
        valueRange = valueRange,
    )
}

/** The value over the thumb, in a window of its own: the tool bar around the slider clips. */
@Composable
private fun ValueBubble(text: String) {
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val above = remember(gap) { AboveAnchor(gap) }
    Popup(popupPositionProvider = above, properties = PopupProperties(focusable = false)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp,
        ) {
            Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Centred over the anchor, [gap] above it; under it when there is no room above. */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val top = anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, if (top >= 0) top else anchorBounds.bottom + gap)
    }
}
