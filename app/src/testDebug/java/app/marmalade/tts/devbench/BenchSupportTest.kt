package app.marmalade.tts.devbench

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the formats the desktop side of the B1 benchmark parses. */
class BenchSupportTest {

    @Test
    fun `sentences are the non-blank, non-comment lines, trimmed`() {
        val text = "# header\n  First one.  \n\nSecond, with a comma.\n   \n#skip\nThird"
        assertEquals(
            listOf("First one.", "Second, with a comma.", "Third"),
            BenchSupport.parseSentences(text),
        )
    }

    @Test
    fun `wav header describes 16-bit mono PCM of the given rate`() {
        val pcm = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = BenchSupport.wavBytes(pcm, 24_000)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + pcm.size * 2, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + pcm.size * 2, b.getInt(4))
        assertEquals("WAVEfmt ", String(bytes, 8, 8, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(24_000, b.getInt(24))
        assertEquals(48_000, b.getInt(28)) // byte rate
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(pcm.size * 2, b.getInt(40))
        for ((i, s) in pcm.withIndex()) assertEquals(s, b.getShort(44 + i * 2))
    }

    @Test
    fun `median handles odd, even and empty lists`() {
        assertEquals(2.0, BenchSupport.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, BenchSupport.median(listOf(4.0, 1.0, 3.0, 2.0)), 0.0)
        assertTrue(BenchSupport.median(emptyList()).isNaN())
    }

    @Test
    fun `log lines carry key=value pairs with a dot decimal`() {
        val r = BenchSupport.SentenceResult(index = 3, chars = 42, ttfaMs = 120, totalMs = 250, audioMs = 1000)
        assertEquals(0.25, r.rtf, 1e-9)
        assertEquals(
            "sentence run=k7m engine=kokoro-7m-dev pass=1 idx=3 chars=42 ttfaMs=120 totalMs=250 audioMs=1000 rtf=0.250",
            BenchSupport.sentenceLine("k7m", "kokoro-7m-dev", 1, r),
        )
        val r2 = r.copy(index = 4, ttfaMs = 200, totalMs = 500)
        assertEquals(
            "pass run=k7m engine=kokoro-7m-dev pass=2 n=2 medianTtfaMs=160 medianRtf=0.375 totalAudioMs=2000 totalRenderMs=750",
            BenchSupport.passSummaryLine("k7m", "kokoro-7m-dev", 2, listOf(r, r2)),
        )
    }

    @Test
    fun `zero-length audio has no rtf`() {
        assertTrue(BenchSupport.SentenceResult(0, 0, -1, 5, 0).rtf.isNaN())
    }
}
