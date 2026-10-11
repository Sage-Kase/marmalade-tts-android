package app.marmalade.tts.engine.pocket

import app.marmalade.tts.engine.SliceCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketStreamPlanTest {

    // The rule's arithmetic with the bare decode estimate (safety 1); the shipped factor is tested below.
    private fun clock(perFrame: Double, overlap: Boolean = true) =
        PocketSliceClock(maxBatch = 46, playedFrameMs = 80.0, decodeMs = { n -> perFrame * n }, overlap = overlap, safety = 1.0)

    @Test
    fun `the first slice goes at 6 frames and no slice exceeds the exact batch`() {
        val c = clock(perFrame = 10.0)
        assertFalse(c.shouldCut(5, nowMs = 50.0, arMsPerFrame = 10.0))
        assertTrue(c.shouldCut(6, nowMs = 60.0, arMsPerFrame = 10.0))
        c.submitted(6, nowMs = 60.0)
        assertTrue(c.shouldCut(46, nowMs = 100.0, arMsPerFrame = 10.0))
    }

    @Test
    fun `a later slice is cut once one more frame would let playback run dry`() {
        val c = clock(perFrame = 10.0)
        // Ready at 60, so its 480 ms of audio plays until 540.
        assertEquals(60.0, c.submitted(6, nowMs = 0.0).second, 1e-9)
        // Waiting one frame (to 340) then decoding 5 frames (50) + 100 margin = 490: still before 540.
        assertFalse(c.shouldCut(4, nowMs = 300.0, arMsPerFrame = 40.0))
        // At 360: 400 + 60 + 100 = 560, past 540.
        assertTrue(c.shouldCut(5, nowMs = 360.0, arMsPerFrame = 40.0))
        // Never below the 4-frame minimum, however late.
        assertFalse(c.shouldCut(3, nowMs = 1000.0, arMsPerFrame = 40.0))
    }

    @Test
    fun `actual decode times move the slices queued behind`() {
        val c = clock(perFrame = 10.0)
        c.submitted(6, nowMs = 0.0) // expected ready 60
        assertEquals(140.0, c.submitted(4, nowMs = 100.0).second, 1e-9)
        c.decoded(0, nowMs = 160.0) // 100 ms late: slice 1 now expected at 240
        assertEquals(0.0, c.stallMs(), 1e-9) // playback 160..640 covers it
        c.decoded(1, nowMs = 900.0)
        assertEquals(260.0, c.stallMs(), 1e-9)
        c.holdMs = 260.0
        assertEquals(0.0, c.stallMs(), 1e-9)
    }

    private fun hold(
        frames: List<Int>, ar: Double, perFrame: Double, overlap: Boolean, prefill: Double = 0.0,
        safety: Double = 1.0,
    ) = PocketStreamPlan.holdMs(frames, 46, ar, prefill, { n -> 60 + perFrame * n }, overlap, playedFrameMs = 80.0, safety = safety)

    private fun stall(frames: List<Int>, ar: Double, perFrame: Double, overlap: Boolean, hold: Double) =
        PocketStreamPlan.stallMs(frames, 46, ar, 0.0, { n -> 60 + perFrame * n }, overlap, playedFrameMs = 80.0, hold = hold, safety = 1.0)

    @Test
    fun `a render that keeps up needs no hold`() {
        // The 6a at 1x: AR 35 ms and decode 60 + 24 ms per frame against 80 ms of audio per frame.
        assertEquals(0.0, hold(listOf(92), ar = 35.0, perFrame = 24.0, overlap = true), 1e-9)
        assertEquals(0.0, hold(listOf(31, 29, 26), ar = 35.0, perFrame = 24.0, overlap = true, prefill = 100.0), 1e-9)
        val safety = PocketSliceClock.DECODE_SAFETY
        assertEquals(0.0, hold(listOf(92), ar = 35.0, perFrame = 24.0, overlap = true, safety = safety), 1e-9)
        assertEquals(0.0, hold(listOf(31, 29, 26), ar = 35.0, perFrame = 24.0, overlap = true, prefill = 100.0, safety = safety), 1e-9)
    }

    /**
     * Runs one 6a-like request through the clock (AR 31 ms per frame, the
     * decoder alongside at 60 + 22 ms per frame), with slice [slowSlice]
     * really taking [slowBy] times as long. Returns playback's total wait.
     */
    private fun streamed(safety: Double, slowSlice: Int, slowBy: Double): Double {
        val dec = { n: Int -> 60.0 + 22.0 * n }
        val c = PocketSliceClock(46, 80.0, dec, overlap = true, safety = safety)
        val actual = ArrayList<Double>() // when each slice really is ready
        var reported = 0
        fun report(upTo: Double) {
            while (reported < actual.size && actual[reported] <= upTo) c.decoded(reported, actual[reported++])
        }
        var t = 0.0
        var pending = 0
        fun submit() {
            val (i, _) = c.submitted(pending, t)
            val start = maxOf(t, actual.lastOrNull() ?: 0.0)
            actual += start + dec(pending) * (if (i == slowSlice) slowBy else 1.0)
            pending = 0
        }
        repeat(300) {
            t += 31.0
            pending++
            report(t)
            if (c.shouldCut(pending, t, 31.0)) submit()
        }
        if (pending > 0) submit()
        report(Double.MAX_VALUE)
        return c.stallMs()
    }

    @Test
    fun `the safety factor keeps enough audio ahead to ride out a slow slice`() {
        // On the 6a a slice now and then took 2.4-5.5x its usual time (decoder parked on a little core).
        // With the bare estimate a slow early slice makes playback wait (later ones have audio
        // banked by then); with the factor none do.
        val bare = (1..12).count { streamed(1.0, it, 3.0) > 0.0 }
        assertEquals(4, bare)
        for (slow in 1..12) {
            assertEquals("slice $slow", 0.0, streamed(PocketSliceClock.DECODE_SAFETY, slow, 3.0), 1e-9)
        }
        // Nothing slow: no wait either way.
        assertEquals(0.0, streamed(1.0, -1, 1.0), 1e-9)
        assertEquals(0.0, streamed(PocketSliceClock.DECODE_SAFETY, -1, 1.0), 1e-9)
    }

    @Test
    fun `a render slower than playback is held just enough`() {
        // AR at 100 ms per 80 ms frame: without a hold playback waits 832 ms.
        assertEquals(832.0, stall(listOf(60), ar = 100.0, perFrame = 24.0, overlap = true, hold = 0.0), 1e-9)
        val h = hold(listOf(60), ar = 100.0, perFrame = 24.0, overlap = true)
        assertEquals(872.0, h, 1e-9)
        // A longer hold lets later slices grow, which leaves a small wait (20 ms) after 3 passes.
        assertTrue(stall(listOf(60), ar = 100.0, perFrame = 24.0, overlap = true, hold = h) <= 20.0)
        // Inline, decoding also blocks the AR loop: 60 + 40 ms per frame of AR + decode
        // keeps up in overlap, not inline.
        assertEquals(0.0, hold(listOf(60), ar = 60.0, perFrame = 40.0, overlap = true), 1e-9)
        assertTrue(hold(listOf(60), ar = 60.0, perFrame = 40.0, overlap = false) > 0.0)
    }

    @Test
    fun `decode cost fits a fixed cost per run once two sizes are seen`() {
        val cost = SliceCost()
        assertEquals(0.0, cost.ms(6), 1e-9)
        cost.add(6, 210.0)
        // One size seen: its ms per frame for every frame (over-estimates bigger slices).
        assertEquals(46 * 35.0, cost.ms(46), 1e-9)
        cost.add(46, 1100.0)
        // 22.25 ms per frame + 76.5 ms per run.
        assertEquals(76.5 + 12 * 22.25, cost.ms(12), 1e-9)
    }

    @Test
    fun `decode cost does not fit a line through slices of nearly the same size`() {
        // The 6a's first two slices of a paragraph: the line through them predicted ~0 ms for 25 frames.
        val cost = SliceCost()
        cost.add(6, 257.0)
        cost.add(7, 244.0)
        assertEquals(25 * 250.5 / 6.5, cost.ms(25), 1e-9)
        // A line that tilts the wrong way over a wide spread is not trusted either.
        cost.add(20, 240.0)
        assertEquals(20 * 247.0 / 11.0, cost.ms(20), 1e-9)
    }

    @Test
    fun `the bank holds until its release time, then sends everything in order`() {
        val bank = PocketSliceBank<String>()
        assertEquals(emptyList<String>(), bank.offer("a", nowMs = 0))
        bank.releaseAtMs = 1000
        assertEquals(emptyList<String>(), bank.offer("b", nowMs = 500))
        assertEquals(listOf("a", "b", "c"), bank.offer("c", nowMs = 1000))
        assertEquals(listOf("d"), bank.offer("d", nowMs = 1100))
        val timer = PocketSliceBank<String>().apply { releaseAtMs = 5000 }
        timer.offer("a", nowMs = 0)
        assertEquals(listOf("a"), timer.flush())
        assertEquals(listOf("b"), timer.offer("b", nowMs = 1))
    }
}
