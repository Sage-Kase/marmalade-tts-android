package app.marmalade.tts.engine

import app.marmalade.tts.data.db.VoiceMeta
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import java.io.File

/**
 * Engines only one build type offers. The release build has none: this is
 * the empty twin of the debug source set's file, which lists the streaming
 * dev engines (issue #9). Keep the two files' signatures identical.
 */
object BuildTypeEngines {

    val names: List<String> = emptyList()

    val voices: List<VoiceMeta> = emptyList()

    fun displayName(engineName: String): String? = null

    fun voiceLabel(engineName: String, voiceKey: String): String? = null

    fun isInstalled(engineName: String, filesDir: File): Boolean = false
}

/** No extra engines: [EngineRegistry]'s `Set<TtsEngine>` is empty. */
@Module
@InstallIn(SingletonComponent::class)
object BuildTypeEngineModule {
    @Provides
    @ElementsIntoSet
    fun engines(): Set<TtsEngine> = emptySet()
}
