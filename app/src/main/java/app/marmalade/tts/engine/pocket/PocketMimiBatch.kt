package app.marmalade.tts.engine.pocket

/**
 * How many latent frames one `mimi_decoder` run may take and still decode
 * exactly what frame-by-frame decoding would (issue #15 "D").
 *
 * mimi's decoder transformer runs at 200 Hz, so one 12.5 Hz latent frame is
 * [STEPS_PER_FRAME] attention steps, and each step looks back [CONTEXT]
 * steps. The export keeps its keys in a ring buffer whose capacity is the
 * cache slots' time axis (1000 in every bundle so far). A run of B frames
 * writes 16·B keys before attending, so once `16·B + 249` exceeds the
 * capacity the batch's tail overwrites keys its head still needs: the first
 * frames after the batch start come out wrong (10–17 dB SNR at B = 64).
 * That was the "batched decode corrupts the leading edge" artefact the old
 * P-AL lead-in only partly hid. Batches within the limit are exact.
 */
object PocketMimiBatch {

    const val STEPS_PER_FRAME = 16
    const val CONTEXT = 250

    /** Largest exact batch for a ring of [capacity] steps (46 for 1000). */
    fun maxExactBatch(capacity: Int): Int = ((capacity - (CONTEXT - 1)) / STEPS_PER_FRAME).coerceAtLeast(1)

    /**
     * The ring capacity: the time axis of the decoder's 5-D attention caches
     * (the encoder transformer's, which the decoder only passes through, don't count).
     */
    fun ringCapacity(manifest: List<PocketBundle.StateSpec>): Int =
        manifest.filter {
            it.dtype == PocketBundle.StateDtype.FLOAT32 && it.shape.size == 5 && !it.path.startsWith("encoder")
        }
            .minOfOrNull { it.shape[3].toInt() }
            ?: error("mimi state manifest has no attention cache")

    /** Split [numFrames] into consecutive runs of at most [batch] frames. */
    fun batches(numFrames: Int, batch: Int): List<IntRange> {
        require(batch >= 1)
        return (0 until numFrames step batch).map { it until minOf(it + batch, numFrames) }
    }
}

/**
 * Cache lengths other than the bundle's 1000, for graphs exported with a
 * symbolic cache axis (issue #15 "F", the `*_dyncache.onnx` prototypes; the
 * shipped graphs reject any other length).
 */
object PocketCacheShapes {

    /**
     * Ring capacity for a decoder with a symbolic ring axis: 249 + 16·4, so
     * 4-frame runs stay exact. Small runs keep the first audio quick, and on
     * the 6a they decoded as fast per frame as 46-frame runs on the 1000 ring.
     */
    const val DYNAMIC_MIMI_CAPACITY = 320

    /**
     * Rows flow_lm_main's cache needs for a chunk: the voice prompt (BOS +
     * voice frames), the chunk's text tokens and its frame cap, rounded up to
     * 64 and never past the export's 1000-row position tables. Bundled
     * voices with a full 50-token chunk need 410–446 rows (448).
     */
    fun flowLenFor(promptRows: Int, tokens: Int, maxFrames: Int): Int =
        minOf(MAX_FLOW_CACHE, (promptRows + tokens + maxFrames + 63) / 64 * 64)

    const val MAX_FLOW_CACHE = 1000

    private fun isCache(spec: PocketBundle.StateSpec) =
        spec.dtype == PocketBundle.StateDtype.FLOAT32 && spec.shape.size == 5

    private fun PocketBundle.StateSpec.withAxis(axis: Int, len: Int) =
        copy(shape = shape.copyOf().also { it[axis] = len.toLong() })

    /** flow_lm_main's KV caches `[2,1,L,16,64]` at length [len]. */
    fun flowLm(manifest: List<PocketBundle.StateSpec>, len: Int): List<PocketBundle.StateSpec> =
        manifest.map { if (isCache(it)) it.withAxis(2, len) else it }

    /**
     * mimi's decoder attention rings `[2,1,8,cap,64]` at [capacity]; the
     * encoder transformer's caches, which the decoder graph only passes
     * through, at length 0.
     */
    fun mimi(manifest: List<PocketBundle.StateSpec>, capacity: Int): List<PocketBundle.StateSpec> =
        manifest.map {
            when {
                !isCache(it) -> it
                it.path.startsWith("encoder") -> it.withAxis(3, 0)
                else -> it.withAxis(3, capacity)
            }
        }
}
