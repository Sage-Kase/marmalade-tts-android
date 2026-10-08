package app.marmalade.tts.engine.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketStreamPlanTest {

    @Test
    fun `slices double from 6 frames up to the exact batch`() {
        assertEquals(listOf(6, 12, 24, 46, 46, 46), (0..5).map { PocketStreamPlan.sliceFrames(it, 46) })
        assertEquals(listOf(4, 4), (0..1).map { PocketStreamPlan.sliceFrames(it, 4) })
        assertEquals(46, PocketStreamPlan.sliceFrames(1000, 46))
    }

    @Test
    fun `no target yet holds, a met target releases everything in order`() {
        val bank = PocketSliceBank<String>(playbackRate = 1f)
        assertEquals(emptyList<String>(), bank.offer("a", 480))
        bank.targetPlayedMs = 1000
        assertEquals(emptyList<String>(), bank.offer("b", 480))
        assertEquals(listOf("a", "b", "c"), bank.offer("c", 960))
        assertEquals(listOf("d"), bank.offer("d", 480))
    }

    @Test
    fun `a zero target sends at once and the bank is judged on the played clock`() {
        val now = PocketSliceBank<String>(1f).apply { targetPlayedMs = 0 }
        assertEquals(listOf("a"), now.offer("a", 480))
        val fast = PocketSliceBank<String>(playbackRate = 2f).apply { targetPlayedMs = 500 }
        assertEquals(emptyList<String>(), fast.offer("a", 960)) // 480 ms played
        assertEquals(listOf("a", "b"), fast.offer("b", 960))
        val end = PocketSliceBank<String>(1f).apply { targetPlayedMs = 10_000 }
        end.offer("a", 480)
        assertEquals(listOf("a"), end.flush())
        assertEquals(listOf("b"), end.offer("b", 480))
    }
}
