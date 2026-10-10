package app.marmalade.tts.engine.kokoro

import app.marmalade.tts.audio.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // #49: a chunk after the first, cut while rendering when its wait outruns the audio ahead.

    @Test
    fun `a later chunk whose plan the audio ahead covers stays whole`() {
        // 140 tokens × 6 ms + 150 ms = 990 ms, 1.2 s still to play.
        assertNull(kokoroLaterHead(tokens = 140, aheadMs = 1_200, msPerToken = 6.0, fixedMs = 150.0, maxHead = 120))
    }

    @Test
    fun `a wait within the seam tolerance stays whole`() {
        // 990 ms of wait, 900 ms ahead: 90 ms short, inside the seam's pause.
        assertNull(kokoroLaterHead(tokens = 140, aheadMs = 900, msPerToken = 6.0, fixedMs = 150.0, maxHead = 120))
    }

    @Test
    fun `a later chunk whose plan would be heard gets a head sized to what is ahead`() {
        // "Good morning." left ~0.5 s: 140 tokens plan in ~1 s. (500 − 150) / 6 = 58.
        assertEquals(58, kokoroLaterHead(tokens = 140, aheadMs = 500, msPerToken = 6.0, fixedMs = 150.0, maxHead = 120))
    }

    @Test
    fun `the head stays within its bounds`() {
        assertEquals(15, kokoroLaterHead(tokens = 140, aheadMs = 0, msPerToken = 6.0, fixedMs = 150.0, maxHead = 120))
        // The whole path's render cost: 30 ms a token, 3 s ahead → 100, capped at 55.
        assertEquals(55, kokoroLaterHead(tokens = 300, aheadMs = 3_000, msPerToken = 30.0, fixedMs = 0.0, maxHead = 55))
    }

    private val laterBudget = KokoroDirectEngine.STREAM_TOKEN_BUDGET.copy(firstPiece = 30, firstPieceMin = 15)

    @Test
    fun `a later sentence is cut at its clause into a head and the rest whole`() {
        val sentence = "The sun is out today, and a light breeze is moving over the hills while we walk down to the river."
        val chunk = TextChunker.TokenChunk(sentence, sentence.length)
        val pieces = TextChunker.clauseHead(chunk, laterBudget) { it.length }
        assertEquals(listOf("The sun is out today,", sentence.substringAfter(", ")), pieces.map { it.text })
        assertTrue(pieces.all { it.rowTokens == sentence.length })
    }

    @Test
    fun `cutting a piece of a cut sentence keeps the sentence's style row`() {
        val piece = TextChunker.TokenChunk("$clause, $clause, $clause.", 152, rowTokens = 400)
        val pieces = TextChunker.clauseHead(piece, laterBudget.copy(firstPiece = 50)) { it.length }
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.rowTokens == 400 })
    }

    @Test
    fun `a later sentence without a clause mark stays whole`() {
        val sentence = "And a light breeze is moving over the hills while we walk down to the river after lunch."
        val chunk = TextChunker.TokenChunk(sentence, sentence.length)
        assertEquals(listOf(chunk), TextChunker.clauseHead(chunk, laterBudget) { it.length })
    }
}
