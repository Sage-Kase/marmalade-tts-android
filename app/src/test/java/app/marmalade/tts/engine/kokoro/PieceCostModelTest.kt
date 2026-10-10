package app.marmalade.tts.engine.kokoro

import org.junit.Assert.assertEquals
import org.junit.Test

/** The later-chunk wait predictor (#49), on Pixel 6a plan times. */
class PieceCostModelTest {

    private fun model() = PieceCostModel(defaultFixedMs = 100.0, defaultMsPerToken = 3.0)

    @Test
    fun `before any measurement it is the default line`() {
        assertEquals(100.0 + 3.0 * 150, model().lineFor(150).at(150), 1e-9)
    }

    @Test
    fun `one measurement anchors the default slope`() {
        val m = model().apply { record(19, 100.0) }
        // 100 ms at 19 tokens, 3 ms a token: 150 tokens → 493 ms.
        assertEquals(493.0, m.lineFor(150).at(150), 1e-9)
    }

    @Test
    fun `a short and a long piece give the line through both`() {
        // English v26 plans: 19 → 100 ms, 153 → 250 ms.
        val m = model().apply { record(19, 100.0); record(153, 250.0) }
        val line = m.lineFor(151)
        assertEquals(150.0 / 134, line.msPerToken, 1e-9)
        assertEquals(247.8, line.at(151), 0.1)
        // A short piece is predicted on the same line.
        assertEquals(100.0, m.lineFor(19).at(19), 1e-9)
    }

    @Test
    fun `each bin keeps its latest measurement`() {
        val m = model().apply { record(19, 100.0); record(153, 250.0); record(164, 345.0) }
        assertEquals(345.0, m.lineFor(164).at(164), 1e-9)
    }

    @Test
    fun `a falling line reads as flat`() {
        // A cold short piece slower than a warm long one.
        val m = model().apply { record(10, 260.0); record(153, 250.0) }
        val line = m.lineFor(153)
        assertEquals(0.0, line.msPerToken, 1e-9)
        assertEquals(250.0, line.fixedMs, 1e-9)
    }

    @Test
    fun `the fixed part never goes below zero`() {
        // The whole path's render: 18 → 592 ms, 162 → 2671 ms (fixed ~330 ms).
        val m = PieceCostModel(0.0, 30.0).apply { record(18, 592.0); record(162, 2671.0) }
        assertEquals(332.1, m.lineFor(162).fixedMs, 0.1)
        val steep = PieceCostModel(0.0, 30.0).apply { record(100, 100.0) }
        assertEquals(0.0, steep.lineFor(100).fixedMs, 1e-9)
    }

    @Test
    fun `nonsense measurements are ignored`() {
        val m = model().apply { record(0, 50.0); record(20, Double.NaN); record(20, -1.0) }
        assertEquals(100.0 + 3.0 * 20, m.lineFor(20).at(20), 1e-9)
    }
}
