package app.marmalade.tts.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.pocket.NpyReader
import app.marmalade.tts.engine.pocket.PocketAudio
import app.marmalade.tts.engine.pocket.PocketBundle
import app.marmalade.tts.engine.pocket.PocketClonedVoice
import app.marmalade.tts.engine.pocket.PocketClonedVoiceStore
import app.marmalade.tts.engine.pocket.PocketClonedVoiceSummary
import app.marmalade.tts.engine.pocket.PocketStates
import app.marmalade.tts.engine.pocket.PocketTokenizer
import app.marmalade.tts.engine.pocket.initStates
import app.marmalade.tts.engine.pocket.PocketCacheShapes
import app.marmalade.tts.engine.pocket.PocketMimiBatch
import app.marmalade.tts.engine.pocket.PocketDecodeCost
import app.marmalade.tts.engine.pocket.PocketSessionPlan
import app.marmalade.tts.engine.pocket.PocketSliceBank
import app.marmalade.tts.engine.pocket.PocketStreamPlan
import app.marmalade.tts.engine.pocket.PocketStateCarry
import app.marmalade.tts.engine.pocket.StateFrame
import app.marmalade.tts.engine.pocket.PocketTimes
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pocket TTS engine — Kyutai Labs' Latent Space Diffusion TTS running
 * directly on `com.microsoft.onnxruntime:onnxruntime-android`.
 *
 * v0.3.0-alpha.2 lights up real synthesis with the full 5-graph LSD
 * pipeline:
 *
 *   text → tokenize → text_conditioner → text_embeddings [1,T,1024]
 *   voice WAV → resample 24kHz → mimi_encoder → voice_emb [1,V,1024]
 *               (cached to disk after first encode)
 *
 *   flow_lm phase 1 (voice cond):  sequence=[1,0,32]  +  text_embeds=BOS++voice_emb
 *   flow_lm phase 2 (text cond):   sequence=[1,0,32]  +  text_embeds=text_embeds
 *   flow_lm phase 3 (autoregressive, per latent frame):
 *      sequence=[1,1,32] (NaN→BOS first step, then previous latent)
 *      → conditioning [1,1024] + eos_logit [1,1]
 *      → Euler-integrated flow_lm_flow: x_{t+1} = x_t + flow(c,s,t,x)·(1/steps)
 *      → emit latent, check EOS
 *   mimi_decoder(all latents) → float PCM @ 24 kHz → PCM16
 *
 * Voice cloning (v0.3.0): users supply WAV; same `mimi_encoder` path,
 * with the resulting embedding written to `cloned_voices/<id>.bin`.
 *
 * Why a separate class from the other direct engines: different model
 * topology (5 graphs vs 1), a stateful streaming codec, and voice
 * cloning. The shared surface is [TtsEngine] — same contract Synthesizer
 * + the two TTS services route through.
 *
 * Thread-safety: model loading is gated by a coroutine [Mutex]; synthesis
 * itself is gated by [Mutex.withLock] so concurrent `synthesize` calls
 * serialise (the model's KV cache state isn't reentrant).
 */
@Singleton
open class PocketEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settingsRepository: app.marmalade.tts.data.SettingsRepository,
) : TtsEngine {

    override val engineName: String = ENGINE_NAME

    override val sampleRate: Int
        get() = bundle?.sampleRate ?: PocketVoiceCatalog.SAMPLE_RATE

    /**
     * Pocket handles its own chunking internally via [chunkPocketByTokens],
     * which is sentence-aware and token-aware (vs the Synthesizer's outer
     * char-based [app.marmalade.tts.audio.TextChunker], which would have
     * to guess at tokenizer behavior). We advertise no limit so the
     * Synthesizer hands us the full input string in one call.
     */
    override val maxInputChars: Int = Int.MAX_VALUE

    /**
     * Pocket's five ONNX graphs are autoregressive and expose no speed
     * input — nothing in the pipeline can change the rate. The services
     * time-stretch the output instead; see [TtsEngine.supportsNativeSpeed].
     */
    override val supportsNativeSpeed: Boolean = false

    private val engineDir: File get() = File(ctx.filesDir, "engines/$ENGINE_NAME")
    private val voicesDir: File get() = File(engineDir, "voices")
    private val voiceCacheDir: File get() = File(engineDir, "voice_cache")
    private val clonedVoicesDir: File get() = File(engineDir, "cloned_voices")

    private val loadLock = Mutex()
    private val synthLock = Mutex()

    /**
     * Raised by [release] so an in-flight synth (which holds [synthLock]
     * for the whole utterance) aborts at its next per-frame check instead
     * of making release wait out the full AR loop.
     */
    private val releaseRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    // -- live state (null while not loaded) ----------------------------------

    @Volatile private var env: OrtEnvironment? = null
    private var bundle: PocketBundle? = null
    private var tokenizer: PocketTokenizer? = null

    private var textCondSession: OrtSession? = null
    private var mimiEncoderSession: OrtSession? = null
    private var mimiDecoderSession: OrtSession? = null
    private var flowLmMainSession: OrtSession? = null
    private var flowLmFlowSession: OrtSession? = null

    /**
     * mimi_decoder's init state (56 slots), allocated once at load. Every
     * chunk's decode starts from it through a [PocketStateCarry]; ORT only
     * reads graph inputs, so it is never refilled (#15 "A").
     */
    private var mimiState: PocketStates? = null

    /**
     * mimi state shapes to initialise from: the bundle's manifest, or ring
     * buffers resized for a decoder with a symbolic ring axis (#15 "F"; debug
     * knob `pocket.mimiCap`).
     */
    private var mimiManifest: List<PocketBundle.StateSpec> = emptyList()

    /**
     * The flow_lm init states chunks start from (read-only, like
     * [mimiState]), by KV-cache length: one entry for the shipped graph, one
     * per voice-sized length for a graph with a symbolic cache axis.
     */
    private val flowInitStates = HashMap<Int, PocketStates>()

    /**
     * #15 "F": flow_lm_main's cache axis is symbolic (a `*_dyncache`
     * export), so each voice gets a cache just long enough for its prompt
     * plus one chunk ([PocketCacheShapes.flowLenFor]) instead of 1000 rows
     * that every AR step copies. The shipped graph is fixed at 1000.
     */
    private var flowCacheDynamic = false
    private var flowCacheLenKnob: Int? = null

    /**
     * #15 "C": flow_lm's state right after the voice prompt (phase 1), for
     * the last voice used, so later chunks skip re-running the prompt. Phase
     * 1 has no randomness, so starting from it is exact. One voice only
     * (~49 MB native with the shipped 1000-row cache). Touched under [synthLock].
     */
    private var voicePrompt: VoicePrompt? = null

    private class VoicePrompt(val embedding: FloatArray, val cacheLen: Int?, val frame: StateFrame)

    /**
     * Latent frames per mimi_decoder run (#15 "D"): consecutive batches no
     * larger than [PocketMimiBatch.maxExactBatch], so every frame decodes
     * exactly as it would alone. Debug knob `pocket.decode` = a smaller
     * frame count.
     */
    private var decodeBatch = 0

    /**
     * #15 "E": decode and send audio in slices while the AR loop runs
     * ([streamChunks]). [STREAM_OVERLAP] decodes on a second thread, so the
     * decoder's time hides behind the next frames' AR steps. Debug knob
     * `pocket.stream` = `off` | `inline` | `overlap`.
     */
    private var streamMode = STREAM_OFF

    /** The decoder's measured cost per slice size, for the first-sound hold (decoder thread only). */
    private val decodeCost = PocketDecodeCost()

    /** Audio per text token (EWMA), to estimate a request's length for the hold. */
    @Volatile private var streamMsPerToken = DEFAULT_MS_PER_TOKEN

    /** Debug knob `pocket.ortProfile`: requests left before ORT's profiler is closed. */
    private var profileRequestsLeft = 0

    /** Debug knob `pocket.seed`: reseeds the noise at each request, for bit-exact A/B checks. */
    private var seed: Long? = null

    /** `[1, 1, 1024]` learned embedding prepended to the voice prompt. */
    private var bosBeforeVoice: FloatArray? = null

    /** In-memory cache of voice embeddings (built-in + user-cloned). */
    private val voiceEmbeddings: ConcurrentHashMap<String, FloatArray> = ConcurrentHashMap()

    /**
     * P-D: async warmup. See KittenDirectEngine for the rationale —
     * Pocket's warmup is heavier (~1-2 s, runs a real 2-frame AR step
     * + mimi decode), so hiding it behind the Speak-screen mount → tap
     * gap is the highest-impact engine of the three.
     */
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var warmupJob: Job? = null

    override fun isInstalled(): Boolean {
        if (!engineDir.isDirectory) return false
        // Always-present, variant-independent files.
        for (name in REQUIRED_NON_ONNX_FILES) {
            if (!File(engineDir, name).isFile) return false
        }
        // ONNX filenames vary per bundle variant — read them from bundle.json
        // and verify each one exists. Parse failures fall through to false.
        val bundleSpec = runCatching {
            PocketBundle.load(File(engineDir, "bundle.json"))
        }.getOrNull() ?: return false
        val onnxFiles = bundleSpec.onnxFiles
        val requiredOnnx = listOf(
            onnxFiles.textConditioner,
            onnxFiles.mimiEncoder,
            onnxFiles.mimiDecoder,
            onnxFiles.flowLmMain,
            onnxFiles.flowLmFlow,
        )
        for (name in requiredOnnx) {
            if (!File(engineDir, name).isFile) return false
        }
        if (!voicesDir.isDirectory) return false
        for (voice in PocketVoiceCatalog.voices) {
            // WAV filenames are the lowercase voice key from the id, not the
            // capitalized displayName.
            val key = voice.id.substringAfterLast(':')
            if (!File(voicesDir, "$key.wav").isFile) return false
        }
        return true
    }

    /**
     * [env] is `doLoad`'s publish-last marker — the same field
     * [ensureModelLoaded]'s unlocked fast path treats as "fully loaded".
     */
    override fun isLoaded(): Boolean = env != null

    override fun ensureModelLoaded() {
        // ensureModelLoaded is declared non-suspend by TtsEngine to keep
        // the system-TTS callback path simple. We bridge to the suspend
        // loadLock by running blocking on Dispatchers.IO inside synth's
        // own coroutine. The volatile field check makes the hot path
        // zero-cost after first load.
        if (env != null) return
        kotlinx.coroutines.runBlocking { ensureLoadedSuspending() }
    }

    private suspend fun ensureLoadedSuspending() {
        if (env != null) return
        loadLock.withLock {
            if (env != null) return
            if (!isInstalled()) throw EngineNotInstalledException(ENGINE_NAME)
            Log.i(TAG, "Loading Pocket TTS bundle from $engineDir …")
            val t0 = System.currentTimeMillis()
            // Resolve intra-op thread count: manual override takes priority,
            // else the CPU-cluster autodetect runs. Done here (suspend
            // context) so doLoad stays non-suspend.
            val manualThreads: Int? = settingsRepository.intraOpThreads.firstOrNull()
            val threadCount = EngineDevKnobs.get(ctx.filesDir, "threads")?.toIntOrNull()
                ?: manualThreads
                ?: app.marmalade.tts.perf.CpuClusterDetector.detectPerfCoreCount()
            try {
                doLoad(threadCount)
                val elapsed = System.currentTimeMillis() - t0
                Log.i(TAG, "Pocket TTS loaded in ${elapsed} ms")
            } catch (t: Throwable) {
                // Best-effort cleanup so a half-loaded engine doesn't
                // leak across retries.
                releaseInternal()
                throw IllegalStateException("Failed to load Pocket TTS: ${t.message}", t)
            }
        }
    }

    private fun doLoad(intraOpThreads: Int) {
        // Build everything against the local [ort]; publish to [env] only
        // at the END. ensureModelLoaded's unlocked fast path treats
        // env != null as "fully loaded", so an early publish let a
        // concurrent first synth see a half-loaded engine ("bundle
        // missing after load"). Same rationale as KittenDirectEngine /
        // KokoroDirectEngine.doLoad.
        val ort = OrtEnvironment.getEnvironment()
        bundle = PocketBundle.load(File(engineDir, "bundle.json"))
        tokenizer = PocketTokenizer.load(File(engineDir, "tokenizer.model"))

        // bos_before_voice.npy: shape [1,1,1024] float32. Stash the flat
        // 1024-d vector — we'll concat along the time axis when needed.
        val npy = NpyReader.readFloat32(File(engineDir, "bos_before_voice.npy"))
        check(npy.data.size == bundle!!.conditioningDim) {
            "bos_before_voice.npy has ${npy.data.size} floats; expected ${bundle!!.conditioningDim}"
        }
        bosBeforeVoice = npy.data

        // ORT SessionOptions. Thread count is per-device — see
        // CpuClusterDetector for the autodetect heuristic. Empirical
        // calibration on Pixel 8a (Tensor G3):
        //   intra-op=1:  ~155 ms/frame
        //   intra-op=4:  ~99 ms/frame
        //   intra-op=6:  ~80 ms/frame  (NekoSpeak default for Tensor G3)
        // Tensor G3 = 1×X3 + 4×A715 + 4×A510. The autodetect picks the 4
        // A715s: a screen-off reader can't use the X3 (see
        // CpuClusterDetector; #13 "C"). Pocket on the 8a, B1 set, screen
        // off: RTF 2.12 with 4 threads vs 2.22 with 5. Snapdragon Gen 2/3
        // and Exynos 2400 have different topologies — the detector
        // handles each.
        // Users can override via Settings → Performance → Thread count.
        Log.i(TAG, "ORT intraOpNumThreads=$intraOpThreads (manual setting or autodetect)")
        // P-Q: flow_lm_flow is a 1-frame Euler net — over-parallelising it
        // wakes the whole pool just to do a small matmul, so it gets half
        // the threads (at least 2). Per-graph settings and their debug A/B
        // knobs: [PocketSessionPlan].
        val knob = { key: String -> EngineDevKnobs.get(ctx.filesDir, key) }
        val plan = PocketSessionPlan.resolve(intraOpThreads, knob)
        profileRequestsLeft = knob("pocket.ortProfile")?.toIntOrNull() ?: 0
        val profileDir = if (profileRequestsLeft > 0) File(ctx.filesDir, "bench/ortprof").apply { mkdirs() } else null
        seed = knob("pocket.seed")?.toLongOrNull()
        flowCacheLenKnob = knob("pocket.cacheLen")?.toIntOrNull()
        val mimiCapKnob = knob("pocket.mimiCap")?.toIntOrNull()
        streamMode = when (knob("pocket.stream")) {
            "inline" -> STREAM_INLINE
            "overlap" -> STREAM_OVERLAP
            else -> STREAM_OFF
        }
        val decodeKnob = knob("pocket.decode")

        val files = bundle!!.onnxFiles
        Log.i(TAG, "Loading Pocket bundle (quantization=${bundle!!.quantizationVariant}): " +
            "text_cond=${files.textConditioner}, mimi_enc=${files.mimiEncoder}, " +
            "mimi_dec=${files.mimiDecoder}, flow_main=${files.flowLmMain}, " +
            "flow_flow=${files.flowLmFlow}")
        fun logPlan() = Log.i(TAG, "session plan: " + plan.entries.joinToString(" ") { (g, c) ->
            "$g=${c.threads}t${if (c.xnnpack) "+xnn" else ""}${if (c.spin) "+spin" else ""}"
        } + (if (profileDir != null) " ortProfile=$profileRequestsLeft" else "") +
            (seed?.let { " seed=$it" } ?: "") + " decode=$decodeBatch stream=$streamMode" +
            " cacheLen=${flowCacheLenKnob ?: if (flowCacheDynamic) "per-voice" else "1000"}" +
            " mimiCap=${PocketMimiBatch.ringCapacity(mimiManifest)}")
        fun session(graph: String, fileName: String): OrtSession {
            // ORT copies the options into the native session at creation, so
            // they're closed right after — otherwise every keepalive
            // evict/reload leaked the native OrtSessionOptions.
            return buildPocketSessionOptions(plan.getValue(graph), graph, profileDir).use { opts ->
                createSession(ort, opts, fileName)
            }
        }
        // Debug knob `pocket.file.<graph>`: load another file from the engine
        // dir (#15 "F": model-file prototypes sideloaded beside the bundle).
        fun file(graph: String, default: String) = knob("pocket.file.$graph") ?: default
        textCondSession = session("text", file("text", files.textConditioner))
        mimiEncoderSession = session("enc", file("enc", files.mimiEncoder))
        mimiDecoderSession = session("dec", file("dec", files.mimiDecoder))
        flowLmMainSession = session("main", file("main", files.flowLmMain))
        flowLmFlowSession = session("flow", file("flow", files.flowLmFlow))

        // #15 "F": graphs exported with a symbolic cache axis take shorter
        // caches (a cache's declared shape has -1 there; the shipped ones say 1000).
        fun dynamic(session: OrtSession, manifest: List<PocketBundle.StateSpec>, axis: Int) =
            manifest.firstOrNull { it.shape.size == 5 && !it.path.startsWith("encoder") }?.let { spec ->
                ((session.inputInfo[spec.inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape?.getOrNull(axis) ?: 0L) < 0L
            } ?: false
        flowCacheDynamic = dynamic(flowLmMainSession!!, bundle!!.flowLmStateManifest, 2)
        val mimiCap = mimiCapKnob
            ?: if (dynamic(mimiDecoderSession!!, bundle!!.mimiStateManifest, 3)) PocketCacheShapes.DYNAMIC_MIMI_CAPACITY else null
        mimiManifest = mimiCap?.let { PocketCacheShapes.mimi(bundle!!.mimiStateManifest, it) } ?: bundle!!.mimiStateManifest
        val exact = PocketMimiBatch.maxExactBatch(PocketMimiBatch.ringCapacity(mimiManifest))
        decodeBatch = (decodeKnob?.toIntOrNull() ?: exact).coerceIn(1, exact)
        logPlan()

        mimiState = initStates(mimiManifest)

        // Publish only after every field a sibling caller's synth path
        // touches is non-null. Warmup launches after the publish because
        // warmupSynth reads [env] itself.
        env = ort

        // P-D: warmup runs async — see KittenDirectEngine for details.
        warmupJob = warmupScope.launch {
            synthLock.withLock { warmupSynth() }
        }
    }

    /**
     * One graph's OrtSession.SessionOptions ([PocketSessionPlan] decides
     * threads, XNNPACK and spinning per graph). [profileDir] non-null turns
     * on ORT's per-node profiler for the `pocket.ortProfile` debug knob.
     */
    private fun buildPocketSessionOptions(
        cfg: app.marmalade.tts.engine.pocket.PocketGraphConfig,
        label: String,
        profileDir: File?,
    ): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(cfg.threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Memory-pattern optimization: disabling it tanks RTF. (Its
            // interaction with output pinning, P-V, caused a chunk-boundary
            // glitch; pinning was reverted and later removed, #15.)
            setMemoryPatternOptimization(true)
            // P-T: flip [VERBOSE_ORT_DIAGNOSTIC] to true and run one synth to
            // capture node placement in logcat (filter `adb logcat -s onnxruntime`).
            // Confirms which ops fall back to CPU EP (looking for
            // `DynamicQuantizeLinear`, fused `Attention`, anything tagged
            // "fallback to CPU EP" / "Node placed on"). Flip back to false after
            // capture — VERBOSE per-run logging swamps logcat.
            if (VERBOSE_ORT_DIAGNOSTIC) {
                setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
                setSessionLogVerbosityLevel(0)
                Log.w(TAG, "ORT verbose diagnostic ENABLED for $label — disable after capture")
            }
            if (profileDir != null) enableProfiling(File(profileDir, label).absolutePath)
            // Spinning keeps the intra-op threads hot between the many
            // short calls of the AR loop instead of paying a wake-up each.
            // XNNPACK has its own pthread pool, and spinning ORT threads
            // fight it for cores (ORT warns about it).
            addConfigEntry("session.intra_op.allow_spinning", if (cfg.spin) "1" else "0")
            // XNNPACK execution provider. ORT falls back to the CPU EP for
            // any op XNNPACK doesn't support (flow_lm_main's MatMulInteger
            // among them). Wrapped because XNNPACK is optional in some
            // onnxruntime-android builds; without it the CPU EP runs alone.
            if (cfg.xnnpack) {
                try {
                    addXnnpack(mapOf("intra_op_num_threads" to cfg.threads.toString()))
                } catch (t: Throwable) {
                    Log.w(TAG, "XNNPACK EP unavailable for $label; using CPU EP only", t)
                }
            }
        }
    }

    /** Debug knob `pocket.ortProfile`: close every session's profile and log where it went. */
    private fun endOrtProfiling() {
        for ((name, s) in listOf(
            "text" to textCondSession, "enc" to mimiEncoderSession, "dec" to mimiDecoderSession,
            "main" to flowLmMainSession, "flow" to flowLmFlowSession,
        )) {
            runCatching { s?.endProfiling() }
                .onSuccess { Log.i(PERF_TAG, "pocket ortProfile $name=$it") }
                .onFailure { Log.w(TAG, "endProfiling($name) failed: ${it.message}") }
        }
    }

    /**
     * Run a tiny throwaway synth at the end of [doLoad] to amortize
     * ORT's first-inference JIT/kernel-compile cost. Without this,
     * the first user-triggered synth pays an extra ~500 ms before any
     * audio comes back. With this, that cost is folded into engine
     * load (which the user already waits for once per process).
     *
     * What we warm up:
     *   - mimi_encoder (via [embeddingForVoice] on first voice)
     *   - text_conditioner + flow_lm_main + flow_lm_flow (via 2 AR steps)
     *   - mimi_decoder (via [runMimiDecoder])
     *
     * Failures here are non-fatal — the engine still loads, just
     * without warm caches. Logged so we know if a build silently
     * regressed warmup.
     */
    private fun warmupSynth() {
        val tStart = System.currentTimeMillis()
        try {
            val bundle = bundle ?: return
            val tokenizer = tokenizer ?: return
            val voiceName = bundle.predefinedVoices.firstOrNull() ?: return
            val voiceEmb = embeddingForVoice(voiceName)
            val preprocessed = preprocessForPocket("Hi.", bundle)
            val tokens = tokenizer.encode(preprocessed)
            val phases = ArrayList<PhaseSpan>(2)
            val ar = startArSession(bundle, voiceEmb, tokens, preprocessed, phases = phases)
            for (p in phases) {
                Log.d(PERF_TAG, "pocket warmup prefill ${p.name}=${p.ms}ms${p.detail?.let { " ($it)" } ?: ""}")
            }
            val latents = ArrayList<FloatArray>(3)
            try {
                repeat(2) {
                    val step = stepAr(ar) ?: return@repeat
                    latents.add(step.latent)
                }
            } finally {
                ar.carry.close()
            }
            if (latents.isNotEmpty()) {
                val flat = FloatArray(latents.size * bundle.latentDim)
                var pos = 0
                for (l in latents) {
                    System.arraycopy(l, 0, flat, pos, bundle.latentDim)
                    pos += bundle.latentDim
                }
                runMimiDecoder(bundle, flat, mimiState ?: return)
            }
            Log.i(TAG, "Pocket warmup synth completed in ${System.currentTimeMillis() - tStart} ms")
        } catch (t: Throwable) {
            Log.w(TAG, "Pocket warmup failed (non-fatal): ${t.message}")
        }
    }

    /**
     * Load an ONNX session, applying the ARMv7 byte-array workaround on
     * 32-bit devices. ARMv7 traps SIGBUS (BUS_ADRALN) on misaligned
     * 32-bit loads, which fires for INT8 ONNX tensors whose data offsets
     * end up at 1-byte alignment when ORT mmaps the file. Loading the
     * model into a heap-allocated `ByteArray` produces 4-byte alignment
     * via the JVM heap allocator and dodges the trap. arm64-v8a handles
     * misaligned access in hardware so mmap is fine there.
     *
     * Cost: ~50 MB transient heap during 5-session load on armeabi-v7a.
     * Pixel-class arm64 devices pay nothing.
     */
    private fun createSession(
        ort: OrtEnvironment,
        opts: OrtSession.SessionOptions,
        fileName: String,
    ): OrtSession {
        val file = File(engineDir, fileName)
        return if (is32BitArm()) {
            ort.createSession(file.readBytes(), opts)
        } else {
            ort.createSession(file.absolutePath, opts)
        }
    }

    private fun is32BitArm(): Boolean {
        val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        return primary == "armeabi-v7a" || primary == "armeabi"
    }

    /**
     * Synthesize the full input as one merged PCM buffer. Delegates to
     * [synthesizeStream] + concat — the streaming path chunks input
     * internally via [TextChunker] to respect Pocket's 50-token-per-
     * AR-session limit. Going through it from `synthesize` too means
     * every caller (the TTS services, the bench's old batched mode,
     * anyone passing full text in one call) gets correct behaviour
     * without needing to know the chunking rules.
     *
     */
    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio = withContext(Dispatchers.Default) {
        // Pocket does its own phonemization upstream of ORT; the espeak
        // language knob doesn't apply.
        val parts = ArrayList<ShortArray>()
        var sampleRate = 0
        synthesizeStream(text, voiceId, speed, phonemizationLanguage).collect { chunk ->
            parts.add(chunk.pcm)
            sampleRate = chunk.sampleRate
        }
        if (parts.isEmpty()) {
            return@withContext SynthAudio(
                pcm = ShortArray(0),
                sampleRate = bundle?.sampleRate ?: PocketVoiceCatalog.SAMPLE_RATE,
            )
        }
        val total = parts.sumOf { it.size }
        val merged = ShortArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, merged, pos, p.size)
            pos += p.size
        }
        SynthAudio(pcm = merged, sampleRate = sampleRate)
    }

    /**
     * Streaming variant — per-chunk batched pipeline with adaptive
     * pre-roll. Architecture (alpha.7, replaces the within-chunk
     * frame-streaming pattern that stuttered under sub-realtime
     * generation):
     *
     *   text ─► chunkPocketByTokens ─► mini-chunks (≤ MAX_TOKENS each)
     *           (sentence-aware,            │
     *            token-counted)             ▼
     *                              ┌────────────────────────┐
     *                              │ For each mini-chunk:   │
     *                              │   - fresh flow_lm state│
     *                              │   - fresh mimi state   │  ← matches upstream
     *                              │   - run AR to EOS      │     _decode_audio_worker
     *                              │   - mimi_decode whole  │
     *                              │   - emit 1 SynthAudio  │
     *                              └────────────────────────┘
     *
     * Each emitted SynthAudio is a fully-formed PCM buffer; the
     * AudioTrack consumer plays it without underrun. Inter-chunk
     * gaps only appear if the next chunk isn't ready when the
     * current chunk's audio drains.
     *
     * Adaptive pre-roll: while generating chunk 0, we measure the
     * wall-time of frames 2..5 (skipping frame 0 — which carries
     * ORT JIT/warmup cost — and frame 1 conservatively). The
     * MAX of those samples is our per-frame steady-state estimate.
     * From that we compute K = how many chunks to buffer before
     * the first emit:
     *
     *   deficitMs = max(0, perFrameMaxMs − frameBudgetMs)
     *   K = ceil(totalChunks × deficitMs / frameBudgetMs), capped at 3
     *
     * If gen is at or above realtime (deficitMs == 0), K = 1 and
     * chunks emit as they're ready. If gen is sub-realtime, K grows
     * so the buffer never fully drains before all chunks complete.
     * TTFA pays the K×gen cost; we cap K at 3 so TTFA stays bounded
     * (≈ 3 × 3 s gen on slow hardware).
     */
    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
        playbackRate: Float,
    ): Flow<SynthAudio> = channelFlow {
        // TTFA diagnostic — see KittenDirectEngine for the rationale.
        val streamStartNs = System.nanoTime()
        ensureLoadedSuspending()
        val loadWaitMs = (System.nanoTime() - streamStartNs) / 1_000_000
        val bundle = bundle ?: error("bundle missing after load")
        val tokenizer = tokenizer ?: error("tokenizer missing after load")

        // `speed` is deliberately unused: the AR graphs have no speed input.
        // The services time-stretch instead — see [supportsNativeSpeed].

        val voiceName = voiceId.substringAfter(':', voiceId)

        // Chunking parity with KokoroDirect: sentence-only splits, never
        // mid-word, pack tiny adjacent sentences via `minChars` so a 5-char
        // "Yes." doesn't waste an AR session as its own chunk. Pocket used
        // to chunk by tokens (cap 25), which produced orphan fragments
        // ("Outside,") when a long sentence got comma-split — every
        // chunk boundary breaks Pocket's prosody seed, so it sounded
        // choppy. Post-process pass below enforces the model's hard
        // token limit on the rare 255-char chunk that exceeds it.
        val preprocessed = preprocessForPocket(text.trim(), bundle)
        val outerChunks = TextChunker.chunk(
            text = preprocessed,
            maxChars = POCKET_MAX_CHARS_PER_CHUNK,
            packSentences = false,
            sentenceOnly = true,
            allowWordSplits = false,
            minChars = POCKET_MIN_CHARS_PER_CHUNK,
        )
        val miniChunks = ArrayList<String>(outerChunks.size)
        for (chunk in outerChunks) {
            val tokenCount = tokenizer.encode(chunk).size
            if (tokenCount <= bundle.maxTokenPerChunk) {
                miniChunks.add(chunk)
            } else {
                // Safety net: a single sentence packs more tokens than the
                // model can handle. Fall back to the old token-aware
                // sub-splitter (commas → words) for this chunk only.
                Log.d(TAG, "outer chunk has $tokenCount tokens > ${bundle.maxTokenPerChunk}; sub-splitting")
                miniChunks.addAll(chunkPocketByTokens(chunk, tokenizer, bundle, bundle.maxTokenPerChunk))
            }
        }
        if (miniChunks.isEmpty()) return@channelFlow
        Log.d(TAG, "synthesizeStream: ${miniChunks.size} mini-chunk(s) from ${outerChunks.size} outer chunk(s)")
        for ((i, c) in miniChunks.withIndex()) {
            Log.d(TAG, "  chunk[$i] (${c.length} chars): \"${c.take(80)}${if (c.length > 80) "…" else ""}\"")
        }

        // Everything that touches an ORT session runs under synthLock, and the
        // pipelined mimi decode below is a child of this inner coroutineScope
        // rather than of the channelFlow's producer scope. That way, when the
        // AR loop throws (Stop's ensureActive, the release check), the scope
        // cancels and then WAITS for an in-flight decode before withLock
        // releases — otherwise release() could close mimi_decoder mid-run()
        // (native SIGSEGV).
        synthLock.withLock { coroutineScope {
            // Under the lock: a first-use voice encodes through mimi_encoder.
            val voiceEmb = embeddingForVoice(voiceName)
            seed?.let { random.setSeed(it) }
            if (streamMode != STREAM_OFF) {
                streamChunks(this@channelFlow, bundle, voiceEmb, miniChunks, playbackRate, streamStartNs, loadWaitMs)
                if (profileRequestsLeft > 0 && --profileRequestsLeft == 0) endOrtProfiling()
                return@coroutineScope
            }
            var prerollChunks = 1
            val buffered = ArrayList<SynthAudio>(MAX_PREROLL_CHUNKS)
            // Pipelining: when sub-realtime (K > 1), kick off chunk N's mimi
            // decode on a worker thread while chunk N+1's AR loop runs.
            // [pendingDecode] holds the previous chunk's in-flight decode
            // (chunkIdx, deferred audio) — awaited before the next emit.
            // Disjoint ORT sessions (flow_lm_* on the main coroutine,
            // mimi_decoder on the worker) make this safe; the win is total
            // synth time, not TTFA, so we gate to K > 1 to avoid delaying
            // chunk-0 emit on realtime workloads.
            var pendingDecode: Pair<Int, Deferred<SynthAudio>>? = null

            // Each chunk decodes from the engine-level init state (read-only),
            // a cold codec state: P-AE's attempt to carry mimi state across
            // chunks for a "continuous trajectory" was reverted as confounded
            // by silent chunk boundaries.
            val mimiState = this@PocketEngine.mimiState
                ?: error("mimi state missing — engine not loaded?")

            // Local helper: emit a finished chunk's audio per the K-buffer
            // policy. Lambda (not local fun) so we can suspend on `send`.
            // P-A diagnostic: track inter-send producer gap; counts mini-
            // chunks emitted to downstream (incl. buffered flushes).
            var prevSendNs = 0L
            var firstEmitted = false
            val emitOrBuffer: suspend (SynthAudio, Int) -> Unit = { audio, chunkIdx ->
                if (chunkIdx < prerollChunks - 1 && chunkIdx < miniChunks.size - 1) {
                    buffered.add(audio)
                } else {
                    // Flush previously-buffered chunks (in order), then this one.
                    for (b in buffered) {
                        val gMs = if (prevSendNs == 0L) -1L else (System.nanoTime() - prevSendNs) / 1_000_000
                        Log.d(PERF_TAG, "pocket emit(buf) audio=${b.pcm.size * 1000L / b.sampleRate}ms gap=${gMs}ms")
                        if (!firstEmitted) {
                            val ttfa = (System.nanoTime() - streamStartNs) / 1_000_000
                            Log.d(PERF_TAG, "pocket TTFA=${ttfa}ms (loadWait=${loadWaitMs}ms + producePath=${ttfa - loadWaitMs}ms) K=$prerollChunks")
                            firstEmitted = true
                        }
                        this@channelFlow.send(b)
                        prevSendNs = System.nanoTime()
                    }
                    buffered.clear()
                    val gapMs = if (prevSendNs == 0L) -1L else (System.nanoTime() - prevSendNs) / 1_000_000
                    val audioMs = audio.pcm.size * 1000L / audio.sampleRate
                    Log.d(PERF_TAG, "pocket emit chunkIdx=$chunkIdx audio=${audioMs}ms gap=${gapMs}ms K=$prerollChunks")
                    if (!firstEmitted) {
                        val ttfa = (System.nanoTime() - streamStartNs) / 1_000_000
                        Log.d(PERF_TAG, "pocket TTFA=${ttfa}ms (loadWait=${loadWaitMs}ms + producePath=${ttfa - loadWaitMs}ms) K=$prerollChunks")
                        firstEmitted = true
                    }
                    this@channelFlow.send(audio)
                    prevSendNs = System.nanoTime()
                }
            }

            for ((idx, miniChunk) in miniChunks.withIndex()) {
                val isFirst = idx == 0
                val frameTimes = if (isFirst) ArrayList<Long>(8) else null

                // 1. AR loop for this chunk (synchronous on current coroutine).
                val arStartNs = System.nanoTime()
                val latents = generateChunkLatents(
                    bundle = bundle,
                    voiceEmb = voiceEmb,
                    miniChunk = miniChunk,
                    frameTimes = frameTimes,
                    chunkIdx = idx,
                )
                val arMs = (System.nanoTime() - arStartNs) / 1_000_000
                Log.d(PERF_TAG, "pocket chunk=$idx ar=${arMs}ms (text='${miniChunk.take(40)}${if (miniChunk.length > 40) "…" else ""}')")

                // 2. After chunk 0, calculate K from observed frame times.
                //
                // Originally used the raw max of frames[1..4]. That triggered
                // K=2 on any single-frame hiccup (e.g. 93ms outlier when the
                // sustained average was 60ms — well under the 80ms budget),
                // doubling TTFA for no real reason. Switched to a
                // trimmed-max: sort, drop the single worst frame, take the
                // max of the remaining 3. Still conservative — a SUSTAINED
                // sub-realtime workload trips K>1 — but a one-off cold-cache
                // spike doesn't.
                if (isFirst && frameTimes != null && frameTimes.size >= 5) {
                    val window = frameTimes.subList(1, 5)
                    val trimmedMaxMs = window.sortedDescending().drop(1).first()
                    // The budget is per PLAYED frame: a downstream time-
                    // stretch (playbackRate > 1) drains each rendered frame
                    // in frameDuration / playbackRate, so the deficit — and
                    // K — must be judged against that shorter clock.
                    val frameBudgetMs = 1000.0 / bundle.frameRate /
                        playbackRate.coerceAtLeast(0.01f)
                    val deficitMs = (trimmedMaxMs - frameBudgetMs).coerceAtLeast(0.0)
                    prerollChunks = if (deficitMs == 0.0) {
                        1
                    } else {
                        Math.ceil(miniChunks.size * deficitMs / frameBudgetMs)
                            .toInt()
                            .coerceIn(1, MAX_PREROLL_CHUNKS)
                    }
                    Log.d(
                        TAG,
                        "Adaptive pre-roll: chunk0 frames[1..4]=$window (raw frames=$frameTimes), " +
                            "trimmedMax=${trimmedMaxMs}ms, deficit=${deficitMs}ms, " +
                            "K=$prerollChunks chunk(s) of ${miniChunks.size} total",
                    )
                }

                // 3. Drain any previously-pending decode (await + emit/buffer).
                // Done BEFORE starting this chunk's decode so the await happens
                // in the gap between iterations (typically the deferred is
                // already complete by now thanks to the AR loop overlap).
                pendingDecode?.let { (prevIdx, prevJob) ->
                    val prevAudio = prevJob.await()
                    emitOrBuffer(prevAudio, prevIdx)
                }
                pendingDecode = null

                // 4. Decide whether to pipeline THIS chunk's decode or run
                // it synchronously. Gate on K > 1 (sub-realtime regime where
                // total-time savings matter more than first-emit latency)
                // AND not the last chunk (no pipelining benefit on the tail).
                val canPipeline = prerollChunks > 1 && idx < miniChunks.size - 1
                if (canPipeline) {
                    pendingDecode = idx to async(Dispatchers.Default) {
                        val decodeStartNs = System.nanoTime()
                        val out = decodeChunkAudio(bundle, latents, mimiState, idx)
                        val decodeMs = (System.nanoTime() - decodeStartNs) / 1_000_000
                        Log.d(PERF_TAG, "pocket chunk=$idx decode=${decodeMs}ms (pipelined)")
                        out
                    }
                } else {
                    val decodeStartNs = System.nanoTime()
                    val audio = decodeChunkAudio(bundle, latents, mimiState, idx)
                    val decodeMs = (System.nanoTime() - decodeStartNs) / 1_000_000
                    Log.d(PERF_TAG, "pocket chunk=$idx decode=${decodeMs}ms (sync)")
                    emitOrBuffer(audio, idx)
                }
            }

            // Drain any remaining pending decode after the last iteration.
            pendingDecode?.let { (prevIdx, prevJob) ->
                val prevAudio = prevJob.await()
                emitOrBuffer(prevAudio, prevIdx)
            }
            // Defensive — buffered should be empty after the loop's flush logic.
            for (b in buffered) this@channelFlow.send(b)
            if (profileRequestsLeft > 0 && --profileRequestsLeft == 0) endOrtProfiling()
        } }
    }.flowOn(Dispatchers.Default)

    /** One slice of latent frames for the decoder. */
    private class DecodeJob(
        val chunk: Int,
        val slice: Int,
        val latents: FloatArray,
        val frames: Int,
        val last: Boolean,
        val chunkStartNs: Long,
    )

    /**
     * The decoder side of [streamChunks]: one mimi state per chunk, decoded
     * a slice at a time (exact: every slice is within the ring limit,
     * [PocketMimiBatch]). Used by one thread at a time.
     */
    private inner class SliceDecoder(
        private val bundle: PocketBundle,
        private val onAudio: suspend (SynthAudio, DecodeJob, Double) -> Unit,
    ) {
        private val init = mimiState ?: error("mimi state missing — engine not loaded?")
        private var carry: PocketStateCarry? = null
        private var times = PocketTimes()
        private var chunkDecodeNs = 0L

        suspend fun decode(job: DecodeJob) {
            if (job.slice == 0) {
                close()
                carry = PocketStateCarry(PocketStateCarry.bindSlots(env ?: error("ORT env missing"), bundle.mimiStateManifest, init))
                times = PocketTimes()
                chunkDecodeNs = 0L
            }
            val t0 = System.nanoTime()
            val pcm = decodeMimiChunk(bundle, job.latents, job.frames, carry ?: error("no decode in progress"), times)
            val decodeNs = System.nanoTime() - t0
            chunkDecodeNs += decodeNs
            times.decodedFrames += job.frames
            Log.d(PERF_TAG, "pocket slice chunk=${job.chunk} slice=${job.slice} frames=${job.frames} decode=${decodeNs / 1_000_000}ms")
            if (job.last) {
                Log.d(PERF_TAG, times.decodeLine(job.chunk, chunkDecodeNs / 1_000_000))
                close()
            }
            onAudio(SynthAudio(floatToPcm16(pcm), bundle.sampleRate, resumePoint = job.slice == 0), job, decodeNs / 1e6)
        }

        fun close() {
            carry?.close()
            carry = null
        }
    }

    /**
     * #15 "E" — streamed render. Each chunk's latent frames go to the
     * decoder in slices ([PocketStreamPlan.sliceFrames]: 6, 12, 24, then
     * 46 frames) as the AR loop produces them, and each decoded slice is
     * sent at once, or held in a [PocketSliceBank] until enough audio is
     * banked for a slower-than-real-time render not to run dry. With
     * [STREAM_OVERLAP] the decoder runs on its own coroutine, overlapping
     * the AR steps; the decoder's coroutine is a child of this scope, so a
     * cancelled render still waits for a decode in flight before [synthLock]
     * is released (same rule as the whole-chunk pipeline).
     */
    private suspend fun streamChunks(
        out: ProducerScope<SynthAudio>,
        bundle: PocketBundle,
        voiceEmb: FloatArray,
        miniChunks: List<String>,
        playbackRate: Float,
        streamStartNs: Long,
        loadWaitMs: Long,
    ) = coroutineScope {
        val tokenizer = tokenizer ?: error("tokenizer missing")
        val chunkTokens = miniChunks.map { tokenizer.encode(preprocessForPocket(it, bundle)).size }
        val bank = PocketSliceBank<SynthAudio>()
        val bankLock = Mutex()
        val frameMs = 1000.0 / bundle.frameRate
        var firstSent = false
        suspend fun send(audio: List<SynthAudio>) {
            for (a in audio) {
                if (!firstSent) {
                    firstSent = true
                    val ttfa = (System.nanoTime() - streamStartNs) / 1_000_000
                    Log.d(PERF_TAG, "pocket TTFA=${ttfa}ms (loadWait=${loadWaitMs}ms + producePath=${ttfa - loadWaitMs}ms) stream=$streamMode")
                }
                Log.d(PERF_TAG, "pocket send audio=${a.pcm.size * 1000L / a.sampleRate}ms")
                out.send(a)
            }
        }
        // The AR loop's pace in chunk 0, from its frame times (not the prefill).
        val firstFrameNs = LongArray(2)
        var chunk0Frames = 0
        var holdTimer: Job? = null
        val decoder = SliceDecoder(bundle) { audio, job, decodeMs ->
            decodeCost.add(job.frames, decodeMs)
            if (bank.releaseAtMs == null) {
                // First slice of the request: simulate the rest of it and hold
                // the first sound just long enough that playback never waits.
                val nowNs = System.nanoTime()
                val arMsPerFrame = (firstFrameNs[1] - firstFrameNs[0]) / 1e6 / (chunk0Frames - 1).coerceAtLeast(1)
                val framesPerToken = streamMsPerToken / frameMs
                val estFrames = chunkTokens.mapIndexed { i, tokens ->
                    val est = (tokens * framesPerToken).toInt()
                    if (i == 0) maxOf(est, chunk0Frames + 1) else est
                }
                val prefillMs = (firstFrameNs[0] - job.chunkStartNs) / 1e6 - arMsPerFrame
                val holdMs = PocketStreamPlan.holdMs(
                    estFrames, decodeBatch, arMsPerFrame, prefillMs, decodeCost::ms,
                    overlap = streamMode == STREAM_OVERLAP, playedFrameMs = frameMs / playbackRate.coerceAtLeast(0.01f),
                ).coerceAtMost(STREAM_HOLD_CAP_MS)
                bank.releaseAtMs = nowNs / 1_000_000 + holdMs.toLong()
                Log.d(
                    PERF_TAG,
                    "pocket hold=${holdMs.toLong()}ms ar=${"%.1f".format(arMsPerFrame)}ms/f " +
                        "dec(6)=${decodeCost.ms(6).toLong()} dec(46)=${decodeCost.ms(46).toLong()}ms " +
                        "estFrames=$estFrames rate=$playbackRate",
                )
                if (holdMs > 0) {
                    holdTimer = launch {
                        kotlinx.coroutines.delay(holdMs.toLong())
                        bankLock.withLock { send(bank.flush()) }
                    }
                }
            }
            if (job.last) {
                val chunkFrames = (0 until job.slice).sumOf { PocketStreamPlan.sliceFrames(it, decodeBatch) } + job.frames
                val rtf = (System.nanoTime() - job.chunkStartNs) / 1e6 / (chunkFrames * frameMs)
                streamMsPerToken = 0.5 * streamMsPerToken + 0.5 * (chunkFrames * frameMs / chunkTokens[job.chunk].coerceAtLeast(1))
                Log.d(PERF_TAG, "pocket chunk=${job.chunk} rtf=${"%.2f".format(rtf)} audio=${(chunkFrames * frameMs).toLong()}ms")
            }
            bankLock.withLock { send(bank.offer(audio, System.nanoTime() / 1_000_000)) }
        }
        val jobs = Channel<DecodeJob>(Channel.UNLIMITED)
        val worker = if (streamMode == STREAM_OVERLAP) {
            launch(Dispatchers.Default) {
                try {
                    for (job in jobs) decoder.decode(job)
                } finally {
                    decoder.close()
                }
            }
        } else {
            null
        }
        suspend fun submit(job: DecodeJob) = if (worker != null) jobs.send(job) else decoder.decode(job)
        try {
            for ((idx, miniChunk) in miniChunks.withIndex()) {
                val chunkStartNs = System.nanoTime()
                var slice = 0
                val pending = ArrayList<FloatArray>(decodeBatch + 1)
                fun take(n: Int): FloatArray {
                    val flat = FloatArray(n * bundle.latentDim)
                    for (i in 0 until n) System.arraycopy(pending[i], 0, flat, i * bundle.latentDim, bundle.latentDim)
                    pending.subList(0, n).clear()
                    return flat
                }
                // A slice goes once MORE than its size is pending, so the
                // chunk's final slice always has a frame and carries `last`.
                generateChunkLatents(bundle, voiceEmb, miniChunk, frameTimes = null, chunkIdx = idx) { latent ->
                    if (idx == 0) {
                        if (chunk0Frames == 0) firstFrameNs[0] = System.nanoTime()
                        firstFrameNs[1] = System.nanoTime()
                        chunk0Frames++
                    }
                    pending.add(latent)
                    val n = PocketStreamPlan.sliceFrames(slice, decodeBatch)
                    if (pending.size > n) submit(DecodeJob(idx, slice++, take(n), n, last = false, chunkStartNs))
                }
                if (pending.isNotEmpty()) {
                    val n = pending.size
                    submit(DecodeJob(idx, slice, take(n), n, last = true, chunkStartNs))
                }
            }
            jobs.close()
            worker?.join()
            bankLock.withLock { send(bank.flush()) }
        } finally {
            // A render shorter than its hold ends here, not when the timer fires.
            holdTimer?.cancel()
            jobs.close()
            if (worker == null) decoder.close()
        }
    }

    /**
     * AR loop for one mini-chunk. Returns the flat latent buffer (frames
     * concatenated along time) or `null` if the chunk produced no frames.
     *
     * Was a single `generateChunkBatched` before v0.3.0-alpha.7; the
     * streaming path can pipeline this chunk's AR with the *previous*
     * chunk's mimi decode (see [synthesizeStream]). The two phases use
     * disjoint ORT sessions (`flow_lm_main` + `flow_lm_flow` here;
     * `mimi_decoder` in [decodeChunkAudio]) so they can safely run
     * concurrently.
     *
     * If [frameTimes] is non-null, the per-frame wall-clock (ms) of
     * each AR step is appended (caller uses these for adaptive pre-roll
     * on the first chunk).
     */
    private suspend fun generateChunkLatents(
        bundle: PocketBundle,
        voiceEmb: FloatArray,
        miniChunk: String,
        frameTimes: MutableList<Long>?,
        chunkIdx: Int = -1,
        onLatent: (suspend (FloatArray) -> Unit)? = null,
    ): FloatArray? {
        val tokenizer = tokenizer ?: error("tokenizer missing")
        val preprocessed = preprocessForPocket(miniChunk, bundle)
        val tokens = tokenizer.encode(preprocessed)
        if (tokens.size > bundle.maxTokenPerChunk) {
            Log.w(
                TAG,
                "Mini-chunk has ${tokens.size} tokens (cap ${bundle.maxTokenPerChunk}); " +
                    "chunker should have prevented this — output may skip words.",
            )
        }

        // P-AM.1: time the per-chunk prefill (phase 1 voice cond + phase 2
        // text cond) so logcat shows prefill vs AR-loop share directly.
        val phases = ArrayList<PhaseSpan>(2)
        val ar = startArSession(bundle, voiceEmb, tokens, preprocessed, phases = phases)
        for (p in phases) {
            Log.d(PERF_TAG, "pocket chunk=$chunkIdx prefill ${p.name}=${p.ms}ms${p.detail?.let { " ($it)" } ?: ""}")
        }
        try {
            return arLoop(ar, bundle, miniChunk, frameTimes, chunkIdx, onLatent)
        } finally {
            ar.carry.close()
        }
    }

    /** [generateChunkLatents]' frame loop, from a primed [ar] to the flat latents. */
    private suspend fun arLoop(
        ar: ArSession,
        bundle: PocketBundle,
        miniChunk: String,
        frameTimes: MutableList<Long>?,
        chunkIdx: Int,
        onLatent: (suspend (FloatArray) -> Unit)?,
    ): FloatArray? {
        val latents = ArrayList<FloatArray>(ar.maxFrames)
        // P-AD diagnostics: track latent magnitude + EOS so a glitchy chunk's
        // logcat distinguishes the two leading theories (per adversarial
        // review): (1) EOS-failure → over-generation (eosFired=false,
        // frames==maxFrames); (2) bad noise draw → AR divergence (peakLatentAbs
        // blows up / nonFinite=true). The corruption persists across a whole
        // chunk because the AR loop feeds previousLatent=nextLatent forward.
        var peakLatentAbs = 0f
        var sawNonFinite = false
        val loopStartNs = System.nanoTime()
        while (true) {
            // Cooperative cancellation. The AR step is a non-suspend ORT
            // native call (~50–100 ms on Pixel 8a depending on EP/thread
            // count); without this check, a Stop tap couldn't take effect
            // until the current mini-chunk finished its full AR loop.
            coroutineContext.ensureActive()
            check(!releaseRequested.get()) { "Pocket engine released mid-synthesis" }
            val tStart = System.currentTimeMillis()
            val step = stepAr(ar) ?: break
            if (frameTimes != null && frameTimes.size < 8) {
                frameTimes.add(System.currentTimeMillis() - tStart)
            }
            for (v in step.latent) {
                if (!v.isFinite()) { sawNonFinite = true; continue }
                val a = if (v < 0f) -v else v
                if (a > peakLatentAbs) peakLatentAbs = a
            }
            latents.add(step.latent)
            onLatent?.invoke(step.latent)
        }

        Log.d(PERF_TAG, ar.times.arLine(chunkIdx, (System.nanoTime() - loopStartNs) / 1_000_000))

        // Per-chunk summary. WARN on the two failure signatures; INFO otherwise.
        val summary = "Pocket chunk $chunkIdx: frames=${latents.size}/${ar.maxFrames} " +
            "eosFired=${ar.eosFired}@${ar.eosFiredAtFrame} " +
            "peakLatentAbs=${"%.2f".format(peakLatentAbs)} nonFinite=$sawNonFinite " +
            "text=\"${miniChunk.take(48)}\""
        if (!ar.eosFired || sawNonFinite) {
            Log.w(TAG, "SUSPECT $summary")
        } else {
            Log.i(TAG, summary)
        }

        if (latents.isEmpty()) return null
        val flat = FloatArray(latents.size * bundle.latentDim)
        var pos = 0
        for (l in latents) {
            System.arraycopy(l, 0, flat, pos, bundle.latentDim)
            pos += bundle.latentDim
        }
        return flat
    }

    /**
     * Run the mimi decoder on a flat latent buffer and convert to PCM16.
     * `null` input → empty audio (caller filters empty chunks).
     *
     * Safe to invoke from a background coroutine while the AR loop runs
     * on disjoint sessions — this is the streaming-path pipeline that
     * overlaps chunk N's mimi decode with chunk N+1's AR loop. It mutates
     * the engine-level [mimiState], though, so at most one decode may be
     * in flight, and it must finish before [synthLock] is released (see
     * the coroutineScope in [synthesizeStream]).
     */
    private fun decodeChunkAudio(
        bundle: PocketBundle,
        latents: FloatArray?,
        mimiState: PocketStates,
        chunkIdx: Int,
    ): SynthAudio {
        if (latents == null || latents.isEmpty()) {
            return SynthAudio(pcm = ShortArray(0), sampleRate = bundle.sampleRate)
        }
        // The chunk-start "distorted first word" artifact was the decoder's
        // ring buffer overflowing in big batches; [runMimiDecoder]'s exact
        // batches ([PocketMimiBatch]) never overflow it, so no warm-prime /
        // trim / fade is needed here.
        val times = PocketTimes()
        val startNs = System.nanoTime()
        val pcmFloat = runMimiDecoder(bundle, latents, mimiState, times)
        Log.d(PERF_TAG, times.decodeLine(chunkIdx, (System.nanoTime() - startNs) / 1_000_000))
        return SynthAudio(pcm = floatToPcm16(pcmFloat), sampleRate = bundle.sampleRate)
    }

    /**
     * Token-aware sentence chunker for Pocket. Mirrors upstream
     * `split_into_best_sentences` (tts_model.py:978):
     *
     *  1. Apply [preprocessForPocket] (uppercase, trailing period,
     *     whitespace norm) to the whole input.
     *  2. Split on sentence boundaries `[.!?]` followed by whitespace.
     *  3. Any sentence exceeding [maxTokens] sub-splits on `,;:`.
     *  4. Any sub-segment still exceeding [maxTokens] falls through to
     *     greedy word packing.
     *  5. Greedy bin-pack consecutive segments into chunks ≤ maxTokens.
     *
     * Returns a list of textual chunks (each safe to feed to
     * [generateChunkLatents] without exceeding [PocketBundle.maxTokenPerChunk]).
     */
    private fun chunkPocketByTokens(
        text: String,
        tokenizer: PocketTokenizer,
        bundle: PocketBundle,
        maxTokens: Int,
    ): List<String> {
        val preprocessed = preprocessForPocket(text.trim(), bundle)
        if (preprocessed.isEmpty()) return emptyList()

        // Step 1: split into sentences.
        val sentences = SENTENCE_SPLIT_REGEX.split(preprocessed)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return emptyList()

        // Step 2: refine — sub-split oversized sentences.
        val segments = ArrayList<String>(sentences.size * 2)
        for (s in sentences) {
            val n = tokenizer.encode(s).size
            if (n <= maxTokens) {
                segments.add(s)
                continue
            }
            val parts = COMMA_SPLIT_REGEX.split(s)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size > 1) {
                for (p in parts) {
                    if (tokenizer.encode(p).size <= maxTokens) {
                        segments.add(p)
                    } else {
                        segments.addAll(splitByWordsToTokenLimit(p, tokenizer, maxTokens))
                    }
                }
            } else {
                segments.addAll(splitByWordsToTokenLimit(s, tokenizer, maxTokens))
            }
        }

        // Step 3: greedy bin-pack into chunks.
        val chunks = ArrayList<String>(segments.size)
        var cur = StringBuilder()
        var curTokens = 0
        for (seg in segments) {
            val segTokens = tokenizer.encode(seg).size
            if (cur.isEmpty()) {
                cur.append(seg)
                curTokens = segTokens
                continue
            }
            if (curTokens + segTokens <= maxTokens) {
                cur.append(' ').append(seg)
                curTokens += segTokens
            } else {
                chunks.add(cur.toString())
                cur = StringBuilder(seg)
                curTokens = segTokens
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        return chunks
    }

    /**
     * Last-resort word splitter for sentences/clauses that exceed
     * [maxTokens] even after `,;:` splitting. Greedy: walks words,
     * re-encodes the running buffer each step; emits a chunk and resets
     * whenever the buffer would exceed [maxTokens].
     *
     * A single word exceeding [maxTokens] (rare — URLs, hash strings)
     * still emits as its own chunk; the engine warns and proceeds.
     */
    private fun splitByWordsToTokenLimit(
        text: String,
        tokenizer: PocketTokenizer,
        maxTokens: Int,
    ): List<String> {
        val words = text.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<String>(words.size / 5 + 1)
        var cur = StringBuilder()
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (tokenizer.encode(candidate).size <= maxTokens) {
                cur = StringBuilder(candidate)
            } else {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur = StringBuilder(w)
                } else {
                    // single overlong word — emit alone, reset.
                    out.add(w)
                    cur = StringBuilder()
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    override fun release() {
        try {
            // Closing a session another thread is mid-run() on is a native
            // SIGSEGV, so release must exclude BOTH a concurrent load
            // (loadLock) and a concurrent synth (synthLock). synthesizeStream
            // holds synthLock for the whole utterance, so we first raise
            // [releaseRequested] — the AR loop's per-frame check aborts the
            // synth within ~one frame — then take synthLock → loadLock and
            // close. That lock order can't deadlock: this is the only place
            // both locks are held at once (load never waits on synthLock;
            // synth takes loadLock only via ensureLoadedSuspending, before
            // acquiring synthLock).
            releaseRequested.set(true)
            try {
                kotlinx.coroutines.runBlocking {
                    synthLock.withLock { loadLock.withLock { releaseInternal() } }
                }
            } finally {
                releaseRequested.set(false)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "release() ignored failure: ${t.message}")
        }
    }

    private fun releaseInternal() {
        // Cancel any mid-flight warmup so it doesn't blow up trying to
        // touch sessions we're about to null out.
        warmupJob?.cancel()
        warmupJob = null
        listOf(
            textCondSession,
            mimiEncoderSession,
            mimiDecoderSession,
            flowLmMainSession,
            flowLmFlowSession,
        ).forEach { s ->
            try { s?.close() } catch (_: Throwable) {}
        }
        textCondSession = null
        mimiEncoderSession = null
        mimiDecoderSession = null
        flowLmMainSession = null
        flowLmFlowSession = null
        bundle = null
        tokenizer = null
        bosBeforeVoice = null
        // The 56 mimi state slots are MB-scale direct buffers — nulling
        // here is what actually frees the native memory after uninstall
        // (this @Singleton outlives the engine's files).
        mimiState = null
        voicePrompt?.frame?.release()
        voicePrompt = null
        flowInitStates.clear()
        voiceEmbeddings.clear()
        // OrtEnvironment is process-scoped; don't close it.
        env = null
    }

    // -- voice embedding cache -----------------------------------------------

    /**
     * Look up [voiceName]'s 1024-d voice embedding sequence (shape
     * `[V, 1024]` returned as a flat FloatArray of length `V * 1024`).
     *
     * Order of resolution:
     *   1. In-memory cache.
     *   2. If `voiceName` starts with `"cloned-"`: read from
     *      `cloned_voices/<voiceName>.bin` (PVS1 format).
     *   3. On-disk built-in `.emb` cache.
     *   4. Encode from `voices/<name>.wav` via `mimi_encoder`, write the
     *      .emb cache, then return.
     */
    private fun embeddingForVoice(voiceName: String): FloatArray {
        voiceEmbeddings[voiceName]?.let { return it }

        if (voiceName.startsWith(CLONED_VOICE_PREFIX)) {
            val cacheFile = File(clonedVoicesDir, "$voiceName.bin")
            check(cacheFile.isFile) { "Cloned voice missing: $cacheFile" }
            val cloned = PocketClonedVoiceStore.read(cacheFile)
            voiceEmbeddings[voiceName] = cloned.embedding
            return cloned.embedding
        }

        if (!voiceCacheDir.exists()) voiceCacheDir.mkdirs()
        val cacheFile = File(voiceCacheDir, "$voiceName.emb")
        if (cacheFile.isFile) {
            try {
                val cached = readEmbCache(cacheFile)
                voiceEmbeddings[voiceName] = cached
                return cached
            } catch (t: Throwable) {
                Log.w(TAG, "Voice cache $cacheFile unreadable, re-encoding: ${t.message}")
                cacheFile.delete()
            }
        }

        val wavFile = File(voicesDir, "$voiceName.wav")
        check(wavFile.isFile) { "Voice WAV missing: $wavFile" }
        val wav = PocketAudio.readWav(wavFile)
        val embedding = encodePcm(wav.samples, wav.sampleRate)
        try {
            writeEmbCache(cacheFile, embedding)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write voice cache $cacheFile: ${t.message}")
        }
        voiceEmbeddings[voiceName] = embedding
        return embedding
    }

    /**
     * Run [pcm] (at [srcSampleRate], in [-1, 1] float range) through the
     * `mimi_encoder` and return the flat `[numFrames, 1024]` embedding.
     *
     * Pre-processing matches the Python ground truth:
     *   - resample to the bundle's sample rate (24 kHz for english_2026-04),
     *   - divide by peak if any sample exceeds 1.0,
     *   - truncate to 30 s (the encoder's effective receptive field).
     *
     * Shared between the built-in voice encoder (called lazily on first
     * use of each Kyutai-supplied WAV) and the [cloneVoice] path
     * (called when the user supplies their own audio).
     */
    private fun encodePcm(pcm: FloatArray, srcSampleRate: Int): FloatArray {
        val bundle = bundle ?: error("bundle missing")
        val ort = env ?: error("ORT env missing")
        val session = mimiEncoderSession ?: error("mimi encoder session missing")

        var processed = PocketAudio.resample(pcm, srcSampleRate, bundle.sampleRate)
        processed = PocketAudio.normalizeIfClipping(processed)
        val cap = 30 * bundle.sampleRate
        if (processed.size > cap) processed = processed.copyOf(cap)

        // mimi_encoder input: `audio` float32 [1, 1, T]
        val buf = ByteBuffer.allocateDirect(processed.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buf.put(processed)
        buf.rewind()
        OnnxTensor.createTensor(ort, buf, longArrayOf(1, 1, processed.size.toLong())).use { audioTensor ->
            session.run(mapOf("audio" to audioTensor)).use { result ->
                val out = result.get("latents").orElseThrow {
                    IllegalStateException("mimi_encoder did not return 'latents'")
                } as OnnxTensor
                val shape = out.info.shape
                // Expected: [1, V, 1024]
                check(shape.size == 3 && shape[0] == 1L && shape[2].toInt() == bundle.conditioningDim) {
                    "mimi_encoder unexpected output shape: ${shape.toList()}"
                }
                val numFrames = shape[1].toInt()
                val flat = FloatArray(numFrames * bundle.conditioningDim)
                out.floatBuffer.get(flat)
                return flat
            }
        }
    }

    // -- voice cloning (backend) --------------------------------------------
    //
    // Surface area only. There is NO user-facing affordance yet — the
    // cloning UX (recorder, file picker, consent dialog, alias editor
    // entry) is deliberately deferred pending the ethical/UX call on
    // whether and how to expose this capability. These methods exist so
    // the eventual UI can wire up without further engine changes, and
    // so we can exercise the encode path end-to-end in isolation.
    //
    // The cloned voice's mimi embedding is computed once at clone time
    // and persisted to disk (PVS1 binary format) — synthesis just
    // reads the file. No live audio leaves the device.

    /**
     * Clone a voice from arbitrary PCM input. The audio is resampled +
     * normalised + capped to 30 s before being fed through
     * `mimi_encoder`. The resulting embedding is written to
     * `cloned_voices/cloned-<uuid>.bin` and returned via its full voice
     * ID (`pocket-tts-en-v2026_04:cloned-<uuid>`).
     *
     * Idempotent w.r.t. failure: if the encode succeeds but the file
     * write fails, no state survives the throw. The cloned voice is
     * also added to the in-memory cache so the first synth call after
     * cloning doesn't pay the file-read cost.
     *
     * @param displayName user-facing label (≤ 256 UTF-8 bytes)
     * @param pcm mono float32 PCM in [-1, 1]
     * @param srcSampleRate sample rate of [pcm]
     * @return full voice ID, e.g. `"pocket-tts-en-v2026_04:cloned-abc..."`
     */
    suspend fun cloneVoice(
        displayName: String,
        pcm: FloatArray,
        srcSampleRate: Int,
    ): String = withContext(Dispatchers.Default) {
        require(displayName.isNotBlank()) { "Cloned voice display name must not be blank" }
        require(pcm.isNotEmpty()) { "Cloned voice PCM is empty" }
        ensureLoadedSuspending()
        val bundle = bundle ?: error("bundle missing after load")

        synthLock.withLock {
            val embedding = encodePcm(pcm, srcSampleRate)
            val numFrames = embedding.size / bundle.conditioningDim
            val localId = CLONED_VOICE_PREFIX + java.util.UUID.randomUUID().toString()
            val voice = PocketClonedVoice(
                id = localId,
                displayName = displayName,
                createdAtMillis = System.currentTimeMillis(),
                numFrames = numFrames,
                embedding = embedding,
            )
            PocketClonedVoiceStore.write(clonedVoicesDir, voice)
            voiceEmbeddings[localId] = embedding
            Log.i(TAG, "Cloned voice '$displayName' as $localId ($numFrames frames)")
            "${PocketVoiceCatalog.ENGINE}:$localId"
        }
    }

    /**
     * List every cloned voice on disk. Reads only the headers — does
     * NOT load embeddings into memory. Cheap to call from a UI flow.
     */
    suspend fun listClonedVoices(): List<PocketClonedVoiceSummary> = withContext(Dispatchers.IO) {
        PocketClonedVoiceStore.list(clonedVoicesDir)
    }

    /**
     * Delete the cloned voice with [voiceId] (the full
     * `pocket-tts-en-v2026_04:cloned-<uuid>` form, or just the local
     * `cloned-<uuid>` part). Returns true if a file was removed.
     * Also drops the in-memory cache entry.
     */
    suspend fun deleteClonedVoice(voiceId: String): Boolean = withContext(Dispatchers.IO) {
        val localId = voiceId.substringAfter(':', voiceId)
        voiceEmbeddings.remove(localId)
        PocketClonedVoiceStore.delete(clonedVoicesDir, localId)
    }

    private fun readEmbCache(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= 4) { "voice cache too short" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numFrames = buf.getInt()
        val condDim = bundle!!.conditioningDim
        val expectedFloats = numFrames * condDim
        require(bytes.size == 4 + expectedFloats * 4) {
            "voice cache size mismatch: header says $numFrames frames " +
                "but file holds ${(bytes.size - 4) / 4} floats"
        }
        val out = FloatArray(expectedFloats)
        buf.asFloatBuffer().get(out)
        return out
    }

    private fun writeEmbCache(file: File, embedding: FloatArray) {
        val condDim = bundle!!.conditioningDim
        require(embedding.size % condDim == 0) { "embedding length not divisible by conditioningDim" }
        val numFrames = embedding.size / condDim
        val out = ByteBuffer.allocate(4 + embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(numFrames)
        out.asFloatBuffer().put(embedding)
        file.writeBytes(out.array())
    }

    // -- flow LM pipeline ----------------------------------------------------

    /**
     * Live state of an in-flight autoregressive synthesis. Held across
     * many [stepAr] calls; not safe to share between synths.
     *
     * Carved out of the old monolithic `runFlowLm` so the AR loop can
     * be driven frame-by-frame from `synthesizeStream`'s flow builder
     * (the batched `synthesize` collects that same stream).
     */
    private class ArSession(
        val bundle: PocketBundle,
        val ort: OrtEnvironment,
        val main: OrtSession,
        val flow: OrtSession,
        /** flow_lm state (#15 "A"); the caller closes it when the loop ends. */
        val carry: PocketStateCarry,
        val maxFrames: Int,
        val framesAfterEos: Int,
        var previousLatent: FloatArray,
        var frameIdx: Int = 0,
        var eosFired: Boolean = false,
        var eosFiredAtFrame: Int = -1,
        var framesPostEos: Int = 0,
        var done: Boolean = false,
        val times: PocketTimes = PocketTimes(),
    ) {
        /**
         * Reusable buffers for the inner flow_lm_flow Euler loop. Pre-
         * allocated once at session start so [runFlowEuler] doesn't
         * `allocateDirect` 4 buffers per Euler step per frame. Layout:
         *   - cBuf    : conditioning vector (latentDim ⇒ conditioningDim,
         *               written once per frame, read by every Euler step)
         *   - sBuf, tBuf : scalar timestep boundaries (1 float each)
         *   - xBuf    : current x vector (latentDim floats, updated each step)
         *   - flowOut : output read scratch (latentDim floats)
         *
         * Native order direct buffers ⇒ zero-copy at ORT's JNI layer.
         */
        val flowScratch: FlowEulerScratch = FlowEulerScratch(
            latentDim = bundle.latentDim,
            conditioningDim = bundle.conditioningDim,
        )

    }

    /**
     * Per-Euler-loop scratch buffers (see [ArSession.flowScratch]).
     * Lives for the AR session lifetime so the inner loop never allocates.
     */
    private class FlowEulerScratch(latentDim: Int, conditioningDim: Int) {
        val cBuf: java.nio.ByteBuffer = java.nio.ByteBuffer
            .allocateDirect(conditioningDim * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        val cFloatBuf: java.nio.FloatBuffer = cBuf.asFloatBuffer()

        val sBuf: java.nio.ByteBuffer = java.nio.ByteBuffer
            .allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
        val sFloatBuf: java.nio.FloatBuffer = sBuf.asFloatBuffer()

        val tBuf: java.nio.ByteBuffer = java.nio.ByteBuffer
            .allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
        val tFloatBuf: java.nio.FloatBuffer = tBuf.asFloatBuffer()

        val xBuf: java.nio.ByteBuffer = java.nio.ByteBuffer
            .allocateDirect(latentDim * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        val xFloatBuf: java.nio.FloatBuffer = xBuf.asFloatBuffer()

        /** Reusable read-out array — flow_lm_flow output bytes copied here per step. */
        val flowOut: FloatArray = FloatArray(latentDim)
    }

    private data class ArStepResult(val latent: FloatArray, val eosLogit: Float)

    /**
     * Run phase 1 (voice conditioning) and phase 2 (text conditioning),
     * leaving the flow_lm state primed for the autoregressive loop.
     * Returns the live AR session whose `carry` holds the post-priming
     * state ready for [stepAr] calls.
     *
     * [phases], when non-null, receives a timing span per conditioning
     * phase (P-AM.1 — logged by callers under [PERF_TAG]).
     */
    private fun startArSession(
        bundle: PocketBundle,
        voiceEmbedding: FloatArray,
        tokens: IntArray,
        originalText: String,
        phases: MutableList<PhaseSpan>?,
    ): ArSession {
        val ort = env ?: error("ORT env missing")
        val main = flowLmMainSession ?: error("flow_lm_main session missing")
        val flow = flowLmFlowSession ?: error("flow_lm_flow session missing")
        val textCond = textCondSession ?: error("text_conditioner session missing")

        // Zero-copy (#15 "A"): every chunk starts from the engine-level init
        // state, which ORT only ever reads, so it is never refilled.
        // The voice prompt's state is cached by embedding identity (#15 "C").
        // A symbolic cache axis (#15 "F") is sized for the voice prompt plus
        // the largest normal chunk, so one cached prompt serves every chunk.
        val promptRows = voiceEmbedding.size / bundle.conditioningDim + if (bundle.insertBosBeforeVoice) 1 else 0
        val sizingTokens = maxOf(tokens.size, bundle.maxTokenPerChunk)
        val cacheLen = flowCacheLenKnob ?: if (flowCacheDynamic) {
            PocketCacheShapes.flowLenFor(promptRows, sizingTokens, estimateMaxFrames(bundle, sizingTokens))
        } else {
            null
        }
        val manifest = cacheLen?.let { PocketCacheShapes.flowLm(bundle.flowLmStateManifest, it) } ?: bundle.flowLmStateManifest
        val cachedPrompt = voicePrompt
            ?.takeIf { it.embedding === voiceEmbedding && it.cacheLen == cacheLen }?.frame
        val carry = if (cachedPrompt != null) {
            PocketStateCarry(cachedPrompt.retain())
        } else {
            val init = flowInitStates.getOrPut(cacheLen ?: 0) { initStates(manifest) }
            PocketStateCarry(PocketStateCarry.bindSlots(ort, bundle.flowLmStateManifest, init))
        }
        try {
            // PHASE 1: voice conditioning.
            // text_embeddings = bos_before_voice ++ voice_embedding, shape [1, V+1, conditioningDim]
            val phase1Start = System.currentTimeMillis()
            if (cachedPrompt == null) {
                val bos = bosBeforeVoice ?: error("bos_before_voice missing")
                val voiceFrames = voiceEmbedding.size / bundle.conditioningDim
                val totalFrames = voiceFrames + if (bundle.insertBosBeforeVoice) 1 else 0
                val concat = FloatArray(totalFrames * bundle.conditioningDim)
                run {
                    var pos = 0
                    if (bundle.insertBosBeforeVoice) {
                        System.arraycopy(bos, 0, concat, pos, bos.size)
                        pos += bos.size
                    }
                    System.arraycopy(voiceEmbedding, 0, concat, pos, voiceEmbedding.size)
                }
                runFlowLmMain(
                    session = main,
                    bundle = bundle,
                    ort = ort,
                    carry = carry,
                    sequenceData = FloatArray(0),
                    sequenceShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
                    textEmbedsData = concat,
                    textEmbedsShape = longArrayOf(1, totalFrames.toLong(), bundle.conditioningDim.toLong()),
                    captureConditioning = false,
                )
                run {
                    voicePrompt?.frame?.release()
                    voicePrompt = VoicePrompt(voiceEmbedding, cacheLen, carry.snapshot())
                }
            }
            phases?.add(
                PhaseSpan(
                    "flow-lm phase 1 (voice cond)",
                    System.currentTimeMillis() - phase1Start,
                    detail = if (cachedPrompt != null) "cached" else null,
                ),
            )

            // PHASE 2: text conditioning.
            val phase2Start = System.currentTimeMillis()
            val tcStart = System.currentTimeMillis()
            val textEmbeds = runTextConditioner(textCond, tokens, bundle, ort)
            val tcMs = System.currentTimeMillis() - tcStart
            runFlowLmMain(
                session = main,
                bundle = bundle,
                ort = ort,
                carry = carry,
                sequenceData = FloatArray(0),
                sequenceShape = longArrayOf(1, 0, bundle.latentDim.toLong()),
                textEmbedsData = textEmbeds,
                textEmbedsShape = longArrayOf(1, tokens.size.toLong(), bundle.conditioningDim.toLong()),
                captureConditioning = false,
            )
            phases?.add(
                PhaseSpan(
                    "flow-lm phase 2 (text cond)",
                    System.currentTimeMillis() - phase2Start,
                    detail = "incl. text_conditioner $tcMs ms",
                ),
            )
        } catch (t: Throwable) {
            carry.close()
            throw t
        }

        return ArSession(
            bundle = bundle,
            ort = ort,
            main = main,
            flow = flow,
            carry = carry,
            maxFrames = estimateMaxFrames(bundle, tokens.size),
            framesAfterEos = framesAfterEosFor(bundle, originalText),
            previousLatent = FloatArray(bundle.latentDim) { Float.NaN },
        )
    }

    /**
     * Advance the AR session by one frame. Returns the new latent +
     * the raw EOS logit (caller checks against `EOS_THRESHOLD`).
     * Returns `null` when the session is done (max frames hit, or EOS
     * fired and the post-EOS tail has elapsed).
     *
     * Updates [ar] in place: increments frameIdx, updates state +
     * previousLatent, sets eosFired / done flags as appropriate.
     */
    private fun stepAr(ar: ArSession): ArStepResult? {
        if (ar.done || ar.frameIdx >= ar.maxFrames) return null
        val frame = ar.frameIdx
        // P-Z REVERTED: persistent seq/text scratch buffers caused
        // chunk-boundary glitches (same symptom as the original P-X #2
        // attempt). Going back to fresh per-frame directFloatTensor —
        // the eliminated allocations are tiny (128 bytes each) and the
        // bigger accumulation problem is already solved by P-Y.
        val capture = runFlowLmMain(
            session = ar.main,
            bundle = ar.bundle,
            ort = ar.ort,
            carry = ar.carry,
            sequenceData = ar.previousLatent,
            sequenceShape = longArrayOf(1, 1, ar.bundle.latentDim.toLong()),
            textEmbedsData = FloatArray(0),
            textEmbedsShape = longArrayOf(1, 0, ar.bundle.conditioningDim.toLong()),
            captureConditioning = true,
            times = ar.times,
        )!! // captureConditioning=true guarantees non-null
        val flowStartNs = System.nanoTime()
        val nextLatent = runFlowEuler(ar.flow, ar.ort, capture.conditioning, ar.flowScratch, ar.bundle.latentDim)
        ar.times.flowNs += System.nanoTime() - flowStartNs
        ar.times.frames++
        ar.previousLatent = nextLatent
        ar.frameIdx++

        if (!ar.eosFired && capture.eosLogit > EOS_THRESHOLD) {
            ar.eosFired = true
            ar.eosFiredAtFrame = frame
            Log.d(TAG, "EOS at frame $frame (logit=${capture.eosLogit}); +${ar.framesAfterEos} more")
        }
        if (ar.eosFired) {
            ar.framesPostEos++
            if (ar.framesPostEos >= ar.framesAfterEos) ar.done = true
        }
        return ArStepResult(nextLatent, capture.eosLogit)
    }

    /** Result of a `flow_lm_main` call when phase 3 captures the value outputs. */
    private data class FlowLmCapture(val conditioning: FloatArray, val eosLogit: Float)

    /**
     * Run `text_conditioner(tokens)` and return the flat embeddings
     * (shape `[T, conditioningDim]`). Tokenizer output is an int64
     * tensor; the conditioner is a plain embedding lookup.
     */
    private fun runTextConditioner(
        session: OrtSession,
        tokens: IntArray,
        bundle: PocketBundle,
        ort: OrtEnvironment,
    ): FloatArray {
        val tokensLong = LongArray(tokens.size) { tokens[it].toLong() }
        val tokT = directLongTensor(
            ort, tokensLong, longArrayOf(1, tokens.size.toLong()),
        )
        try {
            session.run(mapOf("token_ids" to tokT)).use { result ->
                val out = result.get("embeddings").orElseThrow {
                    IllegalStateException("text_conditioner did not return 'embeddings'")
                } as OnnxTensor
                val flat = FloatArray(tokens.size * bundle.conditioningDim)
                out.floatBuffer.get(flat)
                return flat
            }
        } finally {
            tokT.close()
        }
    }

    /**
     * Run flow_lm_main once (#15 "A", zero-copy): the state tensors come
     * from [carry] and the run's state outputs become its next frame, so no
     * state byte is copied in Java. Phases 1 + 2 and the AR step build
     * `sequence` and `text_embeddings` per call (their shapes differ).
     *
     * @param captureConditioning when true, returns the `conditioning`
     *   and `eos_logit` outputs (phase 3 needs them). When false (phases
     *   1 + 2) returns null.
     */
    private fun runFlowLmMain(
        session: OrtSession,
        bundle: PocketBundle,
        ort: OrtEnvironment,
        carry: PocketStateCarry,
        sequenceData: FloatArray,
        sequenceShape: LongArray,
        textEmbedsData: FloatArray,
        textEmbedsShape: LongArray,
        captureConditioning: Boolean,
        times: PocketTimes? = null,
    ): FlowLmCapture? {
        val seqT = directFloatTensor(ort, sequenceData, sequenceShape)
        val textT = directFloatTensor(ort, textEmbedsData, textEmbedsShape)
        try {
            val bindStartNs = System.nanoTime()
            val inputs = LinkedHashMap<String, OnnxTensor>(bundle.flowLmStateManifest.size + 2)
            inputs["sequence"] = seqT
            inputs["text_embeddings"] = textT
            carry.bind(bundle.flowLmStateManifest, inputs)
            val runStartNs = System.nanoTime()
            val result = session.run(inputs)
            val runEndNs = System.nanoTime()
            val capture = try {
                if (captureConditioning) readCapture(result, bundle) else null
            } catch (t: Throwable) {
                result.close()
                throw t
            }
            carry.advance(bundle.flowLmStateManifest, result)
            if (times != null) {
                times.mainNs += runEndNs - runStartNs
                times.mainStateNs += (runStartNs - bindStartNs) + (System.nanoTime() - runEndNs)
            }
            return capture
        } finally {
            seqT.close()
            textT.close()
        }
    }

    private fun readCapture(result: OrtSession.Result, bundle: PocketBundle): FlowLmCapture {
        val condTensor = result.get("conditioning").orElseThrow {
            IllegalStateException("flow_lm_main did not return 'conditioning'")
        } as OnnxTensor
        val cond = FloatArray(bundle.conditioningDim)
        condTensor.floatBuffer.get(cond)
        val eosTensor = result.get("eos_logit").orElseThrow {
            IllegalStateException("flow_lm_main did not return 'eos_logit'")
        } as OnnxTensor
        return FlowLmCapture(conditioning = cond, eosLogit = eosTensor.floatBuffer.get(0))
    }

    /**
     * Euler-integrated flow matching. Walks `x` from random noise toward
     * the conditioning target across [LSD_DECODE_STEPS] flow-net calls.
     *
     * Python's default is `steps=1` (LSD training collapses the
     * trajectory into one step). NekoSpeak uses 20 by default. v0.3.0-
     * alpha.2 ships 1 to match the upstream contract and minimise the
     * per-frame ORT calls; we can bump it after listening tests if 1
     * step shows artefacts.
     */
    private fun runFlowEuler(
        session: OrtSession,
        ort: OrtEnvironment,
        conditioning: FloatArray,
        scratch: FlowEulerScratch,
        latentDim: Int,
    ): FloatArray {
        // x_0 = random normal scaled by sqrt(temperature), optionally
        // truncated to ±NOISE_CLAMP_ABS (matches upstream's `noise_clamp`
        // parameter — see constant docstring). `current` is the returned
        // latent — caller holds a reference, so it must stay a per-call
        // allocation (the next stepAr call will overwrite
        // ar.previousLatent and drop our reference).
        val noiseStd = sqrt(TEMPERATURE).toFloat()
        val clamp = NOISE_CLAMP_ABS
        val current = FloatArray(latentDim) {
            var x = random.nextGaussian().toFloat() * noiseStd
            // Rejection sample (trunc_normal_ semantics). Rate ~3e-4 at
            // std=0.837, clamp=3 — the inner loop almost never re-runs.
            while (x > clamp || x < -clamp) {
                x = random.nextGaussian().toFloat() * noiseStd
            }
            x
        }
        val steps = LSD_DECODE_STEPS
        val dt = 1f / steps

        // Write conditioning into the scratch's persistent direct buffer.
        // Rewind first so put() lands at offset 0.
        scratch.cFloatBuf.rewind()
        scratch.cFloatBuf.put(conditioning)
        scratch.cFloatBuf.rewind()
        val cTensor = OnnxTensor.createTensor(
            ort, scratch.cFloatBuf, longArrayOf(1, conditioning.size.toLong()),
        )

        try {
            for (j in 0 until steps) {
                val s = j.toFloat() / steps
                val t = (j + 1).toFloat() / steps

                // Refill the scratch buffers in place (no allocations).
                scratch.sFloatBuf.rewind()
                scratch.sFloatBuf.put(s)
                scratch.sFloatBuf.rewind()

                scratch.tFloatBuf.rewind()
                scratch.tFloatBuf.put(t)
                scratch.tFloatBuf.rewind()

                scratch.xFloatBuf.rewind()
                scratch.xFloatBuf.put(current)
                scratch.xFloatBuf.rewind()

                OnnxTensor.createTensor(ort, scratch.sFloatBuf, longArrayOf(1, 1)).use { sT ->
                    OnnxTensor.createTensor(ort, scratch.tFloatBuf, longArrayOf(1, 1)).use { tT ->
                        OnnxTensor.createTensor(ort, scratch.xFloatBuf, longArrayOf(1, latentDim.toLong())).use { xT ->
                            val inputs = mapOf("c" to cTensor, "s" to sT, "t" to tT, "x" to xT)
                            session.run(inputs).use { result ->
                                val flowOut = result.get("flow_dir").orElseThrow {
                                    IllegalStateException("flow_lm_flow did not return 'flow_dir'")
                                } as OnnxTensor
                                // Copy into scratch's persistent FloatArray; integrate into current.
                                flowOut.floatBuffer.get(scratch.flowOut)
                                for (k in 0 until latentDim) current[k] += scratch.flowOut[k] * dt
                            }
                        }
                    }
                }
            }
        } finally {
            cTensor.close()
        }
        return current
    }

    // -- mimi decoder --------------------------------------------------------

    /**
     * Decode [numFrames] latent frames through mimi_decoder, advancing
     * [carry] (zero-copy, #15 "A": the run's state outputs become the next
     * run's inputs).
     */
    private fun decodeMimiChunk(
        bundle: PocketBundle,
        latents: FloatArray,
        numFrames: Int,
        carry: PocketStateCarry,
        times: PocketTimes?,
    ): FloatArray {
        val ort = env ?: error("ORT env missing")
        val session = mimiDecoderSession ?: error("mimi decoder session missing")
        val bindStartNs = System.nanoTime()
        val latT = directFloatTensor(
            ort, latents,
            longArrayOf(1, numFrames.toLong(), bundle.latentDim.toLong()),
        )
        try {
            val inputs = LinkedHashMap<String, OnnxTensor>(bundle.mimiStateManifest.size + 1)
            inputs["latent"] = latT
            carry.bind(bundle.mimiStateManifest, inputs)
            val out = FloatArray(numFrames * bundle.samplesPerFrame)
            val runStartNs = System.nanoTime()
            val result = session.run(inputs)
            val runEndNs = System.nanoTime()
            try {
                val audioTensor = result.get("audio_frame").orElseThrow {
                    IllegalStateException("mimi_decoder did not return 'audio_frame'")
                } as OnnxTensor
                audioTensor.floatBuffer.get(out)
            } catch (t: Throwable) {
                result.close()
                throw t
            }
            carry.advance(bundle.mimiStateManifest, result)
            if (times != null) {
                if (numFrames == 1) {
                    times.leadNs += runEndNs - runStartNs; times.leadRuns++
                } else {
                    times.batchNs += runEndNs - runStartNs; times.batchRuns++
                }
                times.decodeStateNs += (runStartNs - bindStartNs) + (System.nanoTime() - runEndNs)
            }
            return out
        } finally {
            latT.close()
        }
    }

    /**
     * Non-streaming mimi decoder: chunks [latents] internally to keep
     * memory bounded for long inputs, but produces all PCM up front.
     * Used by [synthesize]; the streaming path calls [decodeMimiChunk]
     * directly with its own chunking + state lifecycle.
     */
    private fun runMimiDecoder(
        bundle: PocketBundle,
        latents: FloatArray,
        mimiState: PocketStates = initStates(mimiManifest),
        times: PocketTimes? = null,
    ): FloatArray {
        if (latents.isEmpty()) return FloatArray(0)
        val numFrames = latents.size / bundle.latentDim
        val dim = bundle.latentDim
        val spf = bundle.samplesPerFrame
        val pcm = FloatArray(numFrames * spf)
        times?.decodedFrames = numFrames

        // [mimiState] is only the init source, never written (#15 "A").
        val carry = PocketStateCarry(PocketStateCarry.bindSlots(env ?: error("ORT env missing"), bundle.mimiStateManifest, mimiState))
        try {
            // #15 "D": consecutive exact batches, nothing decoded twice.
            for (range in PocketMimiBatch.batches(numFrames, decodeBatch)) {
                val n = range.last - range.first + 1
                val part = latents.copyOfRange(range.first * dim, (range.last + 1) * dim)
                val out = decodeMimiChunk(bundle, part, n, carry, times)
                System.arraycopy(out, 0, pcm, range.first * spf, out.size)
            }
        } finally {
            carry.close()
        }
        return pcm
    }

    // -- helpers -------------------------------------------------------------

    /**
     * Pocket's text preprocessing pre-tokenizer. Mirrors Python's
     * `prepare_text_prompt`: trim, collapse internal whitespace,
     * optionally swap `;`→`,`, capitalize the first letter, append `.`
     * if the input ends in an alphanumeric char, optionally prepend
     * 8 spaces for <5-word inputs.
     */
    private fun preprocessForPocket(raw: String, bundle: PocketBundle): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        // P-AG — fold smart punctuation to ASCII BEFORE tokenizing. The
        // bundled SentencePiece vocab has exactly one apostrophe piece: a
        // straight `'` (U+0027). Mobile keyboards autocorrect to a curly
        // apostrophe (U+2019), which NFKC does NOT fold, so a contraction
        // like "that's" byte-falls-back on the curly char → the model
        // renders the OOV bytes as a stumble/pause. Normalising here makes
        // contractions tokenize via the real `'` piece. Correctly-typed
        // ASCII text is unaffected.
        s = normalizeSmartPunctuation(s)
        // P-AM — treat line breaks as sentence boundaries. A blank line or a
        // hard return in the input box separates thoughts, so it should read
        // with a sentence pause instead of running together (e.g. a title line
        // followed by a paragraph). Turn each newline run into ". " unless the
        // line already ends in sentence punctuation (then just keep it + a
        // space); the chunker then splits on it like any other sentence end.
        // Android-input robustness — upstream `prepare_text_prompt` collapses
        // newlines to spaces. TODO: mirror to the marmalade_tts CLI.
        s = Regex("([.!?]?)[ \\t]*\\n[\\s]*").replace(s) { m ->
            if (m.groupValues[1].isNotEmpty()) m.groupValues[1] + " " else ". "
        }
        // Collapse remaining runs of whitespace (tabs/multi-spaces) to single spaces.
        s = s.replace(Regex("\\s+"), " ")
        if (bundle.removeSemicolons) s = s.replace(';', ',')
        // Capitalize first letter (matches Python).
        s = s[0].uppercaseChar() + s.substring(1)
        // Append `.` if last char is alphanumeric.
        if (s.last().isLetterOrDigit()) s += "."
        // Short-input padding (only fires if the bundle requests it).
        if (bundle.padWithSpacesForShortInputs) {
            val wordCount = s.split(' ').count { it.isNotEmpty() }
            if (wordCount < 5) s = "        $s"
        }
        return s
    }

    /**
     * Fold the common smart-punctuation characters (curly quotes, dashes,
     * ellipsis) to their ASCII equivalents. See [preprocessForPocket] for
     * why this matters — the bundle's SentencePiece vocab only has straight
     * ASCII punctuation pieces, and NFKC doesn't perform these mappings, so
     * un-normalised smart characters byte-fall-back and the model renders
     * them as stumbles/pauses. Shared by the production preprocessor and
     * [app.marmalade.tts.engine.PocketDevEngine].
     */
    private fun normalizeSmartPunctuation(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(
                when (ch) {
                    '‘', '’', '‚', '‛', '′', 'ʼ' -> '\''
                    '“', '”', '„', '‟', '″' -> '"'
                    '–', '—', '‒', '―' -> '-'
                    ' ', ' ', ' ' -> ' '
                    else -> ch
                },
            )
        }
        // Ellipsis → single sentence-terminating dot.
        return sb.toString().replace('…', '.')
    }

    /**
     * Estimate a generous safety cap on the number of generated frames.
     * Mirrors Python's `_estimate_max_gen_len` heuristic: speech ≈ 1
     * frame per 3-character token, padded by 2 seconds.
     */
    private fun estimateMaxFrames(bundle: PocketBundle, numTokens: Int): Int {
        val seconds = numTokens / 3.0 + 2.0
        val frames = Math.ceil(seconds * bundle.frameRate).toInt()
        return frames.coerceAtMost(MAX_FRAMES_HARD_CAP)
    }

    /**
     * Per the Python ground truth (`prepare_text_prompt`,
     * `tts_model.py:913-926` + `:622`): use **word count** to decide
     * 3 (≤4 words) or 1 (longer), then add 2 for safety. We
     * previously used token count which gave 5/3 instead of 3/1 —
     * generated 2 extra surplus frames per utterance, which surfaces
     * as ~160 ms of trailing silence / low-confidence audio per
     * chunk. Matters audibly when streaming back-to-back chunks of a
     * long input.
     */
    private fun framesAfterEosFor(bundle: PocketBundle, originalText: String): Int {
        bundle.modelRecommendedFramesAfterEos?.let { return it + 2 }
        val wordCount = originalText.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val base = if (wordCount <= 4) 3 else 1
        return base + 2
    }

    /**
     * Convert mimi_decoder's float output (already in [-1, 1] modulo
     * occasional excursions) to PCM16. Clamps defensively so a rogue
     * sample doesn't wrap-around into a loud click.
     */
    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            out[i] = (clamped * 32767f).toInt().toShort()
        }
        return out
    }

    /**
     * Create a float OnnxTensor backed by a fresh direct ByteBuffer (native
     * byte order) rather than `FloatBuffer.wrap(heapArr)`. Heap-wrapped
     * buffers force ORT's JNI layer to memcpy heap → native memory on every
     * call; direct buffers skip that copy. For per-AR-frame inputs the
     * difference adds up (~50 MB/s of memcpy traffic at ~12.5 fps).
     *
     * The buffer is owned by the returned tensor's lifecycle — callers
     * close the tensor as usual; the underlying direct memory is then
     * eligible for GC. We don't reuse the buffer across calls because the
     * shapes vary per call site (phase 1 has T_text = V+1; phase 2 has
     * T_text = tokens; AR has T_seq = 1, T_text = 0).
     */
    private fun directFloatTensor(
        ort: OrtEnvironment,
        data: FloatArray,
        shape: LongArray,
    ): OnnxTensor {
        // ORT requires a non-null buffer even for 0-element shapes; allocate
        // a 1-float dummy in that case (the shape masks it out).
        val byteCount = (if (data.isEmpty()) 1 else data.size) * 4
        val buf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).asFloatBuffer()
        if (data.isNotEmpty()) {
            buf.put(data)
            buf.rewind()
        }
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    /** Long-tensor sibling of [directFloatTensor] — used by `runTextConditioner` for token IDs. */
    private fun directLongTensor(
        ort: OrtEnvironment,
        data: LongArray,
        shape: LongArray,
    ): OnnxTensor {
        val byteCount = (if (data.isEmpty()) 1 else data.size) * 8
        val buf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).asLongBuffer()
        if (data.isNotEmpty()) {
            buf.put(data)
            buf.rewind()
        }
        return OnnxTensor.createTensor(ort, buf, shape)
    }

    companion object {
        private const val TAG = "PocketEngine"
        /** Logcat tag for the P-A streaming perf diagnostic — `adb logcat -s StreamPerf`. */
        private const val PERF_TAG = "StreamPerf"
        const val ENGINE_NAME = "pocket-tts-en-v2026_04"

        /**
         * Files that exist in every Pocket bundle regardless of variant.
         * The 5 ONNX filenames are variant-specific (declared in bundle.json's
         * `onnx_files` block) and checked separately in [isInstalled].
         */
        private val REQUIRED_NON_ONNX_FILES = listOf(
            "tokenizer.model",
            "bos_before_voice.npy",
            "bundle.json",
        )

        /**
         * The exported mimi_decoder is not window-invariant: a batch longer
         * than its attention ring holds overflows it and corrupts the
         * batch's early frames (the old "distorted first word"). History:
         * P-X (one whole-chunk batch) glitched; P-AI decoded a per-frame
         * ramp then batches; P-AL (alpha.12) re-decoded 64-frame batches
         * behind an 8-frame per-frame lead-in, which still corrupted
         * frames 8–26 of long sentences. #15 "D" found the cause, a 1000-slot
         * ring at 16 decoder steps per frame, and decodes in batches that
         * fit it ([PocketMimiBatch.maxExactBatch]).
         */

        /**
         * P-T diagnostic toggle. When true, every Pocket session gets
         * VERBOSE ORT logging (level 0). Captures kernel placement at
         * session-create so we can see which ops escape XNNPACK. Flip back
         * to false after capture — VERBOSE logging during inference is a
         * firehose. Inspect with `adb logcat -s onnxruntime`.
         */
        private const val VERBOSE_ORT_DIAGNOSTIC = false

        // The chunk-start "distorted first word" artifact is fixed at its
        // root by exact decode batches in [runMimiDecoder]. The earlier
        // mask-the-symptom knobs — MIMI_COLD_START_TRIM_MS (P-AA), warm-prime
        // MIMI_WARMUP_FRAMES (P-AF), and a never-wired CHUNK_FADE_IN_MS (P-AB)
        // — were all removed once the real cause was found.

        /**
         * Euler integration steps for flow_lm_flow. **Upstream ships 1** —
         * LSD (Lagrangian Self-Distillation) trains the flow to collapse the
         * diffusion trajectory into a single step, so 1 is the quality floor,
         * not a corner cut.
         *
         * History: P-AC.D (2026-05) bumped 1 → 4 to mask the intermittent
         * chunk-start artefact (frame 0's BOS-substituted conditioning + a
         * single Euler step could land off-manifold → ~1 degraded word). BUT
         * that artefact was later ROOT-CAUSED (P-AC/P-AL, 2026-06) to the mimi
         * decoder not being window-invariant (fixed by exact decode batches,
         * #15 "D"). So the reason for 4 is superseded — reverted to upstream's
         * 1 to reclaim ~3× of the per-frame flow_lm_flow work (a real RTF
         * win). If a frame-0 artefact returns, that's a separate conditioning
         * bug to fix at the source, not a reason to re-inflate steps.
         */
        private const val LSD_DECODE_STEPS = 1

        /** Sampling temperature for the initial noise. Python default. */
        private const val TEMPERATURE = 0.7

        /**
         * P-AC.C — absolute bound applied to each component of the
         * initial noise vector in [runFlowEuler]. Matches upstream's
         * `noise_clamp` parameter (kyutai-labs/pocket-tts flow_lm.py
         * lines 101–110), which routes to `torch.nn.init.trunc_normal_(
         * mean=0, std=sqrt(temp), a=-noise_clamp, b=noise_clamp)`.
         *
         * Upstream's default is `None` (no clamp). We apply 3.0 to
         * truncate fat-tail noise draws, the leading hypothesis for the
         * intermittent chunk-start artefact (every chunk starts a fresh
         * AR session whose frame 0 is BOS-substituted; with
         * LSD_DECODE_STEPS=1 the single Euler step preserves most of
         * the initial noise, so a tail draw projects to an off-manifold
         * latent that mimi decodes into ~1 word of degraded audio).
         *
         * With std≈0.837 and clamp=3.0 the rejection rate per scalar is
         * ~3e-4 — perceptually free on good draws, eliminates the bad
         * draws. Set to a large value (e.g. 100f) to effectively
         * disable the clamp without removing the code path.
         */
        private const val NOISE_CLAMP_ABS = 3.0f

        const val STREAM_OFF = "off"
        const val STREAM_INLINE = "inline"
        const val STREAM_OVERLAP = "overlap"

        /**
         * Longest first-sound hold (#15 "E"): a render slower than real time
         * would otherwise hold a long request for most of its render.
         */
        private const val STREAM_HOLD_CAP_MS = 3_000.0

        /** Starting guess for audio per Pocket token (#15 "E"); refined per chunk. */
        private const val DEFAULT_MS_PER_TOKEN = 110.0

        /** Raw eos_logit threshold from the export wrapper. */
        private const val EOS_THRESHOLD = -4.0f

        /** Belt-and-braces ceiling. Should never trip under normal use. */
        private const val MAX_FRAMES_HARD_CAP = 500

        /**
         * Voice-name prefix that marks a cloned voice. Built-in voices
         * use Kyutai's reference names (alba/azelma/...). Cloned voices
         * carry a UUID with this prefix so the lookup in
         * [embeddingForVoice] can route to the right on-disk format.
         */
        const val CLONED_VOICE_PREFIX = "cloned-"

        // -- chunking + pre-roll knobs (synthesizeStream) -----------------
        //
        // Pocket adopted KokoroDirect's former chunking discipline
        // (sentence-only, char-bound, minChars-merge; Kokoro has since moved
        // to token-sized plans, TextChunker.planByTokens). An older
        // token-based bin-pack produced orphan fragments ("Outside,")
        // whenever a sentence got comma-split, which broke Pocket's per-chunk
        // prosody seed and sounded choppy; the main path now uses
        // [TextChunker.chunk] with those params, and the rare oversized-sentence
        // fallback ([chunkPocketByTokens]) caps at bundle.maxTokenPerChunk.

        /** Sentence-boundary cap (KokoroDirect's former character cap). */
        private const val POCKET_MAX_CHARS_PER_CHUNK = 255

        /**
         * Tiny-sentence merge threshold. Lower than KokoroDirect's 80
         * because Pocket runs sub-realtime: merging two ~70-char sentences
         * into a single ~150-char chunk doubles the AR loop's wall-clock
         * for that chunk, which exceeds the prior chunk's audio duration
         * and starves the consumer (audible gap). Set to 40 so sentences
         * 40+ chars emit alone — keeps per-chunk size variance low so the
         * adaptive pre-roll's K=1 holds without underrun. Truly tiny
         * sentences ("Yes.") still merge.
         */
        private const val POCKET_MIN_CHARS_PER_CHUNK = 40

        /**
         * Adaptive pre-roll ceiling. The K formula scales with the
         * per-frame deficit and the chunk count; on slow devices it
         * would otherwise demand 4+ chunks of buffering. We cap it
         * to keep TTFA bounded.
         *
         * Calibration: at the fp32-mimi precision shipped with v0.3.0-
         * alpha.7, a typical 15-frame chunk takes ~2.25 s wall-time on
         * Pixel 8a (Tensor G3) when the per-frame deficit is 80 ms+.
         * Cap K at 2 ⇒ worst-case TTFA ≈ 4.5 s, vs the older int8
         * timing where K=3 fit a 3 s budget. The int8 budget no longer
         * applies; the trade-off is now stutter risk (lower K) vs
         * startup delay (higher K).
         */
        private const val MAX_PREROLL_CHUNKS = 2

        /** Sentence boundary: `.`, `!`, or `?` followed by whitespace. */
        private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")

        /** Sub-sentence boundary: `,`, `;`, or `:` followed by whitespace. */
        private val COMMA_SPLIT_REGEX = Regex("(?<=[,;:])\\s+")

        /** Generic whitespace splitter for word-level fallback. */
        private val WHITESPACE_REGEX = Regex("\\s+")

        // Stochastic noise source for the flow/Euler sampler (nextGaussian()
        // per Euler step). NOTE: this is a STATIC companion singleton — shared
        // process-wide, NOT ThreadLocal and NOT engine-scoped. Access is
        // serialised via synthLock so the draw order is deterministic within a
        // synth, but the seed is the global default → output is non-reproducible
        // run-to-run. Seeding it (per utterance) would make generation
        // deterministic — deliberately not done (see the RTF/validation notes).
        private val random: java.util.Random = java.util.Random()
    }
}
