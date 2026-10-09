package app.marmalade.tts.engine.pocket

/**
 * Where one chunk's render time goes, graph by graph (issue #15 profile).
 * The engine adds nanoTime spans as it runs and logs [arLine] / [decodeLine]
 * once per chunk under `StreamPerf`, so a phone run says from logcat alone
 * whether the AR loop's time is the transformer, its state plumbing, the
 * flow head, or the decoder.
 *
 * Not thread-safe: one instance per AR loop, a separate one per decode
 * (the pipelined decode runs on another thread).
 */
class PocketTimes {
    /** `flow_lm_main` session.run, AR steps only. */
    var mainNs = 0L
    /** Carrying flow_lm state from one step's outputs to the next step's inputs. */
    var mainStateNs = 0L
    /** `flow_lm_flow` (all Euler steps of a frame). */
    var flowNs = 0L
    var frames = 0

    /** `mimi_decoder` runs (one per slice) and their count. */
    var decodeRunNs = 0L
    var decodeRuns = 0
    /** Binding the decoder's inputs and carrying its state. */
    var decodeStateNs = 0L
    var decodedFrames = 0

    fun arLine(chunkIdx: Int, arMs: Long): String {
        val f = frames.coerceAtLeast(1)
        val other = arMs - ms(mainNs + mainStateNs + flowNs)
        return "pocket chunk=$chunkIdx prof frames=$frames ar=${arMs}ms " +
            "main=${ms(mainNs)}ms(${perFrame(mainNs, f)}/f) " +
            "state=${ms(mainStateNs)}ms(${perFrame(mainStateNs, f)}/f) " +
            "flow=${ms(flowNs)}ms(${perFrame(flowNs, f)}/f) " +
            "rest=${other}ms"
    }

    fun decodeLine(chunkIdx: Int, decodeMs: Long): String =
        "pocket chunk=$chunkIdx prof decode=${decodeMs}ms frames=$decodedFrames " +
            "runs=${ms(decodeRunNs)}ms/$decodeRuns " +
            "state=${ms(decodeStateNs)}ms"

    private fun ms(ns: Long): Long = ns / 1_000_000

    private fun perFrame(ns: Long, frames: Int): String =
        String.format(java.util.Locale.ROOT, "%.1f", ns / 1e6 / frames)
}
