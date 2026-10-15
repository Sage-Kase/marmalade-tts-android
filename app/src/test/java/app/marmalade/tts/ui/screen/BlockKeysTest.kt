package app.marmalade.tts.ui.screen

import app.marmalade.tts.audio.EffectBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BlockKeysTest {

    private val reverb = EffectBlock.Reverb(50f)
    private val pitch = EffectBlock.Pitch(100f)
    private val vol = EffectBlock.Vol(1.2f)

    @Test
    fun anAppendedBlockKeepsTheOthersKeys() {
        val keys = BlockKeys()
        val before = keys.keysFor(listOf(reverb, pitch))
        val after = keys.keysFor(listOf(reverb, pitch, vol))
        assertEquals(before, after.take(2))
        assertEquals(3, after.toSet().size)
    }

    @Test
    fun aRemovedBlockTakesOnlyItsOwnKey() {
        val keys = BlockKeys()
        val before = keys.keysFor(listOf(reverb, pitch, vol))
        val after = keys.keysFor(listOf(reverb, vol))
        assertEquals(listOf(before[0], before[2]), after)
    }

    @Test
    fun aMovedBlockCarriesItsKey() {
        val keys = BlockKeys()
        val before = keys.keysFor(listOf(reverb, pitch, vol))
        val after = keys.keysFor(listOf(reverb, vol, pitch))
        assertEquals(listOf(before[0], before[2], before[1]), after)
    }

    @Test
    fun anEditInPlaceKeepsEveryKey() {
        val keys = BlockKeys()
        val before = keys.keysFor(listOf(reverb, pitch))
        val after = keys.keysFor(listOf(reverb, EffectBlock.Pitch(-200f)))
        assertEquals(before, after)
    }

    @Test
    fun twoEqualBlocksStillGetDistinctKeys() {
        val keys = BlockKeys()
        val k = keys.keysFor(listOf(reverb, reverb))
        assertNotEquals(k[0], k[1])
        assertEquals(k + k.max().plus(1), keys.keysFor(listOf(reverb, reverb, reverb)))
    }

    @Test
    fun anUnrelatedChainMintsFreshKeys() {
        val keys = BlockKeys()
        val before = keys.keysFor(listOf(reverb))
        val after = keys.keysFor(listOf(pitch, vol, reverb))
        assertEquals(3, after.toSet().size)
        assertEquals(emptySet<Int>(), before.toSet() intersect after.toSet())
    }
}
