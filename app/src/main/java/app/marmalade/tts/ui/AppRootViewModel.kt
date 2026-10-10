package app.marmalade.tts.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.ui.theme.ThemePreset
import app.marmalade.tts.ui.whatsnew.WhatsNew
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Routing-level ViewModel — surfaces the onboarded flag that [AppRoot] uses
 * to choose between the onboarding wizard and the main screen graph, plus
 * the user's theme preset so [MainActivity] can apply it before any
 * composable renders.
 *
 * The onboarded flag is written by [app.marmalade.tts.ui.onboarding.OnboardingViewModel];
 * the theme preset is written by SettingsViewModel.
 *
 * Also owns the once-per-release "What's new" pop-up (#41): decided once
 * per process at creation ([WhatsNew.decide]), so a fresh install is marked
 * seen before onboarding flips the flag that would make it look like an
 * update. Activity-scoped, so rotation keeps the decision; after process
 * death an undismissed pop-up simply shows again.
 */
@HiltViewModel
class AppRootViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {
    val onboarded: Flow<Boolean> = settings.onboarded
    val themePreset: Flow<ThemePreset> = settings.themePreset.map { ThemePreset.fromString(it) }
    /** "system" / "light" / "dark" — see [resolveThemeIsDark] for the contract. */
    val themeMode: Flow<String> = settings.themeMode

    private val _showWhatsNew = MutableStateFlow(false)

    /** True while the "What's new" pop-up is owed and not yet dismissed. */
    val showWhatsNew: StateFlow<Boolean> = _showWhatsNew.asStateFlow()

    init {
        viewModelScope.launch {
            val decision = WhatsNew.decide(
                seenVersion = settings.whatsNewSeenVersion.first(),
                onboarded = settings.onboarded.first(),
            )
            when (decision) {
                WhatsNew.Decision.Show -> _showWhatsNew.value = true
                WhatsNew.Decision.MarkSeen -> settings.setWhatsNewSeenVersion(WhatsNew.NOTES_VERSION)
                WhatsNew.Decision.None -> Unit
            }
        }
    }

    /** "Got it", back or a tap outside: these notes are never shown unasked again. */
    fun dismissWhatsNew() {
        _showWhatsNew.value = false
        viewModelScope.launch { settings.setWhatsNewSeenVersion(WhatsNew.NOTES_VERSION) }
    }
}
