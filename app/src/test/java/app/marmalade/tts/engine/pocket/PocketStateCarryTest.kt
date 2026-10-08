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
    fun `a snapshot keeps its frame alive across a restore`() {
        val first = Owner()
        val second = Owner()
        val carry = PocketStateCarry(StateFrame(emptyMap(), first))
        val snap = carry.snapshot()
        // The carry moves on to another frame (as advance would) …
        val moved = StateFrame(emptyMap(), second)
        carry.restore(moved)
        moved.release()
        assertEquals(0, first.closed)
        // … and rewinds to the snapshot: the frame it left is closed, the snapshot isn't.
        carry.restore(snap)
        assertEquals(1, second.closed)
        assertEquals(0, first.closed)
        snap.release()
        assertEquals(0, first.closed)
        carry.close()
        assertEquals(1, first.closed)
    }
}
