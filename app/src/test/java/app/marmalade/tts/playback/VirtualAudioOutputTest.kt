package app.marmalade.tts.playback

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer's own contract ([TrackWriter] over the harness's virtual
 * AudioTrack), before any Narrator scenario leans on it.
 */
class VirtualAudioOutputTest {

    private fun tag(chunk: Int, epoch: Int = 1, segment: Int = 0, session: Long = 7) =
        ChunkTag(sessionId = session, epoch = epoch, segment = segment, chunk = chunk)

    private fun pcm(ms: Int, rate: Int = 24_000) = ShortArray(rate * ms / 1000) { (it % 100).toShort() }

    private class Rig(
        scope: TestScope,
        parkNext: Track? = null,
        bufferMs: Int = 30,
        startThresholdMs: Int = 0,
        pauseLatencyMs: Long = 0,
    ) {
        val clock = { scope.testScheduler.currentTime }
        val out = VirtualAudioOutput(
            scope.backgroundScope, clock,
            bufferMs = bufferMs, startThresholdMs = startThresholdMs, pauseLatencyMs = pauseLatencyMs,
        )
            .also { it.parkNext = parkNext }
        val events = ArrayList<Pair<Long, OutputEvent>>()

        init {
            out.attach { events += clock() to it }
        }

        fun begin(session: Long = 7, epoch: Int = 1, rate: Int = 24_000, tempo: Float = 1f, track: Track = Track.Main) =
            out.submit(OutputCommand.Begin(track, session, epoch, rate, emptyList(), tempo))

        fun write(tag: ChunkTag, pcm: ShortArray, track: Track = Track.Main) = out.submit(OutputCommand.Write(track, tag, pcm))

        fun end(session: Long = 7, epoch: Int = 1, track: Track = Track.Main) = out.submit(OutputCommand.End(track, session, epoch))

        fun at(predicate: (OutputEvent) -> Boolean): Long? = events.firstOrNull { predicate(it.second) }?.first

        fun heardMs(track: Track = Track.Main, from: Long = 0) =
            out.timeline.filter { it.track == track && it.atMs >= from }.sumOf { it.audibleMs }
    }

    private fun TestScope.run(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `side-band marks survive a live 2x Tempo - two 1 s chunks are heard in about 1 s`() = runTest {
        val r = Rig(this)
        r.begin(tempo = 2f)
        r.write(tag(0), pcm(1_000))
        r.write(tag(1), pcm(1_000))
        r.end()
        r.out.submit(OutputCommand.Play(Track.Main))
        run(3_000)
        val started = r.events.filter { it.second is OutputEvent.ChunkStarted }
        assertEquals(listOf(0, 1), started.map { (it.second as OutputEvent.ChunkStarted).tag.chunk })
        assertTrue("chunk 1 heard at ${started[1].first}", started[1].first in 480..560)
        val ended = r.at { it is OutputEvent.SessionEnded }!!
        assertTrue("ended at $ended", ended in 980..1_080)
    }

    @Test
    fun `a flush drops what is queued and any later item of an older epoch`() = runTest {
        val r = Rig(this)
        r.begin()
        r.write(tag(0), pcm(2_000))
        r.out.submit(OutputCommand.Play(Track.Main))
        run(500)
        r.out.submit(OutputCommand.Flush(Track.Main, 7, minEpoch = 2))
        r.write(tag(1, epoch = 1), pcm(500))
        run(2_000)
        assertEquals(1, r.out.staleDropped)
        val heard = r.heardMs()
        assertTrue("heard $heard ms", heard in 490.0..540.0)
        assertTrue(r.out.timeline.none { it.stale })
        assertEquals(0, r.out.illegalFlushes)
    }

    @Test
    fun `the writer drops a whole stale session - Begin, Write and End of an older epoch after a flush`() = runTest {
        // A stale Begin would make its Write shapeable: only the writer's own
        // epoch check (not the harness's bookkeeping) keeps it off the track.
        val r = Rig(this)
        r.begin()
        r.write(tag(0), pcm(1_000))
        r.out.submit(OutputCommand.Play(Track.Main))
        run(300)
        r.out.submit(OutputCommand.Flush(Track.Main, 7, minEpoch = 2))
        val t = testScheduler.currentTime
        r.begin(epoch = 1)
        r.write(tag(1, epoch = 1), pcm(300))
        r.end(epoch = 1)
        r.begin(epoch = 2)
        r.write(tag(0, epoch = 2), pcm(300))
        r.end(epoch = 2)
        r.out.submit(OutputCommand.Play(Track.Main))
        run(2_000)
        val heard = r.events.filter { it.first >= t }.mapNotNull { (it.second as? OutputEvent.ChunkStarted)?.tag }
        assertEquals("only the new epoch", listOf(tag(0, epoch = 2)), heard)
        assertTrue(r.out.timeline.none { it.stale })
        assertTrue("dropped as stale, not as an orphan Write", r.out.writerLog.none { "without its session's Begin" in it })
        assertNotNull(r.at { it is OutputEvent.SessionEnded && it.epoch == 2 })
        assertTrue("the stale End reported nothing", r.events.none { (_, e) -> e is OutputEvent.SessionEnded && e.epoch == 1 })
    }

    @Test
    fun `the tail is heard - the mixer's last partial period is padded through, on time`() = runTest {
        val r = Rig(this)
        r.begin()
        r.write(tag(0), pcm(1_005)) // not a whole number of 10 ms periods
        r.end()
        r.out.submit(OutputCommand.Play(Track.Main))
        run(3_000)
        assertEquals("every frame heard", 1_005.0, r.heardMs(), 0.01)
        val ended = r.at { it is OutputEvent.SessionEnded }
        assertNotNull("session end reported (no abandoned drain)", ended)
        assertTrue("ended at $ended", ended!! in 1_000..1_040)
    }

    @Test
    fun `a chunk before an underrun is heard in full before the next arrives`() = runTest {
        val r = Rig(this)
        r.begin()
        r.write(tag(0), pcm(505))
        r.out.submit(OutputCommand.Play(Track.Main))
        run(2_000) // the next chunk is late: the track starves
        val finished = r.at { it is OutputEvent.ChunkFinished }
        assertNotNull(finished)
        // The live Tempo stage holds back up to a frame (~43 ms) until more
        // input comes; at a near-underrun the writer pushes it through.
        assertTrue("chunk 0 finished at $finished", finished!! in 505..560)
        assertEquals("all of it heard before the next chunk", 505.0, r.heardMs(), 0.5)
        assertEquals("its last frame was heard by then", 0.0, r.heardMs(Track.Main, from = finished + 10), 0.0)
        val t = testScheduler.currentTime
        r.write(tag(1), pcm(500))
        r.end()
        run(1_000)
        val c1 = r.at { it is OutputEvent.ChunkStarted && it.tag.chunk == 1 }!!
        assertTrue("chunk 1 heard ${c1 - t} ms after it was written", c1 - t <= 100)
        assertEquals(1_005.0, r.heardMs(), 0.01)
    }

    @Test
    fun `one track across blocks and sessions at one rate - no rebuild, no gap`() = runTest {
        val r = Rig(this)
        r.begin()
        for (seg in 0..2) for (c in 0..1) r.write(tag(c, segment = seg), pcm(400))
        r.end()
        r.begin(session = 8)
        r.write(tag(0, session = 8), pcm(400))
        r.end(session = 8)
        r.out.submit(OutputCommand.Play(Track.Main))
        run(4_000)
        assertEquals("one track built", 1, r.out.opens.size)
        assertEquals(2_800.0, r.heardMs(), 0.01)
        val slices = r.out.timeline.filter { it.audibleMs > 0 }
        assertEquals("no silence from the first frame to the last", slices.size.toLong(), (slices.last().atMs - slices.first().atMs) / 10 + 1)
    }

    @Test
    fun `flush while the writer waits on a full, paused track - nothing stale heard, the head restarts`() = runTest {
        val r = Rig(this)
        r.begin()
        r.write(tag(0), pcm(5_000))
        run(200) // paused: the buffer fills and the writer holds the rest
        assertEquals(0.0, r.heardMs(), 0.0)
        r.out.submit(OutputCommand.Flush(Track.Main, 7, minEpoch = 2))
        r.begin(epoch = 2)
        r.write(tag(0, epoch = 2), pcm(300))
        r.end(epoch = 2)
        val t = testScheduler.currentTime
        r.out.submit(OutputCommand.Play(Track.Main))
        run(1_000)
        assertEquals(0, r.out.illegalFlushes)
        assertTrue(r.out.timeline.none { it.stale })
        assertEquals("only the new epoch heard", 300.0, r.heardMs(), 0.01)
        val started = r.events.filter { it.second is OutputEvent.ChunkStarted }.map { (it.second as OutputEvent.ChunkStarted).tag }
        assertEquals(listOf(tag(0, epoch = 2)), started)
        val ended = r.at { it is OutputEvent.SessionEnded }!!
        assertTrue("marks follow the reset head: ended ${ended - t} ms after Play", ended - t in 300..340)
    }

    @Test
    fun `flush while playing pauses the track first, so the flush is never ignored`() = runTest {
        val r = Rig(this)
        r.begin()
        r.write(tag(0), pcm(3_000))
        r.out.submit(OutputCommand.Play(Track.Main))
        run(1_000)
        r.out.submit(OutputCommand.Flush(Track.Main, 7, minEpoch = 2))
        r.begin(epoch = 2)
        r.write(tag(0, epoch = 2), pcm(200))
        r.end(epoch = 2)
        run(1_000)
        assertEquals(0, r.out.illegalFlushes)
        assertTrue(r.out.timeline.none { it.stale })
        assertTrue("heard ${r.heardMs()}", r.heardMs() in 1_190.0..1_240.0)
    }

    @Test
    fun `a rate change rebuilds the track only after the last frame before it is heard`() = runTest {
        val r = Rig(this)
        r.begin()
        r.write(tag(0), pcm(605))
        r.end()
        r.begin(session = 8, rate = 22_050)
        r.write(tag(0, session = 8), pcm(500, rate = 22_050))
        r.end(session = 8)
        r.out.submit(OutputCommand.Play(Track.Main))
        run(3_000)
        val firstEnded = r.at { it is OutputEvent.SessionEnded && it.sessionId == 7L }!!
        assertEquals(listOf(22_050), r.out.rebuilds.map { it.second })
        val rebuilt = r.out.rebuilds.single().first
        assertTrue("rebuilt at $rebuilt, after session 7's end at $firstEnded", rebuilt >= firstEnded)
        assertEquals("both sessions heard in full", 1_105.0, r.heardMs(), 0.1)
        assertNotNull(r.at { it is OutputEvent.SessionEnded && it.sessionId == 8L })
    }

    @Test
    fun `two tracks - the interrupt plays while the main track holds its audio paused`() = runTest {
        val r = Rig(this)
        r.begin()
        r.write(tag(0), pcm(2_000))
        r.end()
        r.out.submit(OutputCommand.Play(Track.Main))
        run(500)
        r.out.submit(OutputCommand.Pause(Track.Main))
        r.begin(session = 9, rate = 22_050, track = Track.Interrupt)
        r.write(tag(0, session = 9), pcm(400, rate = 22_050), track = Track.Interrupt)
        r.end(session = 9, track = Track.Interrupt)
        r.out.submit(OutputCommand.Play(Track.Interrupt))
        run(1_000)
        val shotEnd = r.at { it is OutputEvent.SessionEnded && it.sessionId == 9L }!!
        assertEquals(400.0, r.heardMs(Track.Interrupt), 0.1)
        assertEquals("main silent while paused", 0.0, r.heardMs(Track.Main, from = 510), 0.0)
        r.out.submit(OutputCommand.Release(Track.Interrupt))
        r.out.submit(OutputCommand.Play(Track.Main))
        run(2_000)
        assertTrue(shotEnd > 0)
        assertEquals("main resumed where it paused", 2_000.0, r.heardMs(Track.Main), 0.1)
        assertEquals(listOf(Track.Main, Track.Interrupt), r.out.opens.map { it.second })
        assertTrue(!r.out.trackOpen(Track.Interrupt))
    }

    @Test
    fun `a parked track - head frozen while playing - fails the session instead of blocking`() = runTest {
        val r = Rig(this, parkNext = Track.Main)
        r.begin()
        r.write(tag(0), pcm(2_000))
        r.out.submit(OutputCommand.Play(Track.Main))
        run(4_000)
        val failed = r.events.single { it.second is OutputEvent.TrackFailed }
        assertTrue("failed at ${failed.first}", failed.first in 2_500..2_600)
        assertEquals(7L, (failed.second as OutputEvent.TrackFailed).sessionId)
        assertTrue(!r.out.trackOpen(Track.Main))
    }

    @Test
    fun `a parked track fails a session already written to its end - nothing is left waiting for its end`() = runTest {
        // 200 ms fits the buffer whole: no pending audio, no queued item, the
        // session already ended on the writer's side. Only the marks still
        // say whose audio is stuck (the emulator caught this, #11).
        val r = Rig(this, parkNext = Track.Main)
        r.begin()
        r.write(tag(0), pcm(20))
        r.end()
        r.out.submit(OutputCommand.Play(Track.Main))
        run(4_000)
        val failed = r.events.single { it.second is OutputEvent.TrackFailed }.second as OutputEvent.TrackFailed
        assertEquals(7L to 1, failed.sessionId to failed.epoch)
    }

    @Test
    fun `a parked track fails every session queued on it, in order`() = runTest {
        val r = Rig(this, parkNext = Track.Main)
        r.begin()
        r.write(tag(0), pcm(10))
        r.end()
        r.begin(session = 8)
        r.write(tag(0, session = 8), pcm(2_000))
        r.end(session = 8)
        r.out.submit(OutputCommand.Play(Track.Main))
        run(4_000)
        val failed = r.events.mapNotNull { it.second as? OutputEvent.TrackFailed }
        assertEquals(listOf(7L, 8L), failed.map { it.sessionId })
    }

    @Test
    fun `padding grown to beat a start threshold shrinks back once the tail is heard`() = runTest {
        // Device-like track: 250 ms buffer that starts only when full. A short
        // first session needs ~a buffer of padding to start at all; that must
        // not stick, or every later session end queues a buffer of silence.
        val r = Rig(this, bufferMs = 250, startThresholdMs = 250)
        r.begin()
        r.write(tag(0), pcm(100))
        r.end()
        r.out.submit(OutputCommand.Play(Track.Main))
        run(3_000)
        assertNotNull("session 7 heard to its end", r.at { it is OutputEvent.SessionEnded && it.sessionId == 7L })
        val padBefore = r.out.paddedMs(Track.Main)
        assertTrue("the first session needed a big pad: $padBefore ms", padBefore >= 140.0)
        r.begin(session = 8)
        r.write(tag(0, session = 8), pcm(100))
        r.end(session = 8)
        run(3_000)
        assertNotNull("session 8 heard to its end", r.at { it is OutputEvent.SessionEnded && it.sessionId == 8L })
        val pad8 = r.out.paddedMs(Track.Main) - padBefore
        assertTrue("session 8 padded $pad8 ms", pad8 <= 80.0)
    }

    @Test
    fun `a pause that lands just before the tail is heard still drops the padding behind it`() = runTest {
        // AudioTrack pauses asynchronously: right after pause() the head can
        // still be short of the real end, then play it out. The padding
        // queued behind it must still go, or the next session starts late.
        val r = Rig(this, bufferMs = 250, pauseLatencyMs = 30)
        r.begin()
        r.write(tag(0), pcm(100))
        r.end()
        r.out.submit(OutputCommand.Play(Track.Main))
        run(70) // 20-30 ms of real audio left, padding queued behind it
        r.out.submit(OutputCommand.Pause(Track.Main))
        run(500)
        assertNotNull("session 7 heard to its end during the pause latency", r.at { it is OutputEvent.SessionEnded })
        r.begin(session = 8)
        r.write(tag(0, session = 8), pcm(200))
        r.end(session = 8)
        run(100)
        val t = testScheduler.currentTime
        r.out.submit(OutputCommand.Play(Track.Main))
        run(500)
        val first = r.out.timeline.first { it.atMs >= t && it.audibleMs > 0 }
        assertTrue("session 8 heard ${first.atMs - t} ms after Play", first.atMs - t <= 10)
    }

    @Test
    fun `a track with nothing to play is released after a while and rebuilt on the next Begin`() = runTest {
        val r = Rig(this)
        r.begin()
        r.write(tag(0), pcm(200))
        r.end()
        r.out.submit(OutputCommand.Play(Track.Main))
        run(1_000)
        r.out.submit(OutputCommand.Pause(Track.Main))
        run(TrackWriter.DEFAULT_IDLE_RELEASE_MS + 100)
        assertTrue(!r.out.trackOpen(Track.Main))
        r.begin(session = 8)
        r.write(tag(0, session = 8), pcm(200))
        r.end(session = 8)
        r.out.submit(OutputCommand.Play(Track.Main))
        run(1_000)
        assertEquals(2, r.out.opens.size)
        assertEquals(400.0, r.heardMs(), 0.01)
    }
}
