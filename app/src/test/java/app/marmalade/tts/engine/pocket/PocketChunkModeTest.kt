package app.marmalade.tts.engine.pocket

import app.marmalade.tts.engine.PieceGate
import app.marmalade.tts.engine.SliceEstimate
import app.marmalade.tts.engine.keepsUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chunk mode's model of the rest of a Pocket mini-chunk (#54). */
class PocketChunkModeTest {

    // The 6a's decoder shape: a fixed cost per run plus a cost per frame.
    private val decode6a = { n: Int -> 60.0 + 24.0 * n }

    private fun assertSlices(expected: List<SliceEstimate>, actual: List<SliceEstimate>) {
        assertEquals(expected.size, actual.size)
        for ((e, a) in expected.zip(actual)) {
            assertEquals(e.renderMs, a.renderMs, 1e-9)
            assertEquals(e.playedMs, a.playedMs, 1e-9)
        }
    }

    @Test
    fun `the length guess is the token rate plus 20 percent, rounded up`() {
        // 10 tokens × 110 ms = 13.75 frames of 80 ms; × 1.2 = 16.5.
        assertEquals(17, PocketStreamPlan.chunkEstFrames(10, 110.0, 80.0))
    }

    @Test
    fun `with the decoder beside the AR loop each slice waits for its frames or the decoder`() {
        // 4 frames generated but not decoded, 10 still to generate, cut 4 at a time.
        val rest = PocketStreamPlan.chunkRest(
            estFrames = 20, generatedFrames = 10, decodedFrames = 6, finished = false, sliceFrames = 4,
            arMsPerFrame = 50.0, decodeMs = decode6a, overlap = true, playedFrameMs = 80.0, decodeSafety = 1.0,
        )
        // Arrivals 156 (queued frames, decoder free now), 356, 556 (AR-bound), 664 (decoder-bound).
        assertSlices(
            listOf(SliceEstimate(156.0, 320.0), SliceEstimate(200.0, 320.0), SliceEstimate(200.0, 320.0), SliceEstimate(108.0, 160.0)),
            rest,
        )
    }

    @Test
    fun `an inline decoder blocks the AR loop`() {
        val rest = PocketStreamPlan.chunkRest(
            estFrames = 20, generatedFrames = 10, decodedFrames = 6, finished = false, sliceFrames = 4,
            arMsPerFrame = 50.0, decodeMs = decode6a, overlap = false, playedFrameMs = 80.0, decodeSafety = 1.0,
        )
        // Arrivals 156, 512, 868, 1076: AR and decode add up.
        assertEquals(listOf(156.0, 356.0, 356.0, 208.0), rest.map { it.renderMs })
    }

    @Test
    fun `each decode is charged the streaming cut rule's safety`() {
        // The same chunk as above with the default 3x: the decoder is now the
        // bottleneck, 3 × (60 + 24 × 4) = 468 ms per 4-frame slice.
        val rest = PocketStreamPlan.chunkRest(
            estFrames = 20, generatedFrames = 10, decodedFrames = 6, finished = false, sliceFrames = 4,
            arMsPerFrame = 50.0, decodeMs = decode6a, overlap = true, playedFrameMs = 80.0,
        )
        assertEquals(listOf(468.0, 468.0, 468.0, 324.0), rest.map { it.renderMs })
    }

    @Test
    fun `a finished chunk has only its queued frames left, an overtaken guess one more slice`() {
        val done = PocketStreamPlan.chunkRest(
            estFrames = 40, generatedFrames = 10, decodedFrames = 6, finished = true, sliceFrames = 4,
            arMsPerFrame = 50.0, decodeMs = decode6a, overlap = true, playedFrameMs = 80.0, decodeSafety = 1.0,
        )
        assertSlices(listOf(SliceEstimate(156.0, 320.0)), done)
        val overtaken = PocketStreamPlan.chunkRest(
            estFrames = 8, generatedFrames = 10, decodedFrames = 6, finished = false, sliceFrames = 4,
            arMsPerFrame = 50.0, decodeMs = decode6a, overlap = true, playedFrameMs = 80.0,
        )
        assertEquals(8 * 80.0, overtaken.sumOf { it.playedMs }, 1e-9)
        assertTrue(PocketStreamPlan.chunkRest(10, 10, 10, finished = true, 4, 50.0, decode6a, true, 80.0).isEmpty())
    }

    // A chunk's first 6-frame slice just decoded (a 7th generated), guessed at 40 frames.
    private fun firstSliceRest(arMs: Double, decodeMs: (Int) -> Double, playedFrameMs: Double) =
        PocketStreamPlan.chunkRest(
            estFrames = 40, generatedFrames = 7, decodedFrames = 6, finished = false, sliceFrames = 6,
            arMsPerFrame = arMs, decodeMs = decodeMs, overlap = true, playedFrameMs = playedFrameMs,
        )

    @Test
    fun `a render slower than playback holds the chunk, a fast one releases its first slice`() {
        // 6a pace at 2x: 50 ms per AR frame for 40 ms of played audio.
        val slow = firstSliceRest(arMs = 50.0, decodeMs = decode6a, playedFrameMs = 40.0)
        assertFalse(keepsUp(aheadMs = 0.0, bankedMs = 6 * 40.0, rest = slow))
        // The same render with the last sentence's 5 s still to play: no need to wait.
        assertTrue(keepsUp(aheadMs = 5_000.0, bankedMs = 6 * 40.0, rest = slow))
        // A fast render at 1x: 30 ms per frame for 80 ms played.
        val fast = firstSliceRest(arMs = 30.0, decodeMs = { n -> 30.0 + 5.0 * n }, playedFrameMs = 80.0)
        assertTrue(keepsUp(aheadMs = 0.0, bankedMs = 6 * 80.0, rest = fast))
    }

    @Test
    fun `the gate holds a slow chunk to its end and passes a fast one straight through`() {
        val slow = firstSliceRest(arMs = 50.0, decodeMs = decode6a, playedFrameMs = 40.0)
        val held = PieceGate<String>(chunk = true)
        held.startPiece()
        assertEquals(emptyList<String>(), held.offer("s0", 240.0, nowMs = 0, aheadMs = 0.0) { slow })
        assertEquals(emptyList<String>(), held.offer("s1", 240.0, nowMs = 300, aheadMs = 0.0) { slow.drop(1) })
        assertEquals(listOf("s0", "s1"), held.endPiece(nowMs = 2_000))

        val fast = firstSliceRest(arMs = 30.0, decodeMs = { n -> 30.0 + 5.0 * n }, playedFrameMs = 80.0)
        val open = PieceGate<String>(chunk = true)
        open.startPiece()
        assertEquals(listOf("f0"), open.offer("f0", 480.0, nowMs = 0, aheadMs = 0.0) { fast })
        assertEquals(listOf("f1"), open.offer("f1", 480.0, nowMs = 200, aheadMs = 0.0) { fast.drop(1) })

        // Stream mode: even the slow render's slices go at once.
        val stream = PieceGate<String>(chunk = false)
        stream.startPiece()
        assertEquals(listOf("s0"), stream.offer("s0", 240.0, nowMs = 0, aheadMs = 0.0) { slow })
    }
}
