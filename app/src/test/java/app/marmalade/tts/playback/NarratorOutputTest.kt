package app.marmalade.tts.playback

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.playback.NarratorHarness.Companion.VITS_ENGINE
import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import app.marmalade.tts.playback.NarratorHarness.Companion.voice
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.2 scenarios about the continuous output (step C2), run through the
 * device's own TrackWriter over the harness's virtual AudioTrack: no gap at a
 * paragraph (15), one track per session, the tail heard, nothing stale after
 * a jump or a flush mid-write, rate changes and the interrupt track.
 *
 * Fake engine defaults ("cool"): a 60-char sentence renders in 1584 ms and
 * yields 3630 ms of audio.
 */
class NarratorOutputTest {

    private fun article(n: Int = 3, sentences: Int = 2) = List(n) { paragraph(sentences, fill = 'a' + it) }

    private fun share(fill: Char = 'q') = OneShotRequest(
        text = paragraph(1, fill = fill),
        voice = OneShotVoice.LanguageAware(),
        origin = Origin.External,
    )

    private fun TestScope.harness() = NarratorHarness(this)

    private fun NarratorHarness.done() {
        checkInvariants()
        assertEquals("flush() called on a playing track (AudioTrack ignores it)", 0, output.illegalFlushes)
        narrator.close()
    }

    private fun NarratorHarness.opensOn(track: Track) = output.opens.filter { it.second == track }

    @Test
    fun `15 - cool paragraph boundaries - no gap, one track for the session, every frame heard`() = runTest {
        val h = harness()
        val segs = article(4)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        val starts = (0 until 4).map { h.firstHeard(id, it)!! }
        for (i in 1 until 4) {
            assertEquals("gap before segment $i", 0.0, h.gapsMs(Track.Main, from = starts[i] - 500, to = starts[i] + 500), 0.0)
        }
        assertEquals(0.0, h.gapsMs(), 0.0)
        assertEquals("one track, opened once", listOf(24_000), h.opensOn(Track.Main).map { it.third })
        assertTrue(h.output.rebuilds.isEmpty())
        // 4 segments × 2 sentences × 3630 ms: the last block's tail included.
        assertEquals(4 * 2 * 3_630.0, h.audibleMs(Track.Main), 0.5)
        val boundaries = h.output.writerLog.filter { "seg " in it && "→" in it }
        assertEquals(3, boundaries.size)
        assertTrue(boundaries.joinToString("\n"), boundaries.all { it.endsWith("pad=0ms underrun=0ms") })
        h.done()
    }

    @Test
    fun `15b - an alias effect runs as one chain across blocks - its tail only at the session's end`() = runTest {
        val h = harness()
        h.articleVoice = voice(effects = listOf(EffectBlock.Reverb(50f)))
        val segs = article(3)
        h.open(segs)
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        assertEquals(0.0, h.gapsMs(), 0.0)
        assertEquals(1, h.opensOn(Track.Main).size)
        // One reverb ring-out after the last block, not one per block.
        val extra = h.audibleMs(Track.Main) - 3 * 2 * 3_630.0
        assertTrue("one tail of $extra ms", extra in 0.0..3_000.0)
        h.done()
    }

    @Test
    fun `15c - a speed change right at a paragraph boundary makes no gap`() = runTest {
        val h = harness()
        val segs = article(3, sentences = 1)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 1) != null }
        h.narrator.setSpeed(2f)
        h.runUntil { h.firstHeard(id, 2) != null }
        h.narrator.setSpeed(0.75f)
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        assertEquals(0.0, h.gapsMs(), 0.0)
        assertEquals("no re-render", segs.size, h.kokoro.renders.size)
        h.done()
    }

    @Test
    fun `stale epoch - rapid Next while audio is queued - nothing of an old epoch is ever heard`() = runTest {
        val h = harness()
        val segs = article(6, sentences = 1)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null && h.kokoro.completedRendersOf(segs[1]).isNotEmpty() }
        h.run(700)
        repeat(3) {
            h.narrator.next()
            h.run(10)
        }
        val t = h.now()
        h.runUntil(what = "segment 3 heard") { h.firstHeard(id, 3, after = t) != null }
        val epoch = h.narrator.articleEpoch()!!
        // After the last jump only the newest epoch is on air.
        assertTrue(
            h.output.timeline.filter { it.atMs >= t && it.audibleMs > 0 }.all { it.tag!!.epoch == epoch },
        )
        assertEquals(3, h.article.index)
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        h.done()
    }

    @Test
    fun `flush during a write - seek while paused with the writer holding a full buffer, resume plays the target`() = runTest {
        val h = harness()
        val segs = article(4)
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil { h.firstHeard(id, 0) != null }
        h.run(300)
        h.narrator.pause()
        // Paused: the writer keeps writing until the track is full, then holds the rest.
        h.run(2_000)
        val pausedHeard = h.audibleMs(Track.Main)
        h.narrator.jumpTo(2, play = false)
        h.run(2_000)
        assertEquals("silent while paused", pausedHeard, h.audibleMs(Track.Main), 0.0)
        val t = h.now()
        h.narrator.play()
        h.runUntil(what = "segment 2 heard") { h.firstHeard(id, 2, after = t) != null }
        val first = h.output.timeline.first { it.atMs >= t && it.audibleMs > 0 }
        assertEquals("the first frame after resume is the target's", 2 to 0, first.tag!!.segment to first.tag!!.chunk)
        assertTrue(h.firstHeard(id, 2, after = t)!! - t <= 20)
        h.done()
    }

    @Test
    fun `rate change - a one-shot at 22_05 kHz, then the article at 24 kHz on the same track - rebuilt at the boundary, nothing cut`() = runTest {
        val h = harness()
        h.oneShotVoices = { voice(engine = VITS_ENGINE) }
        h.open(article(2), autoplay = false)
        val id = h.narrator.articleId()!!
        val shot = h.narrator.speakOneShot(share())
        h.settle()
        h.narrator.playArticle()
        h.runUntil(what = "article heard") { h.firstHeard(id, 0) != null }
        val shotEnd = h.output.events.first { (_, e) -> e is OutputEvent.SessionEnded && e.sessionId == shot.id }.first
        assertEquals(Outcome.Played, shot.outcome.getCompleted())
        assertEquals(listOf(22_050, 24_000), h.opensOn(Track.Main).map { it.third })
        val rebuilt = h.output.rebuilds.single()
        assertEquals(24_000, rebuilt.second)
        assertTrue("rebuilt at ${rebuilt.first}, the one-shot ended at $shotEnd", rebuilt.first >= shotEnd)
        // VITS fake: one 60-char sentence = 3630 ms, all of it heard before the switch.
        assertEquals(3_630.0, h.audibleMs(Track.Main, to = shotEnd + 10), 0.5)
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        h.done()
    }

    @Test
    fun `interrupt - a 22_05 kHz share reads on its own track, the article's track is kept, paused, then resumes at the chunk start`() = runTest {
        val h = harness()
        h.oneShotVoices = { voice(engine = VITS_ENGINE) }
        h.open(article(3))
        val id = h.narrator.articleId()!!
        h.runUntil { h.heardStarts().any { it.second.sessionId == id && it.second.chunk == 1 } }
        h.run(1_000)
        val shot = h.narrator.speakOneShot(share())
        h.settle()
        val t = h.now()
        h.runUntil { shot.outcome.isCompleted }
        val done = h.now()
        assertEquals(listOf(22_050), h.opensOn(Track.Interrupt).map { it.third })
        assertEquals(3_630.0, h.audibleMs(Track.Interrupt), 0.5)
        assertEquals("article silent meanwhile", 0.0, h.audibleMs(Track.Main, from = t + 10, to = done), 0.0)
        h.runUntil { h.firstHeard(id, 0, after = done) != null }
        val resumed = h.heardStarts().first { it.first >= done && it.second.sessionId == id }.second
        assertEquals("from the start of the interrupted chunk", 0 to 1, resumed.segment to resumed.chunk)
        assertEquals("the article's track was never rebuilt", 1, h.opensOn(Track.Main).size)
        assertTrue("the interrupt track is released", !h.output.trackOpen(Track.Interrupt))
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        h.done()
    }

    @Test
    fun `a parked track fails the article once, and Play starts it again on a fresh track`() = runTest {
        val h = harness()
        h.output.parkNext = Track.Main
        h.open(article(2))
        h.runUntil(what = "failed") { h.article.status is Status.Failed }
        assertEquals(Status.Failed(FailureKind.FAILED), h.article.status)
        h.narrator.play()
        val id = h.narrator.articleId()!!
        h.runUntil(what = "heard") { h.firstHeard(id, 0) != null }
        assertEquals(2, h.opensOn(Track.Main).size)
        assertNotNull(h.output.events.singleOrNull { it.second is OutputEvent.TrackFailed })
        h.done()
    }
}
