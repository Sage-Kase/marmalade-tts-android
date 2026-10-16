package app.marmalade.tts.ui.components

import app.marmalade.tts.data.Expressiveness
import app.marmalade.tts.data.db.VoiceAlias
import org.junit.Assert.assertEquals
import org.junit.Test

class SliderMarksTest {

    /** The fractions Material puts the ticks at: every stop, ends included. */
    private fun ticks(steps: Int) = (0..steps + 1).map { it.toFloat() / (steps + 1) }

    private fun fraction(value: Float, min: Float, max: Float) = (value - min) / (max - min)

    @Test
    fun speedSliderMarksOnlyTheOneTimesStop() {
        val mark = fraction(1f, VoiceAlias.MIN_SPEED, VoiceAlias.MAX_SPEED)
        val marked = ticks(14).withIndex().filter { isMarkTick(it.value, mark, 14) }.map { it.index }
        // 0.5× is stop 0, so 1.0× is stop 5.
        assertEquals(listOf(5), marked)
    }

    @Test
    fun expressivenessSliderMarksOnlyPocketsDefault() {
        val steps = 4
        val mark = fraction(Expressiveness.POCKET_DEFAULT, Expressiveness.MIN, Expressiveness.MAX)
        val marked = ticks(steps).withIndex().filter { isMarkTick(it.value, mark, steps) }.map { it.index }
        // 0.2 is stop 0, so 0.3 is stop 1.
        assertEquals(listOf(1), marked)
    }

    @Test
    fun aFewPixelsOffStillFindsTheMark() {
        val mark = 1f / 3
        // A 300 px track whose outer ticks the rounded ends push in by 4 px.
        assertEquals(true, isMarkTick((100f + 4f) / 300f, mark, 14))
        assertEquals(false, isMarkTick((120f) / 300f, mark, 14))
    }
}
