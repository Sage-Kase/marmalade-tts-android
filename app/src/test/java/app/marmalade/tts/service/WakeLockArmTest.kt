package app.marmalade.tts.service

import app.marmalade.tts.playback.ArticleView
import app.marmalade.tts.playback.NarratorState
import app.marmalade.tts.playback.PausedBy
import app.marmalade.tts.playback.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake-lock rule the Narrator's state drives (the per-chunk re-arm comes from the writer). */
class WakeLockArmTest {

    private val acquires = ArrayList<Long>()
    private var releases = 0
    private val arm = WakeLockArm(acquire = { acquires += it }, release = { releases++ })

    private fun state(status: Status) =
        NarratorState(article = ArticleView("k", 3, 0, 1f, 1f, status))

    @Test
    fun `held while buffering or playing, re-armed per chunk, released once on pause`() {
        arm.onChunk()
        assertTrue("nothing to re-arm before a hold", acquires.isEmpty())

        arm.onState(state(Status.Buffering))
        arm.onChunk()
        arm.onState(state(Status.Playing))
        assertEquals(listOf(WakeLockArm.TIMEOUT_MS, WakeLockArm.TIMEOUT_MS, WakeLockArm.TIMEOUT_MS), acquires)

        arm.onState(state(Status.Paused(PausedBy.User)))
        arm.onState(state(Status.Idle))
        arm.onChunk()
        assertEquals(1, releases)
        assertEquals(3, acquires.size)
    }
}
