package app.marmalade.tts.engine.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketTokenSplitTest {

    /** A stand-in tokenizer: one token per two characters, spaces included. */
    private val tokens: (String) -> Int = { (it.length + 1) / 2 }

    @Test
    fun `words pack greedily up to the limit`() {
        assertEquals(listOf("aa bb", "cc dd", "ee"), PocketTokenSplit.byWords("aa bb cc  dd ee", 3, tokens))
        assertEquals(emptyList<String>(), PocketTokenSplit.byWords("  ", 3, tokens))
    }

    @Test
    fun `an overlong word is cut into pieces that each fit`() {
        val url = "https://example.com/" + "section/item?ref=abc&page=".repeat(20)
        val pieces = PocketTokenSplit.byWords(url, 50, tokens)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { tokens(it) <= 50 })
        assertEquals(url, pieces.joinToString(""))
        // Every piece but the last is as long as the limit allows.
        assertTrue(pieces.dropLast(1).all { tokens(it) == 50 })
    }

    @Test
    fun `words around an overlong word keep packing`() {
        val word = "x".repeat(13) // 7 tokens at a limit of 3
        assertEquals(
            listOf("aa", "xxxxxx", "xxxxxx", "x bb"),
            PocketTokenSplit.byWords("aa $word bb", 3, tokens),
        )
    }

    @Test
    fun `a piece never splits a surrogate pair and always makes progress`() {
        val word = "😀".repeat(4) // four emoji, two chars each
        val pieces = PocketTokenSplit.splitLongWord(word, 0, tokens)
        assertEquals(List(4) { "😀" }, pieces)
    }
}
