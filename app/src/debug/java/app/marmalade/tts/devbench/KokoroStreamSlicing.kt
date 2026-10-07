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
     * Cut `[0, totalFrames)` like [KittenStreamSlicing.slices] (a [first]-frame
     * slice, then [size] frames growing to [max]), cutting only before
     * [tailStart]; the last slice runs to the end.
     * Each renders [context] extra frames per side, clamped to the render.
     */
    fun slices(
        totalFrames: Int,
        tailStart: Int,
        first: Int = KittenStreamSlicing.FIRST_SLICE_FRAMES,
        size: Int = KittenStreamSlicing.SLICE_FRAMES,
        context: Int = KittenStreamSlicing.CONTEXT_FRAMES,
        max: Int = size,
    ): List<KittenStreamSlicing.Slice> {
        if (totalFrames <= 0) return emptyList()
        val cutEnd = tailStart.coerceIn(1, totalFrames)
        val body = KittenStreamSlicing.slices(0 until cutEnd, totalFrames, first, size, context, max)
        val last = body.last()
        return body.dropLast(1) + KittenStreamSlicing.Slice(
            keep = last.keep.first until totalFrames,
            render = last.render.first until totalFrames,
        )
    }
}

/**
 * Per-chunk statistics for the generator's 48 norm layers (issue #5).
 *
 * Kokoro's generator statistics move with the chunk far more than Kitten's:
 * one fixed per-voice average is off by ~2.9 dB on held-out sentences, mostly
 * as a level change (short chunks come out quieter, long ones louder). Most
 * of that is explained by two things the planner already knows before any
 * audio is made: the share of frames spent in pauses (pad, punctuation and
 * space tokens) and the chunk's length. So each voice ships a small linear
 * model instead of a fixed table (desktop fit, `statsmodel.py`):
 *
 *   mean, var = c0 + c1·p + c2·ln(T)   per channel, p = pause fraction, T = frames
 *
 * and the norm becomes y = x·a + b with a = scale / √(var + eps), b = bias − mean·a.
 */
internal object KokoroGenStats {

    /** Rows of a voice's model table, each [S] floats: mean c0..c2, var c0..c2, scale, bias, eps. */
    const val MODEL_ROWS = 9

    /** Frames spent in pad / punctuation / space tokens (ids ≤ [SPACE_TOKEN]). */
    fun pauseFrames(ids: IntArray, durFrames: LongArray): Long {
        var n = 0L
        for (i in ids.indices) if (ids[i] <= SPACE_TOKEN) n += durFrames[i]
        return n
    }

    /** The generator's `gn` input, float32 [2, S] (row 0 = a, row 1 = b), for one chunk. */
    fun normTable(model: FloatArray, pauseFraction: Double, frames: Int): FloatArray {
        require(model.size % MODEL_ROWS == 0) { "model table is ${model.size} floats, not $MODEL_ROWS rows" }
        val s = model.size / MODEL_ROWS
        val lnT = Math.log(frames.toDouble())
        val out = FloatArray(2 * s)
        for (c in 0 until s) {
            val mean = model[c] + model[s + c] * pauseFraction + model[2 * s + c] * lnT
            val v = maxOf(model[3 * s + c] + model[4 * s + c] * pauseFraction + model[5 * s + c] * lnT, 1e-6)
            val a = model[6 * s + c] / Math.sqrt(v + model[8 * s + c])
            out[c] = a.toFloat()
            out[s + c] = (model[7 * s + c] - mean * a).toFloat()
        }
        return out
    }
}
