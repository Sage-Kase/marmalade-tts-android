package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.install.VoicePackCatalog
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #45: an engine whose download is Outdated (the catalog has published a
 * newer one) still speaks, so its voices stay pickable until the user takes
 * the update. 1.2 re-publishes Kokoro, Pocket and Kokoro German, so a check
 * that wanted `Installed` would have emptied the pickers for nearly every
 * user after updating the app. An older Pocket download (v21) has five of
 * today's ten voices: only those five are offered.
 */
class OutdatedVoicesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val kokoro = KokoroDirectVoiceCatalog.ENGINE
    private val kokoroDe = KokoroGermanVoiceCatalog.ENGINE
    private val pocket = PocketVoiceCatalog.ENGINE
    private val kitten = KittenDirectVoiceCatalog.ENGINE

    private val v21PocketKeys = setOf("alba", "azelma", "eponine", "fantine", "javert")

    private val outdated = InstallState.Outdated(installedSha256 = "old", expectedSha256 = "new")

    /** Room's rows: every catalog voice, as the app seeds them. */
    private val allRows: List<VoiceMeta> =
        KokoroDirectVoiceCatalog.voices + KokoroGermanVoiceCatalog.voices +
            PocketVoiceCatalog.voices + KittenDirectVoiceCatalog.voices + VitsVoiceCatalog.voices

    private fun engineOf(rows: List<VoiceMeta>, engine: String) = rows.filter { it.engine == engine }

    @Test
    fun `outdated Kokoro, Kokoro German and Pocket stay listed`() = runTest {
        val installer = FakeInstaller(
            engines = mapOf(kokoro to outdated, kokoroDe to outdated, pocket to outdated),
            pocketKeys = v21PocketKeys,
        )
        val assets = probeInstalledVoiceAssets(installer, anyCloudKeySet = false)
        assertEquals(setOf(kokoro, kokoroDe, pocket), assets.engines)

        val pickable = allRows.pickableVoices(assets, showDeveloper = false, flavor = "fdroid")
        assertEquals(KokoroDirectVoiceCatalog.voices, engineOf(pickable, kokoro))
        assertEquals(KokoroGermanVoiceCatalog.voices, engineOf(pickable, kokoroDe))
    }

    @Test
    fun `an outdated v21 Pocket lists its five voices only`() = runTest {
        val installer = FakeInstaller(engines = mapOf(pocket to outdated), pocketKeys = v21PocketKeys)
        val assets = probeInstalledVoiceAssets(installer, anyCloudKeySet = false)

        val pocketIds = engineOf(allRows.pickableVoices(assets, showDeveloper = false, flavor = "fdroid"), pocket)
            .map { it.id.substringAfter(':') }
        assertEquals(listOf("alba", "azelma", "eponine", "fantine", "javert"), pocketIds)
        assertFalse("mary" in pocketIds)
    }

    @Test
    fun `a cloned Pocket voice follows the engine, not the voice files`() {
        val clone = VoiceMeta(
            id = "$pocket:cloned-1234",
            engine = pocket,
            displayName = "My voice",
            languageCode = "en-US",
            sampleRate = 24_000,
            gender = null,
        )
        val assets = InstalledVoiceAssets(engines = setOf(pocket), pocketVoices = setOf("alba"))
        assertTrue(isVoiceAvailable(clone, assets))
    }

    @Test
    fun `a corrupt, failed or missing engine stays hidden`() = runTest {
        val installer = FakeInstaller(
            engines = mapOf(
                kokoro to InstallState.Corrupt,
                pocket to InstallState.Failed("network"),
                kitten to InstallState.Installed,
            ),
            pocketKeys = v21PocketKeys,
        )
        val assets = probeInstalledVoiceAssets(installer, anyCloudKeySet = false)
        assertEquals(setOf(kitten), assets.engines)
        assertEquals(emptySet<String>(), assets.pocketVoices)

        val engines = allRows.pickableVoices(assets, showDeveloper = false, flavor = "fdroid").map { it.engine }.toSet()
        assertEquals(setOf(kitten), engines)
    }

    @Test
    fun `an outdated pack-based engine keeps its installed packs' voices`() = runTest {
        val vits = VitsVoiceCatalog.ENGINE
        val jenny = VoicePackCatalog.EN_JENNY_DIOCO_MEDIUM.id
        val installer = FakeInstaller(
            engines = mapOf(vits to outdated),
            packs = mapOf(jenny to InstallState.Installed),
        )
        val assets = probeInstalledVoiceAssets(installer, anyCloudKeySet = false)
        assertTrue(vits in assets.engines)
        assertEquals(setOf(jenny), assets.packs)
        assertTrue(allRows.pickableVoices(assets, showDeveloper = false, flavor = "fdroid").any { it.engine == vits })
    }

    @Test
    fun `the installer reads Pocket's voices from the engine directory`() = runTest {
        val files = tmp.newFolder("files")
        val voices = File(files, "engines/$pocket/voices").apply { mkdirs() }
        // v21's voices, plus marius, which is on disk but no longer offered.
        for (v in v21PocketKeys + "marius") File(voices, "$v.wav").writeText("x")
        val installer = EngineInstaller(
            filesDir = { files },
            engineHandle = { },
            httpFetcher = { _ -> throw java.io.IOException("not used") },
        )
        assertEquals(v21PocketKeys, installer.pocketVoiceKeys())
    }

    /** Reports fixed states; anything not listed is NotInstalled. */
    private class FakeInstaller(
        private val engines: Map<String, InstallState>,
        private val packs: Map<String, InstallState> = emptyMap(),
        private val pocketKeys: Set<String> = emptySet(),
    ) : EngineInstaller(
        filesDir = { File("/tmp/outdated-voices-test-unused") },
        engineHandle = { },
        httpFetcher = { _ -> throw java.io.IOException("not used") },
    ) {
        override suspend fun verify(engineName: String): InstallState =
            engines[engineName] ?: InstallState.NotInstalled

        override suspend fun verifyPack(packId: String): InstallState =
            packs[packId] ?: InstallState.NotInstalled

        override suspend fun pocketVoiceKeys(): Set<String> = pocketKeys
    }
}
