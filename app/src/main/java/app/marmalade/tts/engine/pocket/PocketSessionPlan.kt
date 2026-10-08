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

    fun resolve(threads: Int, knob: (String) -> String?): Map<String, PocketGraphConfig> {
        val xnnpack = graphSet(knob("pocket.xnnpack"), default = GRAPHS.toSet())
        val spin = graphSet(knob("pocket.spin"), default = emptySet())
        return GRAPHS.associateWith { g ->
            val defaultThreads = if (g == "flow") (threads / 2).coerceAtLeast(2) else threads
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
