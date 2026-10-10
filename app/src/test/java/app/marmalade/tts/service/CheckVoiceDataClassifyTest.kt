package app.marmalade.tts.service

import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VitsVoiceCatalog
import app.marmalade.tts.data.db.VoiceMeta
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CheckVoiceDataActivity.classifyVoices] — the pure
 * classification behind the CHECK_TTS_DATA report. Settings gates the
 * Play-example button and Language picker on the available list, so
 * getting this wrong greys both out (the v1.0.0-beta.1 bug: the report
 * keyed off the vestigial VoiceMeta.isInstalled flag and always came
 * back empty; later, with VITS missing, a Jenny-only device did too).
 */
class CheckVoiceDataClassifyTest {

    private fun voice(id: String, engine: String, lang: String) = VoiceMeta(
        id = id,
        engine = engine,
        displayName = id.substringAfter(':'),
        languageCode = lang,
        sampleRate = 24_000,
        gender = null,
    )

    @Test
    fun `voices of installed engines are available`() {
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(
                voice("kitten:Bella", "kitten-direct-v0_8", "en-US"),
                voice("pocket:Lea", "pocket-tts-en-v2026_04", "en-US"),
            ),
            isEngineInstalled = { it == "kitten-direct-v0_8" },
            installedPocketVoices = { emptyList() },
            installedVitsPacks = { emptyList() },
        )
        assertEquals(listOf("eng-USA"), available)
        assertEquals(emptyList<String>(), unavailable)
    }

    @Test
    fun `nothing installed reports english as unavailable baseline`() {
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(voice("kitten:Bella", "kitten-direct-v0_8", "en-US")),
            isEngineInstalled = { false },
            installedPocketVoices = { emptyList() },
            installedVitsPacks = { emptyList() },
        )
        assertEquals(emptyList<String>(), available)
        assertEquals(listOf("eng-USA"), unavailable)
    }

    @Test
    fun `available wins even when an uninstalled engine reported the language first`() {
        // Kokoro (uninstalled) sorts before Kitten (installed) in the DAO's
        // engine-ordered read; eng-USA must still land in available only.
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(
                voice("kokoro:af_bella", "kokoro-direct-v1_0", "en-US"),
                voice("kokoro:ff_siwis", "kokoro-direct-v1_0", "fr-FR"),
                voice("kitten:Bella", "kitten-direct-v0_8", "en-US"),
            ),
            isEngineInstalled = { it == "kitten-direct-v0_8" },
            installedPocketVoices = { emptyList() },
            installedVitsPacks = { emptyList() },
        )
        assertEquals(listOf("eng-USA"), available)
        assertEquals(listOf("fra-FRA"), unavailable)
    }

    @Test
    fun `unparseable language codes are dropped`() {
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(voice("kitten:Bella", "kitten-direct-v0_8", "xx-YY")),
            isEngineInstalled = { it == "kitten-direct-v0_8" },
            installedPocketVoices = { emptyList() },
            installedVitsPacks = { emptyList() },
        )
        assertEquals(emptyList<String>(), available)
        assertEquals(listOf("eng-USA"), unavailable)
    }

    // -- VITS: judged per pack, released only (the same filter onGetVoices uses)

    private val jenny = VitsVoiceCatalog.voices.single { it.id == VitsVoiceCatalog.voiceId("en-jenny_dioco-medium") }
    private val lada = VitsVoiceCatalog.voices.single { it.id == VitsVoiceCatalog.voiceId("uk-lada-x_low") }

    @Test
    fun `an installed released VITS pack is available with no other engine installed`() {
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(voice("kitten:Bella", "kitten-direct-v0_8", "en-US"), jenny),
            isEngineInstalled = { false },
            installedPocketVoices = { emptyList() },
            installedVitsPacks = { listOf("en-jenny_dioco-medium") },
        )
        assertEquals(listOf("eng-GBR"), available)
        assertEquals(listOf("eng-USA"), unavailable)
    }

    @Test
    fun `a VITS voice whose own pack is absent is unavailable`() {
        val (available, _) = CheckVoiceDataActivity.classifyVoices(
            listOf(jenny),
            isEngineInstalled = { false },
            installedPocketVoices = { emptyList() },
            // Some other pack is on disk — the engine is "installed", Jenny isn't.
            installedVitsPacks = { listOf("uk-lada-x_low") },
        )
        assertEquals(emptyList<String>(), available)
    }

    @Test
    fun `an installed but unreleased VITS pack is never available`() {
        // Relabelled en-US so the row has a tag TtsLocales maps (Ukrainian
        // isn't one) — release is judged by pack id, so only the gate is
        // left to keep it out of the available list.
        val (available, unavailable) = CheckVoiceDataActivity.classifyVoices(
            listOf(lada.copy(languageCode = "en-US")),
            isEngineInstalled = { false },
            installedPocketVoices = { emptyList() },
            installedVitsPacks = { listOf("uk-lada-x_low") },
        )
        assertEquals(emptyList<String>(), available)
        assertEquals(listOf("eng-USA"), unavailable)
    }

    // -- Pocket: built-in voices judged per file (#44)

    private val pocket = PocketVoiceCatalog.ENGINE

    @Test
    fun `only the Pocket voices on disk are advertised`() {
        // A v21 download: five of today's ten built-in voices. Mary isn't
        // there, so offering her would speak with Alba. A cloned voice
        // follows the engine.
        val v21 = listOf("alba", "azelma", "eponine", "fantine", "javert")
        val cloned = voice("$pocket:cloned-1234", pocket, "en-US")
        val advertised = MarmaladeTtsService.advertisableVoices(
            PocketVoiceCatalog.voices + cloned,
            isEngineInstalled = { it == pocket },
            installedPocketVoices = { v21 },
            installedVitsPacks = { emptyList() },
        ).map { it.id.substringAfter(':') }
        assertEquals(v21 + "cloned-1234", advertised)
    }

    @Test
    fun `no Pocket voice is advertised while the engine isn't installed`() {
        val advertised = MarmaladeTtsService.advertisableVoices(
            PocketVoiceCatalog.voices,
            isEngineInstalled = { false },
            installedPocketVoices = { listOf("alba") },
            installedVitsPacks = { emptyList() },
        )
        assertEquals(emptyList<VoiceMeta>(), advertised)
    }
}
