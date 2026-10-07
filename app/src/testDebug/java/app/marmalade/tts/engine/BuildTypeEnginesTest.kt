package app.marmalade.tts.engine

import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #9's debug-only engine seam. Both streaming prototypes are retired
 * (Kitten in #10, Kokoro in #13 "E": the shipped engines stream themselves),
 * so the debug build lists none and moves what pointed at them.
 */
class BuildTypeEnginesTest {

    private val retiredKokoro = "kokoro-stream-dev"

    @Test
    fun `the debug build lists no extra engines`() {
        assertEquals(emptyList<String>(), BuildTypeEngines.names)
        assertEquals(emptyList<Any>(), BuildTypeEngines.voices)
        assertEquals(emptySet<TtsEngine>(), BuildTypeEngineModule.engines())
    }

    @Test
    fun `the retired stream engines no longer route or list`() {
        for (name in listOf(retiredKokoro, "kitten-stream-dev")) {
            assertFalse(name in EngineRegistry.ENGINE_NAMES)
            assertNull(BuildTypeEngines.displayName(name))
            assertNull(BuildTypeEngines.voiceLabel(name, "af_heart"))
        }
    }

    @Test
    fun `the Kokoro stream engine retires onto KokoroDirect`() {
        assertEquals(mapOf(retiredKokoro to KokoroDirectEngine.ENGINE_NAME), BuildTypeEngines.retiredEngines)
        for ((retired, target) in BuildTypeEngines.retiredEngines) {
            assertTrue("$target routes", target in EngineRegistry.ENGINE_NAMES)
            assertFalse("$retired is not listed any more", retired in BuildTypeEngines.names)
        }
    }
}
