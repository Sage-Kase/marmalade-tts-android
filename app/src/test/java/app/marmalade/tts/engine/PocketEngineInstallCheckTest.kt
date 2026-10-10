package app.marmalade.tts.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.ui.screen.NoOpPreferencesDataStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #38: Pocket's runtime install check asks for the default voice's WAV
 * only. v27 adds five voices; when the check asked for every catalog voice, a
 * v21 install (which lacks their WAVs) reported "not installed" on the phone
 * and every Pocket alias broke until the user updated.
 *
 * Issue #44: on such an install a request for one of the missing voices
 * speaks with the default voice instead of failing, and only the voices on
 * disk are listed.
 */
@RunWith(RobolectricTestRunner::class)
class PocketEngineInstallCheckTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(ctx.filesDir, "engines/${PocketVoiceCatalog.ENGINE}")

    @Before
    fun layOutV21Bundle() {
        File(dir, "voices").mkdirs()
        for (name in listOf("tokenizer.model", "bos_before_voice.npy")) File(dir, name).writeText("x")
        val graphs = listOf("text_conditioner", "mimi_encoder", "mimi_decoder", "flow_lm_main_int8", "flow_lm_flow")
        for (g in graphs) File(dir, "$g.onnx").writeText("x")
        File(dir, "bundle.json").writeText(
            """{"sample_rate": 24000, "frame_rate": 12.5, "samples_per_frame": 1920, "latent_dim": 32,
               "conditioning_dim": 1024, "max_token_per_chunk": 50, "model_recommended_frames_after_eos": null,
               "predefined_voices": ["alba", "azelma", "eponine", "fantine", "javert", "marius"],
               "onnx_files": {"text_conditioner": "text_conditioner.onnx", "mimi_encoder": "mimi_encoder.onnx",
               "mimi_decoder": "mimi_decoder.onnx", "flow_lm_main": "flow_lm_main_int8.onnx",
               "flow_lm_flow": "flow_lm_flow.onnx"},
               "flow_lm_state_manifest": [], "mimi_state_manifest": []}""",
        )
        // v21's voices: none of v27's mary, rita, stephen, ramone, jenny.
        for (v in listOf("alba", "azelma", "eponine", "fantine", "javert", "marius")) {
            File(dir, "voices/$v.wav").writeText("x")
        }
    }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun engine() = PocketEngine(ctx, SettingsRepository(NoOpPreferencesDataStore))

    @Test
    fun `a v21 install without the v27 voices still counts as installed`() {
        assertTrue(engine().isInstalled())
    }

    @Test
    fun `without the default voice's WAV it does not`() {
        File(dir, "voices/alba.wav").delete()
        assertFalse(engine().isInstalled())
    }

    @Test
    fun `the installed voices are the offered ones on disk`() {
        // marius is on disk but no longer offered; v27's five are offered but absent.
        assertEquals(setOf("alba", "azelma", "eponine", "fantine", "javert"), engine().installedVoiceKeys())
    }

    @Test
    fun `a voice with only its prepared embedding counts as installed`() {
        File(dir, "voice_cache").mkdirs()
        File(dir, "voice_cache/mary.emb").writeText("x")
        assertTrue("mary" in engine().installedVoiceKeys())
    }

    @Test
    fun `a missing voice renders with the default voice, a present one with itself`() {
        val engine = engine()
        assertEquals("alba", engine.voiceToRender("${PocketVoiceCatalog.ENGINE}:mary"))
        assertEquals("alba", engine.voiceToRender("jenny"))
        assertEquals("javert", engine.voiceToRender("${PocketVoiceCatalog.ENGINE}:javert"))
        assertEquals("cloned-1234", engine.voiceToRender("${PocketVoiceCatalog.ENGINE}:cloned-1234"))
    }
}
