package app.marmalade.tts.engine.kitten

/**
 * Frame arithmetic for [KittenDirectEngine]'s streaming render (issues #3, #9,
 * #10), kept free of Android and ORT so the JVM tests pin it; Kokoro's
 * streaming render reuses it (KokoroStreamSlicing). One frame =
 * [KittenTrim.FRAME_SAMPLES] samples (25 ms at 24 kHz); the generator's
 * inputs run at 2 values per frame (`x`) and 600 per frame (`har`).
 */
internal object KittenStreamSlicing {

    /**
     * Frames of context rendered and discarded on each side of a slice. With
     * the generator's normalisation statistics fixed, 4 frames (100 ms) makes
     * a slice's kept samples equal the unsplit render to float rounding
     * (desktop sweep, issue #3); 2 frames leaves audible joins.
     */
    const val CONTEXT_FRAMES = 4

    /** First slice: 0.5 s, small so the first audio is out quickly. */
    const val FIRST_SLICE_FRAMES = 20

    /** Second slice: 1 s. Larger slices amortise the context overhead. */
    const val SLICE_FRAMES = 40

    /**
     * Cap for the in-app schedule, where slices double after the second
     * (1 s, 2 s, 4 s, 4 s, …). On the phone every generator call costs
     * ~120 ms (Kitten) to ~150–290 ms (Kokoro) before its first frame
     * (issue #5), so fixed 1 s slices spent up to ~60% more CPU than the
     * unsplit model; doubling keeps the call count logarithmic while each
     * slice is still rendered long before the audio ahead of it runs out.
     */
    const val MAX_SLICE_FRAMES = 160

    /**
     * Cap in chunk mode (#54, [app.marmalade.tts.engine.RenderMode]): 2 s. A
     * piece is held until its last slice will arrive in time, and a 4 s last
     * slice needs most of the sentence banked first: Kokoro's 150-token
     * sentence at 2× on a 6a (render ~0.85 of playback) can start ~0.6 s
     * sooner with 2 s slices, for one more generator call.
     */
    const val CHUNK_MODE_MAX_SLICE_FRAMES = 80

    /**
     * The speech-bearing frames `[start, end)` of a render, with the same
     * lead/tail rule as [KittenTrim.trim] (keep 2 lead frames and 3 frames of
     * the trailing pause group), so streaming never renders the trimmed pad.
     * Null when [ids] and [durFrames] disagree.
     */
    fun speechFrames(ids: IntArray, durFrames: LongArray): IntRange? {
        if (ids.size != durFrames.size || ids.isEmpty()) return null
        val total = durFrames.sum().toInt()
        val start = (durFrames[0] - KittenTrim.HEAD_KEEP_FRAMES).coerceAtLeast(0L).toInt()
        var tail = 0L
        for (i in ids.indices.reversed()) {
            if (ids[i] <= SPACE_TOKEN) tail += durFrames[i] else break
        }
        val end = total - (tail - KittenTrim.TAIL_KEEP_FRAMES).coerceAtLeast(0L).toInt()
        return if (start < end) start until end else null
    }

    /** One slice: the frames it keeps ([keep]) and the wider window it renders ([render]). */
    data class Slice(val keep: IntRange, val render: IntRange) {
        /** Offset in samples of the kept audio inside the rendered window's waveform. */
        val keepOffsetSamples: Int get() = (keep.first - render.first) * KittenTrim.FRAME_SAMPLES
        val keepSamples: Int get() = (keep.last - keep.first + 1) * KittenTrim.FRAME_SAMPLES
    }

    /**
     * Cut [speech] into a [first]-frame slice, then a [size]-frame slice, each
     * later one twice the one before up to [max] frames (`max = size`, the
     * default, keeps every later slice at [size]). Each is rendered with
     * [context] extra frames per side, clamped to `[0, totalFrames)`.
     */
    fun slices(
        speech: IntRange,
        totalFrames: Int,
        first: Int = FIRST_SLICE_FRAMES,
        size: Int = SLICE_FRAMES,
        context: Int = CONTEXT_FRAMES,
        max: Int = size,
    ): List<Slice> {
        val out = ArrayList<Slice>()
        var a = speech.first
        val cap = maxOf(max, size)
        var later = size
        while (a <= speech.last) {
            val len = if (out.isEmpty()) first else later.also { later = minOf(later * 2, cap) }
            val b = minOf(speech.last + 1, a + len)
            out += Slice(
                keep = a until b,
                render = maxOf(0, a - context) until minOf(totalFrames, b + context),
            )
            a = b
        }
        return out
    }
}
