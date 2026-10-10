package app.marmalade.tts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #19: Marius retired onto Alba; the VCTK voices sit behind one switch.
 * Issue #38: the v27 voices; the developer twin keeps its v10 archive's five.
 */
class PocketVoiceCatalogTest {

    private val offered = PocketVoiceCatalog.voices.map { it.id }.toSet()

    @Test
    fun `marius is no longer offered and retires onto alba`() {
        val marius = PocketVoiceCatalog.voiceId("marius")
        assertFalse(marius in offered)
        assertEquals(PocketVoiceCatalog.DEFAULT_VOICE_ID, PocketVoiceCatalog.retiredVoices[marius])
    }

    @Test
    fun `retired voices are never offered and always land on an offered voice`() {
        for ((from, to) in PocketVoiceCatalog.retiredVoices) {
            assertFalse("$from is retired but still offered", from in offered)
            assertTrue("$from retires onto $to, which isn't offered", to in offered)
        }
    }

    @Test
    fun `the default voice is offered and not a VCTK voice`() {
        assertTrue(PocketVoiceCatalog.DEFAULT_VOICE_ID in offered)
        assertFalse(PocketVoiceCatalog.DEFAULT_VOICE_ID.substringAfter(':') in PocketVoiceCatalog.VCTK_VOICE_KEYS)
    }

    @Test
    fun `the VCTK switch decides whether those voices are offered or retired`() {
        for (key in PocketVoiceCatalog.VCTK_VOICE_KEYS) {
            val id = PocketVoiceCatalog.voiceId(key)
            assertEquals(PocketVoiceCatalog.OFFER_VCTK_VOICES, id in offered)
            assertEquals(!PocketVoiceCatalog.OFFER_VCTK_VOICES, id in PocketVoiceCatalog.retiredVoices)
        }
    }

    @Test
    fun `the offered list today is v27's ten voices in bundle order`() {
        // Pins today's switch position; update with OFFER_VCTK_VOICES.
        assertTrue(PocketVoiceCatalog.OFFER_VCTK_VOICES)
        assertEquals(
            listOf("alba", "azelma", "eponine", "fantine", "javert", "mary", "rita", "stephen", "ramone", "jenny")
                .map(PocketVoiceCatalog::voiceId),
            PocketVoiceCatalog.voices.map { it.id },
        )
    }

    @Test
    fun `the v27 voices carry the names and genders Max gave`() {
        val byKey = PocketVoiceCatalog.voices.associateBy { it.id.substringAfter(':') }
        for ((key, name, gender) in listOf(
            Triple("mary", "Mary", "female"),
            Triple("rita", "Rita", "female"),
            Triple("stephen", "Stephen", "male"),
            Triple("ramone", "Ramone", "male"),
            // The Jenny (Dioco) licence requires this exact name.
            Triple("jenny", "Jenny", "female"),
        )) {
            assertEquals(name, byKey.getValue(key).displayName)
            assertEquals(gender, byKey.getValue(key).gender)
        }
    }

    @Test
    fun `the developer twin lists only the voices its v10 archive holds`() {
        assertEquals(
            listOf("alba", "azelma", "eponine", "fantine", "javert").map(PocketDevVoiceCatalog::voiceId),
            PocketDevVoiceCatalog.voices.map { it.id },
        )
    }
}
