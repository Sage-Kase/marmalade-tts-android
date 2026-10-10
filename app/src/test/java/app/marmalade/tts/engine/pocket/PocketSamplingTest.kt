package app.marmalade.tts.engine.pocket

import app.marmalade.tts.data.Expressiveness
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/** The alias's expressiveness → the std of Pocket's starting noise (#36). */
class PocketSamplingTest {

    @Test
    fun `no setting samples at the default temperature`() {
        assertEquals(sqrt(Expressiveness.POCKET_DEFAULT), pocketNoiseStd(null), 1e-6f)
    }

    @Test
    fun `the alias's value is the temperature`() {
        assertEquals(sqrt(0.3f), pocketNoiseStd(0.3f), 1e-6f)
        assertEquals(sqrt(0.6f), pocketNoiseStd(0.6f), 1e-6f)
    }

    @Test
    fun `out-of-range values are held to the slider's range`() {
        assertEquals(sqrt(Expressiveness.MIN), pocketNoiseStd(0f), 1e-6f)
        assertEquals(sqrt(Expressiveness.MAX), pocketNoiseStd(4f), 1e-6f)
    }
}
