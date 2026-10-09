package app.marmalade.tts.engine.pocket

/**
 * Last-resort splitting for Pocket text that is still over the chunk's token
 * limit after sentence and `,;:` splits. [tokens] counts a string's tokens
 * (the bundle's tokenizer in the engine).
 */
object PocketTokenSplit {

    private val WHITESPACE = Regex("\\s+")

    /**
     * Greedy word packing: each piece is as many whole words as fit in
     * [maxTokens]. A single word over the limit (a URL, a hash, base64) is
     * cut into pieces by [splitLongWord] (#23): left whole it got one AR
     * session sized for hundreds of tokens, which overflowed flow_lm's
     * 1000-row KV cache with a long voice prompt.
     */
    fun byWords(text: String, maxTokens: Int, tokens: (String) -> Int): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (w in text.split(WHITESPACE)) {
            if (w.isEmpty()) continue
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            when {
                tokens(candidate) <= maxTokens -> cur = candidate
                tokens(w) <= maxTokens -> {
                    out.add(cur)
                    cur = w
                }
                else -> {
                    if (cur.isNotEmpty()) out.add(cur)
                    val pieces = splitLongWord(w, maxTokens, tokens)
                    out.addAll(pieces.dropLast(1))
                    cur = pieces.last()
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * Cut [word] into consecutive pieces, each the longest prefix of what is
     * left that fits in [maxTokens] (at least one character, and never
     * between the halves of a surrogate pair).
     */
    fun splitLongWord(word: String, maxTokens: Int, tokens: (String) -> Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < word.length) {
            var end = nextEnd(word, start)
            while (end < word.length) {
                val next = nextEnd(word, end)
                if (tokens(word.substring(start, next)) > maxTokens) break
                end = next
            }
            out.add(word.substring(start, end))
            start = end
        }
        return out
    }

    private fun nextEnd(s: String, i: Int): Int =
        if (Character.isHighSurrogate(s[i]) && i + 1 < s.length) i + 2 else i + 1
}
