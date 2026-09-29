package app.marmalade.tts.service

import app.marmalade.tts.playback.NarratorHarness
import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import app.marmalade.tts.playback.OneShotRequest
import app.marmalade.tts.playback.OneShotVoice
import app.marmalade.tts.playback.Origin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ACTION_SPEAK` shim against the real Narrator: one in-app request id →
 * one one-shot → exactly one [PreviewCompletions] post, which is what keeps
 * `Synthesizer.speak`'s suspend-until-done contract; `ACTION_STOP_REQUEST`
 * cancels only that one-shot.
 */
class SpeakShimTest {

    private val completions = PreviewCompletions()
    private val posted = ArrayList<PreviewCompletions.Completion>()
    private var settled = 0

    private fun TestScope.rig(): Pair<NarratorHarness, SpeakShim> {
        val h = NarratorHarness(this)
        backgroundScope.launch(h.dispatcher) { completions.events.collect { posted += it } }
        val shim = SpeakShim(h.narrator, completions, backgroundScope)
        shim.onSettled = { settled++ }
        h.settle()
        return h to shim
    }

    private fun request(text: String = paragraph(1), origin: Origin = Origin.InApp) =
        OneShotRequest(text, OneShotVoice.Explicit("kokoro-direct-v1_0:af_bella"), origin)

    @Test
    fun `a played request posts exactly one completion`() = runTest {
        val (h, shim) = rig()

        shim.speak(request(), requestId = 7)
        assertTrue("busy until the outcome", shim.busy)
        h.runUntil { !shim.busy }
        h.run(1_000)

        assertEquals(listOf(PreviewCompletions.Completion(7, error = null)), posted)
        assertEquals(1, settled)
        h.checkInvariants()
        h.narrator.close()
    }

    @Test
    fun `a stop request cancels that one-shot only and posts it as stopped`() = runTest {
        val (h, shim) = rig()
        shim.speak(request(paragraph(1, fill = 'x')), requestId = 1)
        shim.speak(request(paragraph(1, fill = 'y')), requestId = 2)
        h.settle()

        shim.stop(1)
        h.runUntil { !shim.busy }

        assertEquals(PreviewCompletions.Completion(1, error = null, stopped = true), posted.single { it.requestId == 1L })
        assertEquals(PreviewCompletions.Completion(2, error = null), posted.single { it.requestId == 2L })
        assertEquals(2, posted.size)
        h.narrator.close()
    }

    @Test
    fun `a missing engine posts MODEL_MISSING once`() = runTest {
        val (h, shim) = rig()
        h.kokoro.installed = false

        shim.speak(request(), requestId = 3)
        h.runUntil { !shim.busy }

        assertEquals(PreviewCompletions.ErrorKind.MODEL_MISSING, posted.single().error)
        h.narrator.close()
    }

    /** Share sheet, Tasker: no id, nothing awaiting — their errors are the service's notification. */
    @Test
    fun `an external request posts nothing`() = runTest {
        val (h, shim) = rig()

        shim.speak(request(origin = Origin.External), requestId = 0)
        h.runUntil { !shim.busy }

        assertTrue(posted.isEmpty())
        assertEquals(1, settled)
        h.narrator.close()
    }

    @Test
    fun `a stop for a request that is not playing changes nothing`() = runTest {
        val (h, shim) = rig()
        shim.speak(request(), requestId = 5)
        h.settle()

        shim.stop(99)
        h.runUntil { !shim.busy }

        assertEquals(listOf(PreviewCompletions.Completion(5, error = null)), posted)
        assertFalse(shim.busy)
        h.narrator.close()
    }
}
