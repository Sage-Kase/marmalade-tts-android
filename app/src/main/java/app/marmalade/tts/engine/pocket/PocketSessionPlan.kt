package app.marmalade.tts.engine.pocket

/** ORT session settings for one of Pocket's five graphs. */
data class PocketGraphConfig(val threads: Int, val xnnpack: Boolean, val spin: Boolean)

/**
 * Per-graph session settings: the shipped defaults, and the debug-build
 * overrides an on-device A/B sets (issue #15; `EngineDevKnobs`, so a
 * release build always gets the defaults).
 *
 * Graphs: `text` (text_conditioner), `enc` (mimi_encoder), `dec`
 * (mimi_decoder), `main` (flow_lm_main), `flow` (flow_lm_flow).
 *
 * Knobs (all optional):
 * - `pocket.xnnpack` = `all` | `none` | a comma list of graphs that get XNNPACK
 * - `pocket.spin` = `all` | `none` | a comma list of graphs whose intra-op
 *   threads spin between calls
 * - `pocket.<graph>.threads` = intra-op thread count for that graph
 */
object PocketSessionPlan {

    val GRAPHS = listOf("text", "enc", "dec", "main", "flow")

    /**
     * Graphs that get half the threads (at least 2): the flow head is a
     * small matmul (P-Q), and the decoder runs alongside the AR loop, so
     * full pools for both oversubscribe the cores. On the Pixel 6a (#15
     * "B") 2 decoder threads instead of 4 rendered at the same speed (RTF
     * 0.613 vs 0.611) with 11% less CPU, audio within 1 LSB.
     */
    private val HALF_THREADS = setOf("flow", "dec")

    fun resolve(threads: Int, knob: (String) -> String?): Map<String, PocketGraphConfig> {
        val xnnpack = graphSet(knob("pocket.xnnpack"), default = GRAPHS.toSet())
        val spin = graphSet(knob("pocket.spin"), default = emptySet())
        return GRAPHS.associateWith { g ->
            val defaultThreads = if (g in HALF_THREADS) (threads / 2).coerceAtLeast(2) else threads
            PocketGraphConfig(
                threads = knob("pocket.$g.threads")?.toIntOrNull()?.takeIf { it > 0 } ?: defaultThreads,
                xnnpack = g in xnnpack,
                spin = g in spin,
            )
        }
    }

    private fun graphSet(value: String?, default: Set<String>): Set<String> = when (value?.trim()) {
        null, "" -> default
        "all" -> GRAPHS.toSet()
        "none" -> emptySet()
        else -> value.split(',').map { it.trim() }.filter { it in GRAPHS }.toSet()
    }
}
