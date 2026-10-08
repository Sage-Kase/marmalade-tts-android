package app.marmalade.tts.engine.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketMimiBatchTest {

    private fun spec(i: Int, shape: LongArray, dtype: PocketBundle.StateDtype = PocketBundle.StateDtype.FLOAT32) =
        PocketBundle.StateSpec(i, "state_$i", "out_state_$i", dtype, PocketBundle.StateFill.ZEROS, shape)

    @Test
    fun `the shipped ring of 1000 decodes 46 frames exactly, no more`() {
        assertEquals(46, PocketMimiBatch.maxExactBatch(1000))
        // 16·46 + 249 = 985 fits; 16·47 + 249 = 1001 doesn't.
        assertEquals(1, PocketMimiBatch.maxExactBatch(200))
    }

    @Test
    fun `ring capacity is the time axis of the attention caches`() {
        val manifest = listOf(
            spec(0, longArrayOf(1, 512, 6)),
            spec(1, longArrayOf(2, 1, 8, 1000, 64)),
            spec(2, longArrayOf(1), PocketBundle.StateDtype.INT64),
        )
        assertEquals(1000, PocketMimiBatch.ringCapacity(manifest))
    }

    @Test
    fun `batches cover every frame once, in order`() {
        assertEquals(listOf(0 until 32, 32 until 64, 64 until 70), PocketMimiBatch.batches(70, 32))
        assertEquals(emptyList<IntRange>(), PocketMimiBatch.batches(0, 32))
        assertEquals(listOf(0 until 1, 1 until 2), PocketMimiBatch.batches(2, 1))
    }

    @Test
    fun `cache shapes resize the time axis of attention caches only`() {
        val flow = listOf(spec(0, longArrayOf(2, 1, 1000, 16, 64)), spec(1, longArrayOf(1), PocketBundle.StateDtype.INT64))
        val resized = PocketCacheShapes.flowLm(flow, 512)
        assertEquals(listOf(2L, 1, 512, 16, 64), resized[0].shape.toList())
        assertEquals(listOf(1L), resized[1].shape.toList())

        val mimi = listOf(
            spec(19, longArrayOf(2, 1, 8, 1000, 64)).copy(path = "decoder_transformer.transformer.layers.0.self_attn/cache"),
            spec(49, longArrayOf(2, 1, 8, 1000, 64)).copy(path = "encoder_transformer.transformer.layers.0.self_attn/cache"),
            spec(1, longArrayOf(1, 512, 6)),
        )
        val m = PocketCacheShapes.mimi(mimi, 320)
        assertEquals(listOf(2L, 1, 8, 320, 64), m[0].shape.toList())
        assertEquals(listOf(2L, 1, 8, 0, 64), m[1].shape.toList())
        assertEquals(listOf(1L, 512, 6), m[2].shape.toList())
        assertEquals(4, PocketMimiBatch.maxExactBatch(PocketMimiBatch.ringCapacity(m)))
    }
}
