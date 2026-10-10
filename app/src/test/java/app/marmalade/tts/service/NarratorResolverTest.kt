package app.marmalade.tts.service

import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.engine.EngineRegistry
import app.marmalade.tts.lang.VoiceChoice
import app.marmalade.tts.playback.OneShotRequest
import app.marmalade.tts.playback.OneShotVoice
import app.marmalade.tts.playback.Origin
import app.marmalade.tts.playback.ResolveTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Narrator's voice resolution — the routing rules MarmaladeSynthService
 * applied per request before C1, now once per session.
 */
class NarratorResolverTest {

    private val reverb = listOf<EffectBlock>(EffectBlock.Reverb(50f))
    private val primary = alias("id-primary", "kokoro-direct-v1_0", "kokoro-direct-v1_0:af_bella", speed = 1.3f, lang = "en-gb")
    private val zh = alias("id-zh", "kokoro-direct-v1_0", "kokoro-direct-v1_0:zf_xiaoni", speed = 1.1f)
    private val aliases = mutableMapOf(primary.id to primary, zh.id to zh)
    private var primaryId: String? = primary.id
    private var shareChoice: VoiceChoice = VoiceChoice.Primary

    private val resolver = NarratorResolver(
        // TtsRouter.resolveAlias(null, id): the named alias, else the primary.
        aliasFor = { id -> id?.let { aliases[it] } ?: primaryId?.let { aliases[it] } },
        blocksFor = { effectId -> if (effectId == "fx-reverb") reverb else emptyList() },
        shareChoice = { shareChoice },
        rulesFor = { engine -> setOf("rules-of-$engine") },
    )

    @Test
    fun `an article in the primary alias takes its voice, effect, language and speed`() = runTest {
        val v = resolver.resolve(ResolveTarget.Article(VoiceChoice.Primary, "Hello."))

        assertEquals("kokoro-direct-v1_0", v.engine)
        assertEquals(primary.voiceId, v.voiceId)
        assertEquals(reverb, v.effectBlocks)
        assertEquals("en-gb", v.phonemizationLanguage)
        assertEquals(1.3f, v.aliasSpeed)
        assertEquals(setOf("rules-of-kokoro-direct-v1_0"), v.rules)
        assertFalse(v.cloud)
    }

    @Test
    fun `an article picked for another alias reads in that alias`() = runTest {
        val v = resolver.resolve(ResolveTarget.Article(VoiceChoice.Alias("id-zh", zh.voiceId, zh.engine, zh.speed), ""))

        assertEquals(zh.voiceId, v.voiceId)
        assertEquals(1.1f, v.aliasSpeed)
    }

    /** The reader's alias deleted before its first play: the primary reads it. */
    @Test
    fun `a deleted alias falls back to the primary`() = runTest {
        aliases.remove("id-zh")

        val v = resolver.resolve(ResolveTarget.Article(VoiceChoice.Alias("id-zh", zh.voiceId, zh.engine, zh.speed), ""))

        assertEquals(primary.voiceId, v.voiceId)
    }

    @Test
    fun `an installed voice no alias uses is spoken dry at 1x with the engine's own language detection`() = runTest {
        val v = resolver.resolve(
            ResolveTarget.Article(VoiceChoice.Installed("kokoro-direct-v1_0:zf_xiaobei", "kokoro-direct-v1_0"), ""),
        )

        assertEquals("kokoro-direct-v1_0:zf_xiaobei", v.voiceId)
        assertEquals(emptyList<EffectBlock>(), v.effectBlocks)
        assertNull(v.phonemizationLanguage)
        assertEquals(1f, v.aliasSpeed)
    }

    @Test
    fun `with no alias at all an article reads in the default voice`() = runTest {
        primaryId = null

        val v = resolver.resolve(ResolveTarget.Article(VoiceChoice.Primary, ""))

        assertEquals(KittenDirectVoiceCatalog.DEFAULT_VOICE_ID, v.voiceId)
        assertEquals(EngineRegistry.engineNameFor(KittenDirectVoiceCatalog.DEFAULT_VOICE_ID), v.engine)
        assertEquals(1f, v.aliasSpeed)
    }

    @Test
    fun `an explicit voice is spoken exactly as given`() = runTest {
        val v = resolver.resolve(
            oneShot(OneShotVoice.Explicit("kitten-direct-v0_8:Bella", reverb, "en-us", speed = 1.4f)),
        )

        assertEquals("kitten-direct-v0_8", v.engine)
        assertEquals("kitten-direct-v0_8:Bella", v.voiceId)
        assertEquals(reverb, v.effectBlocks)
        assertEquals("en-us", v.phonemizationLanguage)
        assertEquals(1.4f, v.aliasSpeed)
    }

    @Test
    fun `an alias's expressiveness is carried to the engine, and an explicit voice's too`() = runTest {
        aliases[primary.id] = primary.copy(expressiveness = 0.3f)
        assertEquals(0.3f, resolver.resolve(ResolveTarget.Article(VoiceChoice.Primary, "")).expressiveness)

        val explicit = resolver.resolve(oneShot(OneShotVoice.Explicit("kitten-direct-v0_8:Bella", expressiveness = 0.9f)))
        assertEquals(0.9f, explicit.expressiveness)
    }

    @Test
    fun `no expressiveness means the engine default`() = runTest {
        assertNull(resolver.resolve(ResolveTarget.Article(VoiceChoice.Primary, "")).expressiveness)
        val installed = resolver.resolve(
            ResolveTarget.Article(VoiceChoice.Installed("kokoro-direct-v1_0:zf_xiaobei", "kokoro-direct-v1_0"), ""),
        )
        assertNull(installed.expressiveness)
    }

    /** Tasker with no voice and no primary alias: its own engine, speed and effect extras. */
    @Test
    fun `a routed one-shot with no alias speaks its fallback`() = runTest {
        primaryId = null
        val fallback = OneShotVoice.Explicit(
            KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
            reverb,
            speed = 0.8f,
            engine = "kokoro-direct-v1_0",
        )

        val v = resolver.resolve(oneShot(OneShotVoice.Routed(VoiceChoice.Primary, fallback)))

        assertEquals("kokoro-direct-v1_0", v.engine)
        assertEquals(reverb, v.effectBlocks)
        assertEquals(0.8f, v.aliasSpeed)
    }

    @Test
    fun `a routed one-shot with a primary alias ignores its fallback`() = runTest {
        val v = resolver.resolve(
            oneShot(OneShotVoice.Routed(VoiceChoice.Primary, OneShotVoice.Explicit("kitten-direct-v0_8:Bella", speed = 0.8f))),
        )

        assertEquals(primary.voiceId, v.voiceId)
        assertEquals(1.3f, v.aliasSpeed)
    }

    @Test
    fun `a shared text moved to an installed voice is spoken dry at 1x`() = runTest {
        shareChoice = VoiceChoice.Installed("kokoro-direct-v1_0:zf_xiaobei", "kokoro-direct-v1_0")

        val v = resolver.resolve(oneShot(OneShotVoice.LanguageAware()))

        assertEquals("kokoro-direct-v1_0:zf_xiaobei", v.voiceId)
        assertEquals(emptyList<EffectBlock>(), v.effectBlocks)
        assertEquals(1f, v.aliasSpeed)
        assertNull(v.phonemizationLanguage)
    }

    @Test
    fun `a shared text its primary speaks stays on the primary`() = runTest {
        val v = resolver.resolve(oneShot(OneShotVoice.LanguageAware()))

        assertEquals(primary.voiceId, v.voiceId)
    }

    @Test
    fun `cloud voices are marked, unknown engines narrow to the default`() = runTest {
        val cloud = resolver.resolve(oneShot(OneShotVoice.Explicit("${CloudApiVoiceCatalog.ENGINE}:venice:tts-kokoro:af_sky")))
        assertTrue(cloud.cloud)
        assertEquals(CloudApiVoiceCatalog.ENGINE, cloud.engine)

        val unknown = resolver.resolve(oneShot(OneShotVoice.Explicit("nosuch:voice", engine = "nosuch")))
        assertEquals(EngineRegistry.DEFAULT_ENGINE, unknown.engine)
        assertEquals(setOf("rules-of-${EngineRegistry.DEFAULT_ENGINE}"), unknown.rules)
    }

    private fun oneShot(voice: OneShotVoice) = ResolveTarget.OneShot(OneShotRequest("Hello.", voice, Origin.External))

    private fun alias(id: String, engine: String, voiceId: String, speed: Float, lang: String? = null) = VoiceAlias(
        id = id,
        name = id,
        engine = engine,
        voiceId = voiceId,
        speed = speed,
        effectPreset = "NONE",
        effectId = "fx-reverb",
        phonemizationLanguage = lang,
        createdAt = 0L,
    )
}
