package app.marmalade.tts.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.VoiceLatencyTracker
import app.marmalade.tts.playback.FocusChange
import app.marmalade.tts.playback.FocusPort
import app.marmalade.tts.playback.HostPort
import app.marmalade.tts.playback.NarratorShell
import app.marmalade.tts.playback.NarratorState
import app.marmalade.tts.playback.SynthStatsPort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// The Narrator's ports on the device (reader-session redesign, step C1). Each
// duty the service used to do per request now has one owner here, driven by
// the Narrator — never by the service's lifecycle, so a process TalkBack keeps
// alive past the service still cleans up.
// -----------------------------------------------------------------------------

/**
 * "Is the host service foreground right now?" — written by
 * [MarmaladeSynthService] around `startForeground`/`stopForeground`, read by
 * the Narrator before it lets a track play (Android 16 mutes audio outside a
 * foreground service with a media session and focus).
 */
@Singleton
class ServiceHost @Inject constructor() : HostPort {
    @Volatile var foreground: Boolean = false

    override fun isForeground(): Boolean = foreground
}

/**
 * Audio focus, one [AudioFocusRequest] per hold: [abandon] releases exactly
 * the request that was granted (the old per-request service could overwrite
 * it and leak a grant). Changes go to [shell] — the Narrator — on the main
 * looper; transient losses pause (ducks too: `setWillPauseWhenDucked`), a
 * permanent loss pauses the article with its place kept.
 */
@Singleton
class AudioFocusPort @Inject constructor(
    @ApplicationContext context: Context,
) : FocusPort {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /** Set once, right after the Narrator is built (it needs this port first). */
    @Volatile var shell: NarratorShell? = null

    /** The granted request; touched only on the Narrator's loop. */
    private var held: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { code ->
        focusChangeOf(code)?.let { shell?.onFocusChange(it) }
    }

    override fun request(): Boolean {
        val am = audioManager ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            .setOnAudioFocusChangeListener(listener, handler)
            .setWillPauseWhenDucked(true)
            .build()
        val granted = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        held = request.takeIf { granted }
        return granted
    }

    override fun abandon() {
        held?.let { audioManager?.abandonAudioFocusRequest(it) }
        held = null
    }

    companion object {
        /** Media usage, speech content: the focus request's and every track's attributes. */
        val SPEECH_ATTRIBUTES: AudioAttributes by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }

        /** An AudioManager focus change as the Narrator sees it; null for codes it ignores. */
        internal fun focusChangeOf(code: Int): FocusChange? = when (code) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LossTransient
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.LossTransientCanDuck
            AudioManager.AUDIOFOCUS_LOSS -> FocusChange.Loss
            AudioManager.AUDIOFOCUS_GAIN -> FocusChange.Gain
            else -> null
        }
    }
}

/**
 * The render job's bookkeeping, written off the loop: one time-to-first-audio
 * sample per render job into [VoiceLatencyTracker] (the Speak screen's
 * per-voice latency hints) and each warm, unskewed RTF sample into the
 * per-engine rolling average behind the speed-up warning. Fire-and-forget, so
 * a slow DataStore never stalls a render.
 */
@Singleton
class NarratorStats @Inject constructor(
    private val latency: VoiceLatencyTracker,
    private val settings: SettingsRepository,
) : SynthStatsPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun recordLatency(voiceId: String, engine: String, millis: Long, chars: Int) {
        scope.launch { runCatching { latency.record(voiceId, engine, millis, chars) } }
    }

    override fun recordRtf(engine: String, rtf: Double) {
        scope.launch { runCatching { settings.recordEngineRtf(engine, rtf) } }
    }
}

/**
 * Keeps the CPU up while there is speech to produce: a foreground service
 * alone does not — with the screen off the device can suspend during an
 * underrun or a cloud round-trip and freeze inference mid-read. Follows the
 * Narrator's state ([WakeLockArm]), re-armed by the writer on every chunk heard.
 */
@Singleton
class NarratorWakeLock @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val lock: PowerManager.WakeLock? = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        // One logical hold, re-armed many times; one release ends it.
        ?.apply { setReferenceCounted(false) }

    private val arm = WakeLockArm(
        acquire = { timeout -> lock?.acquire(timeout) },
        release = { lock?.takeIf { it.isHeld }?.release() },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Start following [state]; called once, when the Narrator is built. */
    fun follow(state: StateFlow<NarratorState>) {
        scope.launch { state.collect(arm::onState) }
    }

    /** A chunk was heard: re-arm the timeout if held. */
    fun onChunk() = arm.onChunk()

    private companion object {
        /** `app:component`, as PowerManager recommends. */
        const val WAKE_LOCK_TAG = "marmalade:synth"
    }
}

/**
 * The wake-lock rule, free of Android: held while anything is buffering or
 * playing ([ServiceUi.wantsWakeLock]) — never while paused — each arming
 * bounded by [TIMEOUT_MS] and re-armed per chunk, so a wedged session can't
 * keep the CPU up for ever.
 */
class WakeLockArm(
    private val acquire: (timeoutMs: Long) -> Unit,
    private val release: () -> Unit,
) {
    private var holding = false

    @Synchronized
    fun onState(state: NarratorState) {
        val want = ServiceUi.wantsWakeLock(state)
        if (want) acquire(TIMEOUT_MS) else if (holding) release()
        holding = want
    }

    @Synchronized
    fun onChunk() {
        if (holding) acquire(TIMEOUT_MS)
    }

    companion object {
        /**
         * Safety bound on one arming. Re-armed on every chunk, so this only
         * has to outlast the longest honest wait between two — a cold model
         * load, a slow cloud round-trip.
         */
        const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}
