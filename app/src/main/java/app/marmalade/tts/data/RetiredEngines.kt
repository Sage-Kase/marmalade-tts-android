package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import app.marmalade.tts.data.db.VoiceMetaDao
import kotlinx.coroutines.flow.first

/**
 * [voiceId] (`engine:key`) moved onto its engine's replacement in [retired]
 * (retired engine → replacement, same voice keys), or null when its engine
 * isn't retired.
 */
internal fun retiredVoiceReplacement(voiceId: String, retired: Map<String, String>): String? {
    val engine = voiceId.substringBefore(':', missingDelimiterValue = "")
    val target = retired[engine] ?: return null
    return target + voiceId.substring(engine.length)
}

/** [alias] moved onto its voice's replacement engine, or null when it isn't on a retired engine. */
internal fun retiredAliasReplacement(alias: VoiceAlias, retired: Map<String, String>): VoiceAlias? {
    val voiceId = retiredVoiceReplacement(alias.voiceId, retired) ?: return null
    return alias.copy(engine = voiceId.substringBefore(':'), voiceId = voiceId)
}

/**
 * Move everything that points at a retired engine onto its replacement
 * (issue #13 "E": the debug build's `kokoro-stream-dev` became
 * KokoroDirect's own render, and a test phone's primary alias and default
 * voice pointed at it). Aliases keep their ids, so the primary alias,
 * per-app routes and fallback pointers follow them; the default voice moves
 * the same way; the retired engine's voice rows go, so nothing offers a
 * voice no engine can speak. Idempotent (a second run finds nothing to
 * move) and a no-op for an empty [retired], which the release build always
 * passes.
 */
internal suspend fun migrateRetiredEngines(
    retired: Map<String, String>,
    aliases: VoiceAliasDao,
    voices: VoiceMetaDao,
    settings: SettingsRepository,
) {
    if (retired.isEmpty()) return
    for (alias in aliases.getAll().first()) {
        retiredAliasReplacement(alias, retired)?.let { aliases.upsert(it) }
    }
    retiredVoiceReplacement(settings.defaultVoiceId.first(), retired)?.let { settings.setDefaultVoiceId(it) }
    for (engine in retired.keys) voices.deleteByEngine(engine)
}
