package app.marmalade.tts.engine.kitten

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KittenStreamSlicingTest {

    // [pad, 50, 83, 16(space), 54, 4(punct), 16, 10(end), pad]
    private val ids = intArrayOf(0, 50, 83, 16, 54, 4, 16, 10, 0)

    @Test
    fun `speech frames apply the KittenTrim lead and tail rule`() {
        // lead pad 6 frames → keep 2; tail group (4, 16, 10, 0) = 2+3+1+4 = 10 → keep 3
        val dur = longArrayOf(6, 5, 5, 2, 7, 2, 3, 1, 4)
        val total = dur.sum().toInt() // 35
        assertEquals(4 until total - 7, KittenStreamSlicing.speechFrames(ids, dur))
    }

    @Test
    fun `speech frames agree with KittenTrim on the same render`() {
        val dur = longArrayOf(6, 5, 5, 2, 7, 2, 3, 1, 4)
        val wav = FloatArray(dur.sum().toInt() * KittenTrim.FRAME_SAMPLES) { it.toFloat() }
        val trimmed = KittenTrim.trim(ids, wav, dur)!!
        val speech = KittenStreamSlicing.speechFrames(ids, dur)!!
        assertEquals(trimmed.first(), (speech.first * KittenTrim.FRAME_SAMPLES).toFloat())
        assertEquals(trimmed.size, (speech.last - speech.first + 1) * KittenTrim.FRAME_SAMPLES)
    }

    @Test
    fun `mismatched durations give no speech range`() {
        assertNull(KittenStreamSlicing.speechFrames(ids, longArrayOf(1, 2)))
    }

    @Test
    fun `slices tile the speech range exactly, short first slice then full ones`() {
        val speech = 4 until 121
        val slices = KittenStreamSlicing.slices(speech, totalFrames = 130, first = 20, size = 40, context = 4)
        assertEquals(listOf(4 until 24, 24 until 64, 64 until 104, 104 until 121), slices.map { it.keep })
        // Kept ranges are contiguous and cover the speech range with no gap or overlap.
        assertEquals(speech.toList(), slices.flatMap { it.keep.toList() })
    }

    @Test
    fun `with a cap, slices after the second double up to it`() {
        val speech = 0 until 500
        val slices = KittenStreamSlicing.slices(speech, totalFrames = 500, first = 20, size = 40, context = 4, max = 160)
        assertEquals(listOf(20, 40, 80, 160, 160, 40), slices.map { it.keep.last - it.keep.first + 1 })
        assertEquals(speech.toList(), slices.flatMap { it.keep.toList() })
    }

    @Test
    fun `a cap below the second slice means fixed slices`() {
        val slices = KittenStreamSlicing.slices(0 until 120, totalFrames = 120, first = 20, size = 40, context = 4, max = 10)
        assertEquals(listOf(20, 40, 40, 20), slices.map { it.keep.last - it.keep.first + 1 })
    }

    @Test
    fun `render windows add context on both sides, clamped to the render`() {
        val slices = KittenStreamSlicing.slices(2 until 50, totalFrames = 52, first = 20, size = 40, context = 4)
        assertEquals(0 until 26, slices[0].render) // 2-4 clamps to 0
        assertEquals(18 until 52, slices[1].render) // 50+4 clamps to 52
        for (s in slices) {
            assertTrue(s.render.first <= s.keep.first && s.render.last >= s.keep.last)
        }
    }

    @Test
    fun `kept samples sit at the right offset inside the rendered waveform`() {
        val s = KittenStreamSlicing.slices(10 until 30, totalFrames = 40, first = 20, size = 40, context = 4).single()
        assertEquals(4 * KittenTrim.FRAME_SAMPLES, s.keepOffsetSamples)
        assertEquals(20 * KittenTrim.FRAME_SAMPLES, s.keepSamples)
    }
}
