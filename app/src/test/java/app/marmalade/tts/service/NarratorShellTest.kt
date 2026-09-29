package app.marmalade.tts.service

import android.support.v4.media.session.PlaybackStateCompat
import app.marmalade.tts.playback.ErrorNotice
import app.marmalade.tts.playback.FailureKind
import app.marmalade.tts.playback.FocusChange
import app.marmalade.tts.playback.NarratorHarness
import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import app.marmalade.tts.playback.OneShotRequest
import app.marmalade.tts.playback.OneShotVoice
import app.marmalade.tts.playback.Origin
import app.marmalade.tts.playback.Outcome
import app.marmalade.tts.playback.PausedBy
import app.marmalade.tts.playback.Status
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service shell's duties, each against the states the real Narrator
 * produces (NarratorHarness) rather than hand-built ones: what the
 * notification and media session show ([ServiceUi]), when the service may
 * stop, the error notification for UI-less callers, and the wake lock.
 * Residency, keepalive and the TTFA/RTF samples are Narrator ports, checked
 * here for one real read.
 */
class NarratorShellTest {

    private fun TestScope.harness() = NarratorHarness(this)

    private fun NarratorHarness.ui() = ServiceUi.render(state, PlaybackStateCompat.STATE_NONE)

    private fun article(n: Int = 3) = List(n) { paragraph(2, fill = 'a' + it) }

    @Test
    fun `a read puts the article on air, and finishing takes it off`() = runTest {
        val h = harness()
        h.open(article())
        h.settle()
        assertTrue(h.ui().onAir)
        assertEquals(ServiceUi.Text.Preparing, h.ui().text)

        h.runUntil { h.state.article?.status == Status.Playing }
        assertEquals(ServiceUi.Text.Speaking, h.ui().text)
        assertTrue(h.ui().ongoing)

        h.runUntil(maxMs = 300_000) { h.state.article?.status == Status.Finished }
        assertFalse("the service may stop", h.ui().onAir)
        h.narrator.close()
    }

    /** Every shell duty the old service did per request now has a port owner. */
    @Test
    fun `one read claims residency, starts the keepalive and samples latency and RTF`() = runTest {
        val h = harness()
        h.open(article(2))
        h.runUntil { h.state.article?.status == Status.Playing }
        assertTrue("residency held while reading", h.residency.held.isNotEmpty())

        h.runUntil(maxMs = 300_000) { h.state.article?.status == Status.Finished }
        h.settle()

        assertTrue("residency released", h.residency.held.isEmpty())
        assertEquals(1, h.keepaliveStarts)
        assertTrue(h.latencies.isNotEmpty())
        assertTrue(h.rtfs.isNotEmpty())
        h.narrator.close()
    }

    /** Focus refused (a call in progress): nothing plays, the service may stop; no notification, as before C1. */
    @Test
    fun `focus refused at start - nothing on air, nothing rendered`() = runTest {
        val h = harness()
        h.focus.grant = false
        val shot = h.narrator.speakOneShot(OneShotRequest(paragraph(1), OneShotVoice.LanguageAware(), Origin.External))
        h.runUntil { shot.outcome.isCompleted }
        h.run(3_000)

        assertEquals(Outcome.Failed(FailureKind.FOCUS_DENIED, "audio focus denied"), shot.outcome.getCompleted())
        assertFalse(h.ui().onAir)
        assertEquals(null, ServiceUi.errorToPost(h.state, lastPostedId = null))
        assertTrue(h.kokoro.renders.isEmpty())
        h.narrator.close()
    }

    @Test
    fun `a UI-less share that fails gets one notification`() = runTest {
        val h = harness()
        h.kokoro.installed = false
        val shot = h.narrator.speakOneShot(OneShotRequest(paragraph(1), OneShotVoice.LanguageAware(), Origin.External))
        h.runUntil { shot.outcome.isCompleted }

        assertFalse(h.ui().onAir)
        assertEquals(
            ErrorNotice(shot.id, FailureKind.MODEL_MISSING, NarratorHarness.ENGINE),
            ServiceUi.errorToPost(h.state, lastPostedId = null),
        )
        assertEquals(null, ServiceUi.errorToPost(h.state, lastPostedId = shot.id))
        h.narrator.close()
    }

    @Test
    fun `an in-app one-shot's failure is left to its screen`() = runTest {
        val h = harness()
        h.kokoro.installed = false
        val shot = h.narrator.speakOneShot(OneShotRequest(paragraph(1), OneShotVoice.LanguageAware(), Origin.InApp))
        h.runUntil { shot.outcome.isCompleted }

        assertEquals(null, ServiceUi.errorToPost(h.state, lastPostedId = null))
        h.narrator.close()
    }

    /** §6 Q6: another app took the sound for good — paused in place, swipe-away-able. */
    @Test
    fun `a permanent focus loss leaves the article on air but dismissable`() = runTest {
        val h = harness()
        h.open(article())
        h.runUntil { h.state.article?.status == Status.Playing }

        h.narrator.onFocusChange(FocusChange.Loss)
        h.settle()

        assertEquals(Status.Paused(PausedBy.FocusLoss), h.state.article?.status)
        assertTrue(h.ui().onAir)
        assertFalse(h.ui().ongoing)
        assertTrue(h.ui().paused)
        h.narrator.close()
    }

    @Test
    fun `a transient loss keeps the notification ongoing`() = runTest {
        val h = harness()
        h.open(article())
        h.runUntil { h.state.article?.status == Status.Playing }

        h.narrator.onFocusChange(FocusChange.LossTransient)
        h.settle()

        assertTrue(h.ui().ongoing)
        assertTrue(h.ui().paused)
        h.narrator.close()
    }

    /** §6 Q7: a read left paused for 30 minutes is stopped, and the paused notification goes. */
    @Test
    fun `thirty minutes paused takes the read off the notification`() = runTest {
        val h = harness()
        h.open(article())
        h.runUntil { h.state.article?.status == Status.Playing }
        h.narrator.pause()
        h.settle()
        assertTrue(h.ui().onAir)

        h.run(30 * 60_000L + 1_000)

        assertFalse(h.ui().onAir)
        h.checkInvariants()
        h.narrator.close()
    }

    @Test
    fun `the wake lock follows the read - held while it plays, released on pause and at the end`() = runTest {
        val h = harness()
        val calls = ArrayList<String>()
        val arm = WakeLockArm(acquire = { calls += "acquire" }, release = { calls += "release" })
        backgroundScope.launch(h.dispatcher) { h.narrator.state.collect(arm::onState) }
        h.open(article(2))
        h.runUntil { h.state.article?.status == Status.Playing }
        assertEquals("acquire", calls.last())

        h.narrator.pause()
        h.settle()
        assertEquals("release", calls.last())
        val released = calls.size

        h.narrator.play()
        h.runUntil(maxMs = 300_000) { h.state.article?.status == Status.Finished }
        h.settle()
        assertTrue(calls.drop(released).contains("acquire"))
        assertEquals("release", calls.last())
        h.narrator.close()
    }
}
