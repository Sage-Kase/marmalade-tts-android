package app.marmalade.tts.ui.onboarding

import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineDescriptor
import java.util.Locale

// -----------------------------------------------------------------------------
// The voice step's list (#87, Max's concept A): the voices on the phone,
// grouped by engine, and the one picked before the user touches anything.
//
//   - Within an engine: voices in the phone's language first (its region
//     first among those, so a British phone hears Kokoro's British voices
//     first), then the catalog's order: Kitten's best-first `sortOrder`
//     (Rosie first), Kokoro's catalog order, Pocket by name. A best-first
//     order for Pocket and Kokoro is Max's ear to set, not ours.
//   - Engines: the first one with a voice in the phone's language, taking
//     them in [engineOrder] (the phone check's engine first); none → the
//     first engine. The rest follow in [engineOrder].
//   - The default voice is the first voice of the first group. Before #87 it
//     was each catalog's fixed DEFAULT_VOICE_ID, which ignored the phone's
//     language (a Spanish phone with Kokoro got US-English Bella) and
//     Kitten's best-first order.
// -----------------------------------------------------------------------------

/** One engine's voices on the voice step, in the order they are listed. */
data class VoiceGroup(
    val engine: EngineDescriptor,
    val voices: List<VoiceMeta>,
    /** The phone check's engine: its header carries the Recommended label. */
    val isRecommended: Boolean,
)

/**
 * Group the pickable [voices] by engine for the voice step. Engines missing
 * from [engineOrder] or from the catalog are left out (onboarding offers
 * catalog engines only).
 */
internal fun firstVoiceGroups(
    voices: List<VoiceMeta>,
    engineOrder: List<String>,
    phone: Locale,
    recommendedEngine: String?,
): List<VoiceGroup> {
    val inPhoneLanguage = { v: VoiceMeta -> speaksLanguageOf(v, phone) }
    val groups = engineOrder.distinct().mapNotNull { name ->
        val descriptor = EngineCatalog.byName(name) ?: return@mapNotNull null
        val own = voices.filter { it.engine == name }
        if (own.isEmpty()) return@mapNotNull null
        VoiceGroup(
            engine = descriptor,
            voices = own.sortedWith(
                compareByDescending(inPhoneLanguage)
                    .thenByDescending { sameRegionAs(it, phone) }
                    .thenBy { it.sortOrder }
                    .thenBy { it.displayName },
            ),
            isRecommended = name == recommendedEngine,
        )
    }
    val lead = groups.firstOrNull { g -> g.voices.any(inPhoneLanguage) } ?: groups.firstOrNull()
    return listOfNotNull(lead) + groups.filter { it !== lead }
}

/** The voice the step picks before the user does: the first of the first group. */
internal fun List<VoiceGroup>.defaultVoice(): VoiceMeta? = firstOrNull()?.voices?.firstOrNull()

/**
 * The first alias is named after its voice (Max, #87 lab): "Alba", not
 * "default". Alias names allow letters, digits, spaces, `-`, `_` and `'`
 * ([VoiceAlias.isValidName]), so anything else is dropped: "Jenny (Dioco)"
 * becomes "Jenny Dioco". spokenName already drops Kokoro's leading flag.
 */
internal fun aliasNameFor(voice: VoiceMeta): String {
    val kept = voice.spokenName
        .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '\'' }
        .replace(Regex(" +"), " ")
        .trim()
        .take(VoiceAlias.MAX_NAME_LENGTH)
        .trim()
    return kept.ifEmpty { "default" }
}

private fun speaksLanguageOf(voice: VoiceMeta, phone: Locale): Boolean =
    Locale.forLanguageTag(voice.languageCode).language.equals(phone.language, ignoreCase = true)

private fun sameRegionAs(voice: VoiceMeta, phone: Locale): Boolean =
    phone.country.isNotEmpty() &&
        speaksLanguageOf(voice, phone) &&
        Locale.forLanguageTag(voice.languageCode).country.equals(phone.country, ignoreCase = true)
