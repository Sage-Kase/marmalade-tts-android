package app.marmalade.tts.devbench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import app.marmalade.tts.audio.SilenceCompressor
import app.marmalade.tts.audio.TailTrim
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.engine.kitten.PAD_TOKEN
import app.marmalade.tts.engine.kitten.SPACE_TOKEN
import app.marmalade.tts.engine.kitten.encodePhonemes
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.perf.CpuClusterDetector
import app.marmalade.tts.phonemizer.EnPhonemeFixups
import app.marmalade.tts.phonemizer.EspeakPhonemizer
import app.marmalade.tts.phonemizer.SharedEspeakData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// KokoroStreamDevEngine — Kokoro-82M, plan once / decode in slices, DEBUG ONLY
// -----------------------------------------------------------------------------
//
// Issue #5 ("D1c"): the D1a cut ([KittenStreamDevEngine]) applied to the int8
// Kokoro v23 model we ship. Cut on the desktop into two graphs (scripts in
// ~/coding/scratch/kokoro-stream-d1c/):
//
//   planner.onnx  tokens, style, speed → x, s, har, duration
//                 Text model, durations, pitch/energy, the decoder's
//                 frame-rate front, and the harmonic source. Runs ONCE per
//                 chunk with one style row: nothing is re-planned per slice.
//   gen.onnx      x, s, har window, gn → audio
//                 The iSTFTNet generator. Its 48 InstanceNorms take their
//                 statistics from the `gn` input, computed once per chunk from
//                 the voice's small model (genstats/<voice>.bin, see
//                 [KokoroGenStats]) and the planner's durations. That makes it
//                 purely convolutional, so slices rendered with
//                 [KittenStreamSlicing.CONTEXT_FRAMES] of context join exactly.
//
// Chunking mirrors KokoroDirect (TextChunker.planByTokens, the whole
// sentence's style row on every piece) except that sentences stay whole up to
// the model's 500-token cap and the first sentence is never cut short:
// streaming replaces the short first piece. English voices only (espeak
// en-us / en-gb with the Kokoro fixups); the bench is English.
//
// Sideloaded into `${filesDir}/engines/kokoro-stream-dev/`; voices.bin is read
// from the installed KokoroDirect bundle. An optional `dev.properties` there
// overrides the slicing (`first`, `slice`, `context`, in frames), turns on ORT
// profiling (`profile=true`, JSON in `files/bench/` on release) or runs the
// installed, unsplit model.onnx through the same chunking and session setup
// (`whole=true`): the control that attributes the split's cost. `spin=true`
// and `mempattern=false` flip those two ORT session options (per-call
// overhead experiments). Not in
// EngineRegistry or any catalog: only [EngineBenchService] calls it, and the
// debug source set keeps it out of every release build.
// -----------------------------------------------------------------------------

@Singleton
class KokoroStreamDevEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val sharedEspeak: SharedEspeakData,
) : TtsEngine {

    override val engineName: String = ENGINE
    override val sampleRate: Int = KokoroDirectVoiceCatalog.SAMPLE_RATE
    override val supportsNativeSpeed: Boolean = false
    override val maxInputChars: Int = 2000

    private val dir: File get() = File(ctx.filesDir, "engines/$ENGINE")
    private val kokoroDir: File get() = File(ctx.filesDir, "engines/${KokoroDirectEngine.ENGINE_NAME}")

    private val lock = Mutex()
    @Volatile private var env: OrtEnvironment? = null
    private var planner: OrtSession? = null
    private var gen: OrtSession? = null
    private var whole: OrtSession? = null
    private var phonemizer: EspeakPhonemizer? = null
    private var voices: FloatBuffer? = null
    private var dev = DevConfig()
    private val statsModels = HashMap<String, FloatArray>()

    override fun isInstalled(): Boolean =
        File(dir, PLANNER).isFile && File(dir, GEN).isFile && File(dir, "genstats").isDirectory &&
            File(kokoroDir, "voices.bin").isFile

    override fun isLoaded(): Boolean = env != null

    override fun ensureModelLoaded() {
        if (env != null) return
        runBlocking {
            lock.withLock {
                if (env != null) return@withLock
                if (!isInstalled()) throw EngineNotInstalledException(engineName)
                val threads = settings.intraOpThreads.firstOrNull() ?: CpuClusterDetector.detectPerfCoreCount()
                dev = DevConfig.read(File(dir, "dev.properties"))
                val ort = OrtEnvironment.getEnvironment()
                if (dev.whole) {
                    whole = options(threads, "whole").use { ort.createSession(File(kokoroDir, "model.onnx").absolutePath, it) }
                } else {
                    planner = options(threads, "planner").use { ort.createSession(File(dir, PLANNER).absolutePath, it) }
                    gen = options(threads, "gen").use { ort.createSession(File(dir, GEN).absolutePath, it) }
                }
                voices = RandomAccessFile(File(kokoroDir, "voices.bin"), "r").use {
                    it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
                }.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                phonemizer = EspeakPhonemizer(
                    libPath = EspeakPhonemizer.APK_LIB_NAME,
                    dataPath = sharedEspeak.ensure().absolutePath,
                    voice = "en-us",
                    fixupModel = EnPhonemeFixups.Model.KOKORO,
                ).also { check(it.open() >= 0) { "espeak failed to open" } }
                env = ort
                Log.i(TAG, "loaded threads=$threads $dev")
                // Warm the graphs (first-call kernel setup) before anyone times a synth.
                renderChunk(encode("Hi.", "en-us"), null, WARMUP_VOICE) { _, _ -> }
            }
        }
    }

    // Same setup as KokoroDirect for its int8 (QDQ) bundle: CPU EP only —
    // XNNPACK SIGSEGVs on that graph, and the CPU EP's int8 kernels are the fast path.
    private fun options(threads: Int, name: String) = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        if (dev.profile) enableProfiling(File(ctx.filesDir, "bench/prof-kokoro-$name").absolutePath)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setMemoryPatternOptimization(dev.memPattern)
        addConfigEntry("session.intra_op.allow_spinning", if (dev.spin) "1" else "0")
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
    ): SynthAudio = withContext(Dispatchers.Default) {
        val parts = ArrayList<ShortArray>()
        synthesizeStream(text, voiceId, speed, phonemizationLanguage).collect { parts.add(it.pcm) }
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(out, pos); pos += p.size }
        SynthAudio(out, sampleRate)
    }

    override fun synthesizeStream(
        text: String,
        voiceId: String,
        speed: Float,
        phonemizationLanguage: String?,
        playbackRate: Float,
    ): Flow<SynthAudio> = channelFlow {
        val t0 = System.nanoTime()
        ensureModelLoaded()
        val voice = voiceId.substringAfter(':', voiceId)
        val lang = KokoroDirectVoiceCatalog.espeakVoiceFor(voice)
        if (!lang.startsWith("en")) Log.w(TAG, "voice $voice is $lang: this dev engine phonemizes English only")
        val encoded = HashMap<String, IntArray>()
        val chunks = lock.withLock {
            TextChunker.planByTokens(text, TOKEN_BUDGET) { part -> encoded.getOrPut(part) { encode(part, lang) }.size }
        }
        var first = true
        for ((idx, chunk) in chunks.withIndex()) {
            ensureActive()
            lock.withLock {
                val ids = encoded[chunk.text] ?: encode(chunk.text, lang)
                Log.d(PERF_TAG, "kkstream chunk=$idx/${chunks.size} tokens=${ids.size} row=${chunk.rowTokens ?: ids.size}")
                // Each slice goes out the moment it is rendered.
                renderChunk(ids, chunk.rowTokens, voice) { pcm, _ ->
                    if (first) {
                        Log.d(PERF_TAG, "kkstream TTFA=${(System.nanoTime() - t0) / 1_000_000}ms")
                        first = false
                    }
                    send(SynthAudio(pcm, sampleRate))
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    /** KokoroDirect's English encode: espeak IPA → tokens, plus a space token after `.!?`. */
    private fun encode(text: String, lang: String): IntArray {
        val ipa = (phonemizer ?: error("phonemizer missing")).phonemize(text, lang)
        val base = encodePhonemes(ipa)
        val out = ArrayList<Int>(base.size + 8)
        for (i in base.indices) {
            out += base[i]
            if (ipa[i] == '.' || ipa[i] == '!' || ipa[i] == '?') out += SPACE_TOKEN
        }
        return out.toIntArray()
    }

    /**
     * Plan [rawIds] once, then render its frames slice by slice, handing each
     * slice's PCM to [emit] (with `true` on the last) as soon as it exists.
     * Caller holds [lock].
     */
    private suspend fun renderChunk(
        rawIds: IntArray,
        rowTokens: Int?,
        voice: String,
        emit: suspend (ShortArray, Boolean) -> Unit,
    ) {
        if (rawIds.isEmpty()) return
        val ort = env ?: error("not loaded")
        val body = if (rawIds.size > MAX_PHONEMES) {
            Log.w(TAG, "${rawIds.size} tokens > $MAX_PHONEMES — truncated (dev engine; KokoroDirect re-splits)")
            rawIds.copyOf(MAX_PHONEMES)
        } else {
            rawIds
        }
        val ids = IntArray(body.size + 2).also { body.copyInto(it, 1); it[0] = PAD_TOKEN; it[body.size + 1] = PAD_TOKEN }
        val style = styleRow(voice, (rowTokens ?: body.size).coerceIn(0, MAX_TOKEN_LEN - 1))

        val idsT = OnnxTensor.createTensor(ort, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong()))
        val styleT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(style), longArrayOf(1, STYLE_DIM.toLong()))
        val speedT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1))
        val inputs = mapOf("tokens" to idsT, "style" to styleT, "speed" to speedT)
        try {
            whole?.let { model ->
                val tWhole = System.nanoTime()
                val wav = model.run(inputs).use { r -> r[0].value as FloatArray }
                Log.d(PERF_TAG, "kkstream whole=${(System.nanoTime() - tWhole) / 1_000_000}ms tokens=${ids.size} samples=${wav.size}")
                emit(tailTrimmed(wav, 0, wav.size), true)
                return
            }

            val tPlan = System.nanoTime()
            val x: FloatArray
            val xLen: Int
            val s: FloatArray
            val har: FloatArray
            val dur: LongArray
            planner!!.run(inputs).use { r ->
                val xt = r.get("x").get() as OnnxTensor
                xLen = xt.info.shape[2].toInt()
                x = xt.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                s = (r.get("s").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                har = (r.get("har").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                dur = (r.get("duration").get() as OnnxTensor).longBuffer.let { b -> LongArray(b.remaining()).also { b.get(it) } }
            }
            val planMs = (System.nanoTime() - tPlan) / 1_000_000
            val totalFrames = xLen / 2
            check(dur.sum().toInt() == totalFrames && har.size == totalFrames * FRAME_SAMPLES) {
                "planner contract broken: Σdur=${dur.sum()} xLen=$xLen har=${har.size}"
            }
            val tail = KokoroStreamSlicing.tailStart(ids, dur) ?: error("ids/durations disagree")
            val slices = KokoroStreamSlicing.slices(totalFrames, tail, dev.first, dev.slice, dev.context)
            Log.d(PERF_TAG, "kkstream plan=${planMs}ms tokens=${ids.size} frames=$totalFrames tail=$tail slices=${slices.size}")

            val pause = KokoroGenStats.pauseFrames(ids, dur).toDouble() / totalFrames
            val gn = KokoroGenStats.normTable(statsModel(voice), pause, totalFrames)
            val channels = x.size / xLen
            val gnT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(gn), longArrayOf(2, (gn.size / 2).toLong()))
            val sT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(s), longArrayOf(1, s.size.toLong()))
            try {
                for ((si, slice) in slices.withIndex()) {
                    val tGen = System.nanoTime()
                    val wa = slice.render.first
                    val wf = slice.render.last - wa + 1
                    val xw = directFloats(channels * 2 * wf)
                    for (c in 0 until channels) xw.put(x, c * xLen + 2 * wa, 2 * wf)
                    xw.rewind()
                    val hw = directFloats(wf * FRAME_SAMPLES)
                    hw.put(har, wa * FRAME_SAMPLES, wf * FRAME_SAMPLES).rewind()
                    val xT = OnnxTensor.createTensor(ort, xw, longArrayOf(1, channels.toLong(), 2L * wf))
                    val hT = OnnxTensor.createTensor(ort, hw, longArrayOf(1, wf.toLong() * FRAME_SAMPLES, 1))
                    val last = si == slices.size - 1
                    val pcm = try {
                        gen!!.run(mapOf("x" to xT, "s" to sT, "har" to hT, "gn" to gnT)).use { r ->
                            val wav = r[0].value as FloatArray
                            if (last) tailTrimmed(wav, slice.keepOffsetSamples, slice.keepSamples)
                            else pcm16(wav, slice.keepOffsetSamples, slice.keepSamples)
                        }
                    } finally {
                        xT.close(); hT.close()
                    }
                    Log.d(PERF_TAG, "kkstream slice=$si/${slices.size} gen=${(System.nanoTime() - tGen) / 1_000_000}ms " +
                        "keep=${slice.keep.first}..${slice.keep.last} render=${slice.render.first}..${slice.render.last}")
                    emit(pcm, last)
                }
            } finally {
                gnT.close(); sT.close()
            }
        } finally {
            idsT.close(); styleT.close(); speedT.close()
        }
    }

    /** KokoroDirect's chunk tail treatment (TailTrim, then SilenceCompressor) on `wav[offset, offset+len)`. */
    private fun tailTrimmed(wav: FloatArray, offset: Int, len: Int): ShortArray {
        val trimmed = TailTrim.trimTail(wav.copyOfRange(offset, offset + len))
        return SilenceCompressor.compress(pcm16(trimmed, 0, trimmed.size))
    }

    private fun pcm16(wav: FloatArray, offset: Int, len: Int): ShortArray =
        ShortArray(len) { i -> (wav[offset + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }

    private fun directFloats(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** voices.bin is [speakers, 510, 256]; KokoroDirect's speaker index and row rule. */
    private fun styleRow(voice: String, row: Int): FloatArray {
        val sid = KokoroDirectVoiceCatalog.speakerIdFor(voice).takeIf { it >= 0 } ?: error("unknown Kokoro voice $voice")
        val src = (voices ?: error("voices not mapped")).duplicate()
        src.position((sid * MAX_TOKEN_LEN + row) * STYLE_DIM)
        return FloatArray(STYLE_DIM).also { src.get(it) }
    }

    /** The voice's generator-stats model, float32 [[KokoroGenStats.MODEL_ROWS], S]. */
    private fun statsModel(voice: String): FloatArray = statsModels.getOrPut(voice) {
        val file = File(dir, "genstats/$voice.bin")
        check(file.isFile) { "no generator stats for $voice (genstats/$voice.bin)" }
        val bytes = file.readBytes()
        FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    override fun release() {
        runBlocking {
            lock.withLock {
                if (dev.profile) {
                    runCatching { Log.i(TAG, "profile planner=${planner?.endProfiling()} gen=${gen?.endProfiling()} whole=${whole?.endProfiling()}") }
                }
                runCatching { planner?.close() }
                runCatching { gen?.close() }
                runCatching { whole?.close() }
                runCatching { phonemizer?.close() }
                planner = null; gen = null; whole = null; phonemizer = null; voices = null
                statsModels.clear()
                env = null
            }
        }
    }

    /** Slicing, profiling and the whole-model control, overridable from `dev.properties`. */
    private data class DevConfig(
        val first: Int = KittenStreamSlicing.FIRST_SLICE_FRAMES,
        val slice: Int = KittenStreamSlicing.SLICE_FRAMES,
        val context: Int = KittenStreamSlicing.CONTEXT_FRAMES,
        val profile: Boolean = false,
        val whole: Boolean = false,
        val spin: Boolean = false,
        val memPattern: Boolean = true,
    ) {
        companion object {
            fun read(file: File): DevConfig {
                if (!file.isFile) return DevConfig()
                val p = java.util.Properties().apply { file.inputStream().use { load(it) } }
                val d = DevConfig()
                return DevConfig(
                    first = p.getProperty("first")?.toIntOrNull() ?: d.first,
                    slice = p.getProperty("slice")?.toIntOrNull() ?: d.slice,
                    context = p.getProperty("context")?.toIntOrNull() ?: d.context,
                    profile = p.getProperty("profile") == "true",
                    whole = p.getProperty("whole") == "true",
                    spin = p.getProperty("spin") == "true",
                    memPattern = p.getProperty("mempattern") != "false",
                )
            }
        }
    }

    companion object {
        /** Engine name, and the bundle directory under `${filesDir}/engines/`. */
        const val ENGINE = "kokoro-stream-dev"
        private const val TAG = "KokoroStreamDev"
        private const val PERF_TAG = "StreamPerf"
        private const val PLANNER = "planner.onnx"
        private const val GEN = "gen.onnx"
        private const val STYLE_DIM = 256
        private const val MAX_TOKEN_LEN = 510
        private const val MAX_PHONEMES = 500
        private const val FRAME_SAMPLES = TailTrim.FRAME_SAMPLES
        private const val WARMUP_VOICE = "af_heart"

        /**
         * KokoroDirect's merge floor, but sentences stay whole up to the
         * 500-token cap (1.35 × 360 = 486): streaming makes the first sound
         * independent of the chunk's length, so nothing is cut for speed.
         */
        private val TOKEN_BUDGET = TextChunker.TokenBudget(mergeFloor = 90, target = 360, firstPiece = 40, growth = 1.5)
    }
}
