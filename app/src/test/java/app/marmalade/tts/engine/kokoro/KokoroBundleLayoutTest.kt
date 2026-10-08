package app.marmalade.tts.engine.kokoro

import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine.RenderPath
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #13 "E": which bundles KokoroDirect accepts, and which path renders them. */
class KokoroBundleLayoutTest {

    private val dir: File = Files.createTempDirectory("kokoro-layout").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String) = File(dir, name).apply { parentFile!!.mkdirs(); writeText("x") }

    private fun stageCommon() {
        file("voices.bin")
        file("tokens.txt")
    }

    private val voiceKeys = KokoroDirectVoiceCatalog.voices.map { it.id.substringAfter(':') }

    private fun stageGenStats() {
        for (key in voiceKeys) file("genstats/$key.bin")
    }

    private fun stageStreaming() {
        file("planner.onnx")
        file("gen.onnx")
        stageGenStats()
    }

    @Test
    fun `an unsplit-only bundle (v23, German) renders whole`() {
        stageCommon()
        file("model.onnx")
        assertTrue(KokoroDirectEngine.isInstalledIn(dir))
        assertFalse(KokoroDirectEngine.hasStreamingLayout(dir))
        assertEquals(RenderPath.WHOLE, KokoroDirectEngine.renderPathFor(dir, streamKnob = null))
    }

    @Test
    fun `a streaming-only bundle is installed and always streams`() {
        stageCommon()
        stageStreaming()
        assertTrue(KokoroDirectEngine.isInstalledIn(dir))
        assertEquals(RenderPath.STREAMING, KokoroDirectEngine.renderPathFor(dir, streamKnob = null))
        // Nothing to fall back on: the knob can't force the whole path.
        assertEquals(RenderPath.STREAMING, KokoroDirectEngine.renderPathFor(dir, streamKnob = "false"))
    }

    @Test
    fun `with both layouts it streams unless the debug knob turns it off`() {
        stageCommon()
        stageStreaming()
        file("model.onnx")
        assertEquals(RenderPath.STREAMING, KokoroDirectEngine.renderPathFor(dir, streamKnob = null))
        assertEquals(RenderPath.STREAMING, KokoroDirectEngine.renderPathFor(dir, streamKnob = "true"))
        assertEquals(RenderPath.WHOLE, KokoroDirectEngine.renderPathFor(dir, streamKnob = "false"))
    }

    @Test
    fun `the streaming layout needs both graphs and the generator statistics`() {
        stageCommon()
        file("planner.onnx")
        file("gen.onnx")
        assertFalse("no genstats/", KokoroDirectEngine.hasStreamingLayout(dir))
        assertFalse(KokoroDirectEngine.isInstalledIn(dir))
        File(dir, "genstats").mkdirs()
        assertFalse("empty genstats/", KokoroDirectEngine.hasStreamingLayout(dir))
        stageGenStats()
        File(dir, "gen.onnx").delete()
        assertFalse("no generator", KokoroDirectEngine.hasStreamingLayout(dir))
        file("gen.onnx")
        assertTrue(KokoroDirectEngine.hasStreamingLayout(dir))
        assertTrue(KokoroDirectEngine.isInstalledIn(dir))
    }

    @Test
    fun `voices and tokens are required on either path`() {
        file("model.onnx")
        stageStreaming()
        assertFalse(KokoroDirectEngine.isInstalledIn(dir))
        file("voices.bin")
        assertFalse(KokoroDirectEngine.isInstalledIn(dir))
        file("tokens.txt")
        assertTrue(KokoroDirectEngine.isInstalledIn(dir))
        assertFalse(KokoroDirectEngine.isInstalledIn(File(dir, "missing")))
    }

    @Test
    fun `a graph shipped as ort is preferred and counts for the layout`() {
        assertEquals("planner.onnx", KokoroDirectEngine.modelFile(dir, "planner").name)
        file("planner.onnx")
        assertEquals("planner.onnx", KokoroDirectEngine.modelFile(dir, "planner").name)
        file("planner.ort")
        assertEquals("planner.ort", KokoroDirectEngine.modelFile(dir, "planner").name)

        stageCommon()
        file("gen.ort")
        stageGenStats()
        assertTrue(KokoroDirectEngine.hasStreamingLayout(dir))
        File(dir, "planner.onnx").delete()
        assertTrue("planner.ort alone", KokoroDirectEngine.hasStreamingLayout(dir))
    }

    @Test
    fun `a partial genstats renders whole when the unsplit model is there, else is not installed`() {
        stageCommon()
        stageStreaming()
        File(dir, "genstats/${voiceKeys.last()}.bin").delete()
        assertFalse(KokoroDirectEngine.hasStreamingLayout(dir))
        assertFalse(KokoroDirectEngine.isInstalledIn(dir))
        file("model.onnx")
        assertTrue(KokoroDirectEngine.isInstalledIn(dir))
        assertEquals(RenderPath.WHOLE, KokoroDirectEngine.renderPathFor(dir, streamKnob = null))
    }

    @Test
    fun `an empty stats file doesn't count`() {
        stageCommon()
        stageStreaming()
        File(dir, "genstats/${voiceKeys.first()}.bin").writeBytes(ByteArray(0))
        assertFalse(KokoroDirectEngine.hasStreamingLayout(dir))
    }

    @Test
    fun `generator statistics resolve to a catalog voice, never a raw key`() {
        assertEquals("af_heart", KokoroDirectEngine.genStatsKeyFor("af_heart"))
        assertEquals("zf_xiaobei", KokoroDirectEngine.genStatsKeyFor("zf_xiaobei"))
        assertEquals(voiceKeys.first(), KokoroDirectEngine.genStatsKeyFor("no_such_voice"))
        assertEquals(voiceKeys.first(), KokoroDirectEngine.genStatsKeyFor("../../shared_prefs/x"))
    }

    @Test
    fun `the streaming sessions spin unless the debug knob says false`() {
        assertTrue(KokoroDirectEngine.streamSpinning(null))
        assertTrue(KokoroDirectEngine.streamSpinning("true"))
        assertFalse(KokoroDirectEngine.streamSpinning("false"))
        assertTrue("an unparseable knob keeps the default", KokoroDirectEngine.streamSpinning("off"))
    }
}
