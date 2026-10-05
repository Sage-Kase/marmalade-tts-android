package app.marmalade.tts.devbench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import app.marmalade.tts.audio.TextChunker
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.EngineNotInstalledException
import app.marmalade.tts.engine.SynthAudio
import app.marmalade.tts.engine.TtsEngine
import app.marmalade.tts.engine.kitten.KITTEN_END_TOKEN
import app.marmalade.tts.engine.kitten.KittenDirectEngine
import app.marmalade.tts.engine.kitten.KittenTrim
import app.marmalade.tts.engine.kitten.PAD_TOKEN
import app.marmalade.tts.engine.kitten.encodePhonemes
import app.marmalade.tts.perf.CpuClusterDetector
import app.marmalade.tts.phonemizer.EnPhonemeFixups
import app.marmalade.tts.phonemizer.EspeakPhonemizer
import app.marmalade.tts.phonemizer.SharedEspeakData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
// KittenStreamDevEngine — Kitten nano, plan once / decode in slices, DEBUG ONLY
// -----------------------------------------------------------------------------
//
// Issue #3 ("D1a"). The Kitten nano ONNX, cut on the desktop into two graphs
// (scripts in ~/coding/scratch/kitten-stream-d1a/):
//
//   planner.onnx  input_ids, style, speed → x, s128, har, duration
//                 Text model, durations, pitch/energy, the decoder's cheap
//                 frame-rate front, and the harmonic source. Runs ONCE per
//                 chunk: one style row, one duration/pitch plan, exactly as
//                 KittenDirect does today. Nothing is re-planned per slice.
//   gen.onnx      x, s128, har window, gn → waveform
//                 The iSTFTNet generator (~98% of the decoder's time). Its
//                 InstanceNorms are replaced by fixed per-voice statistics
//                 (`gn`, from genstats/<voice>.bin), which makes it purely
//                 convolutional, so it renders in slices that join exactly
//                 given [KittenStreamSlicing.CONTEXT_FRAMES] of context.
//
// The one approximation is those fixed statistics: on the desktop the result
// differs from the unsplit render by ~0.5 dB mel, the same as two unsplit
// renders differ from each other (Kitten's source is random).
//
// Sideloaded into `${filesDir}/engines/kitten-stream-dev/`. An optional
// `dev.properties` there overrides the slicing (`first`, `slice`, `context`,
// in frames) and turns on ORT profiling (`profile=true`, JSON written to
// `files/bench/prof-*` when the engine is released), so on-device
// experiments need no reinstall. `whole=true` runs the installed, unsplit
// kitten.onnx through the same session setup instead: the control for
// attributing the split graphs' cost on the phone. Voice style
// tables are read from the installed KittenDirect bundle. Chunking, style row,
// speed priors and inter-chunk gaps mirror [KittenDirectEngine]. Not in
// EngineRegistry or any catalog: only [EngineBenchService] calls it, and the
// debug source set keeps it out of every release build.
// -----------------------------------------------------------------------------

@Singleton
class KittenStreamDevEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val sharedEspeak: SharedEspeakData,
) : TtsEngine {

    override val engineName: String = ENGINE
    override val sampleRate: Int = KittenDirectVoiceCatalog.SAMPLE_RATE
    override val supportsNativeSpeed: Boolean = false
    override val maxInputChars: Int = 255

    private val dir: File get() = File(ctx.filesDir, "engines/$ENGINE")
    private val kittenVoices: File get() = File(ctx.filesDir, "engines/${KittenDirectEngine.ENGINE_NAME}/voices")

    private val lock = Mutex()
    @Volatile private var env: OrtEnvironment? = null
    private var planner: OrtSession? = null
    private var gen: OrtSession? = null
    private var whole: OrtSession? = null
    private var phonemizer: EspeakPhonemizer? = null
    private var dev = DevConfig()
    private val styleTables = HashMap<String, FloatArray>()
    private val normTables = HashMap<String, FloatArray>()

    override fun isInstalled(): Boolean =
        File(dir, PLANNER).isFile && File(dir, GEN).isFile && File(dir, "genstats").isDirectory &&
            kittenVoices.isDirectory

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
                planner = options(threads, "planner").use { ort.createSession(File(dir, PLANNER).absolutePath, it) }
                gen = options(threads, "gen").use { ort.createSession(File(dir, GEN).absolutePath, it) }
                if (dev.whole) {
                    val model = File(ctx.filesDir, "engines/${KittenDirectEngine.ENGINE_NAME}/kitten.onnx")
                    whole = options(threads, "whole").use { ort.createSession(model.absolutePath, it) }
                }
                phonemizer = EspeakPhonemizer(
                    libPath = EspeakPhonemizer.APK_LIB_NAME,
                    dataPath = sharedEspeak.ensure().absolutePath,
                    voice = "en-us",
                    fixupModel = EnPhonemeFixups.Model.KITTEN,
                ).also { check(it.open() >= 0) { "espeak failed to open" } }
                env = ort
                Log.i(TAG, "loaded threads=$threads $dev")
                // Warm both graphs (first-call kernel setup) before anyone times a synth.
                renderChunk("Hi.", "Hi.", WARMUP_VOICE, 1f, "en-us") { _, _ -> }
            }
        }
    }

    private fun options(threads: Int, name: String) = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        if (dev.profile) enableProfiling(File(ctx.filesDir, "bench/prof-$name").absolutePath)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setMemoryPatternOptimization(true)
        if (Build.SUPPORTED_ABIS.firstOrNull() != "x86_64") {
            addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
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
        val voice = voiceId.substringAfter(':', voiceId).lowercase()
        val espeakVoice = KittenDirectEngine.espeakVoiceFor(phonemizationLanguage)
        val chunks = TextChunker.clauseChunks(text)
        var first = true
        for ((idx, chunk) in chunks.withIndex()) {
            ensureActive()
            val gapMs = when {
                idx == chunks.size - 1 -> 0
                chunk.sentenceEnd -> SENTENCE_GAP_MS
                else -> CLAUSE_GAP_MS
            }
            // Each slice goes out the moment it is rendered; the boundary gap rides on the last.
            lock.withLock {
                renderChunk(chunk.text, chunk.rowText, voice, speed, espeakVoice) { pcm, last ->
                    val out = if (last && gapMs > 0) pcm.copyOf(pcm.size + sampleRate * gapMs / 1000) else pcm
                    if (first) {
                        Log.d(PERF_TAG, "kstream TTFA=${(System.nanoTime() - t0) / 1_000_000}ms")
                        first = false
                    }
                    send(SynthAudio(out, sampleRate))
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Plan [text] once, then render its speech frames slice by slice, handing
     * each slice's PCM to [emit] (with `true` on the last) as soon as it
     * exists. Caller holds [lock].
     */
    private suspend fun renderChunk(
        text: String,
        rowText: String,
        voice: String,
        speed: Float,
        espeakVoice: String,
        emit: suspend (ShortArray, Boolean) -> Unit,
    ) {
        val ort = env ?: error("not loaded")
        val phon = phonemizer ?: error("phonemizer missing")
        var ipa = phon.phonemize(text, espeakVoice)
        if (ipa.length > MAX_PHONEMES) {
            Log.w(TAG, "IPA ${ipa.length} > $MAX_PHONEMES — truncated (dev engine; KittenDirect re-splits)")
            ipa = ipa.take(MAX_PHONEMES)
        }
        if (ipa.isEmpty()) return
        val phonemes = encodePhonemes(ipa)
        val ids = IntArray(phonemes.size + 3).also {
            it[0] = PAD_TOKEN; phonemes.copyInto(it, 1); it[phonemes.size + 1] = KITTEN_END_TOKEN; it[phonemes.size + 2] = PAD_TOKEN
        }
        val style = styleRow(voice, rowText.length)
        val modelSpeed = KittenDirectEngine.effectiveModelSpeed(voice, speed)

        whole?.let { model ->
            renderWhole(ort, model, ids, style, modelSpeed)?.let { emit(it, true) }
            return
        }

        val tPlan = System.nanoTime()
        val idsT = OnnxTensor.createTensor(ort, java.nio.LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong()))
        val styleT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(style), longArrayOf(1, STYLE_DIM.toLong()))
        val speedT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(floatArrayOf(modelSpeed)), longArrayOf(1))
        val x: FloatArray
        val xFrames: Int
        val s128: FloatArray
        val har: FloatArray
        val dur: LongArray
        try {
            planner!!.run(mapOf("input_ids" to idsT, "style" to styleT, "speed" to speedT)).use { r ->
                val xt = r.get(GEN_X).get() as OnnxTensor
                xFrames = xt.info.shape[2].toInt()
                x = xt.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                s128 = (r.get(GEN_S).get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                har = (r.get(GEN_HAR).get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                dur = KittenDirectEngine.extractDurations(r.get("duration").get().value) ?: error("no durations")
            }
        } finally {
            idsT.close(); styleT.close(); speedT.close()
        }
        val planMs = (System.nanoTime() - tPlan) / 1_000_000
        val totalFrames = xFrames / 2
        check(dur.sum().toInt() == totalFrames && har.size == totalFrames * KittenTrim.FRAME_SAMPLES) {
            "planner contract broken: Σdur=${dur.sum()} xFrames=$xFrames har=${har.size}"
        }
        val speech = KittenStreamSlicing.speechFrames(ids, dur) ?: return
        val slices = KittenStreamSlicing.slices(speech, totalFrames, dev.first, dev.slice, dev.context)
        Log.d(PERF_TAG, "kstream plan=${planMs}ms tokens=${ids.size} frames=$totalFrames speech=${speech.first}..${speech.last} slices=${slices.size}")

        val gn = normTable(voice)
        val channels = x.size / xFrames
        val gnT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(gn), longArrayOf(2, (gn.size / 2).toLong()))
        val sT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(s128), longArrayOf(1, s128.size.toLong()))
        try {
            for ((si, slice) in slices.withIndex()) {
                val tGen = System.nanoTime()
                val wa = slice.render.first
                val wf = slice.render.last - wa + 1
                val xw = directFloats(channels * 2 * wf)
                for (c in 0 until channels) xw.put(x, c * xFrames + 2 * wa, 2 * wf)
                xw.rewind()
                val hw = directFloats(wf * KittenTrim.FRAME_SAMPLES)
                hw.put(har, wa * KittenTrim.FRAME_SAMPLES, wf * KittenTrim.FRAME_SAMPLES).rewind()
                val xT = OnnxTensor.createTensor(ort, xw, longArrayOf(1, channels.toLong(), 2L * wf))
                val hT = OnnxTensor.createTensor(ort, hw, longArrayOf(1, wf.toLong() * KittenTrim.FRAME_SAMPLES, 1))
                val pcm = try {
                    gen!!.run(mapOf(GEN_X to xT, GEN_S to sT, GEN_HAR to hT, "gn" to gnT)).use { r ->
                        val wav = r[0].value as FloatArray
                        ShortArray(slice.keepSamples) { i ->
                            (wav[slice.keepOffsetSamples + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                    }
                } finally {
                    xT.close(); hT.close()
                }
                Log.d(PERF_TAG, "kstream slice=$si/${slices.size} gen=${(System.nanoTime() - tGen) / 1_000_000}ms " +
                    "keep=${slice.keep.first}..${slice.keep.last} render=${slice.render.first}..${slice.render.last}")
                emit(pcm, si == slices.size - 1)
            }
        } finally {
            gnT.close(); sT.close()
        }
    }

    /** The unsplit model, timed and trimmed like KittenDirect (`whole=true` control). */
    private fun renderWhole(ort: OrtEnvironment, model: OrtSession, ids: IntArray, style: FloatArray, speed: Float): ShortArray? {
        val t0 = System.nanoTime()
        val idsT = OnnxTensor.createTensor(ort, java.nio.LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong()))
        val styleT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(style), longArrayOf(1, STYLE_DIM.toLong()))
        val speedT = OnnxTensor.createTensor(ort, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1))
        try {
            model.run(mapOf("input_ids" to idsT, "style" to styleT, "speed" to speedT)).use { r ->
                val wav = r[0].value as FloatArray
                val dur = KittenDirectEngine.extractDurations(r[1].value) ?: return null
                Log.d(PERF_TAG, "kstream whole=${(System.nanoTime() - t0) / 1_000_000}ms tokens=${ids.size} frames=${dur.sum()}")
                val trimmed = KittenTrim.trim(ids, wav, dur) ?: return null
                return ShortArray(trimmed.size) { (trimmed[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
            }
        } finally {
            idsT.close(); styleT.close(); speedT.close()
        }
    }

    private fun directFloats(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun styleRow(voice: String, textLen: Int): FloatArray {
        val table = styleTables.getOrPut(voice) { readFloats(File(kittenVoices, "$voice.bin")) }
        val row = textLen.coerceIn(0, table.size / STYLE_DIM - 1)
        return table.copyOfRange(row * STYLE_DIM, (row + 1) * STYLE_DIM)
    }

    /** float32 [2, S]: per-channel multiply (row 0) and add (row 1) for the generator's norm layers. */
    private fun normTable(voice: String): FloatArray =
        normTables.getOrPut(voice) { readFloats(File(dir, "genstats/$voice.bin")) }

    private fun readFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        return FloatArray(bytes.size / 4).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
        }
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
                planner = null; gen = null; whole = null; phonemizer = null
                styleTables.clear(); normTables.clear()
                env = null
            }
        }
    }

    /** Slicing and profiling, overridable from `dev.properties` in the bundle directory. */
    private data class DevConfig(
        val first: Int = KittenStreamSlicing.FIRST_SLICE_FRAMES,
        val slice: Int = KittenStreamSlicing.SLICE_FRAMES,
        val context: Int = KittenStreamSlicing.CONTEXT_FRAMES,
        val profile: Boolean = false,
        val whole: Boolean = false,
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
                )
            }
        }
    }

    companion object {
        /** Engine name, and the bundle directory under `${filesDir}/engines/`. */
        const val ENGINE = "kitten-stream-dev"
        private const val TAG = "KittenStreamDev"
        private const val PERF_TAG = "StreamPerf"
        private const val PLANNER = "planner.onnx"
        private const val GEN = "gen.onnx"
        // Graph-boundary tensor names: planner outputs and generator inputs.
        private const val GEN_X = "/decoder/decode.3/Mul_output_0"
        private const val GEN_S = "/Unsqueeze_12_output_0"
        private const val GEN_HAR = "/decoder/generator/m_source/l_tanh/Tanh_output_0"
        private const val STYLE_DIM = 256
        private const val MAX_PHONEMES = 500
        private const val WARMUP_VOICE = "bella"
        // Same boundary gaps as KittenDirectEngine (Max's 2026-08-07 ear-lab).
        private const val CLAUSE_GAP_MS = 80
        private const val SENTENCE_GAP_MS = 260
    }
}
