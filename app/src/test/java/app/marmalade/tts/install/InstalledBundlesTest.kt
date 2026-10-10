package app.marmalade.tts.install

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [EngineInstaller.installedBundles] and the log line the release gate parses
 * (#51). Robolectric for the real org.json the installer reads meta with.
 */
@RunWith(RobolectricTestRunner::class)
class InstalledBundlesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var installer: TestInstaller

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        installer = TestInstaller(EngineFilesDir { filesDir }, FakeNativeEngineHandle(), FakeHttpFetcher())
    }

    /** A meta file as the installer writes it: org.json escapes the slashes. */
    private fun stage(relDir: String, url: String?, sha: String?): File {
        val dir = File(filesDir, "engines/$relDir").apply { mkdirs() }
        if (url != null && sha != null) {
            File(dir, ".install_meta.json")
                .writeText("""{"archive_sha256":"$sha","archive_url":"${url.replace("/", "\\/")}"}""")
        }
        return dir
    }

    private fun catalog(name: String) = EngineCatalog.byName(name)!!.archive

    @Test
    fun reportsWhatEachEngineOnDiskWasInstalledFrom() = runTest {
        val kokoro = catalog("kokoro-direct-v1_0")
        val old = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v23/kokoro-direct-v1_0.tar.gz"
        stage("kokoro-direct-v1_0", old, "ab".repeat(32))
        val german = catalog("kokoro-de-v1_0")
        stage("kokoro-de-v1_0", german.url, german.sha256)
        val pocket = stage("pocket-tts-en-v2026_04", url = null, sha = null)
        val pack = VoicePackCatalog.all.first()
        stage("${pack.engine}/packs/${pack.id}", pack.archive.url, pack.archive.sha256)

        val byName = installer.installedBundles().associateBy { it.name }

        // Kitten isn't on disk, so it isn't listed.
        assertEquals(
            setOf("kokoro-direct-v1_0", "kokoro-de-v1_0", "pocket-tts-en-v2026_04", "${pack.engine}:${pack.id}"),
            byName.keys,
        )
        assertEquals(
            "kokoro-direct-v1_0 installed=v23 catalog=${InstalledBundle.releaseOf(kokoro.url)} OUTDATED sha=abababababab",
            byName.getValue("kokoro-direct-v1_0").logLine(),
        )
        assertEquals(
            "kokoro-de-v1_0 installed=v25 catalog=v25 current sha=${german.sha256.take(12)}",
            byName.getValue("kokoro-de-v1_0").logLine(),
        )
        assertEquals(
            "pocket-tts-en-v2026_04 installed=? catalog=${InstalledBundle.releaseOf(catalog("pocket-tts-en-v2026_04").url)} OUTDATED sha=?",
            byName.getValue("pocket-tts-en-v2026_04").logLine(),
        )
        assertEquals(true, byName.getValue("${pack.engine}:${pack.id}").isCurrent)
        // Read-only: unlike verify(), a missing meta isn't stamped as current.
        assertFalse(File(pocket, ".install_meta.json").exists())
    }

    @Test
    fun releaseOfReadsTheTagOrGivesUp() {
        assertEquals("v26", InstalledBundle.releaseOf(catalog("kokoro-direct-v1_0").url))
        assertEquals("?", InstalledBundle.releaseOf("https://test/ferret.tar.bz2"))
        assertEquals("?", InstalledBundle.releaseOf(null))
    }
}
