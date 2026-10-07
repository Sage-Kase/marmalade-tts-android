package app.marmalade.tts.service

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.marmalade.tts.playback.ChunkTag
import app.marmalade.tts.playback.OutputCommand
import app.marmalade.tts.playback.OutputEvent
import app.marmalade.tts.playback.PcmTrack
import app.marmalade.tts.playback.PcmTrackFactory
import app.marmalade.tts.playback.Track
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The C2 writer against a REAL AudioTrack (plan §5.3).
 *
 * **Emulator only — never the daily phone.** Run on your own API 35
 * emulator (`$EMULATOR` = its serial, e.g. emulator-5560) with
 * `am instrument`, never `connectedAndroidTest` (it targets every device and
 * uninstalls the app under test). Mute the emulator first if its audio
 * reaches the host's speakers (`media volume --stream 3 --set 0`).
 *
 * ```
 * ./gradlew :app:assembleFdroidDebug :app:assembleFdroidDebugAndroidTest
 * adb -s $EMULATOR install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk
 * adb -s $EMULATOR install -r app/build/outputs/apk/androidTest/fdroid/debug/app-fdroid-debug-androidTest.apk
 * adb -s $EMULATOR shell am instrument -w \
 *   -e class app.marmalade.tts.service.AudioTrackOutputInstrumentedTest \
 *   app.marmalade.tts.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AudioTrackOutputInstrumentedTest {

    private val events: MutableList<Pair<Long, OutputEvent>> = Collections.synchronizedList(ArrayList())
    private val opens: MutableList<Int> = Collections.synchronizedList(ArrayList())
    private val outputs = ArrayList<AudioTrackOutput>()

    private fun output(wrap: (PcmTrack) -> PcmTrack = { it }): AudioTrackOutput =
        AudioTrackOutput(
            onChunkHeard = {},
            factory = PcmTrackFactory { rate ->
                opens += rate
                wrap(AndroidPcmTrack(rate))
            },
        ).also {
            it.attach { e -> events += SystemClock.elapsedRealtime() to e }
            outputs += it
        }

    @After
    fun releaseTracks() {
        for (o in outputs) for (t in Track.entries) o.submit(OutputCommand.Release(t))
        Thread.sleep(100)
    }

    private fun tag(chunk: Int, epoch: Int = 1, segment: Int = 0, session: Long = 7) = ChunkTag(session, epoch, segment, chunk)

    /** A quiet 220 Hz tone, so a listener at the emulator hears something sane. */
    private fun pcm(ms: Int, rate: Int = 24_000) =
        ShortArray(rate * ms / 1000) { (kotlin.math.sin(2 * Math.PI * 220 * it / rate) * 2_000).toInt().toShort() }

    private fun AudioTrackOutput.begin(session: Long = 7, epoch: Int = 1, rate: Int = 24_000) =
        submit(OutputCommand.Begin(Track.Main, session, epoch, rate, emptyList(), 1f))

    private fun AudioTrackOutput.write(tag: ChunkTag, pcm: ShortArray) = submit(OutputCommand.Write(Track.Main, tag, pcm))

    private fun AudioTrackOutput.end(session: Long = 7, epoch: Int = 1) = submit(OutputCommand.End(Track.Main, session, epoch))

    /** When the first event matching [predicate] arrived; looks at least once (0 = "already arrived"). */
    private fun waitFor(timeoutMs: Long, what: String, predicate: (OutputEvent) -> Boolean): Long {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            synchronized(events) { events.firstOrNull { predicate(it.second) } }?.let { return it.first }
            if (SystemClock.elapsedRealtime() >= deadline) break
            Thread.sleep(5)
        }
        throw AssertionError("timed out after $timeoutMs ms waiting for $what; events: ${synchronized(events) { events.toList() }}")
    }

    private fun failures() = synchronized(events) { events.filter { it.second is OutputEvent.TrackFailed } }

    @Test
    fun headIsZeroAfterPauseAndFlush_andCountsFromZeroAgain() {
        val t = AndroidPcmTrack(24_000)
        try {
            val a = pcm(800)
            var at = 0
            while (at < a.size) at += t.write(a, at, a.size - at, null).coerceAtLeast(0).also { if (it == 0) t.play() }
            t.play()
            Thread.sleep(700)
            t.pause()
            val before = t.head()
            t.flush()
            assertEquals("head after flush", 0L, t.head())
            // Less than the track's start threshold never starts on its own
            // (the writer pads for that): push it through with silence.
            val b = pcm(100)
            val pad = ShortArray(24_000 * 150 / 1000)
            assertEquals(b.size, t.write(b, 0, b.size, null))
            assertEquals(pad.size, t.write(pad, 0, pad.size, null))
            val after = (b.size + pad.size).toLong()
            assertTrue("played more before the flush ($before) than written after it ($after)", before > after)
            assertEquals("head after flush + write, before play", 0L, t.head())
            t.play()
            Thread.sleep(600)
            val h = t.head()
            assertTrue("head counts from 0 again: $h", h in 1..after)
        } finally {
            t.release()
        }
    }

    @Test
    fun flushWhileTheWriterWaitsOnAFullPausedTrack_nothingStaleIsHeard() {
        val out = output()
        out.begin()
        out.write(tag(0), pcm(5_000))
        Thread.sleep(400) // paused: the track fills, the writer holds the rest
        out.submit(OutputCommand.Flush(Track.Main, 7, minEpoch = 2))
        out.begin(epoch = 2)
        out.write(tag(0, epoch = 2), pcm(300))
        out.end(epoch = 2)
        val play = SystemClock.elapsedRealtime()
        out.submit(OutputCommand.Play(Track.Main))
        val ended = waitFor(3_000, "epoch 2 ended") { it is OutputEvent.SessionEnded && it.epoch == 2 }
        val started = synchronized(events) { events.mapNotNull { (it.second as? OutputEvent.ChunkStarted)?.tag } }
        assertEquals("only the new epoch was heard", listOf(tag(0, epoch = 2)), started)
        assertTrue("300 ms heard in ${ended - play} ms", ended - play in 250L..1_200L)
        assertTrue(failures().isEmpty())
        assertEquals(1, opens.size)
    }

    @Test
    fun theTailIsHeard_aSessionEndIsReportedOnTime() {
        val out = output()
        out.begin()
        out.write(tag(0), pcm(1_005))
        out.end()
        val play = SystemClock.elapsedRealtime()
        out.submit(OutputCommand.Play(Track.Main))
        val ended = waitFor(4_000, "session end") { it is OutputEvent.SessionEnded }
        assertTrue("1005 ms heard in ${ended - play} ms", ended - play in 950L..1_900L)
        assertTrue(failures().isEmpty())
    }

    @Test
    fun blocksAndSessionsAtOneRatePlayOnOneTrack() {
        val out = output()
        out.begin()
        for (seg in 0..2) for (c in 0..1) out.write(tag(c, segment = seg), pcm(300))
        out.end()
        out.begin(session = 8)
        out.write(tag(0, session = 8), pcm(300))
        out.end(session = 8)
        val play = SystemClock.elapsedRealtime()
        out.submit(OutputCommand.Play(Track.Main))
        val ended = waitFor(6_000, "session 8 ended") { it is OutputEvent.SessionEnded && it.sessionId == 8L }
        assertEquals("one track", listOf(24_000), opens.toList())
        assertTrue("2100 ms heard in ${ended - play} ms", ended - play in 2_000L..3_000L)
        // Each block starts right after the one before it ends (polling granularity).
        for (seg in 1..2) {
            val prevEnd = waitFor(0, "seg ${seg - 1} end") { it is OutputEvent.ChunkFinished && it.tag.segment == seg - 1 && it.tag.chunk == 1 }
            val start = waitFor(0, "seg $seg start") { it is OutputEvent.ChunkStarted && it.tag.segment == seg && it.tag.sessionId == 7L }
            assertTrue("boundary $seg: ${start - prevEnd} ms", start - prevEnd in 0L..60L)
        }
        assertTrue(failures().isEmpty())
    }

    @Test
    fun aRateChangeRebuildsTheTrackAfterTheLastFrameAtTheOldRate() {
        val out = output()
        out.begin()
        out.write(tag(0), pcm(400))
        out.end()
        out.begin(session = 8, rate = 22_050)
        out.write(tag(0, session = 8), pcm(400, rate = 22_050))
        out.end(session = 8)
        out.submit(OutputCommand.Play(Track.Main))
        val secondEnded = waitFor(4_000, "session 8 ended") { it is OutputEvent.SessionEnded && it.sessionId == 8L }
        val firstEnded = waitFor(0, "session 7 ended") { it is OutputEvent.SessionEnded && it.sessionId == 7L }
        val secondStarted = waitFor(0, "session 8 started") { it is OutputEvent.ChunkStarted && it.tag.sessionId == 8L }
        assertEquals(listOf(24_000, 22_050), opens.toList())
        assertTrue("session 8 heard after session 7 ended", secondStarted >= firstEnded)
        assertTrue(secondEnded - firstEnded in 300L..1_500L)
        assertTrue(failures().isEmpty())
    }

    @Test
    fun aShortFirstSession_doesNotLeaveABufferOfSilenceBehindLaterOnes() {
        // A new track starts only near a full buffer, so a short first
        // session needs a big pad. That pad must not stick: a session that
        // arrives just after the next one ended must not queue behind it.
        val out = output()
        out.begin()
        out.write(tag(0), pcm(100))
        out.end()
        out.submit(OutputCommand.Play(Track.Main))
        waitFor(3_000, "session 7 ended") { it is OutputEvent.SessionEnded && it.sessionId == 7L }
        out.begin(session = 8)
        out.write(tag(0, session = 8), pcm(100))
        out.end(session = 8)
        waitFor(3_000, "session 8 ended") { it is OutputEvent.SessionEnded && it.sessionId == 8L }
        Thread.sleep(30)
        val submitted = SystemClock.elapsedRealtime()
        out.begin(session = 9)
        out.write(tag(0, session = 9), pcm(100))
        out.end(session = 9)
        val started = waitFor(3_000, "session 9 heard") { it is OutputEvent.ChunkStarted && it.tag.sessionId == 9L }
        assertTrue("session 9 heard ${started - submitted} ms after it was written", started - submitted <= 150)
        assertTrue(failures().isEmpty())
        assertEquals("one track", 1, opens.size)
    }

    @Test
    fun aTrackThatNeverPlays_isReportedParked_insteadOfBlockingTheWriter() {
        val out = output { real ->
            object : PcmTrack by real {
                override fun play() = Unit // paused behind the writer's back
            }
        }
        out.begin()
        out.write(tag(0), pcm(2_000))
        val play = SystemClock.elapsedRealtime()
        out.submit(OutputCommand.Play(Track.Main))
        val failed = waitFor(6_000, "track failed") { it is OutputEvent.TrackFailed }
        assertTrue("reported after ${failed - play} ms", failed - play in 2_400L..4_000L)
        // The writer is alive: a new session plays.
        out.begin(session = 8)
        out.write(tag(0, session = 8), pcm(200))
        out.end(session = 8)
        assertNotNull(waitFor(6_000, "writer still alive") { it is OutputEvent.TrackFailed && it.sessionId == 8L || it is OutputEvent.SessionEnded })
    }
}
