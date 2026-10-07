package app.marmalade.tts.devbench

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.Random

/**
 * Pure logic for [OnnxBenchService] (issue #12, "X1"): the one-line run spec,
 * the input recipes it names, `.npy` reading, and the timing statistics. Kept
 * free of Android and ONNX Runtime so the JVM tests pin the formats the
 * desktop driver writes and parses.
 *
 * A spec line is whitespace-separated `key=value` pairs:
 *
 *     name=s1.fp32.cpu model=x1/m/s1.fp32.onnx ep=cpu sizes=8,16,32 secPerT=0.0125
 *         in.x=f32[1,133,T+54] in.style=f32[1,256] in.speed=f32[1]=1.0
 *
 * `in.<input>=` takes one [InputSource]: `f32[dims]` (seeded uniform in
 * [-1, 1)), `i64[dims]%N` (seeded ints in [0, N)), `f32[dims]=v` /
 * `i64[dims]=v` (constant), `npy:<path>` (a file, whole) or
 * `npy:<path>|<axis>:<len>` (its first `len` entries along `axis`, wrapping
 * round when `len` is longer). Every dim and `len` is a [DimExpr]: an integer
 * or `[k*]T[±c]`, evaluated at each of `sizes`.
 */
internal object OnnxBenchSpec {

    /** Logcat tag of every harness line — `adb logcat -s X1Bench`. */
    const val TAG = "X1Bench"

    enum class Ep { CPU, XNNPACK }

    enum class Opt { ALL, EXTENDED, BASIC, NONE }

    data class Spec(
        val name: String,
        /** Model path relative to the app's filesDir (`.onnx` or `.ort`). */
        val model: String,
        val ep: Ep = Ep.CPU,
        /** Intra-op threads; null = the app's own perf-core count. */
        val threads: Int? = null,
        val opt: Opt = Opt.ALL,
        val arena: Boolean = true,
        val memPattern: Boolean = true,
        val prepack: Boolean = true,
        val spin: Boolean = false,
        /** Memory-map an `.ort` file and point initializers into the mapping. */
        val mmap: Boolean = false,
        /** Shrink the CPU arena after every run (needs [arena]). */
        val shrink: Boolean = false,
        val sizes: List<Int> = listOf(1),
        /** Timed calls per size, after one untimed-for-the-median first call. */
        val calls: Int = 7,
        /** Seconds of audio per unit of T; 0 when T isn't audio (tokens, steps). */
        val secPerT: Double = 0.0,
        val inputs: Map<String, InputSource>,
    )

    /** `k*T + c`. A plain integer is `k = 0`. */
    data class DimExpr(val k: Long, val c: Long) {
        fun at(t: Int): Long = k * t + c

        companion object {
            private val LINEAR = Regex("""^(?:(\d+)\*)?T(?:([+-])(\d+))?$""")

            fun parse(s: String): DimExpr {
                s.toLongOrNull()?.let { return DimExpr(0, it) }
                val m = LINEAR.matchEntire(s) ?: throw IllegalArgumentException("bad dim '$s'")
                val k = m.groupValues[1].ifEmpty { "1" }.toLong()
                val c = m.groupValues[3].ifEmpty { "0" }.toLong()
                return DimExpr(k, if (m.groupValues[2] == "-") -c else c)
            }
        }
    }

    enum class DType { F32, I64, I32 }

    sealed class InputSource {
        data class Random(val dtype: DType, val dims: List<DimExpr>, val below: Long? = null) : InputSource()
        data class Const(val dtype: DType, val dims: List<DimExpr>, val value: Double) : InputSource()
        data class Npy(val path: String, val axis: Int? = null, val len: DimExpr? = null) : InputSource()

        companion object {
            private val TENSOR = Regex("""^(f32|i64|i32)\[([^\]]*)](?:%(\d+)|=(-?[0-9.eE+-]+))?$""")

            fun parse(s: String): InputSource {
                if (s.startsWith("npy:")) {
                    val body = s.removePrefix("npy:")
                    val bar = body.lastIndexOf('|')
                    if (bar < 0) return Npy(body)
                    val (axis, len) = body.substring(bar + 1).split(':', limit = 2).also {
                        require(it.size == 2) { "bad npy slice '$s'" }
                    }
                    return Npy(body.substring(0, bar), axis.toInt(), DimExpr.parse(len))
                }
                val m = TENSOR.matchEntire(s) ?: throw IllegalArgumentException("bad input source '$s'")
                val dtype = DType.valueOf(m.groupValues[1].uppercase(Locale.ROOT))
                val dims = m.groupValues[2].split(',').map { DimExpr.parse(it.trim()) }
                return when {
                    m.groupValues[4].isNotEmpty() -> Const(dtype, dims, m.groupValues[4].toDouble())
                    m.groupValues[3].isNotEmpty() -> {
                        require(dtype != DType.F32) { "'%N' needs an integer type: '$s'" }
                        Random(dtype, dims, m.groupValues[3].toLong())
                    }
                    else -> {
                        require(dtype == DType.F32) { "integer random input needs '%N': '$s'" }
                        Random(dtype, dims)
                    }
                }
            }
        }
    }

    /** One line of a spec file → [Spec]. */
    fun parseLine(line: String): Spec {
        val kv = LinkedHashMap<String, String>()
        for (token in line.trim().split(Regex("\\s+"))) {
            val eq = token.indexOf('=')
            require(eq > 0) { "bad token '$token'" }
            val key = token.substring(0, eq)
            require(key !in kv) { "duplicate key '$key'" }
            kv[key] = token.substring(eq + 1)
        }
        fun flag(key: String, default: Boolean): Boolean = when (val v = kv[key]) {
            null -> default
            "1" -> true
            "0" -> false
            else -> throw IllegalArgumentException("$key must be 0 or 1, was '$v'")
        }
        val inputs = kv.filterKeys { it.startsWith("in.") }
            .map { (k, v) -> k.removePrefix("in.") to InputSource.parse(v) }
            .toMap()
        val known = setOf(
            "name", "model", "ep", "threads", "opt", "arena", "mempattern", "prepack",
            "spin", "mmap", "shrink", "sizes", "calls", "secPerT",
        )
        val unknown = kv.keys.filterNot { it in known || it.startsWith("in.") }
        require(unknown.isEmpty()) { "unknown keys $unknown" }
        val spec = Spec(
            name = requireNotNull(kv["name"]) { "name missing" },
            model = requireNotNull(kv["model"]) { "model missing" },
            ep = kv["ep"]?.let { Ep.valueOf(it.uppercase(Locale.ROOT)) } ?: Ep.CPU,
            threads = kv["threads"]?.toInt(),
            opt = kv["opt"]?.let { Opt.valueOf(it.uppercase(Locale.ROOT)) } ?: Opt.ALL,
            arena = flag("arena", true),
            memPattern = flag("mempattern", true),
            prepack = flag("prepack", true),
            spin = flag("spin", false),
            mmap = flag("mmap", false),
            shrink = flag("shrink", false),
            sizes = kv["sizes"]?.split(',')?.map { it.toInt() } ?: listOf(1),
            calls = kv["calls"]?.toInt() ?: 7,
            secPerT = kv["secPerT"]?.toDouble() ?: 0.0,
            inputs = inputs,
        )
        require(spec.sizes.isNotEmpty() && spec.sizes.all { it > 0 }) { "sizes must be positive" }
        require(spec.calls >= 1) { "calls must be ≥ 1" }
        require(!spec.shrink || spec.arena) { "shrink needs the arena on" }
        return spec
    }

    /** The spec named [name] in a spec file (blank and `#` lines skipped). */
    fun find(fileText: String, name: String): Spec? = fileText.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { parseLine(it) }
        .firstOrNull { it.name == name }

    // -- tensors ---------------------------------------------------------------

    /** A host tensor: [data] is a FloatArray, LongArray or IntArray in C order. */
    class HostTensor(val shape: LongArray, val data: Any) {
        val count: Int get() = shape.fold(1L) { a, d -> a * d }.toInt()
    }

    /**
     * Builds [source] at size [t]. [seed] makes random fills repeatable per run
     * and input; [readFile] returns a file's bytes (filesDir-relative path).
     */
    fun build(source: InputSource, t: Int, seed: Long, readFile: (String) -> ByteArray): HostTensor =
        when (source) {
            is InputSource.Random -> {
                val shape = shapeAt(source.dims, t)
                val n = count(shape)
                val rnd = Random(seed)
                when (source.dtype) {
                    DType.F32 -> HostTensor(shape, FloatArray(n) { rnd.nextFloat() * 2f - 1f })
                    DType.I64 -> HostTensor(shape, LongArray(n) { (rnd.nextDouble() * source.below!!).toLong() })
                    DType.I32 -> HostTensor(shape, IntArray(n) { (rnd.nextDouble() * source.below!!).toInt() })
                }
            }
            is InputSource.Const -> {
                val shape = shapeAt(source.dims, t)
                val n = count(shape)
                when (source.dtype) {
                    DType.F32 -> HostTensor(shape, FloatArray(n) { source.value.toFloat() })
                    DType.I64 -> HostTensor(shape, LongArray(n) { source.value.toLong() })
                    DType.I32 -> HostTensor(shape, IntArray(n) { source.value.toInt() })
                }
            }
            is InputSource.Npy -> {
                val whole = parseNpy(readFile(source.path))
                if (source.axis == null) whole else takeAlong(whole, source.axis, source.len!!.at(t).toInt())
            }
        }

    private fun shapeAt(dims: List<DimExpr>, t: Int): LongArray = LongArray(dims.size) { i ->
        dims[i].at(t).also { require(it > 0) { "dim $i is $it at T=$t" } }
    }

    private fun count(shape: LongArray): Int = shape.fold(1L) { a, d -> a * d }.toInt()

    /**
     * The first [len] entries of [x] along [axis]; past the end it wraps round
     * to the start (a long input built by repeating a short real one).
     */
    fun takeAlong(x: HostTensor, axis: Int, len: Int): HostTensor {
        require(axis in x.shape.indices && len > 0) { "bad slice axis=$axis len=$len for ${x.shape.toList()}" }
        val outer = x.shape.take(axis).fold(1L) { a, d -> a * d }.toInt()
        val along = x.shape[axis].toInt()
        val inner = x.shape.drop(axis + 1).fold(1L) { a, d -> a * d }.toInt()
        val shape = x.shape.copyOf().also { it[axis] = len.toLong() }
        val n = outer * len * inner
        fun <A> copy(src: A, dst: A) {
            for (o in 0 until outer) for (i in 0 until len) {
                System.arraycopy(src as Any, (o * along + i % along) * inner, dst as Any, (o * len + i) * inner, inner)
            }
        }
        val data: Any = when (val d = x.data) {
            is FloatArray -> FloatArray(n).also { copy(d, it) }
            is LongArray -> LongArray(n).also { copy(d, it) }
            is IntArray -> IntArray(n).also { copy(d, it) }
            else -> error("unsupported data ${d.javaClass}")
        }
        return HostTensor(shape, data)
    }

    /** A little-endian, C-order `.npy` (format 1.0–3.0) of `<f4`, `<i8` or `<i4`. */
    fun parseNpy(bytes: ByteArray): HostTensor {
        require(bytes.size > 10 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") {
            "not a .npy file"
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val major = bytes[6].toInt()
        val (headerLen, headerStart) = if (major == 1) buf.getShort(8).toInt() and 0xFFFF to 10 else buf.getInt(8) to 12
        val header = String(bytes, headerStart, headerLen, Charsets.ISO_8859_1)
        val descr = Regex("""'descr':\s*'([^']+)'""").find(header)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("npy header without descr: $header")
        require(!header.contains("'fortran_order': True")) { "fortran-order .npy unsupported" }
        val shapeText = Regex("""'shape':\s*\(([^)]*)\)""").find(header)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("npy header without shape: $header")
        val shape = shapeText.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toLong() }.toLongArray()
        val n = count(shape)
        buf.position(headerStart + headerLen)
        val data: Any = when (descr) {
            "<f4" -> FloatArray(n).also { buf.asFloatBuffer().get(it) }
            "<i8" -> LongArray(n).also { buf.asLongBuffer().get(it) }
            "<i4" -> IntArray(n).also { buf.asIntBuffer().get(it) }
            else -> throw IllegalArgumentException("unsupported npy dtype $descr")
        }
        return HostTensor(shape, data)
    }

    // -- statistics ------------------------------------------------------------

    /** `y ≈ fixed + slope·x` by least squares; null with fewer than two distinct x. */
    data class Fit(val fixed: Double, val slope: Double, val r2: Double)

    fun fit(xs: List<Double>, ys: List<Double>): Fit? {
        require(xs.size == ys.size)
        if (xs.toSet().size < 2) return null
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val slope = sxy / sxx
        val fixed = my - slope * mx
        val ssTot = ys.sumOf { (it - my) * (it - my) }
        val ssRes = xs.indices.sumOf { val e = ys[it] - (fixed + slope * xs[it]); e * e }
        return Fit(fixed, slope, if (ssTot == 0.0) 1.0 else 1 - ssRes / ssTot)
    }

    // -- log lines the desktop parses --------------------------------------------

    private fun f(value: Double, pattern: String = "%.2f"): String = String.format(Locale.ROOT, pattern, value)

    /** One size's timings: [firstMs] is the first call at this shape, [ms] the timed calls after it. */
    fun sizeLine(name: String, t: Int, secPerT: Double, firstMs: Double, ms: List<Double>, pssKb: Long, outElems: Long): String =
        "size name=$name T=$t audioMs=${f(t * secPerT * 1000, "%.1f")} firstMs=${f(firstMs)} " +
            "medianMs=${f(BenchSupport.median(ms))} minMs=${f(ms.min())} calls=${ms.size} pssKb=$pssKb outElems=$outElems"

    /** The fixed + per-T split of the median times; per audio second when T is audio. */
    fun fitLine(name: String, secPerT: Double, sizes: List<Int>, medians: List<Double>): String {
        val fit = fit(sizes.map { it.toDouble() }, medians) ?: return "fit name=$name n/a"
        val perSec = if (secPerT > 0) f(fit.slope / secPerT) else "n/a"
        return "fit name=$name fixedMs=${f(fit.fixed)} msPerT=${f(fit.slope, "%.4f")} msPerAudioSec=$perSec r2=${f(fit.r2, "%.4f")}"
    }
}
