package app.marmalade.tts.devbench

import app.marmalade.tts.devbench.OnnxBenchSpec.DType
import app.marmalade.tts.devbench.OnnxBenchSpec.DimExpr
import app.marmalade.tts.devbench.OnnxBenchSpec.InputSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the spec grammar and log lines the desktop X1 driver writes and parses (issue #12). */
class OnnxBenchSpecTest {

    @Test
    fun `dims are integers or linear in T`() {
        assertEquals(DimExpr(0, 133), DimExpr.parse("133"))
        assertEquals(DimExpr(1, 0), DimExpr.parse("T"))
        assertEquals(DimExpr(1, 54), DimExpr.parse("T+54"))
        assertEquals(DimExpr(600, 4800), DimExpr.parse("600*T+4800"))
        assertEquals(DimExpr(2, -1), DimExpr.parse("2*T-1"))
        assertEquals(176L, DimExpr.parse("2*T+16").at(80))
        assertThrows(IllegalArgumentException::class.java) { DimExpr.parse("T*2") }
        assertThrows(IllegalArgumentException::class.java) { DimExpr.parse("N") }
    }

    @Test
    fun `input sources cover random, ranged ints, constants and npy slices`() {
        assertEquals(
            InputSource.Random(DType.F32, listOf(DimExpr(0, 1), DimExpr(0, 133), DimExpr(1, 54))),
            InputSource.parse("f32[1,133,T+54]"),
        )
        assertEquals(InputSource.Random(DType.I64, listOf(DimExpr(0, 1), DimExpr(1, 0)), 178), InputSource.parse("i64[1,T]%178"))
        assertEquals(InputSource.Const(DType.F32, listOf(DimExpr(0, 1)), 1.0), InputSource.parse("f32[1]=1.0"))
        assertEquals(InputSource.Npy("x1/in/kg_x.npy"), InputSource.parse("npy:x1/in/kg_x.npy"))
        assertEquals(
            InputSource.Npy("x1/in/kg_x.npy", 2, DimExpr(2, 16)),
            InputSource.parse("npy:x1/in/kg_x.npy|2:2*T+16"),
        )
        assertThrows(IllegalArgumentException::class.java) { InputSource.parse("i64[1,T]") }
        assertThrows(IllegalArgumentException::class.java) { InputSource.parse("f32[1,T]%5") }
        assertThrows(IllegalArgumentException::class.java) { InputSource.parse("f16[1]") }
    }

    @Test
    fun `a spec line parses with defaults and input names that hold slashes`() {
        val s = OnnxBenchSpec.parseLine(
            "name=kg.cpu model=x1/m/gen.onnx sizes=20,40 secPerT=0.025 spin=1 " +
                "in./decoder/decode.3/Mul_output_0=npy:x1/in/x.npy|2:2*T+16 in.gn=npy:x1/in/gn.npy",
        )
        assertEquals("kg.cpu", s.name)
        assertEquals(OnnxBenchSpec.Ep.CPU, s.ep)
        assertEquals(OnnxBenchSpec.Opt.ALL, s.opt)
        assertNull(s.threads)
        assertTrue(s.arena && s.memPattern && s.prepack && s.spin && !s.mmap && !s.shrink)
        assertEquals(listOf(20, 40), s.sizes)
        assertEquals(7, s.calls)
        assertEquals(0.025, s.secPerT, 0.0)
        assertEquals(setOf("/decoder/decode.3/Mul_output_0", "gn"), s.inputs.keys)
    }

    @Test
    fun `a spec line rejects typos and contradictions`() {
        assertThrows(IllegalArgumentException::class.java) { OnnxBenchSpec.parseLine("name=a model=m.onnx areana=0") }
        assertThrows(IllegalArgumentException::class.java) { OnnxBenchSpec.parseLine("name=a model=m.onnx arena=yes") }
        assertThrows(IllegalArgumentException::class.java) { OnnxBenchSpec.parseLine("name=a model=m.onnx arena=0 shrink=1") }
        assertThrows(IllegalArgumentException::class.java) { OnnxBenchSpec.parseLine("name=a model=m.onnx sizes=8,0") }
        assertThrows(IllegalArgumentException::class.java) { OnnxBenchSpec.parseLine("name=a name=b model=m.onnx") }
        assertThrows(IllegalArgumentException::class.java) { OnnxBenchSpec.parseLine("model=m.onnx") }
    }

    @Test
    fun `find picks the named line and skips comments`() {
        val file = "# X1\n\nname=a model=a.onnx\nname=b model=b.ort mmap=1 ep=xnnpack\n"
        val b = OnnxBenchSpec.find(file, "b")!!
        assertEquals("b.ort", b.model)
        assertTrue(b.mmap)
        assertEquals(OnnxBenchSpec.Ep.XNNPACK, b.ep)
        assertNull(OnnxBenchSpec.find(file, "c"))
    }

    @Test
    fun `random inputs are seeded, in range and sized at T`() {
        val src = InputSource.parse("f32[1,4,T+2]")
        val a = OnnxBenchSpec.build(src, 3, seed = 5) { error("no files") }
        val b = OnnxBenchSpec.build(src, 3, seed = 5) { error("no files") }
        assertArrayEquals(longArrayOf(1, 4, 5), a.shape)
        assertArrayEquals(a.data as FloatArray, b.data as FloatArray, 0f)
        assertTrue((a.data as FloatArray).all { it >= -1f && it < 1f })
        val ids = OnnxBenchSpec.build(InputSource.parse("i64[1,T]%178"), 64, seed = 1) { error("no files") }
        assertTrue((ids.data as LongArray).all { it in 0L until 178L })
        val c = OnnxBenchSpec.build(InputSource.parse("f32[1]=1.5"), 9, seed = 1) { error("no files") }
        assertArrayEquals(floatArrayOf(1.5f), c.data as FloatArray, 0f)
    }

    @Test
    fun `random dims that come out non-positive are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            OnnxBenchSpec.build(InputSource.parse("f32[1,T-8]"), 8, seed = 1) { error("no files") }
        }
    }

    @Test
    fun `npy files parse, slice along an axis and wrap round when longer`() {
        // shape (2, 3, 2): value = 100*a + 10*b + c
        val values = FloatArray(12) { i -> (100 * (i / 6) + 10 * (i / 2 % 3) + i % 2).toFloat() }
        val npy = npyF32(values, "(2, 3, 2)")
        val whole = OnnxBenchSpec.parseNpy(npy)
        assertArrayEquals(longArrayOf(2, 3, 2), whole.shape)
        assertArrayEquals(values, whole.data as FloatArray, 0f)

        val first2 = OnnxBenchSpec.build(InputSource.parse("npy:x.npy|1:T"), 2, 0) { npy }
        assertArrayEquals(longArrayOf(2, 2, 2), first2.shape)
        assertArrayEquals(floatArrayOf(0f, 1f, 10f, 11f, 100f, 101f, 110f, 111f), first2.data as FloatArray, 0f)

        val wrapped = OnnxBenchSpec.takeAlong(whole, 1, 5)
        assertArrayEquals(longArrayOf(2, 5, 2), wrapped.shape)
        val row0 = (wrapped.data as FloatArray).copyOfRange(0, 10)
        assertArrayEquals(floatArrayOf(0f, 1f, 10f, 11f, 20f, 21f, 0f, 1f, 10f, 11f), row0, 0f)
    }

    @Test
    fun `npy reads int64 token ids and rejects other dtypes`() {
        val ids = longArrayOf(0, 50, 83, 10, 0)
        val header = headerBytes("<i8", "(1, 5)")
        val body = ByteBuffer.allocate(ids.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply { ids.forEach { putLong(it) } }.array()
        val t = OnnxBenchSpec.parseNpy(header + body)
        assertArrayEquals(longArrayOf(1, 5), t.shape)
        assertArrayEquals(ids, t.data as LongArray)
        assertThrows(IllegalArgumentException::class.java) { OnnxBenchSpec.parseNpy(headerBytes("<f2", "(1,)") + ByteArray(2)) }
        assertThrows(IllegalArgumentException::class.java) { OnnxBenchSpec.parseNpy(ByteArray(16)) }
    }

    @Test
    fun `fit recovers fixed and per-T cost`() {
        val fit = OnnxBenchSpec.fit(listOf(8.0, 16.0, 32.0, 64.0), listOf(14.0, 18.0, 26.0, 42.0))!!
        assertEquals(10.0, fit.fixed, 1e-9)
        assertEquals(0.5, fit.slope, 1e-9)
        assertEquals(1.0, fit.r2, 1e-9)
        assertNull(OnnxBenchSpec.fit(listOf(1.0, 1.0), listOf(3.0, 4.0)))
    }

    @Test
    fun `size and fit lines carry what the desktop parser reads`() {
        assertEquals(
            "size name=a T=8 audioMs=100.0 firstMs=30.50 medianMs=12.00 minMs=11.00 calls=3 pssKb=1234 outElems=2400",
            OnnxBenchSpec.sizeLine("a", 8, 0.0125, 30.5, listOf(12.0, 11.0, 13.0), 1234, 2400),
        )
        assertEquals(
            "fit name=a fixedMs=10.00 msPerT=0.5000 msPerAudioSec=40.00 r2=1.0000",
            OnnxBenchSpec.fitLine("a", 0.0125, listOf(8, 16, 32, 64), listOf(14.0, 18.0, 26.0, 42.0)),
        )
        assertEquals(
            "fit name=t fixedMs=2.00 msPerT=0.1000 msPerAudioSec=n/a r2=1.0000",
            OnnxBenchSpec.fitLine("t", 0.0, listOf(10, 20), listOf(3.0, 4.0)),
        )
        assertEquals("fit name=s n/a", OnnxBenchSpec.fitLine("s", 0.16, listOf(1), listOf(9.0)))
    }

    private fun headerBytes(descr: String, shape: String): ByteArray {
        var dict = "{'descr': '$descr', 'fortran_order': False, 'shape': $shape, }"
        while ((10 + dict.length + 1) % 64 != 0) dict += " "
        dict += "\n"
        val b = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x93.toByte()).put("NUMPY".toByteArray(Charsets.US_ASCII)).put(1).put(0).putShort(dict.length.toShort())
        return b.array() + dict.toByteArray(Charsets.US_ASCII)
    }

    private fun npyF32(values: FloatArray, shape: String): ByteArray {
        val body = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putFloat(it) } }.array()
        return headerBytes("<f4", shape) + body
    }
}
