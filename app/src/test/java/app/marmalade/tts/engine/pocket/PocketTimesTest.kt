package app.marmalade.tts.engine.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketTimesTest {

    @Test
    fun `the AR line splits a chunk's loop time by graph`() {
        val t = PocketTimes().apply {
            mainNs = 2_000_000_000; mainStateNs = 500_000_000; flowNs = 400_000_000; frames = 40
        }
        assertEquals(
            "pocket chunk=1 prof frames=40 ar=3000ms main=2000ms(50.0/f) state=500ms(12.5/f) flow=400ms(10.0/f) rest=100ms",
            t.arLine(1, 3000),
        )
    }

    @Test
    fun `the decode line counts lead-in and batched runs`() {
        val t = PocketTimes().apply {
            leadNs = 800_000_000; leadRuns = 8; batchNs = 300_000_000; batchRuns = 1
            decodeStateNs = 100_000_000; decodedFrames = 30
        }
        assertEquals(
            "pocket chunk=0 prof decode=1250ms frames=30 lead=800ms/8runs batch=300ms/1runs state=100ms",
            t.decodeLine(0, 1250),
        )
    }
}
