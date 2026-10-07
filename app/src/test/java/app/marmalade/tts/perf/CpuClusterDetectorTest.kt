package app.marmalade.tts.perf

import app.marmalade.tts.perf.CpuClusterDetector.Cluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The intra-op thread rule (issue #13 "C"): fast cores, minus a lone prime core. */
class CpuClusterDetectorTest {

    private fun threads(vararg clusters: Cluster) = CpuClusterDetector.threadsFor(clusters.toList())

    @Test
    fun `Pixel 8a - 4 little, 4 mid, 1 prime outside the foreground cpuset - uses the 4 mid cores`() {
        // Tensor G3 sysfs: policy0 1.70 GHz cpus 0-3, policy4 2.37 GHz 4-7, policy8 2.91 GHz 8.
        assertEquals(4, threads(Cluster(1_704_000, 4), Cluster(2_367_000, 4), Cluster(2_914_000, 1)))
    }

    @Test
    fun `1 prime + 3 big + 4 little uses the 3 big cores`() {
        assertEquals(3, threads(Cluster(1_800_000, 4), Cluster(2_500_000, 3), Cluster(3_000_000, 1)))
    }

    @Test
    fun `two mid tiers under a lone prime count both`() {
        // Snapdragon 8 Gen 2 style: 3 little, 2 + 2 mid at different clocks, 1 prime.
        assertEquals(4, threads(Cluster(2_000_000, 3), Cluster(2_800_000, 2), Cluster(2_750_000, 2), Cluster(3_200_000, 1)))
    }

    @Test
    fun `a top tier of two cores is kept`() {
        assertEquals(2, threads(Cluster(1_800_000, 6), Cluster(2_600_000, 2)))
    }

    @Test
    fun `big LITTLE 4 + 4 uses the 4 big cores`() {
        assertEquals(4, threads(Cluster(1_800_000, 4), Cluster(2_400_000, 4)))
    }

    @Test
    fun `a lone fast core with no other fast core is used`() {
        assertEquals(1, threads(Cluster(1_800_000, 7), Cluster(2_600_000, 1)))
    }

    @Test
    fun `single tier uses all cores but one`() {
        assertEquals(7, threads(Cluster(2_000_000, 8)))
        assertEquals(1, threads(Cluster(1_400_000, 1)))
    }

    @Test
    fun `unreadable sysfs has no answer`() {
        assertNull(CpuClusterDetector.threadsFor(emptyList()))
    }
}
