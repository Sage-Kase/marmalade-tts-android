package app.marmalade.tts.devbench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import app.marmalade.tts.devbench.OnnxBenchSpec.TAG
import app.marmalade.tts.engine.EngineRegistry
import app.marmalade.tts.perf.CpuClusterDetector
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// -----------------------------------------------------------------------------
// OnnxBenchService — silent timing of ANY ONNX/ORT file, DEBUG BUILDS ONLY
// -----------------------------------------------------------------------------
//
// Issue #12 ("X1"): times random-weight model shapes (and the shipped Kitten /
// Kokoro graphs under different ONNX Runtime settings) without an engine
// around them. One start = one spec line = one session, then the process
// KILLS ITSELF, so the next start measures load time and memory from a fresh
// process. Nothing plays: outputs are dropped.
//
//   adb shell am start-foreground-service \
//     -n app.marmalade.tts.debug/app.marmalade.tts.devbench.OnnxBenchService \
//     --es spec s1-d384-b8-ctx-istft.fp32.onnx.cpu        [--es specs x1/specs.txt]
//
// The spec file (default `x1/specs.txt` under filesDir) holds one run per
// line; the grammar is in [OnnxBenchSpec]. Model and `.npy` paths are
// filesDir-relative. Lines in logcat, tag `X1Bench`:
//   start  spec + thermal
//   load   envMs (ORT init), loadMs (createSession), PSS before/after, VmHWM
//   first  the first run's ms and PSS after it
//   size   per T: first call at that shape, median/min of the timed calls,
//          PSS after, element count of output 0
//   fit    ms ≈ fixed + slope·T over the size medians (per audio second when
//          secPerT > 0)
//   done / failed
// Desktop driver and lab: ~/coding/scratch/x1-phone-timing/.
// -----------------------------------------------------------------------------

@AndroidEntryPoint
class OnnxBenchService : Service() {

    @Inject lateinit var registry: EngineRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BenchDevice.startForeground(this, NOTIFICATION_ID, "ONNX timing running")
        val name = intent?.getStringExtra(EXTRA_SPEC)
        if (name == null || running) {
            Log.w(TAG, "ignored start (spec=$name, running=$running)")
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        scope.launch {
            val wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "marmalade:x1bench")
            wake.acquire(WAKE_TIMEOUT_MS)
            try {
                val specFile = File(filesDir, intent.getStringExtra(EXTRA_SPECS) ?: DEFAULT_SPECS)
                val spec = OnnxBenchSpec.find(specFile.readText(), name)
                    ?: throw IllegalArgumentException("no spec '$name' in $specFile")
                runSpec(spec)
                Log.i(TAG, "done name=${spec.name} ${memLine()} ${BenchDevice.thermal(this@OnnxBenchService)}")
            } catch (t: Throwable) {
                Log.e(TAG, "failed name=$name err=${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')}", t)
            } finally {
                if (wake.isHeld) wake.release()
                // A fresh process per spec: the next load and PSS start clean.
                delay(300)
                Process.killProcess(Process.myPid())
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runSpec(s: OnnxBenchSpec.Spec) {
        val threads = s.threads ?: CpuClusterDetector.detectPerfCoreCount()
        Log.i(
            TAG,
            "start name=${s.name} model=${s.model} ep=${s.ep} threads=$threads opt=${s.opt} arena=${s.arena} " +
                "mempattern=${s.memPattern} prepack=${s.prepack} spin=${s.spin} mmap=${s.mmap} shrink=${s.shrink} " +
                "sizes=${s.sizes.joinToString(",")} calls=${s.calls} ${BenchDevice.thermal(this)}",
        )
        // App start may have warmed an engine (keepalive): drop it, settle, then baseline.
        delay(SETTLE_MS)
        registry.onDevice.forEach { it.release() }
        System.gc()
        delay(1_000)

        val t0 = System.nanoTime()
        val env = OrtEnvironment.getEnvironment()
        val envMs = ms(t0)
        val pssBaseKb = Debug.getPss()
        val modelFile = File(filesDir, s.model)
        require(modelFile.isFile) { "model missing: $modelFile" }
        val t1 = System.nanoTime()
        val session = sessionOptions(s, threads).use { env.createSession(modelFile.absolutePath, it) }
        val loadMs = ms(t1)
        Log.i(
            TAG,
            "load name=${s.name} envMs=${fmt(envMs)} loadMs=${fmt(loadMs)} fileKb=${modelFile.length() / 1024} " +
                "pssBaseKb=$pssBaseKb ${memLine()}",
        )
        session.use {
            val missing = session.inputNames - s.inputs.keys
            val extra = s.inputs.keys - session.inputNames
            require(missing.isEmpty() && extra.isEmpty()) {
                "inputs don't match: model wants ${session.inputNames}, missing $missing, unknown $extra"
            }
            val runOptions = OrtSession.RunOptions().apply {
                if (s.shrink) addRunConfigEntry("memory.enable_memory_arena_shrinkage", "cpu:0")
            }
            runOptions.use { timeSizes(env, session, it, s) }
        }
    }

    private fun timeSizes(env: OrtEnvironment, session: OrtSession, runOptions: OrtSession.RunOptions, s: OnnxBenchSpec.Spec) {
        val medians = ArrayList<Double>()
        var first = true
        for (t in s.sizes) {
            val inputs = s.inputs.entries.mapIndexed { i, (inputName, source) ->
                inputName to tensor(env, OnnxBenchSpec.build(source, t, SEED + i) { File(filesDir, it).readBytes() })
            }.toMap()
            try {
                var outElems = 0L
                fun call(): Double {
                    val c0 = System.nanoTime()
                    session.run(inputs, runOptions).use { r ->
                        outElems = (r[0] as? OnnxTensor)?.info?.shape?.fold(1L) { a, d -> a * d } ?: -1
                    }
                    return ms(c0)
                }
                val firstMs = call()
                if (first) {
                    Log.i(TAG, "first name=${s.name} T=$t firstCallMs=${fmt(firstMs)} ${memLine()}")
                    first = false
                }
                val timed = List(s.calls) { call() }
                medians += BenchSupport.median(timed)
                Log.i(TAG, OnnxBenchSpec.sizeLine(s.name, t, s.secPerT, firstMs, timed, Debug.getPss(), outElems))
            } finally {
                inputs.values.forEach { it.close() }
            }
        }
        Log.i(TAG, OnnxBenchSpec.fitLine(s.name, s.secPerT, s.sizes, medians))
    }

    private fun sessionOptions(s: OnnxBenchSpec.Spec, threads: Int) = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(
            when (s.opt) {
                OnnxBenchSpec.Opt.ALL -> OrtSession.SessionOptions.OptLevel.ALL_OPT
                OnnxBenchSpec.Opt.EXTENDED -> OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
                OnnxBenchSpec.Opt.BASIC -> OrtSession.SessionOptions.OptLevel.BASIC_OPT
                OnnxBenchSpec.Opt.NONE -> OrtSession.SessionOptions.OptLevel.NO_OPT
            },
        )
        setMemoryPatternOptimization(s.memPattern)
        setCPUArenaAllocator(s.arena)
        addConfigEntry("session.intra_op.allow_spinning", if (s.spin) "1" else "0")
        if (!s.prepack) addConfigEntry("session.disable_prepacking", "1")
        if (s.mmap) {
            addConfigEntry("session.use_memory_mapped_ort_model", "1")
            addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
        }
        if (s.ep == OnnxBenchSpec.Ep.XNNPACK) addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
    }

    private fun tensor(env: OrtEnvironment, h: OnnxBenchSpec.HostTensor): OnnxTensorLike = when (val d = h.data) {
        is FloatArray -> OnnxTensor.createTensor(env, FloatBuffer.wrap(d), h.shape)
        is LongArray -> OnnxTensor.createTensor(env, LongBuffer.wrap(d), h.shape)
        is IntArray -> OnnxTensor.createTensor(env, IntBuffer.wrap(d), h.shape)
        else -> error("unsupported host tensor ${d.javaClass}")
    }

    /** PSS split (total, native heap, other incl. mapped files) and peak/current RSS. */
    private fun memLine(): String {
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val status = runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
        fun vm(key: String) = status.firstOrNull { it.startsWith("$key:") }?.filter(Char::isDigit) ?: "?"
        return "pssKb=${mi.totalPss} nativePssKb=${mi.getMemoryStat("summary.native-heap")} " +
            "otherPssKb=${mi.getMemoryStat("summary.private-other")} systemPssKb=${mi.getMemoryStat("summary.system")} " +
            "vmHwmKb=${vm("VmHWM")} vmRssKb=${vm("VmRSS")}"
    }

    private fun ms(since: Long): Double = (System.nanoTime() - since) / 1e6

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

    companion object {
        const val EXTRA_SPEC = "spec"
        const val EXTRA_SPECS = "specs"

        private const val DEFAULT_SPECS = "x1/specs.txt"
        private const val NOTIFICATION_ID = 0xB2
        private const val SETTLE_MS = 2_500L
        private const val SEED = 12L
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
