package app.marmalade.tts.engine

import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.devbench.KokoroStreamDevEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import java.io.File

/**
 * Engines only the DEBUG build offers (issue #9): the streaming Kokoro (#5)
 * dev engine, pickable like any other voice so Max can listen to articles and
 * system TTS through it before deciding on shipping. Its bundle is sideloaded
 * (no catalog entry, no download). The streaming Kitten dev engine (#3) was
 * retired when KittenDirect itself began streaming (issue #10).
 *
 * The release source set has an empty twin of this file, so no release
 * build lists, routes to or loads them. [EngineRegistry] appends [names] to
 * its name table and takes the instances from [BuildTypeEngineModule];
 * startup seeds [voices]; the picker asks [isInstalled].
 */
object BuildTypeEngines {

    val names: List<String> = listOf(KokoroStreamDevEngine.ENGINE)

    /** Voices that have generator statistics in the sideloaded bundle: the two with a stats model. */
    val voices: List<VoiceMeta> =
        listOf("af_heart", "am_michael").mapNotNull { key ->
            KokoroDirectVoiceCatalog.voices.firstOrNull { it.id.endsWith(":$key") }
                ?.copy(id = "${KokoroStreamDevEngine.ENGINE}:$key", engine = KokoroStreamDevEngine.ENGINE)
        }

    /** Label for the picker, the alias editor and notifications; null for an engine not listed here. */
    fun displayName(engineName: String): String? = when (engineName) {
        KokoroStreamDevEngine.ENGINE -> "Kokoro (streaming)"
        else -> null
    }

    /** Human voice label for [voiceKey] ("am_michael" → "Michael"); null to use the default. */
    fun voiceLabel(engineName: String, voiceKey: String): String? =
        if (engineName == KokoroStreamDevEngine.ENGINE) KokoroDirectVoiceCatalog.bareName(voiceKey) else null

    /** Whether [engineName]'s sideloaded files are under [filesDir]. */
    fun isInstalled(engineName: String, filesDir: File): Boolean = when (engineName) {
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
    fun engines(kokoro: KokoroStreamDevEngine): Set<TtsEngine> = setOf(kokoro)
}
