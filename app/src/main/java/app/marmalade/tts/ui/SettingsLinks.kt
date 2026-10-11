package app.marmalade.tts.ui

// The app's two links into Android's Settings (#53), as pure lists of screens
// to try, best first, so they are unit-tested per Android version.
// SystemSettings resolves each before launching it and opens the first that
// this phone has and lets us start. Ported from marmalade-stt-android #79;
// the activities, actions and row keys were checked in AOSP Settings'
// manifests and preference XML for 9 to 17 (2026-10-10).

/** A place in the app that opens system Settings. */
enum class SettingsLink {
    /** Text-to-speech output, where Marmalade is picked as the preferred engine. */
    TTS,

    /** The battery-optimisation exemption for this app. */
    BATTERY,
}

/**
 * The Settings page a [SettingsTarget] lands on. A [dialog] is answered with
 * one tap, so a quick return from it is an answer, not a bounce.
 */
enum class SettingsPage(val dialog: Boolean = false) {
    /** Text-to-speech output, with the Preferred engine row. */
    TTS,

    /** Android 9 to 15: Languages & input, with the Text-to-speech output row. */
    LANGUAGES_AND_INPUT,

    /** Android 14 to 16: Languages, with the Text-to-speech output row. */
    LANGUAGES,

    /** Android 16 QPR2 and 17: Language & region, whose Speech row holds Text-to-speech output. */
    LANGUAGE_AND_REGION,

    /** The System page, with the row that leads to the language page. */
    SYSTEM,

    /** The yes/no dialog that exempts this app from battery optimisation. */
    BATTERY_DIALOG(dialog = true),

    /** The list of apps and their battery optimisation (App battery usage from 15, and on the Pixel 6a's 14). */
    BATTERY_LIST,

    /** This app's App info page. */
    APP_DETAILS,

    /** Settings' home: the user has to find the page from there. */
    HOME,
}

/** A settings screen to try opening. */
sealed interface SettingsTarget {
    /** Where it lands. */
    val page: SettingsPage

    /**
     * The key of the row to scroll to and highlight (Settings'
     * `:settings:fragment_args_key`, the one its own search results use),
     * or null. A key the page doesn't have is ignored.
     */
    val highlight: String?

    /**
     * An explicit activity of the Settings app, sent with [action] when the
     * page needs one: Android 17's Language & region crashes Settings on a
     * null action (STT #79, the Pixel 8a).
     */
    data class Component(
        val packageName: String,
        val className: String,
        override val page: SettingsPage,
        override val highlight: String? = null,
        val action: String? = null,
    ) : SettingsTarget

    /** An intent action; with [forApp] its data is this app's `package:` URI. */
    data class Action(
        val action: String,
        override val page: SettingsPage,
        val forApp: Boolean = false,
        override val highlight: String? = null,
    ) : SettingsTarget
}

/** One activity an intent matched, as [SettingsLinks.canStart] judges it. */
data class SettingsMatch(val exported: Boolean, val permission: String?)

object SettingsLinks {

    private const val SETTINGS = "com.android.settings"
    private const val ACTION_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"
    private const val ACTION_LANGUAGE_SETTINGS = "android.settings.LANGUAGE_SETTINGS"
    private const val ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS =
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    private const val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS =
        "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    private const val ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    private const val ACTION_SETTINGS = "android.settings.SETTINGS"

    /** The Text-to-speech output row (Languages & input, Languages, and Speech on 16 QPR2+). */
    private const val TTS_ROW = "tts_settings_summary"

    /** The Speech row of Language & region. */
    private const val SPEECH_ROW = "speech_preference"

    private val HOME = SettingsTarget.Action(ACTION_SETTINGS, SettingsPage.HOME)

    private fun settings(activity: String, page: SettingsPage, highlight: String? = null) =
        SettingsTarget.Component(SETTINGS, "$SETTINGS.Settings\$$activity", page, highlight)

    /**
     * `com.android.settings.TTS_SETTINGS` opens `Settings$TextToSpeechSettingsActivity`,
     * exported and with no permission, on every release from 9 to 17. The
     * rest only open where a phone maker dropped or renamed it: the page
     * that holds the Text-to-speech output row, its row highlighted.
     * - 9 to 13: Languages & input. 14 and 15 also have Languages, and
     *   `LANGUAGE_SETTINGS` matches both there (a chooser), so the
     *   activities are named.
     * - 16 has Languages only, and disables it when Language & region
     *   replaces it; 16 QPR2 and 17 open Language & region, whose Speech
     *   page holds the row. The System page, its language row highlighted,
     *   is the nearest when neither opens.
     * `ACTION_LOCALE_SETTINGS`, tried before #53, is the language list,
     * not a page with the row, and is no longer used.
     */
    private fun tts(sdk: Int): List<SettingsTarget> {
        val tts = SettingsTarget.Action(ACTION_TTS_SETTINGS, SettingsPage.TTS)
        val languagesAndInput = settings("LanguageAndInputSettingsActivity", SettingsPage.LANGUAGES_AND_INPUT, TTS_ROW)
        val languages = settings("LanguageSettingsActivity", SettingsPage.LANGUAGES, TTS_ROW)
        // Its onViewCreated compares the intent's action: without one, Settings crashes (Android 17).
        val languageAndRegion = settings("LanguageAndRegionSettingsActivity", SettingsPage.LANGUAGE_AND_REGION, SPEECH_ROW)
            .copy(action = ACTION_LANGUAGE_SETTINGS)
        val systemRow = when {
            sdk >= 36 -> "language_and_region_settings"
            sdk >= 34 -> "language_settings"
            else -> "language_input_settings"
        }
        val system = settings("SystemDashboardActivity", SettingsPage.SYSTEM, systemRow)
        return when {
            sdk >= 34 -> listOf(tts, languageAndRegion, languages, languagesAndInput, system, HOME)
            else -> listOf(tts, languagesAndInput, system, HOME)
        }
    }

    /**
     * The same three actions on every release from 9 to 17, all handled by
     * Settings, exported, with no permission on the activity:
     * - the direct yes/no dialog (`fuelgauge.RequestIgnoreBatteryOptimizations`);
     *   it needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission we
     *   declare, and closes at once if we're already exempt;
     * - the app list (Battery optimization up to 14 in AOSP, App battery usage
     *   from 15 and on the Pixel 6a's 14), for phones that hide the dialog;
     * - this app's App info page.
     */
    private fun battery(): List<SettingsTarget> = listOf(
        SettingsTarget.Action(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, SettingsPage.BATTERY_DIALOG, forApp = true),
        SettingsTarget.Action(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, SettingsPage.BATTERY_LIST),
        SettingsTarget.Action(ACTION_APPLICATION_DETAILS_SETTINGS, SettingsPage.APP_DETAILS, forApp = true),
        HOME,
    )

    /** The screens to try for [link] on Android [sdk], best first; the last one is Settings' home. */
    fun targets(link: SettingsLink, sdk: Int): List<SettingsTarget> = when (link) {
        SettingsLink.TTS -> tts(sdk)
        SettingsLink.BATTERY -> battery()
    }

    /**
     * Whether a quick return to our screen after [targets]`[index]` opened
     * means the page bounced, so the next target should open. Not after the
     * last target (nothing follows), and not after a dialog: a quick Deny is
     * the user's answer (#53 review). A phone that hides the battery dialog
     * doesn't export or resolve it, so it is skipped before launch instead.
     */
    fun watchForBounce(targets: List<SettingsTarget>, index: Int): Boolean =
        index < targets.lastIndex && !targets[index].page.dialog

    /**
     * Whether a target whose intent resolved to [matches] can be started
     * without a chooser: exactly one exported activity that needs no
     * permission we lack ([granted]). The [last] target, Settings' home, may
     * show a chooser rather than open nothing.
     */
    fun canStart(matches: List<SettingsMatch>, last: Boolean, granted: (String) -> Boolean): Boolean {
        val startable = matches.count { it.exported && (it.permission == null || granted(it.permission)) }
        return startable == 1 || (last && startable > 0)
    }
}
