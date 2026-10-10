package app.marmalade.tts.engine.pocket

import app.marmalade.tts.data.Expressiveness
import kotlin.math.sqrt

/**
 * Std of the flow head's starting noise for an alias's
 * [expressiveness] (null = default): upstream's `std = temp ** 0.5`
 * (`flow_lm.py`), with the temperature from
 * [Expressiveness.pocketTemperature]. Shared by both Pocket engines.
 */
internal fun pocketNoiseStd(expressiveness: Float?): Float =
    sqrt(Expressiveness.pocketTemperature(expressiveness))
