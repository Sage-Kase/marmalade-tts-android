package app.marmalade.tts.service

import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import app.marmalade.tts.playback.ErrorNotice
import app.marmalade.tts.playback.NarratorState
import app.marmalade.tts.playback.PausedBy
import app.marmalade.tts.playback.Status

/**
 * Everything [MarmaladeSynthService] shows — media-session state, the
 * notification, whether it stays foreground, the wake lock — as one pure
 * projection of the Narrator's state. The service applies it on every
 * distinct state; nothing else decides what the notification says.
 *
 * "On air" is what the listener hears: a one-shot (share, Speak screen,
 * Tasker) if there is one, else the article. Previous/Next exist only for
 * the article (Max, §6 Q5: no paragraph buttons for shared text).
 */
data class ServiceUi(
    /** A [PlaybackStateCompat] state. */
    val mediaState: Int,
    /** The media session's advertised actions. */
    val actions: Long,
    /** Stop as a custom action (Android 13+ builds its media controls from the session). */
    val showStop: Boolean,
    val text: Text,
    /** The first characters of what is being read, quoted while speaking. */
    val snippet: String,
    /** Show Resume instead of Pause. */
    val paused: Boolean,
    val showPrevious: Boolean,
    val showNext: Boolean,
    /** The article the notification reopens when tapped, or null. */
    val articleKey: String?,
    /**
     * False after a permanent focus loss paused the article (§6 Q6): the
     * service leaves the foreground and the notification can be swiped away
     * (swiping it stops the article).
     */
    val ongoing: Boolean,
    /** Anything on air (playing, buffering or paused); false → the service stops. */
    val onAir: Boolean,
    val wakeLock: Boolean,
) {
    enum class Text { Preparing, Speaking, Paused }

    companion object {
        private const val SNIPPET_CHARS = 40

        fun render(state: NarratorState, previousMediaState: Int): ServiceUi {
            val shot = state.oneShot
            val article = state.article?.takeIf { it.status.isOnAir() }
            val status = shot?.status ?: article?.status
            val articleOnAir = if (shot == null) article else null
            val mediaState = when (status) {
                // A session following one that plays keeps PLAYING until its
                // first audio, so a hand-over doesn't flicker the controls.
                Status.Buffering ->
                    if (previousMediaState == PlaybackStateCompat.STATE_PLAYING) previousMediaState
                    else PlaybackStateCompat.STATE_BUFFERING
                Status.Playing -> PlaybackStateCompat.STATE_PLAYING
                is Status.Paused -> PlaybackStateCompat.STATE_PAUSED
                else ->
                    if (previousMediaState == PlaybackStateCompat.STATE_NONE) previousMediaState
                    else PlaybackStateCompat.STATE_STOPPED
            }
            val showPrevious = articleOnAir != null
            val showNext = articleOnAir != null && articleOnAir.index < articleOnAir.segmentCount - 1
            return ServiceUi(
                mediaState = mediaState,
                actions = sessionActions(showPrevious, showNext),
                showStop = showsStopControl(mediaState),
                text = when (status) {
                    Status.Playing -> Text.Speaking
                    is Status.Paused -> Text.Paused
                    else -> Text.Preparing
                },
                snippet = (shot?.text ?: article?.text).orEmpty().take(SNIPPET_CHARS),
                paused = status is Status.Paused,
                showPrevious = showPrevious,
                showNext = showNext,
                articleKey = articleOnAir?.key,
                ongoing = !(shot == null && article?.status == Status.Paused(PausedBy.FocusLoss)),
                onAir = status != null,
                wakeLock = wantsWakeLock(state),
            )
        }

        /** Buffering or playing, anywhere: the CPU must stay up. Never while paused. */
        fun wantsWakeLock(state: NarratorState): Boolean =
            listOf(state.oneShot?.status, state.article?.status).any { it == Status.Buffering || it == Status.Playing }

        /** The error to post for a UI-less one-shot, once per id ([ErrorNotice] stays set until the next one-shot). */
        fun errorToPost(state: NarratorState, lastPostedId: Long?): ErrorNotice? =
            state.notice?.takeIf { it.oneShotId != lastPostedId }

        /**
         * Transport actions the media session advertises. Play, pause and stop
         * in every state, BUFFERING included, so a media-button stop or pause
         * reaches the service before the first audio does; skips only where
         * the article can step.
         */
        internal fun sessionActions(canPrevious: Boolean, canNext: Boolean): Long {
            var actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            if (canNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            if (canPrevious) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            return actions
        }

        /** Whether the session's controls offer Stop: while there is anything to stop. */
        internal fun showsStopControl(mediaState: Int): Boolean =
            mediaState != PlaybackStateCompat.STATE_NONE && mediaState != PlaybackStateCompat.STATE_STOPPED

        /**
         * Whether a media key should pause where MediaSession's default
         * handling wouldn't: the play/pause toggle (or a headset's single
         * button) during BUFFERING, which the default turns into a no-op play.
         */
        internal fun playPauseKeyPauses(keyCode: Int, mediaState: Int): Boolean =
            mediaState == PlaybackStateCompat.STATE_BUFFERING &&
                (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK)

        private fun Status.isOnAir(): Boolean =
            this == Status.Buffering || this == Status.Playing || this is Status.Paused
    }
}
