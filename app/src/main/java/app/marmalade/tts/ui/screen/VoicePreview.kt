package app.marmalade.tts.ui.screen

import androidx.annotation.StringRes
import app.marmalade.tts.R
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.audio.SynthesizerException
import app.marmalade.tts.data.db.VoiceMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the per-row Preview button.
 *
 * Kept separate from the screen-level playback state because previewing
 * is intentionally a transient affordance — tapping Preview should not
 * block the rest of the picker from being interactive.
 */
sealed class PreviewState {
    object Idle : PreviewState()

    /** Indicates which voice is currently being previewed (by voice ID). */
    data class Playing(val voiceId: String) : PreviewState()

    /**
     * Engine assets aren't bundled. Same condition as
     * [PlaybackState.ModelMissing]. [engineName] is the engine the failed
     * preview belonged to (`"kokoro"` / `"kitten"`) so the UI copy can
     * name the missing engine specifically instead of hardcoding one.
     */
    data class ModelMissing(val engineName: String) : PreviewState()

    /**
     * [message] is the engine's own user-facing detail; when it has none,
     * the UI renders [fallbackRes] instead.
     */
    data class Error(
        val message: String?,
        @StringRes val fallbackRes: Int,
    ) : PreviewState()
}

/**
 * The per-row voice audition, shared by the Voices screen
 * ([VoicePickerViewModel]) and the alias editor's picker sheet
 * ([AliasViewModel]). Plays through [SpeechPlayer], so the audio comes out of
 * `MarmaladeSynthService` like every other in-app read.
 *
 * One preview at a time: [play] cancels whatever is in flight first.
 * Main-thread only (UI callbacks + a viewModelScope), so plain fields.
 */
class VoicePreviewer(
    private val player: SpeechPlayer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    /**
     * Monotonic token for the latest preview. A superseded preview's
     * coroutine still completes (Synthesizer.speak returns success on
     * cancellation) — without the token check it would overwrite the
     * NEW preview's Playing chip with its own terminal state.
     */
    private var generation = 0L

    fun play(voice: VoiceMeta, phrase: String) {
        // Don't stack previews — cancel anything in flight first.
        player.cancel()
        val token = ++generation
        _state.value = PreviewState.Playing(voice.id)

        scope.launch {
            val result = player.speak(phrase, voice.id)
            if (token != generation) return@launch // superseded or stopped
            _state.value = result.fold(
                onSuccess = { PreviewState.Idle },
                onFailure = { err ->
                    when (err) {
                        is SynthesizerException.ModelMissing ->
                            PreviewState.ModelMissing(voice.engine)
                        is SynthesizerException.SynthesisFailed -> PreviewState.Error(
                            err.message,
                            R.string.voices_error_preview_failed,
                        )
                        else -> PreviewState.Error(err.message, R.string.voices_error_unknown)
                    }
                },
            )
        }
    }

    /** Play/stop: a tap on the voice that is playing stops it; any other voice takes over. */
    fun toggle(voice: VoiceMeta, phrase: String) {
        if ((_state.value as? PreviewState.Playing)?.voiceId == voice.id) stop() else play(voice, phrase)
    }

    /**
     * Stop the preview and forget any error it left. Cancels the player only
     * while a preview is playing: `cancel()` stops the player's in-flight
     * request, and when no preview plays that request is someone else's (a
     * Speak-screen read).
     */
    fun stop() {
        if (_state.value is PreviewState.Playing) {
            generation++
            player.cancel()
        }
        _state.value = PreviewState.Idle
    }
}
