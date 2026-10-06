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
}
