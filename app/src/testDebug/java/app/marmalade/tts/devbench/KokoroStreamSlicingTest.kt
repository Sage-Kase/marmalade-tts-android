package app.marmalade.tts.devbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroStreamSlicingTest {

    // [pad, 50, 83, 16(space), 54, 4(punct), 16, pad] — Kokoro wraps with pads only
    private val ids = intArrayOf(0, 50, 83, 16, 54, 4, 16, 0)

    @Test
    fun `tail starts at the trailing run of pad, punctuation and space tokens`() {
        // trailing (4, 16, 0) = 2 + 3 + 9 = 14 frames of a 40-frame render
        val dur = longArrayOf(6, 5, 5, 2, 8, 2, 3, 9)
        assertEquals(26, KokoroStreamSlicing.tailStart(ids, dur))
    }

    @Test
    fun `mismatched durations give no tail`() {
        assertNull(KokoroStreamSlicing.tailStart(ids, longArrayOf(1, 2)))
    }

    @Test
    fun `slices tile the whole render and the last one holds the trailing pause`() {
        val slices = KokoroStreamSlicing.slices(totalFrames = 130, tailStart = 115, first = 20, size = 40, context = 4)
        assertEquals(listOf(0 until 20, 20 until 60, 60 until 100, 100 until 130), slices.map { it.keep })
        assertEquals((0 until 130).toList(), slices.flatMap { it.keep.toList() })
        assertTrue(slices.dropLast(1).all { it.keep.last < 115 })
    }

    @Test
    fun `no cut falls inside the trailing pause`() {
        // body [0, 61) cuts at 20 and 60, leaving a 1-frame slice that the tail absorbs
        val slices = KokoroStreamSlicing.slices(totalFrames = 80, tailStart = 61, first = 20, size = 40, context = 4)
        assertEquals(listOf(0 until 20, 20 until 60, 60 until 80), slices.map { it.keep })
        assertEquals(56 until 80, slices.last().render)
    }

    @Test
    fun `render windows add context, clamped to the render`() {
        val slices = KokoroStreamSlicing.slices(totalFrames = 52, tailStart = 50, first = 20, size = 40, context = 4)
        assertEquals(0 until 24, slices[0].render)
        assertEquals(16 until 52, slices[1].render)
    }

    @Test
    fun `an all-pause or empty render is one slice or none`() {
        assertEquals(listOf(0 until 10), KokoroStreamSlicing.slices(10, tailStart = 0).map { it.keep })
        assertEquals(emptyList<IntRange>(), KokoroStreamSlicing.slices(0, tailStart = 0).map { it.keep })
    }

    @Test
    fun `pause frames count every pad, punctuation and space token`() {
        val dur = longArrayOf(6, 5, 5, 2, 8, 2, 3, 9)
        assertEquals(6L + 2 + 2 + 3 + 9, KokoroGenStats.pauseFrames(ids, dur))
    }

    @Test
    fun `norm table applies the linear stats model per channel`() {
        // S = 2 channels; rows: mean c0..c2, var c0..c2, scale, bias, eps
        val model = floatArrayOf(
            1f, 0f, // mean c0
            2f, 0f, // mean c1 (× p)
            0f, 1f, // mean c2 (× ln T)
            4f, 1f, // var c0
            0f, 0f, // var c1
            0f, 0f, // var c2
            2f, 1f, // scale
            0.5f, 0f, // bias
            0f, 0f, // eps
        )
        val t = KokoroGenStats.normTable(model, pauseFraction = 0.25, frames = 1)
        // channel 0: mean 1.5, var 4 → a = 2/2 = 1, b = 0.5 − 1.5 = −1
        // channel 1: mean 0 (ln 1), var 1 → a = 1, b = 0
        assertEquals(listOf(1f, 1f, -1f, 0f), t.toList())
    }

    @Test
    fun `a negative predicted variance is floored, not NaN`() {
        val model = FloatArray(KokoroGenStats.MODEL_ROWS).also { it[3] = -5f; it[6] = 1f }
        val t = KokoroGenStats.normTable(model, pauseFraction = 0.3, frames = 100)
        assertTrue(t.all { it.isFinite() })
    }
}
