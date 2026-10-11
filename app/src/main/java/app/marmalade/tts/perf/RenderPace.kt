package app.marmalade.tts.perf

import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.service.TopAppHold
import kotlinx.coroutines.flow.first

/**
 * The render speed an engine decides chunk mode on (#54,
 * [app.marmalade.tts.engine.RenderMode]): the same RTF the speed-up warning
 * reads ([SpeedPerfWarning.shouldWarn], through [of]), so the app plays whole
 * pieces exactly where it warns.
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

    /** The measured RTF when there is one, else the prediction: the one rule for both readers. */
    fun of(measuredRtf: Double?, predictedRtf: Double?): Estimate? =
        measuredRtf?.takeIf { it > 0.0 }?.let { Estimate(it, "measured") }
            ?: predictedRtf?.takeIf { it > 0.0 }?.let { Estimate(it, "predicted") }

    suspend fun estimate(settings: SettingsRepository, engineName: String): Estimate? {
        settings.engineRtf.first()[engineName]?.takeIf { it > 0.0 }?.let { return of(it, null) }
        val stored = settings.kittenRtfMeasurement.first()
            ?.takeIf { it.measuredAtMillis >= DeviceCapability.MEASURED_SINCE_MILLIS }
        val probe = DeviceProbe(
            measuredKittenRtf = stored?.rtf,
            computeScore = computeScore,
            readerOnFastCores = TopAppHold.supported,
        )
        return of(null, EngineRecommender.predictedRtf(engineName, probe))
    }
}
