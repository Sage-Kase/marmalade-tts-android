package app.marmalade.tts.engine.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pins the reference counting that lets #15 "A" share state frames instead of copying them. */
class PocketStateCarryTest {

    private class Owner : AutoCloseable {
        var closed = 0
        override fun close() { closed++ }
    }

    @Test
    fun `a frame closes its owner when the last reference goes`() {
        val owner = Owner()
        val frame = StateFrame(emptyMap(), owner)
        frame.retain()
        frame.release()
        assertEquals(0, owner.closed)
        frame.release()
        assertEquals(1, owner.closed)
        assertThrows(IllegalStateException::class.java) { frame.release() }
    }

    @Test
    fun `a snapshot outlives the carry it came from`() {
        // The voice-prompt cache (#15 "C") keeps phase 1's frame after that chunk's carry closes.
        val owner = Owner()
        val carry = PocketStateCarry(StateFrame(emptyMap(), owner))
        val snap = carry.snapshot()
        carry.close()
        assertEquals(0, owner.closed)
        // A later chunk starts from it and closes its own reference.
        PocketStateCarry(snap.retain()).close()
        assertEquals(0, owner.closed)
        snap.release()
        assertEquals(1, owner.closed)
    }
}
