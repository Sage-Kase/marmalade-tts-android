package app.marmalade.tts.engine.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketStreamPlanTest {

    @Test
    fun `slices double from 6 frames up to the exact batch`() {
        assertEquals(listOf(6, 12, 24, 46, 46, 46), (0..5).map { PocketStreamPlan.sliceFrames(it, 46) })
        assertEquals(listOf(4, 4), (0..1).map { PocketStreamPlan.sliceFrames(it, 4) })
        assertEquals(46, PocketStreamPlan.sliceFrames(1000, 46))
    }

    private fun hold(frames: List<Int>, ar: Double, perFrame: Double, overlap: Boolean, prefill: Double = 0.0) =
        PocketStreamPlan.holdMs(frames, 46, ar, prefill, { n -> perFrame * n }, overlap, playedFrameMs = 80.0)

    @Test
    fun `a fast render needs no hold`() {
        // 7th frame at 70 ms → slice of 6 ready at 130; the last 8 frames ready at 220, long before 130 + 480.
        assertEquals(0.0, hold(listOf(14), ar = 10.0, perFrame = 10.0, overlap = true), 1e-9)
    }

    @Test
    fun `a real-time AR loop holds until the doubled slice can't run dry`() {
        // Overlap: slice 0 (6 frames) ready at 7·80 + 120 = 680. Slice 1 (12) is
        // cut at frame 19 (1520) and ready at 1760, but playback started at 680
        // needs it at 680 + 480: hold 600.
        assertEquals(600.0, hold(listOf(20), ar = 80.0, perFrame = 20.0, overlap = true), 1e-9)
        // Inline, decoding also delays the AR loop: slice 1 ready at 1880, hold 720.
        assertEquals(720.0, hold(listOf(20), ar = 80.0, perFrame = 20.0, overlap = false), 1e-9)
    }

    @Test
    fun `a later chunk's prefill and first slice count too`() {
        // Chunk 0: 7 frames → slice 6 at 70 (ready 130), last frame at 70 → slice 1 ready 140.
        // Chunk 1 after a 2000 ms prefill: its first slice (6 frames, cut at frame 7)
        // is ready at 70 + 2000 + 70 + 60 = 2200, but 7 frames (560 ms) play from 130.
        val h = hold(listOf(7, 14), ar = 10.0, perFrame = 10.0, overlap = true, prefill = 2000.0)
        assertEquals(2200.0 - 130 - 560, h, 1e-9)
    }

    @Test
    fun `decode cost fits a fixed cost per run once two sizes are seen`() {
        val cost = PocketDecodeCost()
        assertEquals(0.0, cost.ms(6), 1e-9)
        cost.add(6, 210.0)
        // One size seen: its ms per frame for every frame (over-estimates bigger slices).
        assertEquals(46 * 35.0, cost.ms(46), 1e-9)
        cost.add(46, 1100.0)
        // 22.25 ms per frame + 76.5 ms per run.
        assertEquals(76.5 + 12 * 22.25, cost.ms(12), 1e-9)
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
