package app.marmalade.tts.data

/**
 * An alias's "Expressiveness": how lively vs steady a sampling engine
 * sounds (issue #36). Pocket samples its speech, and this is its sampling
 * temperature — the std of the flow head's starting noise is
 * `sqrt(temperature)`. Low is steady (good for a screen reader), high is
 * lively (good for an audiobook).
 *
 * Stored on [app.marmalade.tts.data.db.VoiceAlias.expressiveness]; null
 * means "engine default". Only Pocket (and its developer twin) samples;
 * Kokoro, Kitten, VITS and cloud voices are deterministic and ignore it,
 * so the editor only offers the slider for [appliesTo] engines.
 */
object Expressiveness {

    /**
     * Slider range and step: 0.2–0.7 in 0.1 detents (Max, 2026-10-10:
     * "Have 0.7 be on the high end"): above 0.7, #36's samples began to
     * slur words.
     */
    const val MIN: Float = 0.2f
    const val MAX: Float = 0.7f
    const val STEP: Float = 0.1f

    /**
     * Pocket's temperature when the alias doesn't set one: Kyutai's
     * recommendation, 0.3 (Max, 2026-10-10, after #38's 180-clip lab:
     * "Start at kyutai's recommendation. I'll trust them."). Up to 1.1.0
     * Pocket always sampled at upstream's older DEFAULT_TEMPERATURE, 0.7.
     */
    const val POCKET_DEFAULT: Float = 0.3f

    /** Engines whose sampler reads the setting. */
    fun appliesTo(engine: String): Boolean =
        engine == PocketVoiceCatalog.ENGINE || engine == PocketDevVoiceCatalog.ENGINE

    /** The temperature Pocket samples at for [expressiveness] (null = default). */
    fun pocketTemperature(expressiveness: Float?): Float =
        expressiveness?.coerceIn(MIN, MAX) ?: POCKET_DEFAULT
}
