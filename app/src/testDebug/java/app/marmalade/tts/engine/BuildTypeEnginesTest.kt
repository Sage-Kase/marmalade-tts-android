package app.marmalade.tts.engine

import app.marmalade.tts.data.VoicePathResolver
import app.marmalade.tts.data.cloud.CloudProviderDirectory
import app.marmalade.tts.devbench.KittenStreamDevEngine
import app.marmalade.tts.devbench.KokoroStreamDevEngine
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #9: the debug build's streaming engines are pickable and routable like any other. */
class BuildTypeEnginesTest {

    private val kitten = KittenStreamDevEngine.ENGINE
    private val kokoro = KokoroStreamDevEngine.ENGINE

    private class NamedEngine(override val engineName: String) : TtsEngine {
        override val sampleRate: Int = 24_000
        override fun isInstalled(): Boolean = true
        override fun isLoaded(): Boolean = false
        override fun ensureModelLoaded() = Unit
        override suspend fun synthesize(
            text: String,
            voiceId: String,
            speed: Float,
            phonemizationLanguage: String?,
        ): SynthAudio = SynthAudio(ShortArray(0), sampleRate)
        override fun release() = Unit
    }

    @Test
    fun `both stream engines route in-app and through system TTS`() {
        for (name in listOf(kitten, kokoro)) {
            assertTrue(name in EngineRegistry.ENGINE_NAMES)
            assertTrue(EngineRegistry.isSystemTtsEngine(name))
        }
        assertEquals(kitten, EngineRegistry.engineNameFor("$kitten:Bella"))
        assertEquals(kokoro, EngineRegistry.systemTtsEngineNameFor("$kokoro:af_heart"))
    }

    @Test
    fun `they are on-device but never warmed`() {
        val registry = EngineRegistry(EngineRegistry.ENGINE_NAMES.map(::NamedEngine))
        val onDevice = registry.onDevice.map { it.engineName }
        val warmable = registry.warmable.map { it.engineName }
        assertTrue(kitten in onDevice && kokoro in onDevice)
        assertFalse(kitten in warmable || kokoro in warmable)
    }

    @Test
    fun `voice rows are the voices with generator stats, ids the engines parse`() {
        val byEngine = BuildTypeEngines.voices.groupBy { it.engine }
        assertEquals(
            listOf("rosie", "bruno", "kiki", "hugo", "bella", "jasper", "luna", "leo"),
            byEngine.getValue(kitten).map { it.id.substringAfter(':').lowercase() },
        )
        assertEquals(listOf("$kokoro:af_heart", "$kokoro:am_michael"), byEngine.getValue(kokoro).map { it.id })
        for (v in BuildTypeEngines.voices) {
            assertEquals(v.engine, EngineRegistry.enginePrefixOf(v.id))
            assertEquals("en-US", v.languageCode)
        }
    }

    @Test
    fun `picker labels name the engine and the voice`() {
        val paths = VoicePathResolver(CloudProviderDirectory { null })
        val heart = paths.resolve("$kokoro:af_heart", kokoro)
        assertEquals("Kokoro (streaming)", heart.source)
        assertEquals("Heart", heart.voice)
        assertEquals("Kitten Nano (streaming)", paths.resolve("$kitten:Bella", kitten).source)
        assertEquals("Kokoro (streaming)", EngineRegistry.displayName(kokoro))
    }

    @Test
    fun `installed means the sideloaded bundle and the borrowed voice tables are both there`() {
        val files = Files.createTempDirectory("bte").toFile()
        try {
            assertFalse(BuildTypeEngines.isInstalled(kitten, files))
            File(files, "engines/$kitten/genstats").mkdirs()
            File(files, "engines/$kitten/planner.onnx").writeText("x")
            File(files, "engines/$kitten/gen.onnx").writeText("x")
            assertFalse(BuildTypeEngines.isInstalled(kitten, files)) // no KittenDirect voices yet
            File(files, "engines/kitten-direct-v0_8/voices").mkdirs()
            assertTrue(BuildTypeEngines.isInstalled(kitten, files))
            assertFalse(BuildTypeEngines.isInstalled(kokoro, files))
            assertFalse(BuildTypeEngines.isInstalled("kitten-direct-v0_8", files))
        } finally {
            files.deleteRecursively()
        }
    }
}
