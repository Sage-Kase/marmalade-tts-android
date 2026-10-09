package app.marmalade.tts.data

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

/**
 * Move everything that points at a retired engine onto its replacement
 * (issue #13 "E": the debug build's `kokoro-stream-dev` became
 * KokoroDirect's own render, and a test phone's primary alias and default
 * voice pointed at it). Aliases move with [VoiceAliasDao.repointEngine],
 * as Kitten Mini's did, and keep their ids, so the primary alias, per-app
 * routes and fallback pointers follow them; the default voice moves
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
    for ((engine, replacement) in retired) aliases.repointEngine(engine, replacement)
    retiredVoiceReplacement(settings.defaultVoiceId.first(), retired)?.let { settings.setDefaultVoiceId(it) }
    for (engine in retired.keys) voices.deleteByEngine(engine)
}

/**
 * Move everything that points at a retired voice onto its replacement
 * (retired voice id → replacement voice id, same engine), the voice-level
 * twin of [migrateRetiredEngines] (issue #19: Pocket's `marius` dropped for
 * quality, replaced by `alba`). Aliases move with
 * [VoiceAliasDao.repointVoice] and keep their ids, so the primary alias,
 * per-app routes and fallback pointers follow them; the default voice moves
 * the same way; the retired voice rows go, so nothing offers them.
 * Idempotent (a second run finds nothing to move) and a no-op for an empty
 * [retired].
 */
internal suspend fun migrateRetiredVoices(
    retired: Map<String, String>,
    aliases: VoiceAliasDao,
    voices: VoiceMetaDao,
    settings: SettingsRepository,
) {
    if (retired.isEmpty()) return
    for ((from, to) in retired) aliases.repointVoice(from, to)
    retired[settings.defaultVoiceId.first()]?.let { settings.setDefaultVoiceId(it) }
    for (from in retired.keys) voices.deleteById(from)
}
