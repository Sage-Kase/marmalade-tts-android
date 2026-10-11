package app.marmalade.tts.perf

import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.service.TopAppHold
import kotlinx.coroutines.flow.first

/**
 * What an engine knows about its own render speed before it has measured
 * anything in this process (#54, [app.marmalade.tts.engine.RenderMode]): the
 * same RTF the speed-up warning reads ([SpeedPerfWarning]), so the app plays
 * whole pieces roughly where it warns.
 *
 *  1. the app's measured warm RTF for the engine ([SettingsRepository.engineRtf],
 *     fed by in-app reads), else
 *  2. [EngineRecommender.predictedRtf] from the setup probe's stored Kitten
 *     RTF (or, without one, the CPU score), on the cores in-app reading
 *     gets here ([DeviceProbe.readerOnFastCores]), else
 *  3. null.
 *
 * Never runs the probe's benchmark: an engine asking must not start another.
 */
object RenderPace {

    data class Estimate(val rtf: Double, val source: String)

    /** Tier-1 CPU score, read once per process (a few sysfs files). */
    private val computeScore: Double? by lazy { computeScoreOf(CpuClusterDetector.readClusters()) }

    suspend fun estimate(settings: SettingsRepository, engineName: String): Estimate? {
        settings.engineRtf.first()[engineName]?.takeIf { it > 0.0 }?.let { return Estimate(it, "measured") }
        val stored = settings.kittenRtfMeasurement.first()
            ?.takeIf { it.measuredAtMillis >= DeviceCapability.MEASURED_SINCE_MILLIS }
        val probe = DeviceProbe(
            measuredKittenRtf = stored?.rtf,
            computeScore = computeScore,
            readerOnFastCores = TopAppHold.supported,
        )
        return EngineRecommender.predictedRtf(engineName, probe)?.let { Estimate(it, "predicted") }
    }
}
