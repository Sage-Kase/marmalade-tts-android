package app.marmalade.tts.engine.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketLineBreaksTest {

    @Test
    fun `a bare line end becomes a sentence break`() {
        assertEquals("Title. Body text", PocketLineBreaks.apply("Title\n\n  Body text"))
    }

    @Test
    fun `a line ending in punctuation keeps it`() {
        assertEquals("Done. Next", PocketLineBreaks.apply("Done.\nNext"))
        assertEquals("a, b", PocketLineBreaks.apply("a,\nb"))
        assertEquals("first; second", PocketLineBreaks.apply("first; \n second"))
        assertEquals("Items: one", PocketLineBreaks.apply("Items:\none"))
    }

    @Test
    fun `closing quotes and brackets after the punctuation count`() {
        assertEquals("He said \"hi.\" Next", PocketLineBreaks.apply("He said \"hi.\"\nNext"))
        assertEquals("(see above.) Then", PocketLineBreaks.apply("(see above.)\nThen"))
        assertEquals("it's 'fine.' Ok", PocketLineBreaks.apply("it's 'fine.'\nOk"))
    }

    @Test
    fun `a quote without punctuation still gets a break`() {
        assertEquals("He said \"hi\". Next", PocketLineBreaks.apply("He said \"hi\"\nNext"))
    }
}
