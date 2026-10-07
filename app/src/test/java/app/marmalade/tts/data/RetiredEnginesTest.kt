package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.ui.screen.FakeAliasDao
import app.marmalade.tts.ui.screen.FakeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #13 "E": what pointed at a retired debug engine moves to its replacement. */
class RetiredEnginesTest {

    private val retired = mapOf("kokoro-stream-dev" to "kokoro-direct-v1_0")

    private fun alias(id: String, voiceId: String) = VoiceAlias(
        id = id,
        name = id,
        engine = voiceId.substringBefore(':'),
        voiceId = voiceId,
        speed = 1.0f,
        effectPreset = "none",
        createdAt = 0L,
        fallbackAliasId = "other",
    )

    private fun voice(id: String) = VoiceMeta(
        id = id,
        engine = id.substringBefore(':'),
        displayName = id,
        languageCode = "en-US",
        sampleRate = 24_000,
        gender = null,
    )

    /** In-memory voice rows; [deleteByEngine] is all the migration writes. */
    private class MemoryVoiceDao(initial: List<VoiceMeta>) : VoiceMetaDao {
        val rows = MutableStateFlow(initial)
        override fun getAll() = rows
        override fun getByEngine(engine: String) = rows.map { list -> list.filter { it.engine == engine } }
        override suspend fun findById(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun count() = rows.value.size
        override suspend fun upsert(voice: VoiceMeta) = upsertAll(listOf(voice))
        override suspend fun upsertAll(voices: List<VoiceMeta>) {
            rows.value = rows.value.filterNot { old -> voices.any { it.id == old.id } } + voices
        }
        override suspend fun deleteByEngine(engine: String) {
            rows.value = rows.value.filterNot { it.engine == engine }
        }
    }

    @Test
    fun `a voice id on a retired engine keeps its key on the replacement`() {
        assertEquals("kokoro-direct-v1_0:af_heart", retiredVoiceReplacement("kokoro-stream-dev:af_heart", retired))
        assertEquals("kokoro-direct-v1_0:am_michael", retiredVoiceReplacement("kokoro-stream-dev:am_michael", retired))
    }

    @Test
    fun `ids on other engines, look-alike prefixes and bare names are left alone`() {
        assertNull(retiredVoiceReplacement("kokoro-direct-v1_0:af_heart", retired))
        assertNull(retiredVoiceReplacement("kokoro-stream-dev-2:af_heart", retired))
        assertNull(retiredVoiceReplacement("kokoro-stream-dev", retired))
        assertNull(retiredVoiceReplacement("", retired))
        assertNull(retiredVoiceReplacement("kokoro-stream-dev:af_heart", emptyMap()))
    }

    @Test
    fun `an alias moves engine and voice but keeps everything else`() {
        val old = alias("primary", "kokoro-stream-dev:af_heart")
        val moved = retiredAliasReplacement(old, retired)!!
        assertEquals(old.copy(engine = "kokoro-direct-v1_0", voiceId = "kokoro-direct-v1_0:af_heart"), moved)
        assertNull(retiredAliasReplacement(alias("kitten", "kitten-direct-v0_8:bella"), retired))
    }

    @Test
    fun `migration re-points aliases and the default voice and drops the retired rows, idempotently`() = runTest {
        val aliases = FakeAliasDao(
            listOf(
                alias("primary", "kokoro-stream-dev:af_heart"),
                alias("michael", "kokoro-stream-dev:am_michael"),
                alias("kitten", "kitten-direct-v0_8:bella"),
            ),
        )
        val voices = MemoryVoiceDao(
            listOf(voice("kokoro-stream-dev:af_heart"), voice("kokoro-direct-v1_0:af_heart")),
        )
        val settings = FakeSettings("kokoro-stream-dev:af_heart")

        repeat(2) { migrateRetiredEngines(retired, aliases, voices, settings) }

        assertEquals(
            mapOf(
                "primary" to "kokoro-direct-v1_0:af_heart",
                "michael" to "kokoro-direct-v1_0:am_michael",
                "kitten" to "kitten-direct-v0_8:bella",
            ),
            aliases.getAll().first().associate { it.id to it.voiceId },
        )
        assertEquals(
            mapOf("primary" to "kokoro-direct-v1_0", "michael" to "kokoro-direct-v1_0", "kitten" to "kitten-direct-v0_8"),
            aliases.getAll().first().associate { it.id to it.engine },
        )
        // Only the two retired aliases were written, once each (the second run found nothing).
        assertEquals(listOf("primary", "michael"), aliases.upsertedAliases.map { it.id })
        assertEquals("kokoro-direct-v1_0:af_heart", settings.defaultVoiceId.first())
        assertEquals(listOf("kokoro-direct-v1_0:af_heart"), voices.rows.value.map { it.id })
    }

    @Test
    fun `an empty retirement map touches nothing`() = runTest {
        val aliases = FakeAliasDao(listOf(alias("primary", "kokoro-stream-dev:af_heart")))
        val voices = MemoryVoiceDao(listOf(voice("kokoro-stream-dev:af_heart")))
        val settings = FakeSettings("kokoro-stream-dev:af_heart")
        migrateRetiredEngines(emptyMap(), aliases, voices, settings)
        assertEquals(emptyList<VoiceAlias>(), aliases.upsertedAliases)
        assertEquals("kokoro-stream-dev:af_heart", settings.defaultVoiceId.first())
        assertEquals(1, voices.rows.value.size)
    }
}
