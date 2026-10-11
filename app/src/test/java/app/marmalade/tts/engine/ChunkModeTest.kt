package app.marmalade.tts.engine

import app.marmalade.tts.perf.RenderPace
import app.marmalade.tts.perf.SpeedPerfWarning
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChunkModeTest {

    // -- the per-request decision ---------------------------------------------

    @Test
    fun `streams while the render keeps up comfortably and chunks past it`() {
        // Kokoro v26 on the Pixel 6a: RTF ~0.43 (#51).
        assertFalse(RenderMode.decide(0.43, "predicted", 1.0f).chunk)
        assertFalse(RenderMode.decide(0.43, "predicted", 1.25f).chunk) // 0.54: streamed without a stall
        assertTrue(RenderMode.decide(0.43, "predicted", 1.5f).chunk) // 0.65: stalled 60–120 ms
        assertTrue(RenderMode.decide(0.43, "predicted", 2.0f).chunk)
        // Kitten (~0.13) streams at any speed the app offers.
        assertFalse(RenderMode.decide(0.13, "measured", 3.0f).chunk)
    }

    @Test
    fun `nothing known follows the static speed rule, as the warning does`() {
        val mode = RenderMode.decide(null, "predicted", 2.0f)
        assertTrue(mode.chunk)
        assertEquals("none", mode.source)
        assertNull(mode.rtf)
        assertFalse(RenderMode.decide(null, "predicted", 1.3f).chunk)
    }

    @Test
    fun `chunk mode is chosen exactly where the speed warning shows`() {
        val rtfs = listOf(null, 0.0, 0.1, 0.13, 0.3, 0.35, 0.43, 0.48, 0.5, 0.6, 0.8, 0.9, 1.2)
        var speed = 0.5f
        while (speed <= 3.0f) {
            for (measured in rtfs) for (predicted in rtfs) {
                val warns = SpeedPerfWarning.shouldWarn(measured, predicted, speed)
                val estimate = RenderPace.of(measured, predicted)
                val mode = RenderMode.decide(estimate?.rtf, estimate?.source ?: "none", speed)
                assertEquals("measured=$measured predicted=$predicted speed=$speed", warns, mode.chunk)
            }
            speed += 0.05f
        }
    }

    @Test
    fun `the debug knob forces either mode`() {
        assertTrue(RenderMode.decide(0.1, "measured", 1.0f, force = RenderMode.forced("on")).chunk)
        assertFalse(RenderMode.decide(0.9, "measured", 2.0f, force = RenderMode.forced("off")).chunk)
        assertEquals("forced", RenderMode.decide(0.9, "measured", 2.0f, force = true).source)
        assertNull(RenderMode.forced(null))
        assertNull(RenderMode.forced("maybe"))
    }

    @Test
    fun `the log line names the mode, the rtf and where it came from`() {
        assertEquals(
            "kokoro-direct-v1_0 mode=chunk rtf=0.43 (predicted) speed=2.00 played=0.86 path=sliced",
            RenderMode.decide(0.43, "predicted", 2.0f).line("kokoro-direct-v1_0"),
        )
        assertEquals(
            "kokoro-de-v1_0 mode=stream rtf=- (none) speed=1.00 played=- path=whole",
            RenderMode.decide(null, "measured", 1.0f).line("kokoro-de-v1_0", path = "whole"),
        )
    }

    // -- the release rule -----------------------------------------------------

    @Test
    fun `a slice must arrive before playback reaches it`() {
        // 500 ms banked; the next slice (1000 ms played) renders in 400 ms.
        assertTrue(keepsUp(0.0, 500.0, listOf(SliceEstimate(400.0, 1000.0)), margin = 1.0))
        assertFalse(keepsUp(0.0, 500.0, listOf(SliceEstimate(600.0, 1000.0)), margin = 1.0))
        // The margin charges each render more.
        assertFalse(keepsUp(0.0, 500.0, listOf(SliceEstimate(400.0, 1000.0)), margin = 1.3))
        // Audio still playing ahead of the piece buys time.
        assertTrue(keepsUp(200.0, 500.0, listOf(SliceEstimate(600.0, 1000.0)), margin = 1.0))
    }

    @Test
    fun `every later slice is checked, not just the next`() {
        // The next slice arrives in time (500 ≤ 600) but the one after it doesn't (1700 > 1100).
        assertFalse(keepsUp(0.0, 600.0, listOf(SliceEstimate(500.0, 500.0), SliceEstimate(1200.0, 2000.0)), margin = 1.0))
        assertTrue(keepsUp(0.0, 600.0, listOf(SliceEstimate(500.0, 500.0), SliceEstimate(500.0, 2000.0)), margin = 1.0))
    }

    @Test
    fun `slices doubling as Kokoro's need most of the piece banked when rendering is behind`() {
        // Kokoro's 0.5, 1, 2, 4 s slices at 2x (250, 500, 1000, 2000 ms played), each rendered in
        // about its played length plus a fixed cost.
        val rest = listOf(SliceEstimate(570.0, 500.0), SliceEstimate(1020.0, 1000.0), SliceEstimate(1700.0, 2000.0))
        assertFalse(keepsUp(0.0, 250.0, rest, margin = 1.0))
        assertFalse(keepsUp(0.0, 750.0, rest.drop(1), margin = 1.0))
        assertTrue(keepsUp(0.0, 1750.0, rest.drop(2), margin = 1.0))
    }

    @Test
    fun `the hold is exactly as long as the latest slice needs`() {
        // Kokoro's 150-token sentence at 2x, 2 s slices: after its first slice (250 ms played
        // banked) the second must wait 450 - 250 = 200 ms, the third 1230 - 750 = 480 ms.
        val rest = listOf(SliceEstimate(450.0, 500.0), SliceEstimate(780.0, 1000.0), SliceEstimate(780.0, 1000.0))
        assertEquals(480.0, holdMs(0.0, 250.0, rest, margin = 1.0), 1e-9)
        // Audio still playing covers a hold shorter than itself...
        assertEquals(0.0, holdMs(480.0, 250.0, rest, margin = 1.0), 1e-9)
        // ...but not a longer one: the piece then starts when the hold ends, not before.
        assertEquals(480.0, holdMs(300.0, 250.0, rest, margin = 1.0), 1e-9)
        // With 4 s slices the last one needs far more banked first.
        val big = listOf(SliceEstimate(450.0, 500.0), SliceEstimate(780.0, 1000.0), SliceEstimate(1440.0, 2000.0))
        assertEquals(920.0, holdMs(0.0, 250.0, big, margin = 1.0), 1e-9)
    }

    @Test
    fun `nothing left to render keeps up`() {
        assertTrue(keepsUp(0.0, 0.0, emptyList()))
    }

    // -- the gate ---------------------------------------------------------------

    @Test
    fun `out of chunk mode every slice goes straight out`() {
        val gate = PieceGate<String>()
        gate.startPiece(chunk = false)
        assertEquals(listOf("a"), gate.offer("a", 250.0, 0, 0.0) { listOf(SliceEstimate(10_000.0, 1.0)) })
        assertEquals(listOf("b"), gate.offer("b", 500.0, 100, 0.0) { emptyList() })
        assertEquals(emptyList<String>(), gate.endPiece(200))
    }

    @Test
    fun `chunk mode holds a piece until the rest can't run dry, then streams the rest`() {
        val released = ArrayList<Triple<Long, Int, Int>>()
        val gate = PieceGate<String>(margin = 1.0) { ms, n, rest -> released += Triple(ms, n, rest) }
        gate.startPiece(chunk = true)
        val rest = listOf(SliceEstimate(570.0, 500.0), SliceEstimate(1020.0, 1000.0), SliceEstimate(1700.0, 2000.0))
        assertEquals(emptyList<String>(), gate.offer("s0", 250.0, 1_000, 0.0) { rest })
        assertEquals(emptyList<String>(), gate.offer("s1", 500.0, 1_570, 0.0) { rest.drop(1) })
        assertEquals(listOf("s0", "s1", "s2"), gate.offer("s2", 1000.0, 2_590, 0.0) { rest.drop(2) })
        assertEquals(listOf(Triple(1_590L, 3, 1)), released)
        // Released: the last slice goes straight out, and the piece's end has nothing left.
        assertEquals(listOf("s3"), gate.offer("s3", 2000.0, 4_510, 0.0) { emptyList() })
        assertEquals(emptyList<String>(), gate.endPiece(4_510))
        // The next piece is held again.
        gate.startPiece(chunk = true)
        assertEquals(emptyList<String>(), gate.offer("t0", 250.0, 5_000, 0.0) { rest })
    }

    @Test
    fun `chunk mode lets a piece that keeps up go after its first slice`() {
        val released = ArrayList<Int>()
        val gate = PieceGate<String>() { _, n, _ -> released += n }
        gate.startPiece(chunk = true)
        val fast = listOf(SliceEstimate(100.0, 500.0), SliceEstimate(200.0, 1000.0))
        assertEquals(listOf("s0"), gate.offer("s0", 250.0, 0, 0.0) { fast })
        // One slice let go at once was never held: no release line.
        assertEquals(emptyList<Int>(), released)
    }

    @Test
    fun `a piece that never gets there goes out whole at its end`() {
        val released = ArrayList<Int>()
        val gate = PieceGate<String>() { _, n, rest -> released += n * 10 + rest }
        gate.startPiece(chunk = true)
        val slow = { listOf(SliceEstimate(5_000.0, 500.0)) }
        assertEquals(emptyList<String>(), gate.offer("s0", 250.0, 0, 0.0, slow))
        assertEquals(emptyList<String>(), gate.offer("s1", 250.0, 10, 0.0, slow))
        assertEquals(listOf("s0", "s1"), gate.endPiece(20))
        assertEquals(listOf(20), released)
        // A one-slice piece held to its end counts as held too.
        gate.startPiece(chunk = true)
        assertEquals(emptyList<String>(), gate.offer("t0", 250.0, 30, 0.0, slow))
        assertEquals(listOf("t0"), gate.endPiece(40))
        assertEquals(listOf(20, 10), released)
    }

    @Test
    fun `the timed gate lets a held piece go when its hold is over, not at the next slice`() = runTest {
        val sent = ArrayList<Pair<Long, String>>()
        val gate = TimedPieceGate<String>(
            scope = this,
            send = { out -> out.forEach { sent += testScheduler.currentTime to it } },
            nowMs = { testScheduler.currentTime },
        )
        val job = launch {
            gate.startPiece(chunk = true)
            // At t=0: 250 ms banked, the next slice due in 450 ms and the one after 780 ms later:
            // hold ≈ 1.1 × 1230 - 750 = 603 ms.
            gate.offer("s0", 250.0, aheadMs = { 0.0 }) {
                listOf(SliceEstimate(450.0, 500.0), SliceEstimate(780.0, 1000.0))
            }
        }
        runCurrent()
        job.join()
        assertEquals(emptyList<Pair<Long, String>>(), sent)
        // The next slice lands on time (≤ 1.1 × 450 ms): with 750 ms banked the
        // last one still needs 1.1 × 780 - 750 = 108 ms, the same moment as before.
        advanceTimeBy(495)
        runCurrent()
        gate.offer("s1", 500.0, aheadMs = { 0.0 }) { listOf(SliceEstimate(780.0, 1000.0)) }
        advanceTimeBy(105)
        runCurrent()
        assertEquals(emptyList<Pair<Long, String>>(), sent)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(listOf("s0", "s1"), sent.map { it.second })
        assertTrue("released at ${sent[0].first}", sent[0].first in 603L..604L)
        // Released: later slices go straight out, and the piece's end has nothing left.
        gate.offer("s2", 1000.0, aheadMs = { 0.0 }) { emptyList() }
        gate.endPiece()
        assertEquals(listOf("s0", "s1", "s2"), sent.map { it.second })
    }

    @Test
    fun `the timed gate's end of piece wins over a pending timer`() = runTest {
        val sent = ArrayList<String>()
        val gate = TimedPieceGate<String>(this, send = { sent += it }, nowMs = { testScheduler.currentTime })
        gate.startPiece(chunk = true)
        gate.offer("s0", 250.0, aheadMs = { 0.0 }) { listOf(SliceEstimate(5_000.0, 500.0)) }
        gate.endPiece()
        assertEquals(listOf("s0"), sent)
        // The cancelled timer never sends again.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("s0"), sent)
    }

    @Test
    fun `at its due time the timed gate holds on while the slice in flight is late`() = runTest {
        val sent = ArrayList<String>()
        val gate = TimedPieceGate<String>(this, send = { sent += it }, nowMs = { testScheduler.currentTime })
        gate.startPiece(chunk = true)
        // Due at ≈ 603 ms (as above), but the next slice (≤ 495 ms) never lands.
        gate.offer("s0", 250.0, aheadMs = { 0.0 }) {
            listOf(SliceEstimate(450.0, 500.0), SliceEstimate(780.0, 1000.0))
        }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(emptyList<String>(), sent)
        // It lands at last, late: the gate decides again from there.
        gate.offer("s1", 500.0, aheadMs = { 0.0 }) { listOf(SliceEstimate(780.0, 1000.0)) }
        advanceTimeBy(120)
        runCurrent()
        assertEquals(listOf("s0", "s1"), sent)
        gate.endPiece()
    }

    @Test
    fun `a faster speed holds longer`() {
        var rate = 1f
        val gate = PieceGate<String>(margin = 1.0, rate = { rate })
        gate.startPiece(chunk = true)
        val rest = listOf(SliceEstimate(400.0, 1000.0))
        // 500 ms of audio banked at 1x covers the 400 ms render.
        assertEquals(listOf("s0"), gate.offer("s0", 500.0, 0, 0.0) { rest })
        gate.startPiece(chunk = true)
        rate = 2f
        // At 2x it plays in 250 ms: hold 150 ms.
        assertEquals(emptyList<String>(), gate.offer("t0", 500.0, 1_000, 0.0) { rest })
        assertEquals(1_150L, gate.dueAtMs)
    }

    @Test
    fun `a length the engine can't bound holds the piece until the next slice`() {
        val gate = PieceGate<String>()
        gate.startPiece(chunk = true)
        assertEquals(emptyList<String>(), gate.offer("s0", 500.0, 0, 0.0) { listOf(SliceEstimate(Double.POSITIVE_INFINITY, 0.0)) })
        assertNull(gate.dueAtMs)
        assertEquals(listOf("s0", "s1"), gate.offer("s1", 500.0, 100, 0.0) { emptyList() })
    }

    // -- the request's pace ---------------------------------------------------

    @Test
    fun `a speed change reaches the next piece's mode and is logged once`() {
        val live = MutableStateFlow(1f)
        val logged = ArrayList<String>()
        val pace = RequestPace(
            "kokoro-direct-v1_0", RenderPace.Estimate(0.43, "measured"), 1f, force = null, live = live,
            log = { logged += it.line("kokoro-direct-v1_0") },
        )
        assertFalse(pace.pieceMode().chunk)
        assertFalse(pace.pieceMode().chunk)
        live.value = 2f
        assertTrue(pace.pieceMode().chunk)
        assertEquals(2f, pace.rate())
        assertEquals(
            listOf(
                "kokoro-direct-v1_0 mode=stream rtf=0.43 (measured) speed=1.00 played=0.43 path=sliced",
                "kokoro-direct-v1_0 mode=chunk rtf=0.43 (measured) speed=2.00 played=0.86 path=sliced",
            ),
            logged,
        )
    }

    @Test
    fun `without a live rate the request's holds`() {
        val pace = RequestPace("x", RenderPace.Estimate(0.43, "predicted"), 1.5f, force = null, log = {})
        assertEquals(1.5f, pace.rate())
        assertTrue(pace.pieceMode().chunk)
    }

    // -- the request's own horizon (a Stop the engine never hears about) ------

    @Test
    fun `a new request's first piece doesn't count the last request's audio as cover`() {
        var now = 0L
        val horizon = PlaybackHorizon { now * 1_000_000 }
        // The last request sent 5 s, then its client flushed it without telling the engine.
        horizon.startRequest(1f)
        horizon.onEmitted(5_000)
        now = 100
        horizon.startRequest(1f)
        assertEquals(4_900L, horizon.remainingMs())
        assertEquals(0L, horizon.requestRemainingMs())
        // So a slow first piece is held, where the stale 4.9 s would have let it go.
        val slow = listOf(SliceEstimate(2_000.0, 1_000.0))
        val gate = PieceGate<String>()
        gate.startPiece(chunk = true)
        assertEquals(emptyList<String>(), gate.offer("s0", 500.0, now, horizon.requestRemainingMs().toDouble()) { slow })
        val stale = PieceGate<String>()
        stale.startPiece(chunk = true)
        assertEquals(listOf("s0"), stale.offer("s0", 500.0, now, horizon.remainingMs().toDouble()) { slow })
    }

    @Test
    fun `this request's own audio still ahead counts, and drains faster after a speed-up`() {
        var now = 0L
        val horizon = PlaybackHorizon { now * 1_000_000 }
        horizon.startRequest(1f)
        horizon.onEmitted(2_000)
        now = 500
        assertEquals(1_500L, horizon.requestRemainingMs())
        horizon.follow(2f)
        assertEquals(750L, horizon.requestRemainingMs())
        horizon.clear()
        assertEquals(0L, horizon.requestRemainingMs())
    }
}
