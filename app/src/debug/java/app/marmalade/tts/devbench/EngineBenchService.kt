package app.marmalade.tts.devbench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.marmalade.tts.R
import app.marmalade.tts.devbench.BenchSupport.TAG
import app.marmalade.tts.engine.EngineRegistry
import app.marmalade.tts.engine.TtsEngine
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// EngineBenchService — silent on-device engine benchmark, DEBUG BUILDS ONLY
// -----------------------------------------------------------------------------
//
// Renders a sentence list through one engine's synthesizeStream (speed 1.0,
// playbackRate 1.0 — what the Narrator asks for) and writes each sentence to a
// WAV. Nothing is played: no AudioTrack, no focus, no notification sound.
// Timing lands in logcat (`B1Bench` lines here, plus each engine's own
// `StreamPerf` lines), so a run is attributable from logs alone.
//
// Dev engines outside the registry: kokoro-7m-dev (issue #1),
// kitten-stream-dev (issue #3, Kitten planned once and decoded in slices) and
// kokoro-stream-dev (issue #5, the same cut for Kokoro-82M).
//
// Started from adb only — the debug manifest guards it with
// android.permission.DUMP, which the shell holds and apps don't:
//
//   adb shell am start-foreground-service \
//     -n app.marmalade.tts.debug/app.marmalade.tts.devbench.EngineBenchService \
//     --es engine kokoro-7m-dev --es voice kokoro-7m-dev:af_msa \
//     --es sentences sentences.txt --es run k7m-cool --ei passes 1
//
// `sentences` is a file under `${filesDir}/bench/` (pushed with run-as); WAVs
// go to `${filesDir}/bench/out/<run>/<idx>.wav` (first pass only).
//
// Per run: release every on-device engine (a clean memory baseline), load the
// target and log the PSS delta, let its async warm-up finish, then render.
// Between sentences it waits out the audio it just rendered (`pace`, default
// on) so each sentence starts like a fresh Speak: the Kokoro engines size
// their short first piece from the audio they think is still playing
// (PlaybackHorizon), and back-to-back requests would skew TTFA.
// -----------------------------------------------------------------------------

@AndroidEntryPoint
class EngineBenchService : Service() {

    @Inject lateinit var registry: EngineRegistry
    @Inject lateinit var kokoro7m: Kokoro7mDevEngine
    @Inject lateinit var kittenStream: KittenStreamDevEngine
    @Inject lateinit var kokoroStream: KokoroStreamDevEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (intent == null || running) {
            Log.w(TAG, "ignored start (intent=${intent != null}, running=$running)")
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val engineName = intent.getStringExtra(EXTRA_ENGINE)
        val engine = engineName?.let(::engineFor)
        val voice = intent.getStringExtra(EXTRA_VOICE)
        val sentencesFile = intent.getStringExtra(EXTRA_SENTENCES)?.let { File(benchDir(), it) }
        if (engine == null || voice == null || sentencesFile == null || !sentencesFile.isFile) {
            Log.e(TAG, "bad request engine=$engineName voice=$voice sentences=$sentencesFile")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val config = RunConfig(
            run = intent.getStringExtra(EXTRA_RUN) ?: "run",
            engine = engine,
            voice = voice,
            sentences = BenchSupport.parseSentences(sentencesFile.readText()),
            passes = intent.getIntExtra(EXTRA_PASSES, 1).coerceAtLeast(1),
            pace = intent.getBooleanExtra(EXTRA_PACE, true),
        )
        running = true
        scope.launch {
            val wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marmalade:b1bench")
            wake.acquire(WAKE_TIMEOUT_MS)
            try {
                runBench(config)
            } catch (t: Throwable) {
                Log.e(TAG, "failed run=${config.run}: ${t.message}", t)
            } finally {
                if (wake.isHeld) wake.release()
                running = false
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class RunConfig(
        val run: String,
        val engine: TtsEngine,
        val voice: String,
        val sentences: List<String>,
        val passes: Int,
        val pace: Boolean,
    )

    private fun engineFor(name: String): TtsEngine? = when (name) {
        Kokoro7mDevEngine.ENGINE -> kokoro7m
        KittenStreamDevEngine.ENGINE -> kittenStream
        KokoroStreamDevEngine.ENGINE -> kokoroStream
        else -> registry.find(name)
    }

    private fun benchDir(): File = File(filesDir, "bench")

    private suspend fun runBench(c: RunConfig) {
        val name = c.engine.engineName
        Log.i(TAG, "start run=${c.run} engine=$name voice=${c.voice} sentences=${c.sentences.size} passes=${c.passes} pace=${c.pace} ${thermal()}")

        // Clean baseline: no engine resident, then load only the target.
        (registry.onDevice + kokoro7m + kittenStream + kokoroStream).forEach { it.release() }
        System.gc()
        delay(1_500)
        val pssBeforeKb = Debug.getPss()
        val t0 = System.nanoTime()
        c.engine.ensureModelLoaded()
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        delay(WARMUP_SETTLE_MS) // the engine's async warm-up synth holds its lock meanwhile
        val pssAfterKb = Debug.getPss()
        Log.i(TAG, "load run=${c.run} engine=$name loadMs=$loadMs pssBeforeKb=$pssBeforeKb pssAfterKb=$pssAfterKb pssDeltaKb=${pssAfterKb - pssBeforeKb}")

        val outDir = File(benchDir(), "out/${c.run}").apply { mkdirs() }
        for (pass in 1..c.passes) {
            val results = ArrayList<BenchSupport.SentenceResult>()
            for ((idx, text) in c.sentences.withIndex()) {
                val pcm = ArrayList<ShortArray>()
                val start = System.nanoTime()
                var firstNs = 0L
                c.engine.synthesizeStream(text, c.voice, 1f, null, 1f).collect {
                    if (firstNs == 0L) firstNs = System.nanoTime()
                    pcm.add(it.pcm)
                }
                val totalMs = (System.nanoTime() - start) / 1_000_000
                val merged = ShortArray(pcm.sumOf { it.size }).also { out ->
                    var pos = 0
                    for (p in pcm) { p.copyInto(out, pos); pos += p.size }
                }
                val audioMs = merged.size * 1000L / c.engine.sampleRate
                val r = BenchSupport.SentenceResult(
                    index = idx,
                    chars = text.length,
                    ttfaMs = if (firstNs == 0L) -1 else (firstNs - start) / 1_000_000,
                    totalMs = totalMs,
                    audioMs = audioMs,
                )
                results += r
                Log.i(TAG, BenchSupport.sentenceLine(c.run, name, pass, r))
                if (pass == 1) {
                    File(outDir, String.format(Locale.ROOT, "%02d.wav", idx))
                        .writeBytes(BenchSupport.wavBytes(merged, c.engine.sampleRate))
                }
                if (c.pace) delay(audioMs + PACE_EXTRA_MS)
            }
            Log.i(TAG, BenchSupport.passSummaryLine(c.run, name, pass, results) + " ${thermal()}")
        }
        Log.i(TAG, "done run=${c.run} engine=$name pssKb=${Debug.getPss()} ${thermal()}")
    }

    /** Thermal state for the log: status (0 none … 6 shutdown), 10 s headroom, battery °C. */
    private fun thermal(): String {
        val pm = getSystemService(PowerManager::class.java)
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getThermalHeadroom(10) else Float.NaN
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) ?: -10) / 10.0
        return String.format(Locale.ROOT, "thermalStatus=%d headroom=%.2f batteryC=%.1f", pm.currentThermalStatus, headroom, tempC)
    }

    // Developer-only, debug-build-only notification: English on purpose (the
    // 8-locale rule covers user-visible strings, and no user build has this).
    private fun startForegroundCompat() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Engine benchmark (debug)", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_marmalade)
            .setContentTitle("Engine benchmark running")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val EXTRA_ENGINE = "engine"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_SENTENCES = "sentences"
        const val EXTRA_RUN = "run"
        const val EXTRA_PASSES = "passes"
        const val EXTRA_PACE = "pace"

        private const val CHANNEL_ID = "marmalade_debug_bench"
        private const val NOTIFICATION_ID = 0xB1
        private const val WARMUP_SETTLE_MS = 3_000L
        private const val PACE_EXTRA_MS = 250L
        private const val WAKE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
