package app.marmalade.tts.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The alias dial's speed badge (#81): shown whenever it wouldn't read "1×". */
class AliasDialTest {

    @Test
    fun `no badge at 1x`() {
        assertFalse(speedBadgeShown(1f))
    }

    @Test
    fun `a badge at any other speed, faster or slower`() {
        assertTrue(speedBadgeShown(1.2f))
        assertTrue(speedBadgeShown(1.25f))
        assertTrue(speedBadgeShown(0.9f))
        assertTrue(speedBadgeShown(2f))
    }
}
