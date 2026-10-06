package app.marmalade.tts.devbench

// -----------------------------------------------------------------------------
// EspeakToMisaki — tied en-us espeak IPA → misaki's spellings (issue #4)
// -----------------------------------------------------------------------------
//
// Kokoro-7M-Distill was distilled on misaki's G2P output only. Fed the app's
// untied espeak IPA it misheard 31–34% of words (Whisper, B1), against 3–4%
// for upstream's own pipeline on the same sentences. Kokoro-82M tolerates
// espeak; the 7M student does not.
//
// This is a port of misaki's OWN espeak → misaki conversion,
// `misaki/espeak.py` EspeakFallback (misaki 0.9.4, en-us, version=None),
// which misaki uses for words missing from its lexicon:
//   * tied diphthongs/affricates → misaki's single symbols (a^ɪ→I, e^ɪ→A,
//     o^ʊ→O, a^ʊ→W, ɔ^ɪ→Y, t^ʃ→ʧ, d^ʒ→ʤ), ə^l→ᵊl, syllabic C̩→ᵊC;
//   * ɚ→əɹ, ɜː→ɜɹ, ɐ→ə, no length marks, the flap ɾ→T, ʔ→t.
// Then one addition of ours: punctuation goes tight against the word
// ("ðˈɛɹ." not "ðˈɛɹ ."), the way misaki writes it.
//
// Measured on desktop (scratch/kokoro7m-check): 3.5–5.5% WER over the 14
// B1 sentences, level with upstream. The golden pairs in
// EspeakToMisakiTest come from the Python reference (render_fix.py).
// -----------------------------------------------------------------------------

internal object EspeakToMisaki {

    /** misaki's E2M table, longest key first, as EspeakFallback applies it. */
    private val E2M: List<Pair<String, String>> = listOf(
        "ʔˌn̩" to "ʔn", "ʔn̩" to "ʔn",
        "a^ɪ" to "I", "a^ʊ" to "W", "d^ʒ" to "ʤ", "e^ɪ" to "A", "t^ʃ" to "ʧ", "ɔ^ɪ" to "Y",
        "ə^l" to "ᵊl", "ʲo" to "jo", "ʲə" to "jə",
        "e" to "A", "ʲ" to "", "ɚ" to "əɹ", "r" to "ɹ", "x" to "k", "ç" to "k",
        "ɐ" to "ə", "ɬ" to "l", "̃" to "",
    ).sortedByDescending { it.first.length }

    private val SYLLABIC = Regex("""(\S)̩""")
    private val SPACE_BEFORE_PUNCT = Regex(""" +([.,;:!?])""")
    private val SPACES = Regex(""" {2,}""")

    /** [tied]: espeak en-us IPA phonemized with `tie = true`. */
    fun convert(tied: String): String {
        var ps = tied
        for ((old, new) in E2M) ps = ps.replace(old, new)
        ps = SYLLABIC.replace(ps, "ᵊ$1").replace("̩", "")
        ps = ps.replace("o^ʊ", "O").replace("ɜːɹ", "ɜɹ").replace("ɜː", "ɜɹ")
            .replace("ɪə", "iə").replace("ː", "")
        ps = ps.replace("o", "ɔ")
        ps = ps.replace("ɾ", "T").replace("ʔ", "t")
        ps = ps.replace("^", "")
        ps = SPACE_BEFORE_PUNCT.replace(ps, "$1")
        return SPACES.replace(ps, " ").trim()
    }
}
