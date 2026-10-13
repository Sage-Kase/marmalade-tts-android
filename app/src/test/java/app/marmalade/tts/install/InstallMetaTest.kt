package app.marmalade.tts.install

import app.marmalade.tts.install.EngineInstallerTest.Companion.ARCHIVE_ROOT
import app.marmalade.tts.install.EngineInstallerTest.Companion.KITTEN_LAYOUT
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The install meta check (`.install_meta.json` against the catalog entry).
 * Robolectric for the real org.json the installer writes and reads it with;
 * [EngineInstallerTest]'s plain JVM run stubs it out.
 */
@RunWith(RobolectricTestRunner::class)
class InstallMetaTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var fetcher: FakeHttpFetcher
    private lateinit var installer: TestInstaller

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        fetcher = FakeHttpFetcher()
        installer = TestInstaller(EngineFilesDir { filesDir }, FakeNativeEngineHandle(), fetcher)
    }

    private fun kitten(url: String, bytes: ByteArray, sameContentAs: Set<String> = emptySet()): EngineDescriptor {
        fetcher.payloads[url] = bytes
        val base = EngineCatalog.byName("kitten-direct-v0_8")!!
        return base.copy(
            archive = EngineArchive(
                url = url,
                sha256 = sha256Hex(bytes),
                sizeBytes = bytes.size.toLong(),
                archiveRoot = ARCHIVE_ROOT,
                sameContentAs = sameContentAs,
            ),
            downloadSizeBytes = bytes.size.toLong(),
        )
    }

    @Test
    fun aNewArchiveMakesTheInstallOutdated() = runTest {
        val v1 = kitten("https://test/v1.tar.bz2", buildArchive(KITTEN_LAYOUT, ARCHIVE_ROOT))
        assertTrue(installer.install(v1) {}.isSuccess)
        assertEquals(InstallState.Installed, installer.verifyAgainst(v1))

        val changed = KITTEN_LAYOUT + ("voices/bella.bin" to "v2".toByteArray())
        val v2 = kitten("https://test/v2.tar.bz2", buildArchive(changed, ARCHIVE_ROOT))
        assertTrue(installer.verifyAgainst(v2) is InstallState.Outdated)
    }

    /**
     * #65: the catalog moves to the same tar in another container (tar.bz2 →
     * tar.gz) and lists the old archive's sha in `sameContentAs`. An install
     * from the old archive stays current rather than asking for a download of
     * the same files; one the repack doesn't list is still Outdated. A fresh
     * install of the repack unpacks the same files through gzip and records
     * the repack's own sha.
     */
    @Test
    fun aRepackOfTheSameFilesLeavesTheInstallCurrent() = runTest {
        val bz2 = kitten("https://test/v1.tar.bz2", buildArchive(KITTEN_LAYOUT, ARCHIVE_ROOT))
        assertTrue(installer.install(bz2) {}.isSuccess)

        val gzBytes = buildArchive(KITTEN_LAYOUT, ARCHIVE_ROOT, gzip = true)
        val repack = kitten("https://test/v2.tar.gz", gzBytes, sameContentAs = setOf(bz2.archive.sha256))
        assertEquals(InstallState.Installed, installer.verifyAgainst(repack))
        val unlisted = kitten("https://test/v2.tar.gz", gzBytes, sameContentAs = setOf("ab".repeat(32)))
        assertTrue(installer.verifyAgainst(unlisted) is InstallState.Outdated)

        installer.uninstall(repack.name)
        assertTrue(installer.install(repack) {}.isSuccess)
        val dir = File(filesDir, "engines/${repack.name}")
        for ((name, bytes) in KITTEN_LAYOUT) {
            assertTrue("$name differs", bytes.contentEquals(File(dir, name).readBytes()))
        }
        assertEquals(InstallState.Installed, installer.verifyAgainst(repack))
        val bundle = installer.installedBundles().single { it.name == repack.name }
        assertEquals(repack.archive.sha256, bundle.installedSha256)
    }
}
