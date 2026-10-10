package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Voice catalog for the developer-only clean-room Pocket engine
 * ([app.marmalade.tts.engine.PocketDevEngine]). Mirrors the built-in
 * voices from [PocketVoiceCatalog] but under a separate engine name so
 * they appear as parallel choices in the voice picker.
 *
 * The engine has its own install (`engines/<ENGINE>/`, the older v10
 * archive, see `EngineCatalog`), so it mirrors only the voices that
 * archive holds ([BUNDLE_VOICE_KEYS]).
 */
object PocketDevVoiceCatalog {

    /**
     * Engine name. The `-dev` suffix marks it as the diagnostic reference;
     * production Pocket uses [PocketVoiceCatalog.ENGINE] without the suffix.
     */
    const val ENGINE = "pocket-tts-en-v2026_04-dev"
    const val LANGUAGE = "en-US"
    const val SAMPLE_RATE = PocketVoiceCatalog.SAMPLE_RATE

    fun voiceId(name: String): String = "$ENGINE:$name"

    /**
     * The production voices this engine's v10 archive has a WAV for. The
     * voices added in production's v27 bundle (issue #38) aren't in it, so
     * the twin doesn't list them, and its install check doesn't ask for them.
     */
    val BUNDLE_VOICE_KEYS: Set<String> = setOf("alba", "azelma", "eponine", "fantine", "javert")

    val voices: List<VoiceMeta> = PocketVoiceCatalog.voices.filter {
        it.id.substringAfterLast(':') in BUNDLE_VOICE_KEYS
    }.map { v ->
        VoiceMeta(
            // Same lowercase voice key as production Pocket's id — the engine
            // resolves `voices/<key>.wav` from it.
            id = voiceId(v.id.substringAfterLast(':')),
            engine = ENGINE,
            displayName = "${v.displayName} (clean)",
            languageCode = LANGUAGE,
            sampleRate = SAMPLE_RATE,
            gender = v.gender,
            isInstalled = false,
        )
    }

    /**
     * [PocketVoiceCatalog.retiredVoices] under this engine's ids, so the
     * clean twin's `marius` row and anything pointing at it go too.
     */
    val retiredVoices: Map<String, String> = PocketVoiceCatalog.retiredVoices.entries.associate { (from, to) ->
        voiceId(from.substringAfterLast(':')) to voiceId(to.substringAfterLast(':'))
    }
}
