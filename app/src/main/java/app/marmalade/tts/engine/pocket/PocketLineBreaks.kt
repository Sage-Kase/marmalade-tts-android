package app.marmalade.tts.engine.pocket

/**
 * P-AM: a line break in Pocket's input reads as a sentence pause, so a title
 * line followed by a paragraph doesn't run together. A line that already ends
 * in punctuation (`.!?,;:`, optionally followed by closing quotes or brackets)
 * keeps it and gets a space; any other line end gets `". "`. Without the
 * punctuation check "a,\nb" read as "a,. b" and `"hi."\nNext` as `"hi.". Next`.
 * The marmalade-tts CLI uses the same rule.
 */
object PocketLineBreaks {

    private val LINE_BREAK = Regex("""([.!?,;:]['")\]]*)?[ \t]*\n\s*""")

    fun apply(text: String): String =
        LINE_BREAK.replace(text) { m -> if (m.groupValues[1].isNotEmpty()) m.groupValues[1] + " " else ". " }
}
