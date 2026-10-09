package app.marmalade.tts.engine.pocket

/**
 * Slice sizes and the first-sound hold for Pocket's streamed render (issue
 * #15 "E"): latent frames are decoded and sent in slices while the AR loop
 * is still running, instead of decoding a whole sentence after its last frame.
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

    /**
     * How long to hold the first slice (ms, after it is ready) so playback
     * never waits for a later one. Doubling slices are each decoded only
     * after their last frame exists, so even a render faster than real time
     * runs dry between slices unless the start is held a little (#15: at a
     * real-time factor of 0.64 on the 6a, 12 of 14 sentences stalled).
     *
     * Simulates the request: [chunkFrames] (estimated frames per chunk), the
     * AR loop at [arMsPerFrame] with [prefillMs] before each later chunk, the
     * decoder at [decodeMs] per slice, either on its own thread ([overlap]:
     * a slice decodes once its frames exist and the previous slice is done)
     * or inline on the AR thread; each slice plays for [playedFrameMs] per
     * frame. Slices are cut as the engine cuts them: a slice goes once one
     * MORE frame than its size is pending, and a chunk's last slice takes
     * what is left. The answer is the largest gap between a slice's ready
     * time and when playback started at the first slice would need it.
     */
    fun holdMs(
        chunkFrames: List<Int>,
        maxBatch: Int,
        arMsPerFrame: Double,
        prefillMs: Double,
        decodeMs: (Int) -> Double,
        overlap: Boolean,
        playedFrameMs: Double,
    ): Double {
        var t = 0.0 // the AR thread's clock
        var decoderFree = 0.0
        var firstReady = Double.NaN
        var playedBefore = 0.0
        var hold = 0.0
        fun submit(n: Int) {
            val ready = if (overlap) maxOf(t, decoderFree) + decodeMs(n) else t + decodeMs(n)
            if (overlap) decoderFree = ready else t = ready // inline decoding blocks the AR loop
            if (firstReady.isNaN()) firstReady = ready
            hold = maxOf(hold, ready - firstReady - playedBefore)
            playedBefore += n * playedFrameMs
        }
        for ((c, frames) in chunkFrames.withIndex()) {
            if (frames <= 0) continue
            if (c > 0) t += prefillMs
            var pending = 0
            var slice = 0
            repeat(frames) {
                t += arMsPerFrame
                pending++
                val n = sliceFrames(slice, maxBatch)
                if (pending > n) {
                    submit(n)
                    pending -= n
                    slice++
                }
            }
            submit(pending)
        }
        return hold
    }
}

/**
 * The decoder's cost per run as `fixed + perFrame × frames`, fitted to the
 * last few slice decodes (on the 6a a 6-frame slice cost ~210 ms and a
 * 46-frame one ~1100 ms: a fixed cost per run dominates small slices).
 * With one slice size seen so far it charges the observed ms per frame to
 * every frame, which over-estimates bigger slices: a longer hold, never a
 * shorter one.
 */
class PocketDecodeCost(private val capacity: Int = 8) {
    private val samples = ArrayDeque<Pair<Int, Double>>()

    fun add(frames: Int, ms: Double) {
        if (frames <= 0) return
        samples.addLast(frames to ms)
        while (samples.size > capacity) samples.removeFirst()
    }

    val isEmpty: Boolean get() = samples.isEmpty()

    fun ms(frames: Int): Double {
        if (samples.isEmpty()) return 0.0
        val n = samples.size.toDouble()
        val mx = samples.sumOf { it.first.toDouble() } / n
        val my = samples.sumOf { it.second } / n
        val sxx = samples.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx < 1e-9) return frames * my / mx
        val slope = samples.sumOf { (it.first - mx) * (it.second - my) } / sxx
        val fixed = my - slope * mx
        // A noisy fit can tilt; a cost is never negative.
        return maxOf(fixed + slope * frames, 0.0)
    }
}

/**
 * Holds a request's first slices until a release time, then sends
 * everything (the streamed equivalent of the old K-chunk pre-roll).
 * [releaseAtMs] is set once, when the first slice is ready
 * ([PocketStreamPlan.holdMs]); until then slices are held.
 */
class PocketSliceBank<T> {
    private val held = ArrayList<T>()
    private var released = false

    var releaseAtMs: Long? = null

    /** Offer one slice at [nowMs]; returns the slices to send now (in order). */
    fun offer(slice: T, nowMs: Long): List<T> {
        if (released) return listOf(slice)
        held += slice
        val at = releaseAtMs ?: return emptyList()
        return if (nowMs >= at) flush() else emptyList()
    }

    /** The hold is over (its timer fired, or the request rendered): everything held goes out. */
    fun flush(): List<T> {
        released = true
        return ArrayList(held).also { held.clear() }
    }
}
