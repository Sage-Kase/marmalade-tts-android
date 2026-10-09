package app.marmalade.tts.phonemizer

import java.text.Normalizer

// -----------------------------------------------------------------------------
// EsTextFixups — pre-espeak corrections for Spanish text.
//
// Spanish spelling before 1952, common in public-domain books, puts an
// accent on the one-letter words "á" (to), "é" (and, before i-), "ó" (or)
// and "ú" (or, before o-). espeak-ng 1.52 reads a standalone á, é or ú as
// the letter's name: "á la casa" → /ˈa aθɛntwˈaða la kˈasa/, "a acentuada"
// out loud (#26, found in a Gutenberg holdout text). A standalone ó only
// gains a stray primary stress, but it is the same old spelling, so it is
// normalised too. Inside a word the accent is real stress and stays.
//
// Other old monosyllable accents ("fué", "dió", "vió", "fuí") are left
// alone: espeak already reads them exactly like the modern spelling.
//
// This is a TEXT fixup, unlike EnPhonemeFixups (which rewrites IPA): the
// damage is a whole extra word, so it is cheapest to stop it before espeak.
// -----------------------------------------------------------------------------

object EsTextFixups {

    // One accented á/é/ó/ú (precomposed, or a vowel + combining acute) with
    // no letter, mark or digit on either side.
    private val LONE_ACCENTED_VOWEL =
        Regex("""(?<![\p{L}\p{M}\p{N}])(?:[áéóúÁÉÓÚ]|[aeouAEOU]\x{0301})(?![\p{L}\p{M}\p{N}])""")

    /** Apply all fixups to text about to be phonemized with a Spanish voice. */
    fun apply(text: String): String =
        LONE_ACCENTED_VOWEL.replace(text) { Normalizer.normalize(it.value, Normalizer.Form.NFD).take(1) }
}
