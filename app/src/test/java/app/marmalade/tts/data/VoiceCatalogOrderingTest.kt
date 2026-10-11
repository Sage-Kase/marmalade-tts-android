package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contracts introduced with `sortOrder` + pretty Kokoro names
 * (CATALOG_VERSION 32): ids keep the raw voice key while displayName is
 * cosmetic, and both catalogs carry an explicit curated order.
 */
class VoiceCatalogOrderingTest {

    @Test
    fun `kokoro displayName is flagged but id keeps the raw key`() {
        val bella = KokoroDirectVoiceCatalog.voices.first { it.id.endsWith(":af_bella") }
        assertEquals("🇺🇸 Bella", bella.displayName)
        assertEquals("kokoro-direct-v1_0:af_bella", bella.id)

        val kumo = KokoroDirectVoiceCatalog.voices.first { it.id.endsWith(":jm_kumo") }
        assertEquals("🇯🇵 Kumo", kumo.displayName)
    }

    @Test
    fun `speakerIdFor resolves raw keys to voices_bin rows`() {
        assertEquals(2, KokoroDirectVoiceCatalog.speakerIdFor("af_bella"))
        assertEquals(41, KokoroDirectVoiceCatalog.speakerIdFor("jm_kumo"))
        assertEquals(-1, KokoroDirectVoiceCatalog.speakerIdFor("🇺🇸 Bella"))
    }

    @Test
    fun `kokoro sortOrder equals speaker index`() {
        for (v in KokoroDirectVoiceCatalog.voices) {
            assertEquals(v.id, KokoroDirectVoiceCatalog.speakerIdFor(v.id.substringAfter(':')), v.sortOrder)
        }
    }

    @Test
    fun `sky is not offered but keeps its voices_bin row, and renders as heart`() {
        val sky = KokoroDirectVoiceCatalog.voiceId("af_sky")
        val heart = KokoroDirectVoiceCatalog.voiceId("af_heart")
        assertEquals(mapOf(sky to heart), KokoroDirectVoiceCatalog.retiredVoices)
        assertTrue(KokoroDirectVoiceCatalog.voices.none { it.id == sky })
        assertEquals(52, KokoroDirectVoiceCatalog.voices.size)
        // Removing Sky from the list must not shift the rows after it.
        assertEquals(10, KokoroDirectVoiceCatalog.speakerIdFor("af_sky"))
        assertEquals(11, KokoroDirectVoiceCatalog.speakerIdFor("am_adam"))
        assertEquals(52, KokoroDirectVoiceCatalog.speakerIdFor("zm_yunyang"))
        assertEquals("af_heart", KokoroDirectVoiceCatalog.liveVoiceKey("af_sky"))
        assertEquals("af_bella", KokoroDirectVoiceCatalog.liveVoiceKey("af_bella"))
        assertEquals("no_such_voice", KokoroDirectVoiceCatalog.liveVoiceKey("no_such_voice"))
    }

    @Test
    fun `kitten voices are in the curated best-first order`() {
        assertEquals(
            listOf("Rosie", "Bruno", "Kiki", "Hugo", "Bella", "Jasper", "Luna", "Leo"),
            KittenDirectVoiceCatalog.voices.sortedBy { it.sortOrder }.map { it.displayName },
        )
    }

    @Test
    fun `spokenName strips the flag for speech`() {
        val flagged = KokoroDirectVoiceCatalog.voices.first { it.id.endsWith(":af_bella") }
        assertEquals("Bella", flagged.spokenName)

        val plain = VoiceMeta("kitten:Bella", "kitten", "Bella", "en-US", 24000, "female")
        assertEquals("Bella", plain.spokenName)
    }
}
