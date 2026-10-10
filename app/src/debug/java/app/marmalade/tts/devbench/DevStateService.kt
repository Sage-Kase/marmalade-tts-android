package app.marmalade.tts.devbench

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.service.MarmaladeSynthService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// DevStateService — set the onboarding flag and the device probe, DEBUG ONLY
// -----------------------------------------------------------------------------
//
// Issue #19: onboarding's engine cards on a test phone, for a real probe and a
// forced-slow one, without wiping the app's data. Started from adb (guarded by
// android.permission.DUMP like the bench services):
//
//   adb shell am start-foreground-service \
//     -n app.marmalade.tts.debug/app.marmalade.tts.devbench.DevStateService \
//     --ez onboarded false --ef kittenRtf 0.9
//
// `onboarded` sets the onboarding-complete flag (false shows the wizard on the
// next launch). `kittenRtf` stores that Kitten benchmark RTF, as if the probe
// had measured it; a value <= 0 clears it, so the next probe measures again.
// Each extra is optional. The resulting state is logged under DevState.
//
// Issue #52: `--es speakFile <name> --es voice <voice id>` [`--ef speed <x>`]
// speaks `files/bench/<name>` in that voice through MarmaladeSynthService,
// the in-app path (Narrator, AudioTrack, wake lock, TopAppHold), as the Speak
// screen does: for timing and battery runs of in-app speech from adb.
// -----------------------------------------------------------------------------

@AndroidEntryPoint
class DevStateService : Service() {

    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BenchDevice.startForeground(this, NOTIFICATION_ID, "Setting developer state")
        scope.launch {
            try {
                if (intent?.hasExtra(EXTRA_ONBOARDED) == true) {
                    settings.setOnboarded(intent.getBooleanExtra(EXTRA_ONBOARDED, true))
                }
                if (intent?.hasExtra(EXTRA_KITTEN_RTF) == true) {
                    val rtf = intent.getFloatExtra(EXTRA_KITTEN_RTF, 0f).toDouble()
                    if (rtf > 0) {
                        settings.setKittenRtfMeasurement(rtf, System.currentTimeMillis())
                    } else {
                        settings.clearKittenRtfMeasurement()
                    }
                }
                intent?.getStringExtra(EXTRA_SPEAK_FILE)?.let { speak(it, intent) }
                Log.i(
                    TAG,
                    "state onboarded=${settings.onboarded.first()} " +
                        "kittenRtf=${settings.kittenRtfMeasurement.first()?.rtf}",
                )
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** Hands `files/bench/[name]` to the synth service as a one-shot, via a cache file (no 10 000-char clamp). */
    private fun speak(name: String, intent: Intent) {
        val text = java.io.File(filesDir, "bench/$name")
        val handoff = java.io.File(cacheDir, "dev-speak.txt")
        text.copyTo(handoff, overwrite = true)
        val speak = Intent(this, MarmaladeSynthService::class.java)
            .setAction(MarmaladeSynthService.ACTION_SPEAK)
            .putExtra(MarmaladeSynthService.EXTRA_TEXT_FILE, handoff.absolutePath)
            .putExtra(MarmaladeSynthService.EXTRA_VOICE, intent.getStringExtra(EXTRA_VOICE))
            .putExtra(MarmaladeSynthService.EXTRA_SPEED, intent.getFloatExtra(EXTRA_SPEED, 1f))
        startForegroundService(speak)
        Log.i(TAG, "speak file=$name voice=${intent.getStringExtra(EXTRA_VOICE)} chars=${text.length()}")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DevState"
        const val EXTRA_ONBOARDED = "onboarded"
        const val EXTRA_KITTEN_RTF = "kittenRtf"
        const val EXTRA_SPEAK_FILE = "speakFile"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_SPEED = "speed"
        const val NOTIFICATION_ID = 0xB3
    }
}
