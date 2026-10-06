package app.marmalade.tts.devbench

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [EspeakToMisaki] matches its Python reference (scratch/kokoro7m-check/render_fix.py, a port
 * of misaki's EspeakFallback) on the B1 sentences, phonemized by the pinned espeak-ng with ties
 * and the JNI shim's punctuation spacing. That reference scored 3.5–5.5% WER on desktop.
 */
class EspeakToMisakiTest {

    @Test
    fun `B1 sentences convert exactly as the desktop reference`() {
        for ((tied, misaki) in GOLDEN) assertEquals(tied, misaki, EspeakToMisaki.convert(tied))
    }

    @Test
    fun `diphthongs and affricates become misaki's single symbols`() {
        assertEquals("I W A O Y ʧ ʤ", EspeakToMisaki.convert("a^ɪ a^ʊ e^ɪ o^ʊ ɔ^ɪ t^ʃ d^ʒ"))
    }

    @Test
    fun `r-coloured vowels, length marks, flap and syllabic l`() {
        assertEquals("səɹ wˈɜɹd fˈɑks lˈɪTᵊl", EspeakToMisaki.convert("sɚ wˈɜːd fˈɑːks lˈɪɾə^l"))
    }

    @Test
    fun `punctuation sits on the word and sentence-end spaces collapse`() {
        assertEquals("həlˈO, ðˈɛɹ. ˈɔn", EspeakToMisaki.convert("həlˈo^ʊ , ðˈɛ^ɹ .  ˈɔn  "))
    }

    private companion object {
        val GOLDEN = listOf(
        "həlˈo^ʊ ðˈɛ^ɹ . " to
            "həlˈO ðˈɛɹ.",
        "ðə kˈɛɾə^l ɪz ˈɔn . " to
            "ðə kˈɛTᵊl ɪz ˈɔn.",
        "plˈiːz kˈɔːl mˌiː bˈæk wɛn juː ɡɛt ɐ t^ʃˈæns . " to
            "plˈiz kˈɔl mˌi bˈæk wɛn ju ɡɛt ə ʧˈæns.",
        "ʃiː sˈɛlz sˈiː ʃˈɛlz ba^ɪ ðə sˈiː ʃˈɔːɹ ˈɛvɹi sˈʌmɚ mˈɔː^ɹnɪŋ . " to
            "ʃi sˈɛlz sˈi ʃˈɛlz bI ðə sˈi ʃˈɔɹ ˈɛvɹi sˈʌməɹ mˈɔɹnɪŋ.",
        "θˈæŋks fɔː^ɹ wˈe^ɪɾɪŋ ," to
            "θˈæŋks fɔɹ wˈATɪŋ,",
        "a^ɪl biː wɪð juː ɪn d^ʒˈʌst ɐ mˈo^ʊmənt . " to
            "Il bi wɪð ju ɪn ʤˈʌst ə mˈOmənt.",
        "ðə kwˈɪk bɹˈa^ʊn fˈɑːks d^ʒˈʌmps ˌo^ʊvɚ ðə lˈe^ɪzi dˈɑːɡ wˌa^ɪl ðə fˈɑː^ɹmɚ wˈɑːt^ʃᵻz fɹʌmðə pˈɔː^ɹt^ʃ . " to
            "ðə kwˈɪk bɹˈWn fˈɑks ʤˈʌmps ˌOvəɹ ðə lˈAzi dˈɑɡ wˌIl ðə fˈɑɹməɹ wˈɑʧᵻz fɹʌmðə pˈɔɹʧ.",
        "ɪf ðə tɹˈe^ɪn ɪz lˈe^ɪt ɐɡˈɛn təmˈɑːɹo^ʊ ," to
            "ɪf ðə tɹˈAn ɪz lˈAt əɡˈɛn təmˈɑɹO,",
        "wiː ʃˌʊd lˈiːv ðə hˈa^ʊs ɐ lˈɪɾə^l ˈɜːlɪɚ . " to
            "wi ʃˌʊd lˈiv ðə hˈWs ə lˈɪTᵊl ˈɜɹliəɹ.",
        "ˌa^ʊɚ lˈa^ɪbɹɛɹi ˈo^ʊpənz æt nˈa^ɪn ˌɔn wˈiːkde^ɪz ænd klˈo^ʊzᵻz æt fˈa^ɪv ˌɔn sˈæɾɚdˌe^ɪz ænd sˈʌnde^ɪz . " to
            "ˌWəɹ lˈIbɹɛɹi ˈOpənz æt nˈIn ˌɔn wˈikdAz ænd klˈOzᵻz æt fˈIv ˌɔn sˈæTəɹdˌAz ænd sˈʌndAz.",
        "ˈæftɚ ðə stˈɔː^ɹm pˈæst ," to
            "ˈæftəɹ ðə stˈɔɹm pˈæst,",
        "ðə hˈo^ʊl nˈe^ɪbɚhˌʊd kˈe^ɪm a^ʊtsˈa^ɪd tə klˈɪ^ɹ ðə fˈɔːlən bɹˈænt^ʃᵻz , t^ʃˈɛk ˌɔn ˈiːt^ʃ ˈʌðɚ ," to
            "ðə hˈOl nˈAbəɹhˌʊd kˈAm WtsˈId tə klˈɪɹ ðə fˈɔlən bɹˈænʧᵻz, ʧˈɛk ˌɔn ˈiʧ ˈʌðəɹ,",
        "ænd ʃˈɛ^ɹ wʌtˈɛvɚ fˈuːd ðe^ɪ hæd lˈɛft . " to
            "ænd ʃˈɛɹ wʌtˈɛvəɹ fˈud ðA hæd lˈɛft.",
        "sˈa^ɪ^əntɪsts hæv dɪskˈʌvɚd ðæt ˈɑːktəpjˌuːsᵻz kæn sˈɑːlv sɚpɹˈa^ɪzɪŋli kˈɑːmplɛks pˈʌzə^lz ænd ˈiːvən ɹᵻmˈɛmbɚ ðə pˈiːpə^l hˌuː tɹˈiːɾᵻd ðˌɛm kˈa^ɪndli ˌo^ʊvɚ mˈɛni mˈʌnθs . " to
            "sˈIəntɪsts hæv dɪskˈʌvəɹd ðæt ˈɑktəpjˌusᵻz kæn sˈɑlv səɹpɹˈIzɪŋli kˈɑmplɛks pˈʌzᵊlz ænd ˈivən ɹᵻmˈɛmbəɹ ðə pˈipᵊl hˌu tɹˈiTᵻd ðˌɛm kˈIndli ˌOvəɹ mˈɛni mˈʌnθs.",
        "ɪnðə bɪɡˈɪnɪŋ wʌzðə wˈɜːd ," to
            "ɪnðə bɪɡˈɪnɪŋ wʌzðə wˈɜɹd,",
        "ænd ðə wˈɜːd wʌz wɪð ɡˈɑːd , ænd ðə wˈɜːd wʌz ɡˈɑːd . " to
            "ænd ðə wˈɜɹd wʌz wɪð ɡˈɑd, ænd ðə wˈɜɹd wʌz ɡˈɑd.",
        "ðə lˈɔː^ɹd ɪz ma^ɪ ʃˈɛpɚd ;" to
            "ðə lˈɔɹd ɪz mI ʃˈɛpəɹd;",
        "a^ɪʃˌɐl nˌɑːt wˈɔnt .  hiː mˌe^ɪkəθ mˌiː tə lˈa^ɪ dˌa^ʊn ɪn ɡɹˈiːn pˈæst^ʃɚz : hiː lˈiːdəθ mˌiː bᵻsˌa^ɪd ðə stˈɪl wˈɔːɾɚz . " to
            "Iʃˌəl nˌɑt wˈɔnt. hi mˌAkəθ mˌi tə lˈI dˌWn ɪn ɡɹˈin pˈæsʧəɹz: hi lˈidəθ mˌi bᵻsˌId ðə stˈɪl wˈɔTəɹz.",
        "jʊɹ ɐpˈɔ^ɪntmənt hˈæzbiːn mˈuːvd tə θˈɜːzde^ɪ ðə twˈɛlfθ æt hˈæf pˈæst θɹˈiː ɪnðɪ ˌæftɚnˈuːn . " to
            "jʊɹ əpˈYntmənt hˈæzbin mˈuvd tə θˈɜɹzdA ðə twˈɛlfθ æt hˈæf pˈæst θɹˈi ɪnðɪ ˌæftəɹnˈun.",
        "ɹᵻmˈɛmbɚ tə wˈɔːɾɚ ðə təmˈe^ɪɾo^ʊz bᵻfˌɔː^ɹ juː ɡˌo^ʊ tə bˈɛd tənˈa^ɪt . " to
            "ɹᵻmˈɛmbəɹ tə wˈɔTəɹ ðə təmˈATOz bᵻfˌɔɹ ju ɡˌO tə bˈɛd tənˈIt.",
        )
    }
}
