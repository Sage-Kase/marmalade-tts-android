package app.marmalade.tts.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.marmalade.tts.playback.FailureKind
import app.marmalade.tts.playback.NarratorControl
import app.marmalade.tts.playback.OneShotRequest
import app.marmalade.tts.playback.Outcome
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The trivial intent shim between `ACTION_SPEAK` callers (the Speak screen
 * and previews through [app.marmalade.tts.audio.Synthesizer], the share sheet
 * through [SpeakDispatcher], Tasker) and the Narrator, until step E moves the
 * in-app callers in-process: one request id → one one-shot → exactly one
 * [PreviewCompletions] post; `ACTION_STOP_REQUEST` → cancel that one-shot.
 * External requests (id 0) post nothing — their errors are the service's
 * notification ([app.marmalade.tts.playback.NarratorState.notice]).
 *
 * A process singleton, not service state: a stop for a request must find it
 * whichever service instance received the speak.
 */
@Singleton
class SpeakShim internal constructor(
    private val narrator: NarratorControl,
    private val completions: PreviewCompletions,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(narrator: NarratorControl, completions: PreviewCompletions) :
        this(narrator, completions, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** In-app request id → the one-shot speaking it. */
    private val handles = ConcurrentHashMap<Long, Long>()
    private val outstanding = AtomicInteger()

    /** Called after every outcome, so the service can re-check whether it is idle. */
    @Volatile var onSettled: () -> Unit = {}

    /** One-shots handed over whose outcome hasn't arrived — the service stays up for them. */
    val busy: Boolean get() = outstanding.get() > 0

    fun speak(request: OneShotRequest, requestId: Long) {
        outstanding.incrementAndGet()
        val handle = narrator.speakOneShot(request)
        if (requestId != 0L) handles[requestId] = handle.id
        scope.launch {
            val outcome = try {
                handle.outcome.await()
            } finally {
                handles.remove(requestId)
                outstanding.decrementAndGet()
            }
            post(requestId, outcome)
            onSettled()
        }
    }

    /** Stop one in-app request, leaving everything else (an external read included) alone. */
    fun stop(requestId: Long) {
        handles[requestId]?.let(narrator::cancelOneShot)
    }

    private fun post(requestId: Long, outcome: Outcome) {
        when (outcome) {
            Outcome.Played, Outcome.Stopped -> completions.post(requestId, null)
            is Outcome.Failed -> completions.post(
                requestId,
                if (outcome.kind == FailureKind.MODEL_MISSING) {
                    PreviewCompletions.ErrorKind.MODEL_MISSING
                } else {
                    PreviewCompletions.ErrorKind.FAILED
                },
                outcome.message,
            )
        }
    }
}

/**
 * Brings [MarmaladeSynthService] up as a foreground service before a UI
 * command that must start audio (plan §2.4's NarratorClient): the Narrator may
 * render at once, but plays only once the host is foreground. False when the
 * OS refused the start — nothing will play.
 */
fun interface PlaybackHost {
    fun start(): Boolean
}

@Singleton
class ServicePlaybackHost @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlaybackHost {

    override fun start(): Boolean {
        val intent = Intent(context, MarmaladeSynthService::class.java)
            .setAction(MarmaladeSynthService.ACTION_HOST)
            .setPackage(context.packageName)
        return runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Log.w(TAG, "Playback host refused to start", it) }
            .isSuccess
    }

    private companion object {
        const val TAG = "PlaybackHost"
    }
}
