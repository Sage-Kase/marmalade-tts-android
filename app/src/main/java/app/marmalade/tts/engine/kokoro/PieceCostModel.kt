package app.marmalade.tts.engine.kokoro

/**
 * A piece's wait before its first sound as a straight line in its token
 * count, fitted to the latest measured short and long pieces (#49). Kokoro's
 * costs carry a large fixed part: on the Pixel 6a the v26 planner takes
 * ~100 ms for 19 English tokens and ~250 ms for 153 (German v28: ~235 ms
 * for 18, ~345 ms for 164), so a cost per token measured on a short piece
 * overstates a long one several times over.
 *
 * Measurements fall in two bins, under and from [SPLIT_TOKENS]; each keeps
 * its latest. With both, the line runs through them (a slope below zero is
 * read as zero); with one, it runs through that one at [defaultMsPerToken];
 * with none, it is [defaultFixedMs] + [defaultMsPerToken] × tokens.
 */
internal class PieceCostModel(
    private val defaultFixedMs: Double,
    private val defaultMsPerToken: Double,
) {
    private var short: Point? = null
    private var long: Point? = null

    private data class Point(val tokens: Int, val ms: Double)

    /** The line's cost per token and its fixed part (≥ 0), for [tokens]-sized pieces. */
    data class Line(val fixedMs: Double, val msPerToken: Double) {
        fun at(tokens: Int): Double = fixedMs + msPerToken * tokens
    }

    @Synchronized
    fun record(tokens: Int, ms: Double) {
        if (tokens <= 0 || ms.isNaN() || ms < 0) return
        if (tokens < SPLIT_TOKENS) short = Point(tokens, ms) else long = Point(tokens, ms)
    }

    /** The line to predict a piece of [tokens] tokens with, anchored at the nearer bin. */
    @Synchronized
    fun lineFor(tokens: Int): Line {
        val s = short
        val l = long
        val slope = if (s != null && l != null) {
            ((l.ms - s.ms) / (l.tokens - s.tokens)).coerceAtLeast(0.0)
        } else {
            defaultMsPerToken
        }
        val anchor = (if (tokens >= SPLIT_TOKENS) l ?: s else s ?: l)
            ?: return Line(defaultFixedMs, defaultMsPerToken)
        return Line((anchor.ms - slope * anchor.tokens).coerceAtLeast(0.0), slope)
    }

    companion object {
        /** Bins split here: a sentence opener vs a full sentence. */
        const val SPLIT_TOKENS = 60
    }
}
