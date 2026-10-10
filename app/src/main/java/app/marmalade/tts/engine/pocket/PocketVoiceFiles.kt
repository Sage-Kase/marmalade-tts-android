package app.marmalade.tts.engine.pocket

import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.engine.PocketEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Which built-in Pocket voices an install can actually speak (#44).
 *
 * A voice needs its reference `voices/<key>.wav` or its prepared
 * `voice_cache/<key>.emb`. Downloads differ: v21 carries five of today's
 * voices, v27 all ten, and a v21 install keeps working (Outdated) until the
 * user updates. So the catalog says what Pocket offers; the disk says what
 * this phone has.
 */
internal object PocketVoiceFiles {

    /** The voice every request falls back to; the install check requires its WAV. */
    val DEFAULT_KEY: String = PocketVoiceCatalog.DEFAULT_VOICE_ID.substringAfterLast(':')

    fun has(engineDir: File, key: String): Boolean =
        nonEmpty(File(engineDir, "voices/$key.wav")) || nonEmpty(File(engineDir, "voice_cache/$key.emb"))

    /** The catalog's offered voices present in [engineDir], by key, in catalog order. */
    fun presentKeys(engineDir: File): Set<String> =
        PocketVoiceCatalog.voices
            .map { it.id.substringAfter(':') }
            .filterTo(LinkedHashSet()) { has(engineDir, it) }

    private fun nonEmpty(f: File): Boolean = f.isFile && f.length() > 0L
}

/**
 * Picks the voice a request is rendered with: the requested one when its
 * files are present (or it is a cloned voice, which lives elsewhere), else
 * [PocketVoiceFiles.DEFAULT_KEY]. [onFallback] runs once per missing voice,
 * not per sentence, so a long read in a missing voice logs one line.
 */
internal class PocketVoiceFallback(
    private val isPresent: (String) -> Boolean,
    private val onFallback: (requested: String, used: String) -> Unit,
) {
    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun keyFor(requested: String): String {
        if (requested.startsWith(PocketEngine.CLONED_VOICE_PREFIX) || isPresent(requested)) return requested
        if (reported.add(requested)) onFallback(requested, PocketVoiceFiles.DEFAULT_KEY)
        return PocketVoiceFiles.DEFAULT_KEY
    }
}
