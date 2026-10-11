package app.marmalade.tts.perf

import android.util.Log
import java.io.File

/**
 * Detects how many "performance" CPU cores are on the device.
 *
 * Modern phones use big.LITTLE-style heterogeneous CPUs:
 *   - 1 prime core (Cortex-X3/X4)
 *   - some perf cores (Cortex-A715/A720/A710)
 *   - some efficiency cores (Cortex-A510/A520)
 *
 * ONNX Runtime intra-op threading benefits from filling the fast cores,
 * but spilling onto efficiency cores often regresses matmul-heavy
 * workloads (efficiency cores are 2-4× slower at the same frequency and
 * thrash the shared cache). This detector counts cores in the *non-lowest*
 * frequency tier — the union of prime + perf, excluding efficiency.
 *
 * Reads `/sys/devices/system/cpu/cpufreq/policy*` on a sysfs that exposes
 * one policy directory per CPU cluster. Each policy lists:
 *   - `cpuinfo_max_freq` — top frequency for the cluster (kHz)
 *   - `related_cpus`     — whitespace-separated list of CPU indices
 *
 * Fallback when sysfs isn't readable (some manufacturers restrict it):
 * `Runtime.availableProcessors() / 2` rounded up. Reasonable
 * compromise for "most phones have roughly half big, half little."
 *
 * Each engine load reads sysfs afresh; it's a handful of small files, and
 * ARM core-frequency tiers don't change at runtime.
 *
 * A lone top-tier core (the "prime" core) is NOT counted (issue #13 "C").
 * Android confines an app's threads to a cpuset that follows its state, and
 * a reader running with the screen off is a foreground service, not the
 * visible app: on the Pixel 8a that cpuset is CPUs 0–7, which leaves out
 * the prime core (CPU 8). Five threads for "4 mid + 1 prime" then put one
 * thread on a slow little core, and every parallel step waits for it:
 * Kitten's generator ran 1.8× faster with 4 threads than with 5 (#12 "X1").
 * Many phones keep their single prime core out of that cpuset, and the app
 * can't read the cpuset itself (SELinux), so the rule is general.
 * Since #52 the reader holds a TTS session to its own engine while it plays
 * (TopAppHold, Android 12+), which puts it in `top-app` with the prime core,
 * as system TTS always was; whether 5 threads now win on the 8a is
 * unmeasured, so the rule stays.
 */
object CpuClusterDetector {

    private const val TAG = "CpuClusterDetector"
    private const val CPUFREQ_ROOT = "/sys/devices/system/cpu/cpufreq"

    /** A lone prime core is left out only when this many fast cores remain. */
    private const val MIN_THREADS_WITHOUT_PRIME = 2

    /**
     * Returns the intra-op thread count for this device: its performance
     * cores, without a lone prime core ([threadsFor]). Always at least 1;
     * capped at 8 since ORT gains diminish past that on consumer hardware.
     */
    fun detectPerfCoreCount(): Int {
        val detected = readFromSysfs()
        val coerced = detected.coerceIn(1, 8)
        Log.i(TAG, "Perf cluster size: $coerced (raw=$detected, total=${Runtime.getRuntime().availableProcessors()})")
        return coerced
    }

    /** One CPU cluster as sysfs describes it: a top frequency and a core count. */
    data class Cluster(val maxFreqKhz: Long, val cpuCount: Int)

    /**
     * The device's CPU clusters, or an empty list when sysfs isn't readable.
     *
     * Shared with [DeviceCapability], which weighs cores by frequency instead
     * of just counting them — same parse, two consumers, so the sysfs layout
     * knowledge stays in one place.
     */
    fun readClusters(): List<Cluster> {
        val root = File(CPUFREQ_ROOT)
        val policies = root.listFiles { f -> f.isDirectory && f.name.startsWith("policy") }
            ?: return emptyList()
        return policies.mapNotNull { policy ->
            val maxFreq = readLong(File(policy, "cpuinfo_max_freq")) ?: return@mapNotNull null
            val relatedCpus = readText(File(policy, "related_cpus")) ?: return@mapNotNull null
            val cpus = relatedCpus.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (cpus.isEmpty()) return@mapNotNull null
            Cluster(maxFreq, cpus.size)
        }
    }

    private fun readFromSysfs(): Int = threadsFor(readClusters()) ?: fallbackEstimate()

    /**
     * Intra-op threads for a device with [clusters]: the cores above the
     * lowest frequency tier, minus a lone top-tier core when at least two
     * other fast cores remain (see the class comment). With fewer, the
     * prime core stays: on a 1 + 1 + 6 (Snapdragon 765G: Pixel 5, 4a 5G)
     * dropping it would halve the threads, while two threads with one on a
     * little core still beat one. A single-tier device uses all its cores
     * but one. Null when [clusters] is empty (sysfs unreadable).
     */
    fun threadsFor(clusters: List<Cluster>): Int? {
        if (clusters.isEmpty()) return null
        val minFreq = clusters.minOf { it.maxFreqKhz }
        val perf = clusters.filter { it.maxFreqKhz > minFreq }
        if (perf.isEmpty()) {
            // Single-tier device (old Snapdragon 4xx etc.): all cores but one,
            // leaving a core for the OS scheduler.
            return (clusters.sumOf { it.cpuCount } - 1).coerceAtLeast(1)
        }
        val perfCores = perf.sumOf { it.cpuCount }
        val topFreq = perf.maxOf { it.maxFreqKhz }
        val topCores = perf.filter { it.maxFreqKhz == topFreq }.sumOf { it.cpuCount }
        return if (topCores == 1 && perfCores - 1 >= MIN_THREADS_WITHOUT_PRIME) perfCores - 1 else perfCores
    }

    private fun fallbackEstimate(): Int {
        // sysfs unreadable. Estimate from /proc-style availableProcessors:
        // most modern phones have roughly half perf, half efficiency.
        return (Runtime.getRuntime().availableProcessors() + 1) / 2
    }

    private fun readLong(f: File): Long? =
        runCatching { f.readText().trim().toLong() }.getOrNull()

    private fun readText(f: File): String? =
        runCatching { f.readText() }.getOrNull()
}
