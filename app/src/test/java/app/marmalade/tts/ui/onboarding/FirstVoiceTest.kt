package app.marmalade.tts.ui.onboarding

import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.db.VoiceMeta
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice step's ordering and the first alias's name (#87). */
class FirstVoiceTest {

    private val kitten = KittenDirectVoiceCatalog.ENGINE
    private val kokoro = KokoroDirectVoiceCatalog.ENGINE

    @Test
    fun `kitten lists its best-first order, so Rosie is the default`() {
        val groups = firstVoiceGroups(
            voices = KittenDirectVoiceCatalog.voices.reversed(),
            engineOrder = listOf(kitten),
            phone = Locale.US,
            recommendedEngine = kitten,
        )

        assertEquals(KittenDirectVoiceCatalog.voices.map { it.id }, groups.single().voices.map { it.id })
        assertEquals("Rosie", groups.defaultVoice()?.displayName)
        assertTrue(groups.single().isRecommended)
    }

    @Test
    fun `a British phone hears Kokoro's British voices first, then the other English ones`() {
        val voices = firstVoiceGroups(
            voices = KokoroDirectVoiceCatalog.voices,
            engineOrder = listOf(kokoro),
            phone = Locale.UK,
            recommendedEngine = null,
        ).single().voices

        val british = voices.takeWhile { it.languageCode == "en-GB" }
        assertEquals(KokoroDirectVoiceCatalog.voices.count { it.languageCode == "en-GB" }, british.size)
        assertEquals("en-US", voices[british.size].languageCode)
        assertEquals(british.sortedBy { it.sortOrder }, british)
    }

    @Test
    fun `an American phone with Kokoro starts on Bella, Kokoro's lead voice`() {
        val groups = firstVoiceGroups(
            voices = KokoroDirectVoiceCatalog.voices.reversed(),
            engineOrder = listOf(kokoro),
            phone = Locale.US,
            recommendedEngine = kokoro,
        )

        assertEquals(KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID, groups.defaultVoice()?.id)
    }

    @Test
    fun `a phone language no engine speaks keeps the engine order`() {
        val groups = firstVoiceGroups(
            voices = KittenDirectVoiceCatalog.voices + KokoroDirectVoiceCatalog.voices,
            engineOrder = listOf(kitten, kokoro),
            phone = Locale.GERMANY,
            recommendedEngine = null,
        )

        assertEquals(listOf(kitten, kokoro), groups.map { it.engine.name })
        assertEquals("Rosie", groups.defaultVoice()?.displayName)
    }

    @Test
    fun `voices of engines outside the order or the catalog are left out`() {
        val stray = voice("cloud-api:openai:tts-1:alloy", engine = "cloud-api", name = "alloy")
        val groups = firstVoiceGroups(
            voices = KittenDirectVoiceCatalog.voices + stray,
            engineOrder = listOf(kitten, "cloud-api"),
            phone = Locale.US,
            recommendedEngine = null,
        )

        assertEquals(listOf(kitten), groups.map { it.engine.name })
    }

    @Test
    fun `nothing installed gives no groups and no default`() {
        val groups = firstVoiceGroups(emptyList(), listOf(kitten), Locale.US, kitten)
        assertTrue(groups.isEmpty())
        assertEquals(null, groups.defaultVoice())
    }

    @Test
    fun `the alias takes the voice's name, minus what alias names don't allow`() {
        assertEquals("Alba", aliasNameFor(voice("p:alba", name = "Alba")))
        assertEquals("Jenny Dioco", aliasNameFor(voice("v:jenny", name = "Jenny (Dioco)")))
        // spokenName drops Kokoro's leading flag.
        assertEquals("Heart", aliasNameFor(voice("k:af_heart", name = "🇺🇸 Heart")))
        assertEquals("default", aliasNameFor(voice("x:?", name = "(?)")))
    }

    private fun voice(id: String, engine: String = kitten, name: String) = VoiceMeta(
        id = id,
        engine = engine,
        displayName = name,
        languageCode = "en-US",
        sampleRate = 24_000,
        gender = null,
    )
}
