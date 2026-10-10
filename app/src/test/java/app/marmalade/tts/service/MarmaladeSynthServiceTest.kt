package app.marmalade.tts.service

import android.content.Intent
import app.marmalade.tts.audio.EffectBlock
import app.marmalade.tts.audio.EffectBlockJson
import app.marmalade.tts.audio.EffectChain
import app.marmalade.tts.audio.EffectPreset
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.lang.VoiceChoice
import app.marmalade.tts.playback.OneShotVoice
import app.marmalade.tts.playback.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The intent shim: how an `ACTION_SPEAK` (Speak screen and previews through
 * Synthesizer, the share sheet through SpeakDispatcher, Tasker) becomes one
 * Narrator one-shot. What the Narrator then does with it — alias routing, the
 * language-aware share, replacing a paused article — is tested against the
 * real Narrator (NarratorSessionsTest, NarratorResolverTest).
 *
 * Robolectric only so the bare Service can be constructed; no injected field
 * is touched.
 */
@RunWith(RobolectricTestRunner::class)
class MarmaladeSynthServiceTest {

    private val service = MarmaladeSynthService()

    private fun speak(build: Intent.() -> Unit = {}) =
        Intent(MarmaladeSynthService.ACTION_SPEAK)
            .putExtra(MarmaladeSynthService.EXTRA_TEXT, "Hello.")
            .apply(build)

    private fun parse(build: Intent.() -> Unit = {}) = service.parseRequest(speak(build))!!

    /** Speak screen, previews, a Tasker call naming a voice: exactly that voice. */
    @Test
    fun `a named voice is spoken exactly as asked`() {
        val parsed = parse {
            putExtra(MarmaladeSynthService.EXTRA_VOICE, "kitten-direct-v0_8:Bella")
            putExtra(MarmaladeSynthService.EXTRA_SPEED, 1.3f)
            putExtra(MarmaladeSynthService.EXTRA_EFFECT, "CAVE")
            putExtra(MarmaladeSynthService.EXTRA_LANG, "en-gb")
        }

        assertEquals("Hello.", parsed.request.text)
        assertEquals(
            OneShotVoice.Explicit(
                voiceId = "kitten-direct-v0_8:Bella",
                effectBlocks = EffectChain.blocksForPreset(EffectPreset.CAVE),
                phonemizationLanguage = "en-gb",
                speed = 1.3f,
                engine = null,
            ),
            parsed.request.voice,
        )
        assertNull("the voice's own speed, not an override", parsed.request.speed)
    }

    @Test
    fun `the applied alias's expressiveness rides along, absent means the engine default`() {
        val with = parse {
            putExtra(MarmaladeSynthService.EXTRA_VOICE, "pocket-tts-en-v2026_04:alba")
            putExtra(MarmaladeSynthService.EXTRA_EXPRESSIVENESS, 0.3f)
        }.request.voice as OneShotVoice.Explicit
        assertEquals(0.3f, with.expressiveness)

        val without = parse {
            putExtra(MarmaladeSynthService.EXTRA_VOICE, "pocket-tts-en-v2026_04:alba")
        }.request.voice as OneShotVoice.Explicit
        assertNull(without.expressiveness)
    }

    /** No voice named: the primary alias, with the caller's own extras if no alias is set. */
    @Test
    fun `no voice goes to the primary alias, the caller's extras as the fallback`() {
        val voice = parse { putExtra(MarmaladeSynthService.EXTRA_ENGINE, "kokoro-direct-v1_0") }.request.voice

        assertEquals(
            OneShotVoice.Routed(
                VoiceChoice.Primary,
                fallback = OneShotVoice.Explicit(
                    voiceId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID,
                    engine = "kokoro-direct-v1_0",
                ),
            ),
            voice,
        )
    }

    /** Shared text follows its language (Max, 2026-09-28). */
    @Test
    fun `shared text with no voice gets the language-aware route`() {
        val voice = parse { putExtra(MarmaladeSynthService.EXTRA_SHARED, true) }.request.voice

        assertEquals(
            OneShotVoice.LanguageAware(OneShotVoice.Explicit(KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)),
            voice,
        )
    }

    @Test
    fun `a caller that names a voice keeps it, shared or not`() {
        val voice = parse {
            putExtra(MarmaladeSynthService.EXTRA_SHARED, true)
            putExtra(MarmaladeSynthService.EXTRA_VOICE, "kitten-direct-v0_8:Bella")
        }.request.voice

        assertEquals(OneShotVoice.Explicit("kitten-direct-v0_8:Bella"), voice)
    }

    /** An in-app request has a screen to show its errors; the rest get a notification. */
    @Test
    fun `an in-app request id makes an in-app one-shot, none an external one`() {
        val inApp = parse { putExtra(MarmaladeSynthService.EXTRA_REQUEST_ID, 42L) }
        assertEquals(42L, inApp.requestId)
        assertEquals(Origin.InApp, inApp.request.origin)

        val external = parse()
        assertEquals(0L, external.requestId)
        assertEquals(Origin.External, external.request.origin)
    }

    /** Custom effects have no preset name, so in-app callers send the exact chain. */
    @Test
    fun `an in-app effect chain wins over the preset, a broken one falls back to it`() {
        val chain = listOf(EffectBlock.Reverb(50f))
        val exact = parse {
            putExtra(MarmaladeSynthService.EXTRA_VOICE, "kitten-direct-v0_8:Bella")
            putExtra(MarmaladeSynthService.EXTRA_EFFECT, "CAVE")
            putExtra(MarmaladeSynthService.EXTRA_EFFECT_BLOCKS, EffectBlockJson.encode(chain))
        }.request.voice as OneShotVoice.Explicit
        assertEquals(chain, exact.effectBlocks)

        val broken = parse {
            putExtra(MarmaladeSynthService.EXTRA_VOICE, "kitten-direct-v0_8:Bella")
            putExtra(MarmaladeSynthService.EXTRA_EFFECT, "CAVE")
            putExtra(MarmaladeSynthService.EXTRA_EFFECT_BLOCKS, "{not json")
        }.request.voice as OneShotVoice.Explicit
        assertEquals(EffectChain.blocksForPreset(EffectPreset.CAVE), broken.effectBlocks)
    }

    /** A typo'd extra from a third-party caller is spoken dry rather than failing. */
    @Test
    fun `an unknown effect preset is spoken dry`() {
        val voice = parse {
            putExtra(MarmaladeSynthService.EXTRA_VOICE, "kitten-direct-v0_8:Bella")
            putExtra(MarmaladeSynthService.EXTRA_EFFECT, "ECHO_CHAMBER")
        }.request.voice as OneShotVoice.Explicit

        assertEquals(emptyList<EffectBlock>(), voice.effectBlocks)
    }

    @Test
    fun `a request without text is refused`() {
        assertNull(service.parseRequest(Intent(MarmaladeSynthService.ACTION_SPEAK)))
        assertNull(service.parseRequest(speak { putExtra(MarmaladeSynthService.EXTRA_TEXT, "   ") }))
    }
}
