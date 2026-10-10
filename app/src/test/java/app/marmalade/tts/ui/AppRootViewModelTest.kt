package app.marmalade.tts.ui

import app.marmalade.tts.ui.screen.FakeSettings
import app.marmalade.tts.ui.whatsnew.WhatsNew
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The "What's new" pop-up as [AppRootViewModel] runs it (#41): decided once
 * at creation from the stored version and the onboarded flag, recorded on
 * dismissal. Each new ViewModel stands for an app launch.
 */
class AppRootViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun freshInstall_doesNotShowAndRecordsTheNotesAsSeen() = runTest {
        val settings = FakeSettings(initialId = "x", initialOnboarded = false)

        val vm = AppRootViewModel(settings)

        assertFalse(vm.showWhatsNew.value)
        assertEquals(WhatsNew.NOTES_VERSION, settings.whatsNewSeenVersion.first())
    }

    @Test
    fun freshInstall_finishingOnboarding_stillNeverShows() = runTest {
        val settings = FakeSettings(initialId = "x", initialOnboarded = false)
        AppRootViewModel(settings)
        settings.setOnboarded(true)

        assertFalse("next launch", AppRootViewModel(settings).showWhatsNew.value)
    }

    @Test
    fun userComingFrom110_seesItOnce() = runTest {
        val settings = FakeSettings(initialId = "x", initialOnboarded = true)

        val first = AppRootViewModel(settings)
        assertTrue("first open after the update", first.showWhatsNew.value)
        assertNull("nothing recorded until dismissed", settings.whatsNewSeenVersion.first())

        first.dismissWhatsNew()
        assertFalse(first.showWhatsNew.value)
        assertEquals(WhatsNew.NOTES_VERSION, settings.whatsNewSeenVersion.first())

        assertFalse("second launch", AppRootViewModel(settings).showWhatsNew.value)
        assertFalse("third launch", AppRootViewModel(settings).showWhatsNew.value)
    }

    @Test
    fun notDismissed_showsAgainNextLaunch() = runTest {
        // Process death mid-dialog: not seen yet, so it comes back.
        val settings = FakeSettings(initialId = "x", initialOnboarded = true)
        AppRootViewModel(settings)

        assertTrue(AppRootViewModel(settings).showWhatsNew.value)
    }

    @Test
    fun olderNotesSeen_showsTheseNotes() = runTest {
        val settings = FakeSettings(initialId = "x", initialOnboarded = true)
        settings.setWhatsNewSeenVersion(WhatsNew.NOTES_VERSION - 10_000)

        assertTrue(AppRootViewModel(settings).showWhatsNew.value)
    }
}
