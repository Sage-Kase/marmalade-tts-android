package app.marmalade.tts.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLinksTest {

    private val home = SettingsTarget.Action("android.settings.SETTINGS", SettingsPage.HOME)
    private val sdks = 28..37
    private val tts = SettingsTarget.Action("com.android.settings.TTS_SETTINGS", SettingsPage.TTS)

    private fun ttsTargets(sdk: Int) = SettingsLinks.targets(SettingsLink.TTS, sdk)

    private fun component(activity: String, page: SettingsPage, highlight: String) =
        SettingsTarget.Component("com.android.settings", "com.android.settings.Settings\$$activity", page, highlight)

    private val languagesAndInput =
        component("LanguageAndInputSettingsActivity", SettingsPage.LANGUAGES_AND_INPUT, "tts_settings_summary")

    @Test
    fun `every link ends on Settings' home on every Android version`() {
        for (link in SettingsLink.entries) for (sdk in sdks) {
            val targets = SettingsLinks.targets(link, sdk)
            assertEquals("$link on $sdk", home, targets.last())
            assertEquals("$link on $sdk: home only as the last resort", 1, targets.count { it.page == SettingsPage.HOME })
        }
    }

    @Test
    fun `the TTS link tries the Text-to-speech action first on every Android version`() {
        for (sdk in sdks) assertEquals("sdk $sdk", tts, ttsTargets(sdk).first())
    }

    @Test
    fun `the language list (LOCALE_SETTINGS) is never tried`() {
        for (sdk in sdks) {
            assertFalse(ttsTargets(sdk).any { it is SettingsTarget.Action && it.action == "android.settings.LOCALE_SETTINGS" })
        }
    }

    @Test
    fun `9 to 13 fall back to Languages and input, then the System page`() {
        for (sdk in 28..33) {
            assertEquals(
                "sdk $sdk",
                listOf(
                    tts,
                    languagesAndInput,
                    component("SystemDashboardActivity", SettingsPage.SYSTEM, "language_input_settings"),
                    home,
                ),
                ttsTargets(sdk),
            )
        }
    }

    @Test
    fun `14 and later fall back to Language and region, Languages, then System`() {
        for (sdk in 34..37) {
            val systemRow = if (sdk >= 36) "language_and_region_settings" else "language_settings"
            assertEquals(
                "sdk $sdk",
                listOf(
                    tts,
                    // With its action: Android 17's page crashes Settings on a null one.
                    component("LanguageAndRegionSettingsActivity", SettingsPage.LANGUAGE_AND_REGION, "speech_preference")
                        .copy(action = "android.settings.LANGUAGE_SETTINGS"),
                    component("LanguageSettingsActivity", SettingsPage.LANGUAGES, "tts_settings_summary"),
                    // Not Languages & input: Settings crashes on it on 15 (the API 35 emulator).
                    component("SystemDashboardActivity", SettingsPage.SYSTEM, systemRow),
                    home,
                ),
                ttsTargets(sdk),
            )
        }
    }

    @Test
    fun `explicit components never go out without an action on Language and region`() {
        for (sdk in sdks) for (target in ttsTargets(sdk)) {
            if (target is SettingsTarget.Component && target.page == SettingsPage.LANGUAGE_AND_REGION) {
                assertEquals("android.settings.LANGUAGE_SETTINGS", target.action)
            }
        }
    }

    @Test
    fun `the battery link tries the dialog, the app list, then App info, on every Android version`() {
        for (sdk in sdks) {
            assertEquals(
                "sdk $sdk",
                listOf(
                    SettingsTarget.Action(
                        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                        SettingsPage.BATTERY_DIALOG,
                        forApp = true,
                    ),
                    SettingsTarget.Action("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", SettingsPage.BATTERY_LIST),
                    SettingsTarget.Action("android.settings.APPLICATION_DETAILS_SETTINGS", SettingsPage.APP_DETAILS, forApp = true),
                    home,
                ),
                SettingsLinks.targets(SettingsLink.BATTERY, sdk),
            )
        }
    }

    @Test
    fun `a quick return from the battery dialog is an answer, not a bounce`() {
        for (sdk in sdks) {
            val battery = SettingsLinks.targets(SettingsLink.BATTERY, sdk)
            assertFalse("sdk $sdk", SettingsLinks.watchForBounce(battery, 0))
            // The app list and App info are full-screen pages: a quick return there is a bounce.
            assertTrue("sdk $sdk", SettingsLinks.watchForBounce(battery, 1))
            assertTrue("sdk $sdk", SettingsLinks.watchForBounce(battery, 2))
        }
    }

    @Test
    fun `every full-screen page is watched for a bounce, except the last resort`() {
        for (link in SettingsLink.entries) for (sdk in sdks) {
            val targets = SettingsLinks.targets(link, sdk)
            for (i in targets.indices) {
                val expected = i < targets.lastIndex && targets[i].page != SettingsPage.BATTERY_DIALOG
                assertEquals("$link on $sdk, target $i", expected, SettingsLinks.watchForBounce(targets, i))
            }
        }
    }

    @Test
    fun `the battery dialog is the only dialog`() {
        assertEquals(listOf(SettingsPage.BATTERY_DIALOG), SettingsPage.entries.filter { it.dialog })
    }

    private val none: (String) -> Boolean = { false }
    private val settings = SettingsMatch(exported = true, permission = null)

    @Test
    fun `one exported match with no permission can be started`() {
        assertTrue(SettingsLinks.canStart(listOf(settings), last = false, granted = none))
    }

    @Test
    fun `no match can't be started, not even as the last resort`() {
        assertFalse(SettingsLinks.canStart(emptyList(), last = false, granted = none))
        assertFalse(SettingsLinks.canStart(emptyList(), last = true, granted = none))
    }

    @Test
    fun `two matches would show a chooser, so only the last resort takes them`() {
        val two = listOf(settings, settings.copy())
        assertFalse(SettingsLinks.canStart(two, last = false, granted = none))
        assertTrue(SettingsLinks.canStart(two, last = true, granted = none))
    }

    @Test
    fun `an unexported match doesn't count`() {
        val hidden = SettingsMatch(exported = false, permission = null)
        assertFalse(SettingsLinks.canStart(listOf(hidden), last = false, granted = none))
        assertFalse(SettingsLinks.canStart(listOf(hidden), last = true, granted = none))
        // The one we can start, beside one we can't, is no chooser.
        assertTrue(SettingsLinks.canStart(listOf(hidden, settings), last = false, granted = none))
    }

    @Test
    fun `a match behind a permission counts only if we hold it`() {
        val guarded = SettingsMatch(exported = true, permission = "android.permission.WRITE_SECURE_SETTINGS")
        assertFalse(SettingsLinks.canStart(listOf(guarded), last = false, granted = none))
        assertTrue(SettingsLinks.canStart(listOf(guarded), last = false, granted = { true }))
    }
}
