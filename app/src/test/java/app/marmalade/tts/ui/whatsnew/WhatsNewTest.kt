package app.marmalade.tts.ui.whatsnew

import app.marmalade.tts.ui.whatsnew.WhatsNew.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** The once-per-release rule behind the "What's new" pop-up (#41). */
class WhatsNewTest {

    @Test
    fun freshInstall_isMarkedSeenWithoutShowing() {
        assertEquals(Decision.MarkSeen, WhatsNew.decide(seenVersion = null, onboarded = false))
    }

    @Test
    fun userComingFrom110_seesIt() {
        // 1.1.0 never stored the key, but it finished onboarding.
        assertEquals(Decision.Show, WhatsNew.decide(seenVersion = null, onboarded = true))
    }

    @Test
    fun afterDismissal_isNotShownAgain() {
        assertEquals(
            Decision.None,
            WhatsNew.decide(seenVersion = WhatsNew.NOTES_VERSION, onboarded = true),
        )
    }

    @Test
    fun nextNotesVersion_isShownAgain() {
        val next = WhatsNew.NOTES_VERSION + 10_000
        assertEquals(
            Decision.Show,
            WhatsNew.decide(seenVersion = WhatsNew.NOTES_VERSION, onboarded = true, notesVersion = next),
        )
    }

    @Test
    fun aNewerStoredVersion_isNotShown() {
        // A downgrade leaves the newer value on disk: nothing to show.
        assertEquals(
            Decision.None,
            WhatsNew.decide(seenVersion = WhatsNew.NOTES_VERSION + 10_000, onboarded = true),
        )
    }

    @Test
    fun notOnboardedWithAnOlderVersion_isMarkedSeen() {
        // Onboarding replayed (debug DevStateService) under older notes: the
        // user is walked through setup, not shown release notes after it.
        assertEquals(
            Decision.MarkSeen,
            WhatsNew.decide(seenVersion = WhatsNew.NOTES_VERSION - 10_000, onboarded = false),
        )
    }

    @Test
    fun notesVersion_is120() {
        // MAJOR*10_000_000 + MINOR*10_000 + PATCH*10, without the ABI digit.
        assertEquals(1 * 10_000_000 + 2 * 10_000, WhatsNew.NOTES_VERSION)
    }
}
