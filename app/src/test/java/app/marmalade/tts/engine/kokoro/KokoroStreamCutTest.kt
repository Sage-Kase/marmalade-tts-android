package app.marmalade.tts.engine.kokoro

import app.marmalade.tts.audio.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The streaming path's short head for a long first sentence (#13 review). */
class KokoroStreamCutTest {

    @Test
    fun `a long first sentence is cut when nothing playing covers its plan`() {
        assertTrue(kokoroStreamCutFirst(firstTokens = 240, aheadMs = 0, holding = false))
        // 240 tokens plan in ~1.44 s: 1 s of audio still to play doesn't cover it.
        assertTrue(kokoroStreamCutFirst(firstTokens = 240, aheadMs = 1_000, holding = false))
    }

    @Test
    fun `audio still playing that covers the plan keeps it whole`() {
        assertFalse(kokoroStreamCutFirst(firstTokens = 240, aheadMs = 2_000, holding = false))
    }

    @Test
    fun `a pre-roll to bank cuts it even behind playing audio`() {
        assertTrue(kokoroStreamCutFirst(firstTokens = 240, aheadMs = 10_000, holding = true))
    }

    @Test
    fun `an everyday sentence is never cut`() {
        assertFalse(kokoroStreamCutFirst(firstTokens = 160, aheadMs = 0, holding = true))
        assertFalse(kokoroStreamCutFirst(firstTokens = 90, aheadMs = 0, holding = false))
    }

    // One token per character, so sizes read straight off the text.
    private fun plan(text: String, cut: Boolean) =
        TextChunker.planByTokens(text, KokoroDirectEngine.STREAM_TOKEN_BUDGET, cutFirstSentence = { cut }) { it.length }

    private val clause = "the quick brown fox jumps over the lazy dog again" // 49 chars

    @Test
    fun `the cut gives a short head and the rest of the sentence whole`() {
        val sentence = List(6) { clause }.joinToString(", ") + "." // 6 clauses, 305 tokens
        val chunks = plan(sentence, cut = true)
        assertEquals(2, chunks.size)
        assertEquals(50, chunks[0].tokens) // one clause: two would pass the 80-token head
        assertTrue(chunks.all { it.rowTokens == sentence.length })
        assertEquals(sentence, chunks.joinToString(" ") { it.text })
    }

    @Test
    fun `later sentences stay whole`() {
        val long = List(6) { clause }.joinToString(", ") + "."
        val chunks = plan("$long $long", cut = true)
        assertEquals(3, chunks.size)
        assertEquals(long, chunks[2].text)
    }

    @Test
    fun `without the cut a long first sentence stays one chunk`() {
        val sentence = List(6) { clause }.joinToString(", ") + "."
        assertEquals(1, plan(sentence, cut = false).size)
    }
}
