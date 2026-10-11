package app.marmalade.tts.engine

import android.util.Log
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.perf.RenderPace
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// -----------------------------------------------------------------------------
// Whole pieces when the engine can't keep up (#54).
//
// The streaming engines (KokoroDirect's streaming path, KittenDirect, Pocket)
// send a piece (a sentence, or a clause piece of one) slice by slice as it
// renders. When the render is slower than playback at the chosen speed, a
// slice comes late and playback stops INSIDE the sentence: a stutter. Max,
// 2026-10-10: "once it gets too fast to keep up the method needs to switch
// to chunking". So, per request:
//
//  - [RenderMode.decide]: stream (today's behaviour) while the render keeps
//    up comfortably, else chunk mode. The RTF is the engine's own recent
//    measurement in this process, else the app's measured or predicted one
//    ([app.marmalade.tts.perf.RenderPace]), times the playback rate.
//  - In chunk mode a [PieceGate] holds each piece's slices until the rest of
//    that piece can't run dry ([holdMs]), or the piece is fully rendered.
//    Any wait then falls BEFORE a sentence or clause, never inside one.
//    Kokoro and Kitten also cut smaller slices then
//    ([app.marmalade.tts.engine.kitten.KittenStreamSlicing.CHUNK_MODE_MAX_SLICE_FRAMES]).
// -----------------------------------------------------------------------------

/** Logcat tag of the per-request mode line (Info, so release builds keep it: the release gate reads it). */
const val RENDER_MODE_TAG = "RenderMode"

/** How one request is sent: slices as they render, or whole pieces. */
data class RenderMode(
    val chunk: Boolean,
    /** Render ms per audio ms at 1.0× the decision used, or null when nothing was known. */
    val rtf: Double?,
    /** Where [rtf] came from: `engine` (this process), `measured`, `predicted`, `none` or `forced`. */
    val source: String,
    val playbackRate: Float,
) {
    /**
     * One Info line per request:
     * `<engine> mode=chunk rtf=0.43 (predicted) speed=2.00 played=0.86 path=sliced`. [path] `whole`:
     * the engine renders every piece whole before sending it (Kokoro without
     * the streaming layout), so it plays whole pieces in either mode.
     */
    fun log(engineName: String, path: String = "sliced") {
        Log.i(RENDER_MODE_TAG, line(engineName, path))
    }

    fun line(engineName: String, path: String = "sliced"): String {
        // Locale.ROOT: the release gate parses it, whatever the phone's language.
        fun f(x: Double) = String.format(Locale.ROOT, "%.2f", x)
        val played = rtf?.let { f(it * playbackRate) } ?: "-"
        return "$engineName mode=${if (chunk) "chunk" else "stream"} " +
            "rtf=${rtf?.let { f(it) } ?: "-"} ($source) " +
            "speed=${f(playbackRate.toDouble())} played=$played path=$path"
    }

    companion object {
        /**
         * Chunk mode from this played RTF (render ms per PLAYED ms) up. Below
         * it the streamed render keeps up with room to spare: on the Pixel 6a
         * Kokoro v26 (RTF ~0.43) streamed without a stall at 1.25× (0.54)
         * and stalled 60–120 ms at 1.5× (0.65) through the release gate
         * (#51). Chunk mode costs little where streaming would have kept up:
         * [keepsUp] then releases a piece after its first slice or two.
         */
        const val CHUNK_FROM_PLAYED_RTF = 0.6

        /**
         * The mode for a request played at [playbackRate], from [rtf] (render
         * ms per audio ms at 1.0×, or null when nothing is known: stream, as
         * before #54). [force] (a debug knob) overrides the decision.
         */
        fun decide(rtf: Double?, source: String, playbackRate: Float, force: Boolean? = null): RenderMode {
            val rate = if (playbackRate > 0f) playbackRate else 1f
            if (force != null) return RenderMode(force, rtf, "forced", rate)
            val chunk = rtf != null && rtf > 0.0 && rtf * rate > CHUNK_FROM_PLAYED_RTF
            return RenderMode(chunk, rtf, if (rtf == null) "none" else source, rate)
        }

        /**
         * The mode for one request of [engineName]: from [ownRtf], the
         * engine's own measurement in this process (NaN: none yet), else
         * [RenderPace]'s. Debug builds read the `chunkMode` knob.
         */
        suspend fun forRequest(
            engineName: String,
            settings: SettingsRepository,
            filesDir: File,
            ownRtf: Double,
            playbackRate: Float,
        ): RenderMode {
            val force = forced(EngineDevKnobs.get(filesDir, "chunkMode"))
            if (ownRtf > 0.0) return decide(ownRtf, "engine", playbackRate, force)
            val estimate = RenderPace.estimate(settings, engineName)
            return decide(estimate?.rtf, estimate?.source ?: "none", playbackRate, force)
        }

        /** Debug knob `chunkMode=on|off` ([EngineDevKnobs]); null = decide. */
        fun forced(knob: String?): Boolean? = when (knob?.trim()?.lowercase()) {
            "on", "true", "chunk" -> true
            "off", "false", "stream" -> false
            else -> null
        }
    }
}

/** One slice of a piece still to render: its render time and its PLAYED length, both ms. */
data class SliceEstimate(val renderMs: Double, val playedMs: Double)

/**
 * How long from now to hold a piece so it plays to its end without running
 * dry (0: release now). The listener has [aheadMs] of earlier audio still to
 * hear, then the piece's [bankedMs] already rendered, then [rest] (in order)
 * as each finishes rendering. Slice j arrives once it and every slice before
 * it rendered (each charged [margin] × its estimate) and is needed once
 * everything before it has played. Held audio queues behind what is ahead,
 * so the piece starts once both the hold and [aheadMs] are over.
 *
 * Exact for the engines' slice grids (they know every later slice's frames
 * once a piece is planned); render time is wall time, played lengths are on
 * the played clock (audio ÷ playback rate).
 */
fun holdMs(aheadMs: Double, bankedMs: Double, rest: List<SliceEstimate>, margin: Double = PieceGate.RENDER_MARGIN): Double {
    var arrives = 0.0
    var before = bankedMs
    var start = 0.0 // when the piece must start, from now
    for (s in rest) {
        arrives += s.renderMs * margin
        start = maxOf(start, arrives - before)
        before += s.playedMs
    }
    return if (start <= aheadMs) 0.0 else start
}

/** Whether a piece released now plays to its end without running dry ([holdMs] is 0). */
fun keepsUp(aheadMs: Double, bankedMs: Double, rest: List<SliceEstimate>, margin: Double = PieceGate.RENDER_MARGIN): Boolean =
    holdMs(aheadMs, bankedMs, rest, margin) <= 0.0

/**
 * Chunk mode's hold, per piece (see the file comment). A streaming engine
 * calls [startPiece] before a piece's first slice, [offer] with each rendered
 * slice, and [endPiece] once the piece is fully rendered; it sends whatever
 * comes back, in order. Out of chunk mode every slice comes straight back.
 *
 * Holding stops for the rest of the piece once [keepsUp] says the slices
 * still to render will each arrive before playback needs them: at the next
 * slice, or earlier at [dueAtMs] if the engine calls [releaseIfDue] then
 * ([TimedPieceGate] does). A piece that never gets there plays once fully
 * rendered, so playback can only wait at a piece's start. [onRelease] hears
 * about each piece that was held: how long after its first slice it went
 * out, how many slices went at once, and how many were still to render.
 * Not thread-safe.
 */
class PieceGate<T>(
    private val chunk: Boolean,
    private val margin: Double = RENDER_MARGIN,
    private val onRelease: (heldMs: Long, slices: Int, rest: Int) -> Unit = { _, _, _ -> },
) {
    private val held = ArrayList<T>()
    private var open = true
    private var heldSince = 0L
    private var restAtDue = 0

    /** Played ms of the current piece held so far. */
    var heldPlayedMs = 0.0
        private set

    /** While holding: when releasing becomes safe, as of the last [offer]. Null otherwise. */
    var dueAtMs: Long? = null
        private set

    fun startPiece() {
        // A piece the engine abandoned (empty render) leaves nothing to send.
        held.clear()
        heldPlayedMs = 0.0
        dueAtMs = null
        open = !chunk
    }

    /**
     * One rendered slice of [playedMs] at [nowMs]. [aheadMs]: earlier audio
     * still to be heard before this piece starts; [rest]: the piece's slices
     * still to render. Returns the slices to send now.
     */
    fun offer(slice: T, playedMs: Double, nowMs: Long, aheadMs: Double, rest: () -> List<SliceEstimate>): List<T> {
        if (open) return listOf(slice)
        if (held.isEmpty()) heldSince = nowMs
        held += slice
        heldPlayedMs += playedMs
        val restNow = rest()
        val wait = holdMs(aheadMs, heldPlayedMs, restNow, margin)
        if (wait > 0.0) {
            dueAtMs = nowMs + Math.ceil(wait).toLong()
            restAtDue = restNow.size
            return emptyList()
        }
        return release(nowMs, restNow.size)
    }

    /** The held slices, once [dueAtMs] has come (empty before, or when nothing is held). */
    fun releaseIfDue(nowMs: Long): List<T> {
        val due = dueAtMs ?: return emptyList()
        if (open || held.isEmpty() || nowMs < due) return emptyList()
        return release(nowMs, restAtDue)
    }

    /** The piece is fully rendered: whatever is still held goes. */
    fun endPiece(nowMs: Long): List<T> = if (held.isEmpty()) emptyList() else release(nowMs, 0)

    private fun release(nowMs: Long, rest: Int): List<T> {
        open = true
        dueAtMs = null
        // One slice let go at once was never held.
        if (held.size > 1 || rest == 0 || nowMs > heldSince) onRelease(nowMs - heldSince, held.size, rest)
        val out = ArrayList(held)
        held.clear()
        heldPlayedMs = 0.0
        return out
    }

    companion object {
        /**
         * Each later slice is assumed to take this many times its estimated
         * render. Small on purpose: near real time (Kokoro at 2× on a 6a
         * renders at ~0.85 of playback) a large margin reads as "behind" and
         * holds nearly the whole sentence (1.3 made a 2.3 s wait where
         * streaming had stuttered ~1.1 s in all, #54).
         */
        const val RENDER_MARGIN = 1.1
    }
}

/**
 * A [PieceGate] whose hold ends at [PieceGate.dueAtMs], not only when the
 * next slice is rendered: a timer on [scope] releases it, so a sentence waits
 * no longer than it must even while the engine is busy with a long slice.
 * Every call (and [send]) runs under one lock; an engine that reads state
 * [send] changes does so inside [offer]'s lambdas or between pieces.
 */
class TimedPieceGate<T>(
    chunk: Boolean,
    private val scope: CoroutineScope,
    private val send: suspend (List<T>) -> Unit,
    onRelease: (heldMs: Long, slices: Int, rest: Int) -> Unit = { _, _, _ -> },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val gate = PieceGate<T>(chunk, onRelease = onRelease)
    private val lock = Mutex()
    private var timer: Job? = null

    suspend fun startPiece() = lock.withLock {
        cancelTimer()
        gate.startPiece()
    }

    suspend fun offer(slice: T, playedMs: Double, aheadMs: () -> Double, rest: () -> List<SliceEstimate>) = lock.withLock {
        cancelTimer()
        send(gate.offer(slice, playedMs, nowMs(), aheadMs(), rest))
        val due = gate.dueAtMs ?: return@withLock
        timer = scope.launch {
            delay((due - nowMs()).coerceAtLeast(0))
            lock.withLock { send(gate.releaseIfDue(nowMs())) }
        }
    }

    suspend fun endPiece() = lock.withLock {
        cancelTimer()
        send(gate.endPiece(nowMs()))
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }
}
