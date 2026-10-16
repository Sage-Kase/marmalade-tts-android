package app.marmalade.tts.ui.components

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.reader.roundedSpeed
import kotlin.math.abs

// -----------------------------------------------------------------------------
// The speed slider: the alias editor's, and the reader's alias dial (#71), so
// speed looks and works the same across the app. 0.5–2.0× in 0.1 steps.
//
// The reader also passes its reading alias's own speed as [extraStop]: moving
// back to it ends the reader's speed override (#67), so the slider must be
// able to land on it exactly. On the 0.1 grid it already does; a speed off the
// grid (1.25×, say) gets a stop of its own, and the slider
// drops its detents to snap by hand (Slider's `steps` are evenly spaced).
// Its track shows no stop marks (#84), and each new stop ticks.
// The onboarding alias step uses it too.
// -----------------------------------------------------------------------------

/** Detents between the ends: 15 intervals of 0.1× (Slider's `steps` excludes the endpoints). */
private const val STEPS = 14

/** Every 0.1× from [VoiceAlias.MIN_SPEED] to [VoiceAlias.MAX_SPEED]. */
private val GRID: List<Float> = (0..STEPS + 1).map {
    roundedSpeed(VoiceAlias.MIN_SPEED + it * (VoiceAlias.MAX_SPEED - VoiceAlias.MIN_SPEED) / (STEPS + 1))
}

/** [speed] on the 0.1× grid, give or take float noise. */
private fun onGrid(speed: Float): Boolean = GRID.any { abs(it - speed) < 0.001f }

/**
 * The stop nearest [value]: one of the 0.1× grid, or [extraStop] when it lies
 * in range — rounded like the reader's speeds ([roundedSpeed]), so a dragged
 * 1.1000001 reads as 1.1 and ends an override at 1.1.
 */
internal fun snapSpeed(value: Float, extraStop: Float?): Float {
    val extra = extraStop?.let(::roundedSpeed)?.takeIf { it in VoiceAlias.MIN_SPEED..VoiceAlias.MAX_SPEED }
    val stops = if (extra == null) GRID else GRID + extra
    return stops.minBy { abs(it - value) }
}

/**
 * [value] is shown clamped to the slider's range. [onValueChange] gets every
 * snapped step of a drag; [onValueChangeFinished] fires once it ends (a tap,
 * the end of a drag, or a TalkBack adjustment).
 */
@Composable
fun SpeedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    /** Spoken as the slider's name, e.g. "Speed". */
    label: String,
    /** Spoken as its value, e.g. "1.10×". */
    valueText: String,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    /** One more stop the slider must be able to land on exactly (the reader's alias speed). */
    extraStop: Float? = null,
) {
    val haptics = LocalHapticFeedback.current
    Slider(
        value = value.coerceIn(VoiceAlias.MIN_SPEED, VoiceAlias.MAX_SPEED),
        onValueChange = {
            val stop = snapSpeed(it, extraStop)
            if (stop != value) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onValueChange(stop)
        },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = VoiceAlias.MIN_SPEED..VoiceAlias.MAX_SPEED,
        steps = if (extraStop == null || onGrid(extraStop)) STEPS else 0,
        track = { SteppedSliderTrack(it) },
        modifier = modifier.semantics {
            contentDescription = label
            stateDescription = valueText
        },
    )
}
