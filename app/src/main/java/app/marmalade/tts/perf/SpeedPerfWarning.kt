package app.marmalade.tts.perf

import app.marmalade.tts.data.db.VoiceAlias

// -----------------------------------------------------------------------------
// The speed-up performance warning — RTF-aware, not a static threshold.
//
// The slider itself is uncapped (0.5–2.0). The job here is only to decide
// WHEN the engine the user picked won't render fast enough on this device to
// keep streaming at the rate they ask for. That one decision ([behind]) drives
// both the warning and the engines' chunk mode (#54,
// app.marmalade.tts.engine.RenderMode): from there the app plays whole
// sentences, so the warning tells the user to expect pauses between them.
// The RTF used is, in order of preference:
//   1. a rolling MEASURED warm RTF for the engine on this device
//      (SettingsRepository.engineRtf, fed by in-app reads);
//   2. EngineRecommender's PROBE-derived prediction for the engine;
//   3. neither → the old static rule (effective speed past
//      VoiceAlias.SPEED_PERF_WARNING_THRESHOLD), so a device we know nothing
//      about still gets the pre-RTF behaviour.
// The engines read the same two through RenderPace.
//
// Design record: docs/UX-NOTES.md, addendum 4 (decided at the lab).
// -----------------------------------------------------------------------------

/** Pure trigger logic for the speed-up performance warning and chunk mode. */
object SpeedPerfWarning {

    /**
     * Behind from this played RTF (RTF × effective speed) up. Below it a
     * streamed render keeps up with room to spare: on the Pixel 6a Kokoro
     * v26 (RTF ~0.43) streamed without a stall at 1.25× (0.54) and stalled
     * 60–120 ms at 1.5× (0.65) through the release gate (#51, #54). It was
     * 0.8 before #54, warning where streaming already stuttered.
     */
    const val EFFECTIVE_RTF_THRESHOLD: Double = 0.6

    /**
     * Whether an engine rendering at [rtf] (render time ÷ audio time at
     * 1.0×; null when nothing is known) falls behind streaming at
     * [effectiveSpeed]: the warning shows and the engine plays whole pieces.
     */
    fun behind(rtf: Double?, effectiveSpeed: Float): Boolean =
        if (rtf != null && rtf > 0.0) {
            rtf * effectiveSpeed > EFFECTIVE_RTF_THRESHOLD
        } else {
            effectiveSpeed > VoiceAlias.SPEED_PERF_WARNING_THRESHOLD
        }

    /**
     * Whether to show the warning for an engine asked to run at
     * [effectiveSpeed] (slider value in the alias editor; chip × alias speed
     * in the reader). [measuredRtf] wins over [predictedRtf].
     */
    fun shouldWarn(
        measuredRtf: Double?,
        predictedRtf: Double?,
        effectiveSpeed: Float,
    ): Boolean = behind(RenderPace.of(measuredRtf, predictedRtf)?.rtf, effectiveSpeed)
}

/** Rolling-average math for the per-engine measured RTF store. */
object RollingRtf {

    /**
     * Weight of the newest sample in the exponential moving average. 0.3 is
     * deliberately boring: recent enough to track a device warming up or
     * throttling within a handful of utterances, smooth enough that one odd
     * measurement (a GC pause, a backgrounded app stealing a core) doesn't
     * flip the warning on its own.
     */
    const val ALPHA: Double = 0.3

    /**
     * Fold [sample] into the running average. The first sample (null
     * [previous]) becomes the average outright; after that it's a standard
     * EMA. Cheap and allocation-free.
     */
    fun update(previous: Double?, sample: Double): Double =
        if (previous == null) sample else previous + ALPHA * (sample - previous)
}
