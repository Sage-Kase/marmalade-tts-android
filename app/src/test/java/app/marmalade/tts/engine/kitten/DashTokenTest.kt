package app.marmalade.tts.engine.kitten

import app.marmalade.tts.phonemizer.GermanG2P
import app.marmalade.tts.phonemizer.KokoroEspeakG2P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #43: a spaced en dash mid-sentence reaches the shared Kokoro/Kitten vocab
 * as itself and must not encode to PAD (token 0).
 *
 * espeak ends a clause at "– " (as at "— "), and the JNI shim
 * (espeak_jni.c) re-injects the glyph as " –" after that clause's phonemes.
 * The strings below are shaped exactly that way; native espeak can't run in
 * a JVM test, so each path is driven from its post-espeak seam. The VITS
 * path splits dashes off before espeak instead (VitsPhonemeIdsTest).
 */
class DashTokenTest {

    private val emDash = encodePhonemes("—").single()

    private fun assertNoPad(ipa: String) {
        val ids = encodePhonemes(ipa)
        assertFalse("PAD in $ipa -> ${ids.toList()}", PAD_TOKEN in ids)
    }

    @Test
    fun `en dash encodes as the em dash pause`() {
        assertEquals(9, emDash)
        assertEquals(emDash, encodePhonemes("–").single())
    }

    @Test
    fun `kitten english, untied espeak with a re-injected en dash`() {
        assertNoPad("həlˈoʊ – wˈɜːld . ")
    }

    @Test
    fun `kokoro non-english, tied espeak through the misaki postprocess`() {
        // French "Bonjour – le monde." in tie mode.
        assertNoPad(KokoroEspeakG2P.postprocess("bɔ̃ʒˈuʁ – lə- mˈɔ̃d . "))
    }

    @Test
    fun `kokoro german, espeak span`() {
        val g2p = GermanG2P { "hˈaloː – vˈiː ɡˈeːt^s ? " }
        val ipa = g2p.phonemes("Hallo – wie geht's?")
        assertNoPad(ipa)
        assertEquals("one em-dash pause", 1, encodePhonemes(ipa).count { it == emDash })
    }

    @Test
    fun `kokoro german, en dash passed through between two override words`() {
        // Override words never reach espeak, so the punctuation-only span
        // between them is passed through literally ("–" itself).
        val g2p = GermanG2P { error("no espeak span expected, got '$it'") }
        val ipa = g2p.phonemes("Claude – Coqui")
        assertEquals("klˈoːt – kˈoːki", ipa)
        assertNoPad(ipa)
    }
}
