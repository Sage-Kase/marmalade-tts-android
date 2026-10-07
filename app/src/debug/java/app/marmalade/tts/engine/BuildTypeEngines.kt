package app.marmalade.tts.engine

import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.devbench.KittenStreamDevEngine
import app.marmalade.tts.devbench.KokoroStreamDevEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import java.io.File

/**
 * Engines only the DEBUG build offers (issue #9): the streaming Kitten (#3)
 * and streaming Kokoro (#5) dev engines, pickable like any other voice so
 * Max can listen to articles and system TTS through them before deciding on
 * shipping. Their bundles are sideloaded (no catalog entry, no download).
 *
 * The release source set has an empty twin of this file, so no release
 * build lists, routes to or loads them. [EngineRegistry] appends [names] to
 * its name table and takes the instances from [BuildTypeEngineModule];
 * startup seeds [voices]; the picker asks [isInstalled].
 */
object BuildTypeEngines {

    val names: List<String> = listOf(KittenStreamDevEngine.ENGINE, KokoroStreamDevEngine.ENGINE)

    /**
     * Voices that have generator statistics in the sideloaded bundles: all 8
     * Kitten voices, and the two Kokoro voices with a stats model.
     */
    val voices: List<VoiceMeta> =
        KittenDirectVoiceCatalog.voices.map { v ->
            v.copy(id = "${KittenStreamDevEngine.ENGINE}:${v.displayName}", engine = KittenStreamDevEngine.ENGINE)
        } + listOf("af_heart", "am_michael").mapNotNull { key ->
            KokoroDirectVoiceCatalog.voices.firstOrNull { it.id.endsWith(":$key") }
                ?.copy(id = "${KokoroStreamDevEngine.ENGINE}:$key", engine = KokoroStreamDevEngine.ENGINE)
        }

    /** Label for the picker, the alias editor and notifications; null for an engine not listed here. */
    fun displayName(engineName: String): String? = when (engineName) {
        KittenStreamDevEngine.ENGINE -> "Kitten Nano (streaming)"
        KokoroStreamDevEngine.ENGINE -> "Kokoro (streaming)"
        else -> null
    }

    /** Human voice label for [voiceKey] ("am_michael" → "Michael"); null to use the default. */
    fun voiceLabel(engineName: String, voiceKey: String): String? =
        if (engineName == KokoroStreamDevEngine.ENGINE) KokoroDirectVoiceCatalog.bareName(voiceKey) else null

    /** Whether [engineName]'s sideloaded files are under [filesDir]. */
    fun isInstalled(engineName: String, filesDir: File): Boolean = when (engineName) {
        KittenStreamDevEngine.ENGINE -> KittenStreamDevEngine.isInstalledIn(filesDir)
        KokoroStreamDevEngine.ENGINE -> KokoroStreamDevEngine.isInstalledIn(filesDir)
        else -> false
    }
}

/** The [BuildTypeEngines] instances, for [EngineRegistry]'s `Set<TtsEngine>`. */
@Module
@InstallIn(SingletonComponent::class)
object BuildTypeEngineModule {
    @Provides
    @ElementsIntoSet
    fun engines(kitten: KittenStreamDevEngine, kokoro: KokoroStreamDevEngine): Set<TtsEngine> =
        setOf(kitten, kokoro)
}
