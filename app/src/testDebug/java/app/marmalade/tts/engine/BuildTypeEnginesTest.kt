package app.marmalade.tts.engine

import app.marmalade.tts.data.VoicePathResolver
import app.marmalade.tts.data.cloud.CloudProviderDirectory
import app.marmalade.tts.data.pickableVoices
import app.marmalade.tts.data.probeInstalledVoiceAssets
import app.marmalade.tts.devbench.KokoroStreamDevEngine
import app.marmalade.tts.install.EngineInstaller
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #9: the debug build's streaming engine is pickable and routable like any other. */
class BuildTypeEnginesTest {

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
    fun `the stream engine routes in-app and through system TTS`() {
        assertTrue(kokoro in EngineRegistry.ENGINE_NAMES)
        assertTrue(EngineRegistry.isSystemTtsEngine(kokoro))
        assertEquals(kokoro, EngineRegistry.engineNameFor("$kokoro:af_heart"))
        assertEquals(kokoro, EngineRegistry.systemTtsEngineNameFor("$kokoro:af_heart"))
    }

    @Test
    fun `the retired Kitten stream engine is gone (KittenDirect streams itself)`() {
        assertFalse("kitten-stream-dev" in EngineRegistry.ENGINE_NAMES)
        assertTrue(BuildTypeEngines.voices.none { it.engine == "kitten-stream-dev" })
    }

    @Test
    fun `it is on-device but never warmed`() {
        val registry = EngineRegistry(EngineRegistry.ENGINE_NAMES.map(::NamedEngine))
        assertTrue(kokoro in registry.onDevice.map { it.engineName })
        assertFalse(kokoro in registry.warmable.map { it.engineName })
    }

    @Test
    fun `voice rows are the voices with generator stats, ids the engines parse`() {
        assertEquals(listOf("$kokoro:af_heart", "$kokoro:am_michael"), BuildTypeEngines.voices.map { it.id })
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
        assertEquals("Kokoro (streaming)", EngineRegistry.displayName(kokoro))
    }

    @Test
    fun `installed means the sideloaded bundle and the borrowed voice table are both there`() {
        val files = Files.createTempDirectory("bte").toFile()
        try {
            assertFalse(BuildTypeEngines.isInstalled(kokoro, files))
            File(files, "engines/$kokoro/genstats").mkdirs()
            File(files, "engines/$kokoro/planner.onnx").writeText("x")
            File(files, "engines/$kokoro/gen.onnx").writeText("x")
            assertFalse(BuildTypeEngines.isInstalled(kokoro, files)) // no KokoroDirect voices yet
            File(files, "engines/kokoro-direct-v1_0/voices.bin").apply { parentFile!!.mkdirs() }.writeText("x")
            assertTrue(BuildTypeEngines.isInstalled(kokoro, files))
            assertFalse(BuildTypeEngines.isInstalled("kokoro-direct-v1_0", files))
        } finally {
            files.deleteRecursively()
        }
    }

    @Test
    fun `the sideloaded bundle makes every stream voice pickable outside developer mode`() = runBlocking {
        val files = Files.createTempDirectory("bte-pick").toFile()
        try {
            File(files, "engines/$kokoro/genstats").mkdirs()
            File(files, "engines/$kokoro/planner.onnx").writeText("x")
            File(files, "engines/$kokoro/gen.onnx").writeText("x")
            File(files, "engines/kokoro-direct-v1_0/voices.bin").apply { parentFile!!.mkdirs() }.writeText("x")
            val installer = EngineInstaller(
                filesDir = { files },
                engineHandle = { },
                httpFetcher = { _ -> throw IOException("not used") },
            )
            val assets = probeInstalledVoiceAssets(installer, anyCloudKeySet = false)
            assertTrue(kokoro in assets.engines)
            assertEquals(
                BuildTypeEngines.voices,
                BuildTypeEngines.voices.pickableVoices(assets, showDeveloper = false, flavor = "fdroid"),
            )
        } finally {
            files.deleteRecursively()
        }
    }
}
