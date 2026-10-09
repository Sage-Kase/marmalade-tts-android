package app.marmalade.tts.engine.pocket

/**
 * When to cut a slice in Pocket's streamed render (issue #15 "E"): latent
 * frames are decoded and sent in slices while the AR loop is still running,
 * instead of decoding a whole sentence after its last frame.
 *
 * Fixed doubling slices (6, 12, 24, 46 frames) ran dry between slices even
 * at a real-time factor of 0.64 on the Pixel 6a (13 of 14 sentences
 * stalled), and holding the first sound to cover them needed the
 * sentence's length in advance, which is only an estimate. So a slice is
 * cut by deadline instead: the first after [FIRST_SLICE_FRAMES] frames, and
 * each later one as soon as waiting for one more frame would let playback
 * run out before the slice could be decoded. Slices grow on their own while
 * playback is ahead (up to the decoder's exact limit), and stay small while
 * it is close.
 *
 * The clock keeps the request's playback timeline: when each submitted
 * slice is (or is expected to be) decoded, and how long its audio plays.
 * The engine feeds it actual decode times as they happen ([decoded]); the
 * [PocketStreamPlan.holdMs] simulation runs the same rule on predicted ones.
 * Thread-safe: the AR loop cuts, the decoder thread reports.
 */
class PocketSliceClock(
    private val maxBatch: Int,
    /** Playback time of one frame (80 ms at 1×, shorter when sped up). */
    private val playedFrameMs: Double,
    private val decodeMs: (Int) -> Double,
    /** The decoder runs on its own thread (else inline, blocking the AR loop). */
    private val overlap: Boolean,
    private val marginMs: Double = MARGIN_MS,
) {
    private val ready = ArrayList<Double>()
    private val frames = ArrayList<Int>()
    private var decoderFree = 0.0

    /** Extra wait before the first sound ([PocketStreamPlan.holdMs]). */
    @Volatile var holdMs = 0.0

    /** Should the [pending] frames go to the decoder now (at [nowMs], AR at [arMsPerFrame])? */
    @Synchronized
    fun shouldCut(pending: Int, nowMs: Double, arMsPerFrame: Double): Boolean {
        if (pending >= maxBatch) return true
        if (ready.isEmpty()) return pending >= FIRST_SLICE_FRAMES
        if (pending < MIN_SLICE_FRAMES) return false
        val next = nowMs + arMsPerFrame
        val start = if (overlap) maxOf(next, decoderFree) else next
        return start + decodeMs(pending + 1) + marginMs > audioEndMs()
    }

    /**
     * [n] frames went to the decoder at [nowMs]. Returns the slice's index
     * (for [decoded]) and its expected ready time.
     */
    @Synchronized
    fun submitted(n: Int, nowMs: Double): Pair<Int, Double> {
        val r = (if (overlap) maxOf(nowMs, decoderFree) else nowMs) + decodeMs(n)
        if (overlap) decoderFree = r
        ready += r
        frames += n
        return ready.size - 1 to r
    }

    /** Slice [index] really was ready at [nowMs]: later slices queued behind it move with it. */
    @Synchronized
    fun decoded(index: Int, nowMs: Double) {
        val shift = nowMs - ready[index]
        for (i in index until ready.size) ready[i] += shift
        if (overlap) decoderFree += shift
    }

    /** Total time playback would wait for slices, given the ready times so far. */
    @Synchronized
    fun stallMs(): Double {
        var stall = 0.0
        var clock = 0.0
        for (i in ready.indices) {
            if (i == 0) {
                clock = ready[0] + holdMs
            } else if (ready[i] > clock) {
                stall += ready[i] - clock
                clock = ready[i]
            }
            clock += frames[i] * playedFrameMs
        }
        return stall
    }

    /** When playback runs out of the audio submitted so far (each slice plays once it is ready). */
    private fun audioEndMs(): Double {
        var end = ready[0] + holdMs + frames[0] * playedFrameMs
        for (i in 1 until ready.size) end = maxOf(end, ready[i]) + frames[i] * playedFrameMs
        return end
    }

    companion object {
        /** The first slice of a request: 6 frames (0.48 s), so the first sound comes quickly. */
        const val FIRST_SLICE_FRAMES = 6

        /**
         * No later slice under 4 frames: each decoder run has a fixed cost
         * (~60 ms on the 6a), so a render that can't keep up would
         * otherwise cut a slice every frame and fall further behind.
         */
        const val MIN_SLICE_FRAMES = 4

        /** Safety margin for AR and decode jitter. */
        const val MARGIN_MS = 100.0
    }
}

object PocketStreamPlan {

    /**
     * How long to hold the first slice (ms, after it is ready) so playback
     * never waits for a later one. 0 when the render keeps up, which on the
     * 6a at 1× is always: the deadline rule ([PocketSliceClock]) then needs
     * no hold. A render slower than playback (a slow phone, or 2× speed)
     * needs one.
     *
     * Simulates the request with the engine's own rule: [chunkFrames]
     * (estimated frames per chunk), the AR loop at [arMsPerFrame] with
     * [prefillMs] before each later chunk, the decoder at [decodeMs] per
     * slice. Each pass adds the stall it still predicts to the hold.
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
        var hold = 0.0
        repeat(HOLD_PASSES) {
            val stall = stallMs(chunkFrames, maxBatch, arMsPerFrame, prefillMs, decodeMs, overlap, playedFrameMs, hold)
            if (stall <= 0.0) return hold
            hold += stall
        }
        return hold
    }

    /** The playback stall [holdMs]' simulation predicts with a first-sound hold of [hold]. */
    internal fun stallMs(
        chunkFrames: List<Int>,
        maxBatch: Int,
        arMsPerFrame: Double,
        prefillMs: Double,
        decodeMs: (Int) -> Double,
        overlap: Boolean,
        playedFrameMs: Double,
        hold: Double,
    ): Double {
        val clock = PocketSliceClock(maxBatch, playedFrameMs, decodeMs, overlap)
        clock.holdMs = hold
        var t = 0.0 // the AR thread's clock
        for ((c, frames) in chunkFrames.withIndex()) {
            if (frames <= 0) continue
            if (c > 0) t += prefillMs
            var pending = 0
            fun submit() {
                val (_, ready) = clock.submitted(pending, t)
                if (!overlap) t = ready // inline decoding blocks the AR loop
                pending = 0
            }
            repeat(frames) {
                t += arMsPerFrame
                pending++
                if (clock.shouldCut(pending, t, arMsPerFrame)) submit()
            }
            if (pending > 0) submit()
        }
        return clock.stallMs()
    }

    private const val HOLD_PASSES = 3
}

/**
 * The decoder's cost per run as `fixed + perFrame × frames`, fitted to the
 * last few slice decodes (on the 6a a 6-frame slice cost ~210 ms and a
 * 46-frame one ~1100 ms: a fixed cost per run dominates small slices).
 * With one slice size seen so far it charges the observed ms per frame to
 * every frame, which over-estimates bigger slices: an earlier cut, never a
 * later one. Thread-safe (the decoder adds, the AR loop reads).
 */
class PocketDecodeCost(private val capacity: Int = 8) {
    private val samples = ArrayDeque<Pair<Int, Double>>()

    @Synchronized
    fun add(frames: Int, ms: Double) {
        if (frames <= 0) return
        samples.addLast(frames to ms)
        while (samples.size > capacity) samples.removeFirst()
    }

    val isEmpty: Boolean @Synchronized get() = samples.isEmpty()

    @Synchronized
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
 * everything: the head start a render slower than playback needs.
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
