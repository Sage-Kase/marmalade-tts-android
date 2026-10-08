package app.marmalade.tts.engine

import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import java.io.File

/**
 * Engines only the DEBUG build offers (issue #9), pickable like any other
 * voice so Max can listen to articles and system TTS through a prototype
 * before deciding on shipping it. Their bundles are sideloaded (no catalog
 * entry, no download).
 *
 * None today: both streaming prototypes became the shipped engines' own
 * render — "Kitten Nano (streaming)" in issue #10, "Kokoro (streaming)"
 * (`kokoro-stream-dev`) in #13 "E" — and [retiredEngines] moves what pointed
 * at them. The seam stays for the next prototype: list it in [names],
 * [voices], [displayName], [voiceLabel] and [isInstalled], and bind its
 * instance in [BuildTypeEngineModule].
 *
 * The release source set has a twin of this file with the same signatures,
 * so no release build lists, routes to or loads a debug engine.
 * [EngineRegistry] appends [names] to its name table and takes the instances
 * from [BuildTypeEngineModule]; startup seeds [voices] and applies
 * [retiredEngines]; the picker asks [isInstalled].
 */
object BuildTypeEngines {

    val names: List<String> = emptyList()

    val voices: List<VoiceMeta> = emptyList()

    /**
     * Retired debug engines → the engine that replaced them, with the same
     * voice keys. App start re-points aliases and the default voice and drops
     * the retired voice rows ([app.marmalade.tts.data.migrateRetiredEngines]).
     */
    val retiredEngines: Map<String, String> = mapOf(
        "kokoro-stream-dev" to KokoroDirectEngine.ENGINE_NAME,
    )

    /** Label for the picker, the alias editor and notifications; null for an engine not listed here. */
    fun displayName(engineName: String): String? = null

    /** Human voice label for [voiceKey]; null to use the default. */
    fun voiceLabel(engineName: String, voiceKey: String): String? = null

    /** Whether [engineName]'s sideloaded files are under [filesDir]. */
    fun isInstalled(engineName: String, filesDir: File): Boolean = false
}

/** The [BuildTypeEngines] instances, for [EngineRegistry]'s `Set<TtsEngine>`: none today. */
@Module
@InstallIn(SingletonComponent::class)
object BuildTypeEngineModule {
    @Provides
    @ElementsIntoSet
    fun engines(): Set<TtsEngine> = emptySet()
}
