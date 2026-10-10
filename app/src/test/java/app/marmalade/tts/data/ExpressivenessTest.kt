package app.marmalade.tts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #36: which engines get the Expressiveness slider, and the
 * temperature Pocket is handed. [Expressiveness.appliesTo] gates both the
 * editor control and what the alias saves, so a wrong answer either hides
 * the slider from Pocket or stores a value nothing reads.
 */
class ExpressivenessTest {

    @Test
    fun `applies to Pocket and its developer twin only`() {
        assertTrue(Expressiveness.appliesTo(PocketVoiceCatalog.ENGINE))
        assertTrue(Expressiveness.appliesTo(PocketDevVoiceCatalog.ENGINE))

        for (engine in listOf(
            KokoroDirectVoiceCatalog.ENGINE,
            KokoroGermanVoiceCatalog.ENGINE,
            KittenDirectVoiceCatalog.ENGINE,
            VitsVoiceCatalog.ENGINE,
            CloudApiVoiceCatalog.ENGINE,
        )) {
            assertFalse("$engine is deterministic", Expressiveness.appliesTo(engine))
        }
    }

    @Test
    fun `pocket temperature is the default for null and clamped otherwise`() {
        assertEquals(Expressiveness.POCKET_DEFAULT, Expressiveness.pocketTemperature(null), 0f)
        assertEquals(0.4f, Expressiveness.pocketTemperature(0.4f), 0f)
        // A value outside the slider's range (a hand-edited or future row)
        // never reaches the sampler unclamped.
        assertEquals(Expressiveness.MIN, Expressiveness.pocketTemperature(0.0f), 0f)
        assertEquals(Expressiveness.MAX, Expressiveness.pocketTemperature(3.0f), 0f)
    }

    @Test
    fun `the slider runs 0_2 to 0_7 and starts at Kyutai's 0_3`() {
        // Max, 2026-10-10: 0.7 is the Lively end; the default is 0.3.
        assertEquals(0.2f, Expressiveness.MIN, 0f)
        assertEquals(0.7f, Expressiveness.MAX, 0f)
        assertEquals(0.3f, Expressiveness.POCKET_DEFAULT, 0f)
    }
}
