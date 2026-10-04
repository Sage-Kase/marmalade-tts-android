package app.marmalade.tts.devbench

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.EngineRegistry
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.phonemizer.SharedEspeakData
import app.marmalade.tts.ui.screen.NoOpPreferencesDataStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The B1 dev engine reads only its own sideloaded directory and is invisible
 * to everything that routes in-app or system speech.
 */
@RunWith(RobolectricTestRunner::class)
class Kokoro7mDevEngineTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val engine = Kokoro7mDevEngine(
        ctx,
        SettingsRepository(NoOpPreferencesDataStore),
        SharedEspeakData(File(ctx.filesDir, "espeak-ng-data"), dataVersion = "test", copyAssets = { }),
    )

    @Test
    fun `installed only when its own bundle directory is complete`() {
        val dir = File(ctx.filesDir, "engines/${Kokoro7mDevEngine.ENGINE}")
        dir.deleteRecursively()
        assertFalse(engine.isInstalled())
        dir.mkdirs()
        File(dir, "model.onnx").writeText("x")
        File(dir, "voices.bin").writeText("x")
        assertFalse(engine.isInstalled())
        File(dir, "tokens.txt").writeText("x")
        assertTrue(engine.isInstalled())
    }

    @Test
    fun `is its own engine, not the installed Kokoro`() {
        assertEquals("kokoro-7m-dev", engine.engineName)
        assertEquals(24_000, engine.sampleRate)
        assertFalse(engine.supportsNativeSpeed)
        assertEquals("kokoro-7m-dev:af_msa", Kokoro7mDevEngine.VOICE_ID)
    }

    @Test
    fun `nothing in-app or system TTS can route to it`() {
        assertFalse(Kokoro7mDevEngine.ENGINE in EngineRegistry.ENGINE_NAMES)
        assertEquals(EngineRegistry.DEFAULT_ENGINE, EngineRegistry.engineNameFor(Kokoro7mDevEngine.VOICE_ID))
        assertEquals(EngineRegistry.DEFAULT_ENGINE, EngineRegistry.systemTtsEngineNameFor(Kokoro7mDevEngine.VOICE_ID))
        assertNull(EngineCatalog.byName(Kokoro7mDevEngine.ENGINE))
    }
}
