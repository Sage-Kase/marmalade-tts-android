package app.marmalade.tts.engine

import android.util.Log
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.perf.RenderPace
import java.io.File
import java.util.Locale

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
//    that piece can't run dry ([keepsUp]), or the piece is fully rendered.
//    Any wait then falls BEFORE a sentence or clause, never inside one.
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
 * Whether a piece released now plays to its end without running dry: the
 * listener has [aheadMs] of earlier audio still to hear, then the piece's
 * [bankedMs] already rendered, then [rest] (in order) as each finishes
 * rendering. Slice j arrives once it and every slice before it rendered (each
 * charged [margin] × its estimate) and is needed once everything before it
 * has played; it must arrive in time, every one.
 *
 * Exact for the engines' slice grids (they know every later slice's frames
 * once a piece is planned); render time is wall time, played lengths are on
 * the played clock (audio ÷ playback rate).
 */
fun keepsUp(aheadMs: Double, bankedMs: Double, rest: List<SliceEstimate>, margin: Double = PieceGate.RENDER_MARGIN): Boolean {
    var arrives = 0.0
    var needed = aheadMs + bankedMs
    for (s in rest) {
        arrives += s.renderMs * margin
        if (arrives > needed) return false
        needed += s.playedMs
    }
    return true
}

/**
 * Chunk mode's hold, per piece (see the file comment). A streaming engine
 * calls [startPiece] before a piece's first slice, [offer] with each rendered
 * slice, and [endPiece] once the piece is fully rendered; it sends whatever
 * comes back, in order. Out of chunk mode every slice comes straight back.
 *
 * Holding stops for the rest of the piece once [keepsUp] says the slices
 * still to render will each arrive before playback needs them. A piece that
 * never gets there plays once fully rendered, so playback can only wait at a
 * piece's start. [onRelease] hears about each piece that was held: how long
 * after its first slice it went out, how many slices went at once, and how
 * many were still to render.
 */
class PieceGate<T>(
    private val chunk: Boolean,
    private val margin: Double = RENDER_MARGIN,
    private val onRelease: (heldMs: Long, slices: Int, rest: Int) -> Unit = { _, _, _ -> },
) {
    private val held = ArrayList<T>()
    private var open = true
    private var heldSince = 0L

    /** Played ms of the current piece held so far. */
    var heldPlayedMs = 0.0
        private set

    fun startPiece() {
        // A piece the engine abandoned (empty render) leaves nothing to send.
        held.clear()
        heldPlayedMs = 0.0
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
        if (!keepsUp(aheadMs, heldPlayedMs, restNow, margin)) return emptyList()
        return release(nowMs, restNow.size)
    }

    /** The piece is fully rendered: whatever is still held goes. */
    fun endPiece(nowMs: Long): List<T> = if (held.isEmpty()) emptyList() else release(nowMs, 0)

    private fun release(nowMs: Long, rest: Int): List<T> {
        open = true
        // One slice let go at once was never held.
        if (held.size > 1 || rest == 0) onRelease(nowMs - heldSince, held.size, rest)
        val out = ArrayList(held)
        held.clear()
        heldPlayedMs = 0.0
        return out
    }

    companion object {
        /**
         * Each later slice is assumed to take this many times its estimated
         * render: the 6a now and then renders a piece 1.4–2× slower than
         * usual (#25), and a late slice is a stutter while an early release
         * costs nothing.
         */
        const val RENDER_MARGIN = 1.3
    }
}
