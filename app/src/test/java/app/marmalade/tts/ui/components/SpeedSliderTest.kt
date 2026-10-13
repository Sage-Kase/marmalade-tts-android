package app.marmalade.tts.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The speed slider's stops (#71): the 0.1× grid, plus the reader's alias speed
 * when that is off the grid, so sliding back to it can end an override.
 */
class SpeedSliderTest {

    @Test
    fun `a drag snaps to the nearest tenth`() {
        assertEquals(1.3f, snapSpeed(1.27f, extraStop = null))
        assertEquals(0.5f, snapSpeed(0.51f, extraStop = null))
        assertEquals(2.0f, snapSpeed(1.98f, extraStop = null))
    }

    @Test
    fun `float noise lands on the exact speed`() {
        assertEquals(1.1f, snapSpeed(1.1000001f, extraStop = 1.1f))
    }

    @Test
    fun `an alias speed off the grid is a stop of its own`() {
        assertEquals(1.25f, snapSpeed(1.24f, extraStop = 1.25f))
        assertEquals(1.3f, snapSpeed(1.29f, extraStop = 1.25f))
    }

    @Test
    fun `an alias speed outside the slider's range adds no stop`() {
        assertEquals(2.0f, snapSpeed(2.0f, extraStop = 2.5f))
    }
}
