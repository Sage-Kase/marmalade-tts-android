package app.marmalade.tts.playback

import app.marmalade.tts.playback.NarratorHarness.Companion.paragraph
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resume points (issue #11): a streaming engine sends one chunk per SLICE of
 * a sentence (KittenDirect, the Kokoro stream dev engine), so "restart the
 * interrupted chunk" alone would restart mid-sentence or mid-word. Only a
 * chunk flagged [app.marmalade.tts.engine.SynthAudio.resumePoint] is a place
 * to restart from; the Narrator rewinds to the last one at or before the
 * heard chunk.
 *
 * Fake engine, 4 slices per sentence: a 60-char sentence is chunks 4k..4k+3
 * of ~907 ms each, and only 4k is a resume point.
 */
class NarratorResumePointTest {

    private fun article(n: Int = 3) = List(n) { paragraph(2, fill = 'a' + it) }

    private fun share() = OneShotRequest(
        text = paragraph(1, fill = 'q'),
        voice = OneShotVoice.LanguageAware(),
        origin = Origin.External,
    )

    private fun TestScope.streamingHarness(config: NarratorConfig = NarratorConfig()) =
        NarratorHarness(this, config).also { it.kokoro.slicesPerChunk = 4 }

    private fun NarratorHarness.done() {
        checkInvariants()
        narrator.close()
    }

    /** Interrupt the article while [chunk] of segment 0 is on air; return the first chunk heard when it resumes. */
    private fun NarratorHarness.interruptAt(chunk: Int, into: Long = 300): ChunkTag {
        open(article())
        val id = narrator.articleId()!!
        runUntil(what = "chunk $chunk heard") { heardStarts().any { it.second.sessionId == id && it.second.chunk == chunk } }
        run(into)
        val shot = narrator.speakOneShot(share())
        settle()
        assertEquals(Status.Paused(PausedBy.Interrupt), article.status)
        runUntil(what = "the share played") { shot.outcome.isCompleted }
        val done = now()
        runUntil(what = "the article resumed") { heardStarts().any { it.first >= done && it.second.sessionId == id } }
        return heardStarts().first { it.first >= done && it.second.sessionId == id }.second
    }

    @Test
    fun `an interrupt mid-sentence resumes at the sentence's first slice, not the heard slice`() = runTest {
        val h = streamingHarness()
        val resumed = h.interruptAt(chunk = 6)
        assertEquals("sentence 2 starts at chunk 4", 0 to 4, resumed.segment to resumed.chunk)
        assertTrue(h.logs.any { "resume after interrupt seg=0 chunk=4" in it })
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        h.done()
    }

    @Test
    fun `an interrupt during a sentence's first slice restarts that slice`() = runTest {
        val h = streamingHarness()
        val resumed = h.interruptAt(chunk = 4)
        assertEquals(0 to 4, resumed.segment to resumed.chunk)
        h.done()
    }

    @Test
    fun `the resume point survives trimming of heard audio`() = runTest {
        // Keep only ~2 s of heard audio: chunks 4-6 (sentence 2's heard slices)
        // would be trimmed, leaving no way back to the sentence start.
        val h = streamingHarness(NarratorConfig(retainHeardMs = 2_000.0))
        val resumed = h.interruptAt(chunk = 7)
        assertEquals(0 to 4, resumed.segment to resumed.chunk)
        assertEquals("rewound within the held audio, not re-rendered", 2 * 4, h.kokoro.rendersOf(article()[0]).size)
        h.done()
    }

    @Test
    fun `a transient focus loss mid-sentence carries on from the writer - no rewind`() = runTest {
        val h = streamingHarness()
        h.open(article())
        val id = h.narrator.articleId()!!
        h.runUntil { h.heardStarts().any { it.second.sessionId == id && it.second.chunk == 6 } }
        h.run(300)
        h.narrator.onFocusChange(FocusChange.LossTransient)
        h.run(1_000)
        val t = h.now()
        h.narrator.onFocusChange(FocusChange.Gain)
        h.runUntil { h.audibleMs(Track.Main, from = t) > 0 }
        val first = h.output.timeline.first { it.atMs >= t && it.audibleMs > 0 }
        assertEquals("the slice that was playing", 0 to 6, first.tag!!.segment to first.tag!!.chunk)
        h.done()
    }

    @Test
    fun `a long cloud paragraph - one resume point, many buffers - still lets the next paragraph prefetch`() = runTest {
        // A cloud request is the whole paragraph; only its first buffer is a
        // resume point. The budget must count audio ahead of the listener
        // (from the heard chunk), not everything since the resume point —
        // else a 70 s paragraph blocks the next one and it starts with a gap.
        val h = NarratorHarness(this)
        h.cloudEngine.slicesPerChunk = 40
        h.cloudEngine.tokenCap = 10_000
        h.articleVoice = NarratorHarness.voice(engine = NarratorHarness.CLOUD_ENGINE, cloud = true)
        val segs = listOf(paragraph(1, chars = 1_200), paragraph(1, fill = 'b'))
        h.open(segs)
        val id = h.narrator.articleId()!!
        h.runUntil(what = "paragraph 1 heard") { h.firstHeard(id, 1) != null }
        val start1 = h.firstHeard(id, 1)!!
        assertEquals("no gap at the long paragraph's end", 0.0, h.gapsMs(Track.Main, from = start1 - 3_000, to = start1 + 500), 0.0)
        h.runUntil(what = "finished") { h.article.status == Status.Finished }
        h.done()
    }
}
