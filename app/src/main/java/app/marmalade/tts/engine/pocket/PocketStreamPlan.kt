package app.marmalade.tts.engine.pocket

/**
 * Slice sizes and the pre-roll bank for Pocket's streamed render (issue #15
 * "E"): latent frames are decoded and sent in slices while the AR loop is
 * still running, instead of decoding a whole sentence after its last frame.
 */
object PocketStreamPlan {

    /**
     * Frames in slice [index] of a chunk: 6 (0.48 s) so the first audio is
     * out quickly, then 12, 24, then [maxBatch] (the decoder's exact-batch
     * limit, 46 = 3.7 s) so long sentences cost few decoder runs.
     */
    fun sliceFrames(index: Int, maxBatch: Int): Int =
        (FIRST_SLICE_FRAMES shl index.coerceAtMost(8)).coerceAtMost(maxBatch).coerceAtLeast(1)

    const val FIRST_SLICE_FRAMES = 6
}

/**
 * Holds a request's first slices until enough audio is banked that a slower-
 * than-real-time render won't run dry (the streamed equivalent of the old
 * K-chunk pre-roll). [targetPlayedMs] is set once, when the first slice is
 * ready and the render speed is known (PrerollGate.streamBankMs); 0 sends
 * everything at once.
 */
class PocketSliceBank<T>(private val playbackRate: Float) {
    private val held = ArrayList<T>()
    private var heldAudioMs = 0L
    private var released = false

    var targetPlayedMs: Long? = null

    /** Offer one slice; returns the slices to send now (in order). */
    fun offer(slice: T, audioMs: Long): List<T> {
        if (released) return listOf(slice)
        held += slice
        heldAudioMs += audioMs
        val target = targetPlayedMs ?: return emptyList()
        if (heldAudioMs / playbackRate.coerceAtLeast(0.01f) < target) return emptyList()
        return flush()
    }

    /** End of the request (or of the hold): everything held goes out. */
    fun flush(): List<T> {
        released = true
        return ArrayList(held).also { held.clear() }
    }
}
