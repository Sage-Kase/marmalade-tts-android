package app.marmalade.tts.ui.components

import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// -----------------------------------------------------------------------------
// How a stepped slider shows its stops (#84). Material draws a dot at every
// detent, which on the 15-stop speed slider read as a dotted line ("the
// sliders with the dots look weird", Max, #83 lab). The stops still snap and
// TalkBack still reads each value; only the marks change. Each stop the
// thumb reaches gives a light haptic tick (TextHandleMove) in their place.
// -----------------------------------------------------------------------------

/** The round-2 lab's options for the marks on a stepped slider's track. */
internal enum class SliderMarks {
    /** No marks: a clean track; the value label and the haptic tick carry the stops. */
    NONE,

    /** One mark, at the slider's natural value (1× speed, Pocket's default liveliness). */
    ONE_MARK,

    /** Every stop, as small faint dots: a ruler rather than a dotted line. */
    FAINT,
}

/** The shipped pick. */
internal val SLIDER_MARKS = SliderMarks.NONE

/**
 * The track for a stepped slider, drawn per [SLIDER_MARKS]. [mark] is the
 * natural value [SliderMarks.ONE_MARK] keeps, inside the slider's range.
 */
@Composable
internal fun SteppedSliderTrack(state: SliderState, mark: Float?) {
    val range = state.valueRange
    val markFraction = mark?.let { (it - range.start) / (range.endInclusive - range.start) }
    SliderDefaults.Track(
        sliderState = state,
        drawTick = when (SLIDER_MARKS) {
            SliderMarks.NONE -> { _, _ -> }
            SliderMarks.ONE_MARK -> { offset, color ->
                if (markFraction != null && isMarkTick(offset.x / size.width, markFraction, state.steps)) {
                    drawTickDot(offset, color, 2.dp.toPx())
                }
            }
            SliderMarks.FAINT -> { offset, color ->
                drawTickDot(offset, color.copy(alpha = color.alpha * 0.45f), 1.dp.toPx())
            }
        },
    )
}

/**
 * Whether the tick at [tickFraction] of the track is the one at
 * [markFraction]. Ticks are evenly spaced ([steps] between the ends), so
 * anything within half a gap is the mark's own; the track's rounded ends
 * nudge the outer ticks by a few pixels, well inside that.
 */
internal fun isMarkTick(tickFraction: Float, markFraction: Float, steps: Int): Boolean =
    abs(tickFraction - markFraction) < 0.5f / (steps + 1)

private fun DrawScope.drawTickDot(center: Offset, color: Color, radius: Float) =
    drawCircle(color = color, radius = radius, center = center)
