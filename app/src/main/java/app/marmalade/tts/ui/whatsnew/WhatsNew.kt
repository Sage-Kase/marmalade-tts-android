package app.marmalade.tts.ui.whatsnew

/**
 * When the "What's new" pop-up shows (#41).
 *
 * It shows once per release that has notes: the first time the app opens
 * after updating to it. The last notes version the user dismissed lives in
 * [app.marmalade.tts.data.SettingsRepository.whatsNewSeenVersion].
 *
 * To ship notes for a new release: replace the strings in
 * `strings_whats_new.xml` in every `res/values` folder (all 8 locales) and raise
 * [NOTES_VERSION] to that release's versionCode. A release without new
 * notes (a patch, say) leaves both alone and nobody sees the pop-up again.
 */
object WhatsNew {

    /**
     * The release the current notes describe, as its versionCode without the
     * ABI digit (1.2.0 = 10_020_000; see the scheme in app/build.gradle.kts).
     * A constant rather than `BuildConfig.VERSION_CODE`, so per-ABI builds
     * and patch releases don't re-show the same notes.
     */
    const val NOTES_VERSION = 10_020_000

    enum class Decision {
        /** An existing user who hasn't dismissed these notes yet. */
        Show,

        /**
         * A fresh install: the notes describe changes it never lived
         * through. Record them as seen without showing them.
         */
        MarkSeen,

        /** Already seen. */
        None,
    }

    /**
     * Decide at app start. [seenVersion] is the stored value (`null` before
     * 1.2, which never wrote it); [onboarded] is the onboarding flag as this
     * launch found it. A 1.1.0 user has finished onboarding but has no stored
     * version, which is how an update is told apart from a fresh install.
     */
    fun decide(seenVersion: Int?, onboarded: Boolean, notesVersion: Int = NOTES_VERSION): Decision =
        when {
            seenVersion != null && seenVersion >= notesVersion -> Decision.None
            !onboarded -> Decision.MarkSeen
            else -> Decision.Show
        }
}
