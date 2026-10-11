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

    private companion object {
        /** The fit needs slice sizes at least this far apart. */
        const val MIN_SPREAD = 8
    }
}
