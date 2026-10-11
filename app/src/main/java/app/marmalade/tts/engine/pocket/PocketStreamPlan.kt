package app.marmalade.tts.engine.pocket

import app.marmalade.tts.engine.SliceEstimate

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
 * The rule assumes a slice can take [DECODE_SAFETY] times its usual decode
 * ("G"): on the 6a the app only gets the mid and little cores, and now and
 * then the scheduler parks the decoder on a little core for a few seconds,
 * so about one slice in 50 took 2.4–5.5× as long. With the bare estimate
 * playback kept only ~0.1 s of decoded audio ahead, and each such slice was
 * a pause; with the factor it keeps a few seconds ahead. Slices still grow
 * to the limit, because the render is faster than playback.
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
    /** How many times its usual decode a slice may take without a pause. */
    private val safety: Double = DECODE_SAFETY,
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
        return start + safety * decodeMs(pending + 1) + marginMs > audioEndMs()
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

        /** Safety margin for AR jitter. */
        const val MARGIN_MS = 100.0

        /** A slice may take this many times its usual decode without a pause ("G"). */
        const val DECODE_SAFETY = 3.0
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
        safety: Double = PocketSliceClock.DECODE_SAFETY,
    ): Double {
        var hold = 0.0
        repeat(HOLD_PASSES) {
            val stall = stallMs(chunkFrames, maxBatch, arMsPerFrame, prefillMs, decodeMs, overlap, playedFrameMs, hold, safety)
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
        safety: Double = PocketSliceClock.DECODE_SAFETY,
    ): Double {
        val clock = PocketSliceClock(maxBatch, playedFrameMs, decodeMs, overlap, safety)
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

    /**
     * Chunk mode's length guess for a mini-chunk (#54): [tokens] at
     * [msPerToken] of audio each, in frames of [frameMs], times
     * [CHUNK_EST_MARGIN]. Lengths are only known to ±15%, and a guess too
     * long only holds a slow render's chunk a little longer.
     */
    fun chunkEstFrames(tokens: Int, msPerToken: Double, frameMs: Double): Int =
        kotlin.math.ceil(tokens * msPerToken / frameMs * CHUNK_EST_MARGIN).toInt()

    /**
     * Chunk mode (#54): the slices of a mini-chunk still to arrive once
     * [decodedFrames] of it are decoded, for [app.marmalade.tts.engine.keepsUp]. The AR loop has
     * generated [generatedFrames] ([finished]: all of them); the chunk is
     * guessed at [estFrames] ([chunkEstFrames]) and, while the AR loop is
     * still on it, at least [PocketSliceClock.MIN_SLICE_FRAMES] more (a
     * guess the render has already overtaken knows nothing better).
     *
     * The frames left are cut into slices of [sliceFrames]. A slice is
     * submitted once the AR loop (at [arMsPerFrame]) has generated its last
     * frame and is decoded in [decodeMs]: beside the AR loop when [overlap],
     * else blocking it. One decode per piece is assumed slow, [decodeSafety]
     * times a full slice's (the streaming cut rule's
     * [PocketSliceClock.DECODE_SAFETY]: about one slice in 50 is parked on a
     * little core), charged on the first slice so every later one waits for
     * it: after release that decode would otherwise stall inside the piece.
     * Not every slice: 3 × (60 + 24 × 6) ms per 6-frame slice that plays
     * 240 ms at 2× reads as a decoder that never keeps up, and held whole
     * chunks (first sound 1.2 s instead of 0.3 s, #54). The gate's own margin
     * ([app.marmalade.tts.engine.PieceGate.RENDER_MARGIN]) covers the AR
     * loop's jitter. Each estimate's render time is the
     * gap between its arrival and the previous one's (the first counts from
     * now), its played length `frames × [playedFrameMs]`.
     */
    fun chunkRest(
        estFrames: Int,
        generatedFrames: Int,
        decodedFrames: Int,
        finished: Boolean,
        sliceFrames: Int,
        arMsPerFrame: Double,
        decodeMs: (Int) -> Double,
        overlap: Boolean,
        playedFrameMs: Double,
        decodeSafety: Double = PocketSliceClock.DECODE_SAFETY,
    ): List<SliceEstimate> {
        val queued = (generatedFrames - decodedFrames).coerceAtLeast(0)
        val toGenerate = if (finished) 0 else maxOf(estFrames - generatedFrames, PocketSliceClock.MIN_SLICE_FRAMES)
        val size = sliceFrames.coerceAtLeast(1)
        val out = ArrayList<SliceEstimate>()
        var ar = 0.0 // when the AR loop has generated the frames so far
        var decoderFree = 0.0
        var lastArrival = 0.0
        var cut = 0
        while (cut < queued + toGenerate) {
            val n = minOf(size, queued + toGenerate - cut)
            // Frames of this slice the AR loop still has to generate.
            ar += (cut + n - maxOf(cut, queued)).coerceAtLeast(0) * arMsPerFrame
            val slow = if (out.isEmpty()) (decodeSafety - 1.0).coerceAtLeast(0.0) * decodeMs(size) else 0.0
            val arrival = (if (overlap) maxOf(ar, decoderFree) else ar) + decodeMs(n) + slow
            if (overlap) decoderFree = arrival else ar = arrival
            out += SliceEstimate(arrival - lastArrival, n * playedFrameMs)
            lastArrival = arrival
            cut += n
        }
        return out
    }

    private const val HOLD_PASSES = 3

    /** [chunkEstFrames]' margin over the token-rate guess. */
    const val CHUNK_EST_MARGIN = 1.2
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
