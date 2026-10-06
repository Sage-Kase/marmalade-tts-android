package app.marmalade.tts.devbench

import app.marmalade.tts.engine.kitten.SPACE_TOKEN

/**
 * Frame arithmetic for [KokoroStreamDevEngine], kept free of Android and ORT
 * so the JVM tests pin it. Kokoro-82M's generator has the same frame grid as
 * Kitten's (600 samples = 25 ms per duration frame at 24 kHz), so slices reuse
 * [KittenStreamSlicing.Slice].
 *
 * Unlike Kitten, Kokoro trims nothing at the head and only the trailing
 * near-silence at the tail (TailTrim + SilenceCompressor, both of which look
 * only at the end of a chunk). So slices tile the WHOLE render, and no cut
 * falls inside the trailing pause: the last slice holds all of it, which lets
 * the engine apply the tail trim to the last slice alone and get the same
 * result as trimming the whole chunk.
 */
internal object KokoroStreamSlicing {

    /**
     * First frame of the trailing pause: the render minus the frames of the
     * trailing run of pad / punctuation / space tokens (ids ≤ [SPACE_TOKEN],
     * the shared IPA vocab). Null when [ids] and [durFrames] disagree.
     */
    fun tailStart(ids: IntArray, durFrames: LongArray): Int? {
        if (ids.size != durFrames.size || ids.isEmpty()) return null
        var tail = 0L
        for (i in ids.indices.reversed()) {
            if (ids[i] <= SPACE_TOKEN) tail += durFrames[i] else break
        }
        return (durFrames.sum() - tail).toInt()
    }

    /**
     * Cut `[0, totalFrames)` into a [first]-frame slice then [size]-frame
     * slices, cutting only before [tailStart]; the last slice runs to the end.
     * Each renders [context] extra frames per side, clamped to the render.
     */
    fun slices(
        totalFrames: Int,
        tailStart: Int,
        first: Int = KittenStreamSlicing.FIRST_SLICE_FRAMES,
        size: Int = KittenStreamSlicing.SLICE_FRAMES,
        context: Int = KittenStreamSlicing.CONTEXT_FRAMES,
    ): List<KittenStreamSlicing.Slice> {
        if (totalFrames <= 0) return emptyList()
        val cutEnd = tailStart.coerceIn(1, totalFrames)
        val body = KittenStreamSlicing.slices(0 until cutEnd, totalFrames, first, size, context)
        val last = body.last()
        return body.dropLast(1) + KittenStreamSlicing.Slice(
            keep = last.keep.first until totalFrames,
            render = last.render.first until totalFrames,
        )
    }
}
