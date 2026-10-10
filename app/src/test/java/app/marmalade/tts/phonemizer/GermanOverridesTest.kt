package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [GermanOverrides] against the golden `lookup` vectors and spot-checks
 * the override table. Robolectric only for the org.json fixture load.
 */
@RunWith(RobolectricTestRunner::class)
class GermanOverridesTest {

    @Test
    fun `every lookup vector matches byte-exactly`() {
        for (v in GermanFixtures.lookup()) {
            assertEquals("normalizeForLookup(${v.input})", v.expected, GermanOverrides.normalizeForLookup(v.input))
        }
    }

    @Test
    fun `override hits resolve to the table phonemes`() {
        assertEquals("ɡˈɪthab", GermanOverrides.overrideFor("GitHub"))
        assertEquals("pˈaɪtɔːɹtʃ", GermanOverrides.overrideFor("PyTorch"))
        assertEquals("hˈyːpnoːzə", GermanOverrides.overrideFor("Hypnose"))
    }

    @Test
    fun `aneinander skips espeak's stray digit`() {
        // Pinned espeak-ng 1.52 phonemizes it "ˌan1a^ɪnˈandɜ" (#46).
        assertEquals("ˌanInˈandɜ", GermanOverrides.overrideFor("aneinander"))
        assertEquals("ˌanInˈandɜ", GermanOverrides.overrideFor("Aneinander"))
    }

    @Test
    fun `aneinander reaches the model without a digit`() {
        val requested = ArrayList<String>()
        val g2p = GermanG2P { span ->
            requested.add(span)
            when (span.trim()) {
                "Sie lehnten" -> "zˌiː lˈeːntən"
                else -> error("unexpected espeak span: $span")
            }
        }
        val out = g2p.phonemes("Sie lehnten aneinander.")
        assertEquals(listOf("Sie lehnten "), requested)
        assertEquals("zˌiː lˈeːntən ˌanInˈandɜ.", out)
    }

    @Test
    fun `plain German word is not overridden`() {
        assertNull(GermanOverrides.overrideFor("Haus"))
    }
}
