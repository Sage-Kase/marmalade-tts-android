package app.marmalade.tts.engine

import kotlin.math.floor
import kotlin.math.max

/**
 * Chunk-grain adaptive pre-roll for the sentence-streaming engines —
 * PocketEngine's frame-grain K mechanism generalised to engines whose
 * only timing signal is "chunk N took renderMs and produced audioMs".
 *
 * The producer feeds every rendered chunk through [onChunkRendered] and
 * emits whatever comes back; anything still held when the input runs
 * out (empty trailing chunks, fewer chunks than expected) is returned
 * by [drain].
 *
 * K — how many chunks to hold before the first emit — is decided once,
 * from chunk 0, against the *played* clock: a downstream time-stretch
 * (see [app.marmalade.tts.service.SpeedPlan.playbackRate]) makes the
 * audio drain `playbackRate`× faster than the engine's own clock, so
 *
 *   playedMs        = audioMs / playbackRate
 *   deficitFraction = max(0, renderMs / playedMs − REALTIME_MARGIN)
 *   shortfall       = (totalChunks − 1) × deficitFraction
 *   K               = 1 + round(shortfall)        (a half rounds up)
 *
 * capped at [maxPreroll]. deficitFraction is the extra render time each
 * played chunk-length costs; over the remaining totalChunks − 1 chunks
 * that adds up to `shortfall` chunk-lengths, which audio banked before
 * playback starts must cover, and each held chunk banks ≈ one
 * chunk-length. At or above realtime (with [REALTIME_MARGIN] headroom
 * to spare) K stays 1 and behaviour is exactly what it was before this
 * class existed.
 *
 * Why round and not ceil: holding a chunk is a *certain* up-front cost
 * — one whole chunk's render time, which is ≥ 0.9 of a chunk-length
 * whenever there is any deficit at all — while the stall it prevents is
 * only the uncovered part of the shortfall. Rounding holds an extra
 * chunk once that remainder reaches half a chunk-length, i.e. it weighs
 * a mid-playback stall at about twice an up-front wait (a stall sounds
 * broken; a wait after a tap doesn't), and never leaves more than half
 * a chunk-length uncovered. The margin already overstates the true
 * stall by 0.1 chunk-lengths per chunk, so most of a sub-half remainder
 * is headroom, not silence. Ceil held a whole chunk for any deficit: a
 * thermally throttled 8a (2026-09-28) played RTF 0.91 at 1.0× over a
 * 5-chunk paragraph — shortfall 4 × 0.01 = 0.04 chunk-lengths, ~190 ms
 * — and ceil made the tap wait an extra ~5 s chunk for it (K=2). Round
 * gives K=1 there, and at 2.0× with RTF 0.6 over 5 chunks (played 1.2,
 * shortfall 1.2) K=2 where ceil gave 3.
 *
 * There is deliberately no speed threshold: measured on the 8a
 * (2026-09-19), warm Kokoro renders at RTF 0.51–0.56, so at 1.0× the
 * played RTF is ~0.55 → K = 1, at 1.5× ~0.84 → still K = 1, and at
 * 2.0× ~1.02–1.12 (deficit 0.12–0.22) → K = 2 once the text has
 * enough chunks for that to add up to half a chunk (≥ 4–6 chunks),
 * which is the ~1.1 s between-sentence stall this fixes; a 2–3 chunk
 * text plays at once and risks one short stall instead. The gate
 * self-calibrates from what chunk 0 actually cost on this device, this
 * session.
 */
class PrerollGate<T>(
    private val playbackRate: Float,
    private val totalChunks: Int,
    private val maxPreroll: Int = MAX_PREROLL_CHUNKS,
) {
    /** Decided after the first rendered chunk; 1 until then. */
    var prerollChunks: Int = 1
        private set

    private val held = ArrayList<T>()
    private var seen = 0

    /**
     * Account for one rendered chunk and return the chunks (in order)
     * that should be emitted now — empty while the pre-roll is still
     * filling.
     */
    fun onChunkRendered(chunk: T, renderMs: Long, audioMs: Long): List<T> {
        if (seen == 0 && audioMs > 0 && playbackRate > 0f) {
            prerollChunks = prerollChunksFor(playbackRate, totalChunks, renderMs.toDouble() / audioMs, maxPreroll)
        }
        val idx = seen++
        // Mirrors PocketEngine's emitOrBuffer: chunks 0..K−2 are held,
        // chunk K−1 flushes everything — unless it's the last chunk,
        // where holding would just delay the tail for nothing.
        return if (idx < prerollChunks - 1 && idx < totalChunks - 1) {
            held.add(chunk)
            emptyList()
        } else {
            val out = ArrayList<T>(held.size + 1)
            out.addAll(held)
            held.clear()
            out.add(chunk)
            out
        }
    }

    /** Chunks still held when the producer ran out of input. */
    fun drain(): List<T> {
        val out = ArrayList<T>(held)
        held.clear()
        return out
    }

    companion object {
        /**
         * K for a text of [totalChunks] chunks rendered at real-time factor
         * [rtf] (render time ÷ audio time, on the engine's clock) and played
         * at [playbackRate]: the class comment's formula, capped at
         * [maxPreroll]. [onChunkRendered] decides with chunk 0's measured
         * RTF. An engine that streams slices of a chunk sizes its pre-roll
         * in audio instead ([streamBankMs]).
         */
        fun prerollChunksFor(
            playbackRate: Float,
            totalChunks: Int,
            rtf: Double,
            maxPreroll: Int = MAX_PREROLL_CHUNKS,
        ): Int {
            if (playbackRate <= 0f || rtf.isNaN()) return 1
            val deficitFraction = max(0.0, rtf * playbackRate - REALTIME_MARGIN)
            val shortfall = (totalChunks - 1) * deficitFraction
            return (1 + floor(shortfall + 0.5).toInt()).coerceIn(1, maxPreroll)
        }

        /**
         * Pre-roll for an engine that streams slices (KokoroDirect, issue
         * #13): how many ms of PLAYED audio to bank before the first send,
         * for a request of about [audioMs] of audio (engine clock) rendered
         * at [rtf] and played at [playbackRate]. Holding is sized in audio,
         * not whole chunks, so a long first sentence never has to render
         * whole before the first sound.
         *
         * With played RTF `r = rtf × playbackRate`, banking B played ms
         * takes `B × r` of render time, and the rest of the text plays
         * stall-free when that covers its deficit:
         * `B × r ≥ (audioMs / playbackRate) × (r − REALTIME_MARGIN)`. The
         * hold's render time is capped at [capRenderMs]: past that, brief
         * pauses later beat a long silence after a tap (Max, 2026-09-28,
         * see [MAX_PREROLL_CHUNKS]). 0 at or under the margin, for an
         * unknown RTF and without a playback rate.
         */
        fun streamBankMs(
            playbackRate: Float,
            rtf: Double,
            audioMs: Long,
            capRenderMs: Long = STREAM_BANK_CAP_RENDER_MS,
        ): Long {
            if (playbackRate <= 0f || !(rtf > 0.0) || audioMs <= 0) return 0
            val played = rtf * playbackRate
            if (played <= REALTIME_MARGIN) return 0
            val holdRenderMs = minOf(audioMs / playbackRate * (played - REALTIME_MARGIN), capRenderMs.toDouble())
            return (holdRenderMs / played).toLong()
        }

        /**
         * Most render time [streamBankMs] holds the first sound for: about
         * what the whole path's K = 2 cost with a short first piece.
         */
        const val STREAM_BANK_CAP_RENDER_MS = 2_000L

        /**
         * Required headroom before K stays at 1: a played RTF of 0.95
         * has technically no deficit but also no slack for a slow
         * sentence, so anything above 0.9 banks chunks.
         */
        const val REALTIME_MARGIN = 0.9

        /**
         * TTFA bound: K chunks must render before the first emit, and a
         * chunk is a sentence or more of render time (~1–2 s warm on the
         * 8a, ~4–5 s throttled). 2 means playback starts within about one
         * extra sentence at most (Max, 2026-09-28, down from 3): at a
         * speed the device can't sustain, brief pauses between sentences
         * beat a long silence after a tap. Pocket's own ceiling is 2 too.
         */
        const val MAX_PREROLL_CHUNKS = 2
    }
}
