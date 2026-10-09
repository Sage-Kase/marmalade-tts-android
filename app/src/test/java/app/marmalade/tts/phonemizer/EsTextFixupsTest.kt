package app.marmalade.tts.phonemizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Old Spanish spelling's accented one-letter words (#26). espeak-ng 1.52
 * reads a standalone "á" as /ˈa aθɛntwˈaða/ ("a acentuada"), so they are
 * normalised before phonemizing. Accents inside words must survive.
 */
class EsTextFixupsTest {

    @Test
    fun `issue sentence loses its standalone accents`() {
        // "Fué" is left as is: espeak reads it exactly like "fue".
        assertEquals("Fué a la casa o al campo", EsTextFixups.apply("Fué á la casa ó al campo"))
    }

    @Test
    fun `all four vowels and both cases`() {
        assertEquals("a e o u A E O U", EsTextFixups.apply("á é ó ú Á É Ó Ú"))
    }

    @Test
    fun `next to punctuation and at the edges`() {
        assertEquals("A las tres, o a las cuatro.", EsTextFixups.apply("Á las tres, ó á las cuatro."))
        assertEquals("¿Va a casa?", EsTextFixups.apply("¿Va á casa?"))
        assertEquals("siete u ocho", EsTextFixups.apply("siete ú ocho"))
        assertEquals("padre e hijos", EsTextFixups.apply("padre é hijos"))
    }

    @Test
    fun `decomposed combining accent is handled`() {
        assertEquals("va a la casa", EsTextFixups.apply("va á la casa"))
    }

    @Test
    fun `accents inside words are untouched`() {
        val text = "Está allá, él lo sabía; ¿qué más? Ánimo, ópera, último."
        assertEquals(text, EsTextFixups.apply(text))
    }

    @Test
    fun `plain text is untouched`() {
        val text = "Fue a la casa o al campo."
        assertEquals(text, EsTextFixups.apply(text))
    }
}
