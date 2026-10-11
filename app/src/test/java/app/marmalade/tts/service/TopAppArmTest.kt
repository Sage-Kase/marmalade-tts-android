package app.marmalade.tts.service

import app.marmalade.tts.playback.ArticleView
import app.marmalade.tts.playback.NarratorState
import app.marmalade.tts.playback.PausedBy
import app.marmalade.tts.playback.Status
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The self TTS session's rule (#52): bound while speech is produced, released a grace after. */
class TopAppArmTest {

    private var binds = 0
    private var unbinds = 0

    private fun TestScope.arm() = TopAppArm(backgroundScope, bind = { binds++ }, unbind = { unbinds++ })

    private fun state(status: Status) =
        NarratorState(article = ArticleView("k", 3, 0, 1f, 1f, status))

    private fun TestScope.run(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `bound once while buffering then playing, released a grace after the read stops`() = runTest {
        val arm = arm()
        arm.onState(state(Status.Idle))
        assertEquals("nothing to hold before speech", 0, binds)

        arm.onState(state(Status.Buffering))
        arm.onState(state(Status.Playing))
        assertEquals(1, binds)

        arm.onState(state(Status.Idle))
        run(TopAppArm.RELEASE_DELAY_MS - 1)
        assertEquals("held through the grace", 0, unbinds)
        run(1)
        assertEquals(1, unbinds)
        assertEquals(1, binds)
    }

    @Test
    fun `a resume within the grace keeps the session - no rebind`() = runTest {
        val arm = arm()
        arm.onState(state(Status.Playing))
        arm.onState(state(Status.Paused(PausedBy.User)))
        run(TopAppArm.RELEASE_DELAY_MS / 2)
        arm.onState(state(Status.Playing))
        run(TopAppArm.RELEASE_DELAY_MS * 2)
        assertEquals(1, binds)
        assertEquals(0, unbinds)
    }

    @Test
    fun `playing again after the release binds a new session`() = runTest {
        val arm = arm()
        arm.onState(state(Status.Playing))
        arm.onState(state(Status.Paused(PausedBy.User)))
        arm.onState(state(Status.Idle))
        run(TopAppArm.RELEASE_DELAY_MS)
        arm.onState(state(Status.Playing))
        assertEquals(2, binds)
        assertEquals(1, unbinds)
    }
}
