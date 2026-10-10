package app.marmalade.tts.engine.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

/** #44: the fallback picks the default voice for a missing one and reports it once per voice. */
class PocketVoiceFallbackTest {

    private val reported = ArrayList<Pair<String, String>>()
    private val fallback = PocketVoiceFallback(isPresent = { it in setOf("alba", "javert") }) { requested, used ->
        reported += requested to used
    }

    @Test
    fun `a present voice is used as is, with nothing reported`() {
        assertEquals("javert", fallback.keyFor("javert"))
        assertEquals(emptyList<Pair<String, String>>(), reported)
    }

    @Test
    fun `a missing voice falls back to the default, reported once however often it is asked for`() {
        repeat(3) { assertEquals("alba", fallback.keyFor("mary")) }
        assertEquals("alba", fallback.keyFor("rita"))
        assertEquals(listOf("mary" to "alba", "rita" to "alba"), reported)
    }

    @Test
    fun `a cloned voice is never replaced`() {
        assertEquals("cloned-abc", fallback.keyFor("cloned-abc"))
        assertEquals(emptyList<Pair<String, String>>(), reported)
    }
}
