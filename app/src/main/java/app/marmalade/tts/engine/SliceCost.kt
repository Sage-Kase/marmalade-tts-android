package app.marmalade.tts.engine

/**
 * A streaming engine's cost per slice as `fixed + perFrame × frames`, fitted
 * to its last few slices: Pocket's decoder (on the 6a a 6-frame slice cost
 * ~210 ms and a 46-frame one ~1100 ms: a fixed cost per run dominates small
 * slices), and Kokoro's and Kitten's generators (#54, ~120 ms per call).
 * Until the sizes seen span [MIN_SPREAD] frames it charges the observed ms
 * per frame to every frame, which over-estimates bigger slices: an earlier
 * cut, never a later one. (A line through a 6- and a 7-frame slice tilted
 * with their jitter and once predicted a 25-frame decode at ~0 ms on the 6a,
 * so that slice was cut late and playback waited 0.8 s.) Thread-safe (one
 * thread adds, another may read).
 */
class SliceCost(private val capacity: Int = 8) {
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
        if (samples.maxOf { it.first } - samples.minOf { it.first } < MIN_SPREAD) return frames * my / mx
        val sxx = samples.sumOf { (it.first - mx) * (it.first - mx) }
        val slope = samples.sumOf { (it.first - mx) * (it.second - my) } / sxx
        // A noisy fit can still tilt: a slice never gets cheaper with more frames.
        if (slope <= 0.0) return frames * my / mx
        return maxOf(my + slope * (frames - mx), 0.0)
    }

    /**
     * [ms], scaled by how far the fit has been running behind (1 to
     * [MAX_OVERRUN]): what chunk mode's hold charges a slice still to render
     * (#54). The scale is the second-worst actual ÷ fit ratio among the
     * slices seen of a similar size (half to twice [frames]): a slowdown
     * shows in several of them and counts, while one outlier (a GC pause,
     * the cold first slice of a request, a short last slice) doesn't. In-app
     * on the 6a at 2× Kokoro's 88-frame slices slowed from 1.14 to 1.42 s
     * over two pieces: the plain fit let a piece go 223 ms too early (it
     * stalled 176 ms inside); this charges 1.06× and holds it 395 ms.
     */
    @Synchronized
    fun cautiousMs(frames: Int): Double {
        val base = ms(frames)
        if (base <= 0.0) return base
        val ratios = samples
            .filter { (n, _) -> n * 2 >= frames && n <= frames * 2 }
            .map { (n, took) -> took / ms(n).coerceAtLeast(1e-9) }
            .sortedDescending()
        val scale = ratios.getOrNull(1) ?: return base
        return base * scale.coerceIn(1.0, MAX_OVERRUN)
    }

    /**
     * The fixed cost of one run (ms): the fit's intercept, or 0 until the
     * sizes seen span [MIN_SPREAD] frames (no line yet to read it from).
     */
    @Synchronized
    fun fixedMs(): Double {
        if (samples.isEmpty() || samples.maxOf { it.first } - samples.minOf { it.first } < MIN_SPREAD) return 0.0
        val n = samples.size.toDouble()
        val mx = samples.sumOf { it.first.toDouble() } / n
        val my = samples.sumOf { it.second } / n
        val sxx = samples.sumOf { (it.first - mx) * (it.first - mx) }
        val slope = samples.sumOf { (it.first - mx) * (it.second - my) } / sxx
        if (slope <= 0.0) return 0.0
        return (my - slope * mx).coerceIn(0.0, my)
    }

    private companion object {
        /** The fit needs slice sizes at least this far apart. */
        const val MIN_SPREAD = 8

        /** [cautiousMs] charges at most this many times the fit. */
        const val MAX_OVERRUN = 1.25
    }
}
