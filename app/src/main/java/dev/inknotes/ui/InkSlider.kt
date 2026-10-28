package dev.inknotes.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's slider: the stock one without the gaps cut into the track beside the thumb,
 * which near either end hid the short part of the line the thumb runs on.
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
) {
    val colors = SliderDefaults.colors()
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        thumb = { SliderDefaults.Thumb(interaction, colors = colors, enabled = enabled) },
        track = { state ->
            SliderDefaults.Track(state, enabled = enabled, colors = colors, drawStopIndicator = null, thumbTrackGapSize = 0.dp)
        },
        valueRange = valueRange,
    )
}
