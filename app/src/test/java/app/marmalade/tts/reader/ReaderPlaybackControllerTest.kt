package app.marmalade.tts.reader

import android.support.v4.media.session.PlaybackStateCompat
import app.marmalade.tts.lang.VoiceChoice
import app.marmalade.tts.playback.FailureKind
import app.marmalade.tts.playback.FocusChange
import app.marmalade.tts.playback.NarratorConfig
import app.marmalade.tts.playback.NarratorHarness
import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import app.marmalade.tts.playback.OneShotRequest
import app.marmalade.tts.playback.OneShotVoice
import app.marmalade.tts.playback.Origin
import app.marmalade.tts.playback.Outcome
import app.marmalade.tts.playback.PausedBy
import app.marmalade.tts.playback.ResolveTarget
import app.marmalade.tts.playback.Status
import app.marmalade.tts.playback.Track
import app.marmalade.tts.service.PlaybackHost
import app.marmalade.tts.service.ServiceUi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// -----------------------------------------------------------------------------
// The reader's stories, against the real Narrator (step C1): ReaderPlaybackController
// drives a Narrator built by NarratorHarness — fake engine, virtual writer
// running the real StreamingEffectChain, fake focus/residency — all on the test
// scheduler's virtual time. What the listener would hear is read off the
// virtual output's timeline; what the reader draws is the controller's state.
//
// Fake engine defaults: every block here is two 60-char sentences, each one
// chunk rendering in 1584 ms into 3630 ms of audio.
// -----------------------------------------------------------------------------

class ReaderPlaybackControllerTest {

    private val blocks = List(6) { paragraph(2, fill = 'a' + it) }

    private lateinit var h: NarratorHarness
    private lateinit var controller: ReaderPlaybackController
    private var hostAllowed = true
    private var hostStarts = 0

    private val state: ReaderPlaybackState get() = controller.state.value
    private val id: Long get() = h.narrator.articleId()!!

    private fun heard(block: Int, after: Long = 0): Long? = h.firstHeard(id, block, after)

    private fun heardAfterJump(block: Int, t: Long): Long {
        h.runUntil(what = "block $block heard") { heard(block, after = t) != null }
        return heard(block, after = t)!! - t
    }

    // -- Playing through --------------------------------------------------------

    /** Today's depth until step G: the block being heard and the one after it. */
    @Test
    fun `playing a new article prepares the block and the next one ahead`() = runTest {
        playing()
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        h.runUntil { heard(0) != null && h.kokoro.completedRendersOf(blocks[1]).size == 2 }

        assertEquals(0, state.currentIndex)
        assertTrue(h.kokoro.rendersOf(blocks[2]).isEmpty())
        done()
    }

    @Test
    fun `a block heard moves the highlight and the one after it is prepared`() = runTest {
        playing()
        h.runUntil { heard(1) != null }

        assertEquals(1, state.currentIndex)
        h.runUntil(what = "block 2 rendering") { h.kokoro.rendersOf(blocks[2]).isNotEmpty() }
        done()
    }

    @Test
    fun `a one-block article plays to the end`() = runTest {
        newController()
        controller.open(article(KEY, listOf(blocks[0])))
        controller.play()

        h.runUntil { state.status == ReaderPlaybackStatus.Finished }
        done()
    }

    @Test
    fun `the last block ending ends the article, every block heard once`() = runTest {
        playing()
        h.runUntil(maxMs = 300_000) { state.status == ReaderPlaybackStatus.Finished }

        assertEquals(blocks.lastIndex, state.currentIndex)
        assertEquals(blocks.indices.toList(), h.heardStarts().filter { it.second.chunk == 0 }.map { it.second.segment })
        assertEquals(0.0, h.gapsMs(), 0.0)
        done()
    }

    /** Was: a completion for a request we already cancelled is ignored. */
    @Test
    fun `audio from before a jump never moves the highlight back`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        val t = h.now()

        controller.playFrom(4)
        h.runUntil { heard(4) != null }
        h.run(2_000)

        assertEquals(4, state.currentIndex)
        assertTrue(h.output.timeline.none { it.atMs > t + 20 && it.tag?.segment == 0 && it.audibleMs > 0 })
        done()
    }

    @Test
    fun `a missing engine stops playback and says why`() = runTest {
        rig()
        h.kokoro.installed = false
        playing(harness = false)
        h.run(3_000)

        assertEquals(ReaderPlaybackStatus.Idle, state.status)
        assertEquals(FailureKind.MODEL_MISSING, state.lastError)
        assertEquals(0.0, h.audibleMs(Track.Main), 0.0)
        done()
    }

    /** A failure will hit the blocks after it too: stop rather than machine-gun the same error. */
    @Test
    fun `a failed block stops playback instead of storming through the article`() = runTest {
        rig()
        h.kokoro.failWhen = { if (it == blocks[1]) RuntimeException("boom") else null }
        playing(harness = false)
        h.runUntil { state.lastError != null }
        h.run(10_000)

        assertEquals(ReaderPlaybackStatus.Idle, state.status)
        assertEquals(FailureKind.FAILED, state.lastError)
        assertTrue(h.kokoro.rendersOf(blocks[2]).isEmpty())
        done()
    }

    @Test
    fun `playing again clears the error`() = runTest {
        rig()
        h.kokoro.installed = false
        playing(harness = false)
        h.run(3_000)
        assertEquals(FailureKind.MODEL_MISSING, state.lastError)

        h.kokoro.installed = true
        controller.play()
        h.settle()

        assertNull(state.lastError)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        h.runUntil { heard(0) != null }
        done()
    }

    /** The notification's Stop: not a failure, and the rest of the article is not read. */
    @Test
    fun `a stop from outside is not an error and does not carry on`() = runTest {
        playing()
        h.runUntil { heard(0) != null }

        h.narrator.stop()
        h.settle()
        val t = h.now()
        h.run(10_000)

        assertEquals(ReaderPlaybackStatus.Idle, state.status)
        assertNull(state.lastError)
        assertEquals(0.0, h.audibleMs(Track.Main, from = t + 10), 0.0)
        done()
    }

    /**
     * A new speak (Speak screen, share sheet) replaces a paused read (plan
     * §2.8). The reader shows stopped — not Paused with nothing to resume —
     * and keeps its place.
     */
    @Test
    fun `a paused read replaced by a share shows stopped and keeps its place`() = runTest {
        playing()
        controller.next()
        h.runUntil { heard(1) != null }
        controller.pause()
        h.settle()

        val shot = h.narrator.speakOneShot(OneShotRequest(paragraph(1, fill = 'q'), OneShotVoice.LanguageAware(), Origin.External))
        h.runUntil { shot.outcome.isCompleted }

        assertEquals(ReaderPlaybackStatus.Idle, state.status)
        assertNull(state.lastError)
        assertEquals(1, state.currentIndex)
        done()
    }

    /** On the last block, a replaced read must not look like the end of the article. */
    @Test
    fun `a paused last block replaced by a share is not the end of the article`() = runTest {
        playing()
        controller.playFrom(blocks.lastIndex)
        h.runUntil { heard(blocks.lastIndex) != null }
        controller.pause()
        h.settle()

        val shot = h.narrator.speakOneShot(OneShotRequest(paragraph(1, fill = 'q'), OneShotVoice.LanguageAware(), Origin.External))
        h.runUntil { shot.outcome.isCompleted }

        assertEquals(ReaderPlaybackStatus.Idle, state.status)
        assertEquals(blocks.lastIndex, state.currentIndex)
        done()
    }

    /** New in C1: a share reads over a playing article, which then carries on by itself. */
    @Test
    fun `a share while the article plays reads over it, the reader keeps showing playing`() = runTest {
        playing()
        h.runUntil { heard(0) != null }

        val shot = h.narrator.speakOneShot(OneShotRequest(paragraph(1, fill = 'q'), OneShotVoice.LanguageAware(), Origin.External))
        h.settle()
        assertEquals(Status.Paused(PausedBy.Interrupt), h.article.status)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)

        h.runUntil { shot.outcome.isCompleted }
        assertEquals(Outcome.Played, shot.outcome.getCompleted())
        val t = h.now()
        h.runUntil(what = "the article heard again") { h.audibleMs(Track.Main, from = t) > 0 }
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        done()
    }

    @Test
    fun `a pause stays silent while the next block goes on preparing`() = runTest {
        playing()
        h.runUntil { heard(0) != null }

        controller.pause()
        h.settle()
        val t = h.now()
        h.run(10_000)

        assertEquals(ReaderPlaybackStatus.Paused, state.status)
        assertEquals(0.0, h.audibleMs(Track.Main, from = t + 20), 0.0)
        assertEquals(2, h.kokoro.completedRendersOf(blocks[1]).size)
        done()
    }

    @Test
    fun `a host service that refuses to start unwinds to idle and says why`() = runTest {
        newController()
        hostAllowed = false
        controller.open(article(KEY, blocks))
        controller.play()
        h.run(3_000)

        assertEquals(ReaderPlaybackStatus.Idle, state.status)
        assertEquals(FailureKind.FAILED, state.lastError)
        assertTrue(h.kokoro.renders.isEmpty())

        hostAllowed = true
        controller.play()
        h.settle()
        assertNull(state.lastError)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        done()
    }

    /** Audio focus refused (a call in progress): nothing plays, and the reader says so. */
    @Test
    fun `audio focus refused stops the read and says why`() = runTest {
        rig()
        h.focus.grant = false
        playing(harness = false)
        h.run(3_000)

        assertEquals(ReaderPlaybackStatus.Idle, state.status)
        assertEquals(FailureKind.FOCUS_DENIED, state.lastError)
        assertEquals(0.0, h.audibleMs(Track.Main), 0.0)
        done()
    }

    // -- Seeking ----------------------------------------------------------------

    @Test
    fun `tapping a block plays from there, and the old block goes quiet`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        val t = h.now()

        controller.playFrom(3)
        heardAfterJump(3, t)

        assertEquals(3, state.currentIndex)
        assertTrue(h.output.timeline.none { it.atMs > t + 20 && it.tag?.segment == 0 && it.audibleMs > 0 })
        done()
    }

    @Test
    fun `tapping outside the article is ignored`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        val starts = hostStarts

        controller.playFrom(blocks.size)
        controller.playFrom(-1)
        h.settle()

        assertEquals(0, state.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertEquals(starts, hostStarts)
        done()
    }

    /**
     * Block 1 is already prepared behind the playing block: Forward plays it
     * within a slice, never re-rendered (re-sending it used to pay a whole fresh
     * time-to-first-audio, Max 2026-09-28).
     */
    @Test
    fun `forward plays the prepared next block at once`() = runTest {
        playing()
        h.runUntil { heard(0) != null && h.kokoro.completedRendersOf(blocks[1]).size == 2 }
        val t = h.now()

        controller.next()

        assertTrue(heardAfterJump(1, t) <= 20)
        assertEquals(1, state.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertEquals(2, h.kokoro.rendersOf(blocks[1]).size)
        done()
    }

    @Test
    fun `forward past the last block ends the article`() = runTest {
        playing()
        controller.playFrom(blocks.lastIndex)
        h.runUntil { heard(blocks.lastIndex) != null }
        val t = h.now()

        controller.next()
        h.run(2_000)

        assertEquals(ReaderPlaybackStatus.Finished, state.status)
        assertEquals(0.0, h.audibleMs(Track.Main, from = t + 10), 0.0)
        done()
    }

    @Test
    fun `tapping the prepared block plays it without rendering it again`() = runTest {
        playing()
        h.runUntil { heard(0) != null && h.kokoro.completedRendersOf(blocks[1]).size == 2 }
        val t = h.now()

        controller.playFrom(1)

        assertTrue(heardAfterJump(1, t) <= 20)
        assertEquals(2, h.kokoro.rendersOf(blocks[1]).size)
        done()
    }

    @Test
    fun `tapping a block that is not prepared renders it from its start`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        val t = h.now()

        controller.playFrom(4)
        heardAfterJump(4, t)

        assertEquals(4, state.currentIndex)
        assertTrue(h.kokoro.rendersOf(blocks[4]).all { it.startMs >= t })
        done()
    }

    @Test
    fun `the block moved to plays on like any other`() = runTest {
        playing()
        h.runUntil { heard(0) != null }

        controller.next()
        h.runUntil { heard(2) != null }

        assertEquals(2, state.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        done()
    }

    @Test
    fun `tapping the prepared block while paused plays it without rendering it again`() = runTest {
        playing()
        h.runUntil { heard(0) != null && h.kokoro.completedRendersOf(blocks[1]).size == 2 }
        controller.pause()
        h.settle()
        val t = h.now()

        controller.playFrom(1)
        h.settle()

        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertTrue(heardAfterJump(1, t) <= 20)
        assertEquals(2, h.kokoro.rendersOf(blocks[1]).size)
        done()
    }

    /** Changed in C1: the prepared audio is kept (it used to be dropped), and nothing is heard. */
    @Test
    fun `forward while paused stays paused and silent, play resumes at the target`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        controller.pause()
        h.settle()
        val t = h.now()

        controller.next()
        h.run(3_000)

        assertEquals(ReaderPlaybackStatus.Paused, state.status)
        assertEquals(1, state.currentIndex)
        assertEquals(0.0, h.audibleMs(Track.Main, from = t + 20), 0.0)

        controller.play()
        heardAfterJump(1, h.now())
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        done()
    }

    /** Changed in C1: the heard audio is kept, so a restart replays it instead of re-rendering. */
    @Test
    fun `backward late in a block restarts it from the audio already heard`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        h.run(2_500)
        val t = h.now()

        controller.previous()

        assertTrue(heardAfterJump(0, t) <= 20)
        assertEquals(0, state.currentIndex)
        assertEquals(2, h.kokoro.rendersOf(blocks[0]).size)
        done()
    }

    @Test
    fun `backward near the start of a block goes to the previous block`() = runTest {
        playing()
        controller.playFrom(3)
        h.runUntil { heard(3) != null }
        h.run(1_000)

        controller.previous()
        h.settle()

        assertEquals(2, state.currentIndex)
        done()
    }

    @Test
    fun `backward later in a block restarts that block`() = runTest {
        playing()
        controller.playFrom(3)
        h.runUntil { heard(3) != null }
        h.run(2_500)
        val t = h.now()

        controller.previous()

        heardAfterJump(3, t)
        assertEquals(3, state.currentIndex)
        done()
    }

    @Test
    fun `backward on the first block restarts it rather than underflowing`() = runTest {
        playing()
        h.runUntil { heard(0) != null }

        controller.previous()
        h.settle()

        assertEquals(0, state.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        done()
    }

    @Test
    fun `the backward window measures from the block being heard, not the article`() = runTest {
        playing()
        h.runUntil { heard(1) != null }
        h.run(500)

        controller.previous()
        h.settle()

        // Block 1 was first heard 500 ms ago, so backward steps to block 0.
        assertEquals(0, state.currentIndex)
        done()
    }

    // -- Pause / resume ---------------------------------------------------------

    @Test
    fun `pause and resume pick up where the audio stopped, nothing rendered again`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        h.run(1_000)

        controller.pause()
        h.settle()
        assertEquals(ReaderPlaybackStatus.Paused, state.status)
        h.run(5_000)
        val t = h.now()
        controller.play()
        h.settle()

        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        h.runUntil { h.audibleMs(Track.Main, from = t) > 0 }
        assertTrue(h.now() - t <= 20)
        assertEquals(2, h.kokoro.rendersOf(blocks[0]).size)
        done()
    }

    @Test
    fun `a pause does not age the current block`() = runTest {
        playing()
        controller.playFrom(3)
        h.runUntil { heard(3) != null }
        h.run(1_000)
        controller.pause()
        h.run(60_000)
        controller.play()
        h.run(500)

        controller.previous()
        h.settle()

        // 1 500 ms heard: still inside the backward window, so backward steps
        // back a block. Without the pause compensation the block would look
        // 61 500 ms old and this would restart block 3.
        assertEquals(2, state.currentIndex)
        done()
    }

    @Test
    fun `tapping a block while paused plays from its start`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        controller.pause()
        h.settle()
        val t = h.now()

        controller.playFrom(4)
        h.settle()

        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertEquals(4, state.currentIndex)
        heardAfterJump(4, t)
        done()
    }

    @Test
    fun `tapping a block after the end plays from it`() = runTest {
        playing()
        controller.playFrom(blocks.lastIndex)
        h.runUntil { heard(blocks.lastIndex) != null }
        controller.next()
        h.settle()
        assertEquals(ReaderPlaybackStatus.Finished, state.status)
        val t = h.now()

        controller.playFrom(2)

        heardAfterJump(2, t)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertEquals(2, state.currentIndex)
        done()
    }

    @Test
    fun `play after the end starts the article again`() = runTest {
        newController()
        controller.open(article(KEY, listOf(blocks[0])))
        controller.play()
        h.runUntil { state.status == ReaderPlaybackStatus.Finished }
        val t = h.now()

        controller.play()

        heardAfterJump(0, t)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertEquals(0, state.currentIndex)
        done()
    }

    // -- Session speed ------------------------------------------------------------

    /** The ViewModel opens each article at its reading alias's own speed; engines render at 1.0. */
    @Test
    fun `a fresh article speaks at the speed it was opened with`() = runTest {
        newController()
        controller.open(article(KEY, blocks), initialSpeed = 1.1f)
        controller.play()
        h.runUntil { heard(0) != null }

        assertEquals(1.1f, state.speed, 0f)
        assertEquals(1.1f, h.article.speed, 0f)
        assertTrue(h.kokoro.requests.all { it.third == 1f })
        done()
    }

    /** The speed sheet offers the way back to the alias's own speed. */
    @Test
    fun `the starting speed is kept after a speed change`() = runTest {
        newController()
        controller.open(article(KEY, blocks), initialSpeed = 1.1f)
        h.settle()

        controller.setSpeed(1.5f)
        h.settle()

        assertEquals(1.5f, state.speed, 0f)
        assertEquals(1.1f, state.startingSpeed, 0f)
        done()
    }

    @Test
    fun `an article opened without a speed reads at 1x`() = runTest {
        playing()

        assertEquals(1.0f, state.speed, 0f)
        assertEquals(1.0f, h.article.speed, 0f)
        done()
    }

    @Test
    fun `the session speed carries on to the blocks after it`() = runTest {
        playing()
        h.runUntil { heard(0) != null }

        controller.setSpeed(1.25f)
        h.runUntil { heard(1) != null }

        assertEquals(1.25f, state.speed, 0f)
        assertEquals(1.25f, h.article.speed, 0f)
        assertTrue(h.kokoro.requests.all { it.third == 1f })
        done()
    }

    /**
     * The speed is a playback-side time-stretch, so audio already rendered just
     * follows the change — no restart, no re-render (Max, 2026-09-28: 2× → 1×
     * used to stop the audio for seconds).
     */
    @Test
    fun `changing speed while playing retimes the audio live`() = runTest {
        newController()
        controller.open(article(KEY, blocks.take(2)))
        controller.play()
        h.runUntil { heard(0) != null }

        controller.setSpeed(1.5f)
        h.settle()
        assertEquals(0, state.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertEquals(1.5f, state.speed, 0f)

        h.runUntil { state.status == ReaderPlaybackStatus.Finished }
        assertEquals("nothing rendered twice", 4, h.kokoro.renders.size)
        done()
    }

    @Test
    fun `a speed change while paused stays paused and resumes the same audio`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        controller.pause()
        h.settle()

        controller.setSpeed(2.0f)
        h.settle()
        assertEquals(ReaderPlaybackStatus.Paused, state.status)
        val t = h.now()
        controller.play()

        h.runUntil { h.audibleMs(Track.Main, from = t) > 0 }
        assertTrue(h.now() - t <= 20)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertEquals(2, h.kokoro.rendersOf(blocks[0]).size)
        done()
    }

    @Test
    fun `a speed change with nothing playing renders nothing`() = runTest {
        newController()
        controller.open(article(KEY, blocks))

        controller.setSpeed(1.5f)
        h.run(3_000)

        assertTrue(h.kokoro.renders.isEmpty())
        assertEquals(1.5f, state.speed, 0f)
        done()
    }

    /** Changed in C1 (plan §6 Q4): a cloud voice's audio is stretched live too, not re-rendered. */
    @Test
    fun `a cloud voice follows a speed change without rendering again`() = runTest {
        rig()
        h.articleVoice = NarratorHarness.voice(engine = NarratorHarness.CLOUD_ENGINE, cloud = true)
        controller.open(article(KEY, blocks.take(2)))
        controller.play()
        h.runUntil { heard(0) != null }

        controller.setSpeed(1.5f)
        h.runUntil { state.status == ReaderPlaybackStatus.Finished }

        assertEquals(4, h.cloudEngine.renders.size)
        done()
    }

    @Test
    fun `speed set before playing applies to the first block`() = runTest {
        newController()
        controller.open(article(KEY, blocks))
        controller.setSpeed(2.0f)
        controller.play()
        h.runUntil { h.heardStarts().any { it.second.segment == 0 && it.second.chunk == 1 } }

        val (c0, c1) = h.heardStarts().filter { it.second.segment == 0 }.map { it.first }
        // 3630 ms of audio at 2x.
        assertTrue("chunk 0 lasted ${c1 - c0}", c1 - c0 in 1_780..1_880)
        assertEquals(2.0f, h.article.speed, 0f)
        done()
    }

    @Test
    fun `setting the speed it already has changes nothing`() = runTest {
        playing()
        h.runUntil { heard(0) != null }

        controller.setSpeed(1.0f)
        h.settle()

        assertTrue(h.logs.none { "speed " in it })
        done()
    }

    @Test
    fun `a new article goes back to the speed it is opened with`() = runTest {
        playing()
        controller.setSpeed(2.0f)
        h.settle()

        controller.open(article(OTHER, listOf(blocks[5])), initialSpeed = 0.9f)
        controller.play()
        h.settle()

        assertEquals(0.9f, state.speed, 0f)
        assertEquals(0.9f, h.article.speed, 0f)
        assertEquals(OTHER, h.article.key)
        done()
    }

    /** Coming back to the article being read is a rebind, not a new session. */
    @Test
    fun `reopening the same article keeps the session speed`() = runTest {
        playing()
        controller.setSpeed(1.5f)
        h.settle()

        assertFalse(controller.open(article(KEY, blocks), initialSpeed = 1.0f))
        h.settle()

        assertEquals(1.5f, state.speed, 0f)
        done()
    }

    // -- The article's voice -------------------------------------------------------

    private val chinese = VoiceChoice.Installed("kokoro-direct-v1_0:zf_xiaobei", "kokoro-direct-v1_0")

    private fun articleVoices() = h.resolveTargets.filterIsInstance<ResolveTarget.Article>().map { it.choice }

    @Test
    fun `every block of an article is read in the voice it was opened with`() = runTest {
        newController()
        controller.open(article(KEY, blocks), voice = chinese)
        controller.play()
        h.runUntil { heard(1) != null }
        controller.playFrom(4)
        h.runUntil { heard(4) != null }

        assertEquals(listOf(chinese), articleVoices())
        done()
    }

    /** A rebind must not re-decide: the voice stays whatever the article started with. */
    @Test
    fun `reopening the same article keeps its voice`() = runTest {
        newController()
        controller.open(article(KEY, blocks), voice = chinese)
        controller.play()
        h.settle()

        controller.open(article(KEY, blocks), voice = VoiceChoice.Primary)
        controller.playFrom(3)
        h.runUntil { heard(3) != null }

        assertEquals(chinese, state.voice)
        assertEquals(listOf(chinese), articleVoices())
        done()
    }

    @Test
    fun `a new article takes the voice it is opened with`() = runTest {
        newController()
        controller.open(article(KEY, blocks), voice = chinese)
        controller.play()
        h.settle()

        controller.open(article(OTHER, listOf(blocks[5])))
        controller.play()
        h.runUntil { heard(0) != null }

        assertEquals(VoiceChoice.Primary, articleVoices().last())
        assertEquals(VoiceChoice.Primary, state.voice)
        done()
    }

    // -- Pauses the reader didn't ask for -----------------------------------------

    /** The notification's Pause, or a media key: the reader follows the one state. */
    @Test
    fun `a pause from the notification moves the reader to paused`() = runTest {
        playing()
        h.runUntil { heard(0) != null }

        h.narrator.pause()
        h.settle()

        assertEquals(ReaderPlaybackStatus.Paused, state.status)
        done()
    }

    @Test
    fun `a resume from the notification moves the reader back to playing`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        controller.pause()
        h.settle()

        h.narrator.play()
        h.settle()

        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertEquals(2, h.kokoro.rendersOf(blocks[0]).size)
        done()
    }

    @Test
    fun `a call pauses the reader and hanging up resumes it`() = runTest {
        playing()
        h.runUntil { heard(0) != null }

        h.narrator.onFocusChange(FocusChange.LossTransient)
        h.settle()
        assertEquals(ReaderPlaybackStatus.Paused, state.status)

        h.narrator.onFocusChange(FocusChange.Gain)
        h.settle()
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        done()
    }

    @Test
    fun `a paused article does not age while the notification holds the pause`() = runTest {
        playing()
        controller.playFrom(3)
        h.runUntil { heard(3) != null }
        h.run(1_000)
        h.narrator.pause()
        h.run(60_000)
        h.narrator.play()
        h.run(500)

        controller.previous()
        h.settle()

        // 1 500 ms heard — still inside the backward window.
        assertEquals(2, state.currentIndex)
        done()
    }

    // -- What the notification shows -----------------------------------------------

    private fun ui() = ServiceUi.render(h.state, PlaybackStateCompat.STATE_NONE)

    @Test
    fun `an idle article puts nothing on the notification`() = runTest {
        newController()
        controller.open(article(KEY, blocks))
        h.settle()

        assertFalse(ui().onAir)
        done()
    }

    @Test
    fun `playing shows the article and both step directions`() = runTest {
        playing()
        controller.next()
        h.runUntil { heard(1) != null }

        val ui = ui()
        assertTrue(ui.onAir)
        assertEquals(KEY, ui.articleKey)
        assertTrue(ui.showNext)
        assertTrue(ui.showPrevious)
        done()
    }

    /** Forward from the last block only ends the article — nothing to skip to. */
    @Test
    fun `the last block shows no forward step`() = runTest {
        playing()
        controller.playFrom(blocks.lastIndex)
        h.runUntil { heard(blocks.lastIndex) != null }

        assertFalse(ui().showNext)
        assertTrue(ui().showPrevious)
        done()
    }

    @Test
    fun `finishing the article takes it off the notification`() = runTest {
        playing()
        controller.playFrom(blocks.lastIndex)
        h.runUntil { state.status == ReaderPlaybackStatus.Finished }

        assertFalse(ui().onAir)
        done()
    }

    // -- Article retention -------------------------------------------------------------

    @Test
    fun `the loaded article is handed back for a rebind`() = runTest {
        playing()

        val held = controller.article(KEY)

        assertEquals(KEY, held?.url)
        assertEquals(blocks, held?.blocks?.map { it.text })
        assertEquals("An article", held?.title)
        done()
    }

    @Test
    fun `a different article is not handed back`() = runTest {
        playing()

        assertNull(controller.article(OTHER))
        done()
    }

    // -- Article identity --------------------------------------------------------------

    @Test
    fun `reopening the same article keeps the playback that is already running`() = runTest {
        playing()
        h.runUntil { heard(1) != null }

        val reopened = controller.open(article(KEY, blocks))
        h.settle()

        assertFalse(reopened)
        assertEquals(1, state.currentIndex)
        assertEquals(ReaderPlaybackStatus.Playing, state.status)
        assertEquals(1, h.resolutions)
        done()
    }

    @Test
    fun `opening a different article stops the old one and starts idle`() = runTest {
        playing()
        h.runUntil { heard(0) != null }
        val old = id
        val t = h.now()

        val reopened = controller.open(article(OTHER, listOf("New.")))
        h.settle()
        h.run(2_000)

        assertTrue(reopened)
        assertEquals(ReaderPlaybackStatus.Idle, state.status)
        assertEquals(0, state.currentIndex)
        assertEquals(1, state.blockCount)
        assertTrue(h.output.timeline.none { it.atMs > t + 10 && it.tag?.sessionId == old && it.audibleMs > 0 })
        done()
    }

    // -- Helpers -----------------------------------------------------------------------

    private companion object {
        const val KEY = "https://example.com/article"
        const val OTHER = "https://example.com/other"
    }

    /** A fresh harness and a controller on it, the controller's projection on the test scheduler. */
    private fun TestScope.rig() {
        h = NarratorHarness(this, NarratorConfig(todaysDepthOnly = true))
        controller = ReaderPlaybackController(
            narrator = h.narrator,
            host = PlaybackHost {
                hostStarts++
                hostAllowed
            },
            scope = backgroundScope,
        )
    }

    private fun TestScope.newController(): ReaderPlaybackController {
        rig()
        return controller
    }

    /** A controller mid-article: [blocks] loaded, playing from block 0. */
    private fun TestScope.playing(harness: Boolean = true) {
        if (harness) rig()
        controller.open(article(KEY, blocks))
        controller.play()
        h.settle()
    }

    private fun done() {
        h.checkInvariants()
        h.narrator.close()
    }

    /** An article whose typed blocks are plain paragraphs of [texts]. */
    private fun article(key: String, texts: List<String>) = ReaderArticle(
        url = key,
        title = "An article",
        byline = "By Max",
        blocks = texts.map { ArticleBlock.Paragraph(it) },
        totalTextChars = texts.sumOf { it.length },
    )
}
