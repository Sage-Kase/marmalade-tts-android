package app.marmalade.tts.engine.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketSessionPlanTest {

    private fun plan(threads: Int, vararg knobs: Pair<String, String>) =
        PocketSessionPlan.resolve(threads) { mapOf(*knobs)[it] }

    @Test
    fun `no knobs gives the shipped settings`() {
        val p = plan(4)
        assertEquals(PocketGraphConfig(threads = 4, xnnpack = true, spin = false), p["main"])
        assertEquals(PocketGraphConfig(threads = 2, xnnpack = true, spin = false), p["flow"])
        assertEquals(PocketGraphConfig(threads = 2, xnnpack = true, spin = false), p["dec"])
        assertEquals(PocketSessionPlan.GRAPHS.toSet(), p.keys)
    }

    @Test
    fun `flow and the decoder get half the threads but never fewer than two`() {
        assertEquals(3, plan(6)["flow"]!!.threads)
        assertEquals(2, plan(1)["flow"]!!.threads)
        assertEquals(3, plan(6)["dec"]!!.threads)
        assertEquals(6, plan(6)["text"]!!.threads)
    }

    @Test
    fun `knobs pick graphs by name and ignore unknown ones`() {
        val p = plan(4, "pocket.xnnpack" to "dec, flow,bogus", "pocket.spin" to "all", "pocket.main.threads" to "3")
        assertEquals(PocketGraphConfig(3, xnnpack = false, spin = true), p["main"])
        assertEquals(PocketGraphConfig(2, xnnpack = true, spin = true), p["dec"])
        assertEquals(PocketGraphConfig(2, xnnpack = true, spin = true), p["flow"])
        assertEquals(false, plan(4, "pocket.xnnpack" to "none")["dec"]!!.xnnpack)
        assertEquals(4, plan(4, "pocket.main.threads" to "zero")["main"]!!.threads)
    }
}
