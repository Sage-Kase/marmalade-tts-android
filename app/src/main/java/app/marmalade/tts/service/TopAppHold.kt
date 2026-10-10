package app.marmalade.tts.service

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import app.marmalade.tts.engine.EngineDevKnobs
import app.marmalade.tts.playback.NarratorState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Puts in-app speech on the phone's fast cores while it plays (#52).
 *
 * With the screen off, a foreground service sits in the `foreground` cpuset,
 * which leaves out the big cores (the 6a's two X1s): Kokoro renders about 2×
 * slower there than system TTS does and stalls at 1.0×. Android's TTS manager
 * binds an engine for every client with `BIND_SCHEDULE_LIKE_TOP_APP`, which
 * puts the engine's whole process — ours, reader included — in `top-app`. So
 * while the Narrator is buffering or playing, this holds a [TextToSpeech]
 * session to the app's own engine. It never speaks; it only exists.
 *
 * Android 12+ only: clients reach engines through the TTS manager service
 * from then on; before, `TextToSpeech` bound the engine from the client's own
 * process, and a self-binding would change nothing.
 *
 * Follows the Narrator's state like [NarratorWakeLock] ([TopAppArm]).
 */
@Singleton
class TopAppHold @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Touched only on the main thread. */
    private var tts: TextToSpeech? = null

    private val arm = TopAppArm(scope, bind = ::bind, unbind = ::unbind)

    /** Start following [state]; called once, when the Narrator is built. */
    fun follow(state: StateFlow<NarratorState>) {
        scope.launch { state.collect(arm::onState) }
    }

    private fun bind() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Debug builds' A/B switch (#52's battery runs); release never reads it.
        if (EngineDevKnobs.get(context.filesDir, "topAppHold") == "off") return
        tts = selfSession(context)
    }

    private fun unbind() {
        val held = tts ?: return
        held.shutdown()
        tts = null
        Log.i(TAG, "self session released")
    }

    companion object {
        private const val TAG = "TopAppHold"

        /**
         * True from [bind] until its session is set up: a [MarmaladeTtsService]
         * created meanwhile was most likely brought up by this session, so it
         * skips warming the system-TTS voice's model (the reader loads its own).
         */
        @Volatile
        var connecting: Boolean = false
            private set

        /**
         * A silent session to this app's own engine; [TextToSpeech.shutdown]
         * ends it. Call on the main thread. Also used by the debug bench.
         */
        fun selfSession(context: Context): TextToSpeech {
            connecting = true
            return TextToSpeech(context, { status ->
                connecting = false
                Log.i(TAG, "self session ${if (status == TextToSpeech.SUCCESS) "bound" else "failed ($status)"}")
            }, context.packageName)
        }
    }
}

/**
 * The hold's rule, free of Android: bound while anything is buffering or
 * playing ([ServiceUi.wantsWakeLock], the wake lock's rule), released
 * [RELEASE_DELAY_MS] after that ends, so a pause-and-resume or a one-shot
 * queued behind another doesn't rebind. Call [onState] on [scope]'s thread.
 */
class TopAppArm(
    private val scope: CoroutineScope,
    private val bind: () -> Unit,
    private val unbind: () -> Unit,
) {
    private var bound = false
    private var release: Job? = null

    fun onState(state: NarratorState) {
        if (ServiceUi.wantsWakeLock(state)) {
            release?.cancel()
            release = null
            if (!bound) {
                bind()
                bound = true
            }
        } else if (bound && release == null) {
            release = scope.launch {
                delay(RELEASE_DELAY_MS)
                release = null
                bound = false
                unbind()
            }
        }
    }

    companion object {
        const val RELEASE_DELAY_MS = 5_000L
    }
}
