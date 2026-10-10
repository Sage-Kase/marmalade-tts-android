package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.VoicePackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every voice a picker can offer resolves to a credit line or to a
 * deliberate none (#37). A new voice or pack that forgets its credit fails
 * here rather than shipping uncredited.
 */
class VoiceCreditsTest {

    /** The English line, as the picker renders it. */
    private fun line(voice: VoiceMeta): String? = VoiceCredits.forVoice(voice)?.let { credit ->
        credit.render("Voice donor", credit.modelBy?.let { "model by $it" })
    }

    @Test
    fun `every Pocket voice and its developer twin has a credit`() {
        for (voice in PocketVoiceCatalog.voices + PocketDevVoiceCatalog.voices) {
            assertNotNull(voice.id, VoiceCredits.forVoice(voice))
        }
    }

    @Test
    fun `Pocket credits follow the per-voice licence table`() {
        fun pocket(key: String) = PocketVoiceCatalog.voices.single { it.id == PocketVoiceCatalog.voiceId(key) }
        assertEquals("Alba Mackenna (Kyutai) · CC BY 4.0", line(pocket("alba")))
        for (key in PocketVoiceCatalog.VCTK_VOICE_KEYS) {
            assertEquals("VCTK (University of Edinburgh) · CC BY 4.0", line(pocket(key)))
        }
        assertEquals("Voice donor · CC0", line(pocket("javert")))
        for (key in PocketVoiceCatalog.KYUTAI_DONOR_VOICE_KEYS) {
            assertEquals("Voice donor · CC0", line(pocket(key)))
        }
        // The Jenny (Dioco) licence: "Jenny (Dioco)" where practical; bespoke, so no licence name.
        assertEquals("Jenny (Dioco)", line(pocket("jenny")))
    }

    @Test
    fun `every VITS voice of every pack has a credit, and CC BY ones name their source`() {
        for (voice in VitsVoiceCatalog.voices) {
            val credit = VoiceCredits.forVoice(voice)
            assertNotNull(voice.id, credit)
            if (credit!!.license == "CC BY 4.0") assertNotNull(voice.id, credit.source)
        }
        for (pack in VoicePackCatalog.all) assertNotNull(pack.id, pack.credit)
    }

    @Test
    fun `Jenny keeps the Dioco attribution and credits the model's author`() {
        val jenny = VitsVoiceCatalog.voices.single {
            VitsVoiceCatalog.packIdOf(it.id) == VoicePackCatalog.EN_JENNY_DIOCO_MEDIUM.id
        }
        assertEquals("Jenny (Dioco)", jenny.displayName)
        assertEquals("Dioco · model by Bryce Beattie", line(jenny))
    }

    @Test
    fun `Kokoro German credits Thorsten Mueller`() {
        assertEquals("Thorsten Müller · CC0", line(KokoroGermanVoiceCatalog.voices.single()))
    }

    @Test
    fun `Kokoro and Kitten stock voices deliberately have none`() {
        for (voice in KokoroDirectVoiceCatalog.voices + KittenDirectVoiceCatalog.voices) {
            assertNull(voice.id, VoiceCredits.forVoice(voice))
        }
    }

    @Test
    fun `a cloned Pocket voice and a cloud voice have none`() {
        val alba = PocketVoiceCatalog.voices.first()
        assertNull(VoiceCredits.forVoice(alba.copy(id = PocketVoiceCatalog.voiceId("cloned-1234"))))
        assertNull(VoiceCredits.forVoice(alba.copy(id = "cloud-api-v1:venice:kokoro:af_sky", engine = CloudApiVoiceCatalog.ENGINE)))
    }

    @Test
    fun `render joins only the parts that exist`() {
        assertEquals("A · B", VoiceCredit(source = "A", license = "B").render("Donor", null))
        assertEquals("Donor · CC0", VoiceCredit(donor = true, license = "CC0").render("Donor", null))
        assertTrue(VoiceCredit(source = "A", modelBy = "M").render("Donor", "by M").endsWith("A · by M"))
    }
}
