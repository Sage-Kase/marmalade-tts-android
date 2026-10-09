package app.marmalade.tts.install

import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #10: Kitten's baked seed is the only copy with the streaming layout,
 * so installing Kitten copies it instead of downloading the old v22 bundle,
 * and an installed pre-1.2 copy is replaced from it at startup — but an
 * uninstalled Kitten stays uninstalled. The routing tests record
 * [seedFromAssets] instead of running it.
 *
 * Issue #17: an app update that changes the seed's files (a new planner)
 * re-copies an installed Kitten exactly once, keyed on the seed's
 * SHA256SUMS. Those tests run the real copy of the real seed (~47 MB).
 */
@RunWith(RobolectricTestRunner::class)
class EngineInstallerSeedTest {

    // Under build/, not /tmp: the real-seed tests copy ~47 MB each, and /tmp
    // is a small shared tmpfs on the dev box (it filled up mid-run in #17).
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val kitten = KittenDirectVoiceCatalog.ENGINE
    private val assets: AssetManager = ApplicationProvider.getApplicationContext<Context>().assets

    private class RecordingInstaller(files: File, assets: AssetManager?) : EngineInstaller(
        filesDir = { files },
        engineHandle = { },
        httpFetcher = { _ -> throw IOException("no downloads in this test") },
        seedAssets = { assets },
    ) {
        val seeded = mutableListOf<String>()
        override suspend fun seedFromAssets(assets: AssetManager, engineName: String): Result<Unit> {
            seeded += engineName
            return Result.success(Unit)
        }
    }

    @Test
    fun `the APK carries the streaming Kitten seed`() {
        fun has(path: String) = runCatching { assets.open("engines-seed/$kitten/$path").close() }.isSuccess
        assertTrue(has("planner.onnx") && has("gen.onnx"))
        for (voice in KittenDirectVoiceCatalog.voices.map { it.displayName.lowercase() }) {
            assertTrue(voice, has("voices/$voice.bin") && has("genstats/$voice.bin"))
        }
        assertFalse(has("kitten.onnx"))
    }

    @Test
    fun `installing a baked engine copies the seed instead of downloading`() = runTest {
        val installer = RecordingInstaller(tmp.root, assets)
        assertTrue(installer.install(kitten) {}.isSuccess)
        assertEquals(listOf(kitten), installer.seeded)
    }

    @Test
    fun `without assets the install falls back to the download`() = runTest {
        val installer = RecordingInstaller(tmp.root, null)
        assertTrue(installer.install(kitten) {}.isFailure) // the fetcher refuses
        assertEquals(emptyList<String>(), installer.seeded)
    }

    @Test
    fun `refreshSeed re-seeds an installed Kitten and leaves an uninstalled one alone`() = runTest {
        val installer = RecordingInstaller(tmp.root, assets)
        assertTrue(installer.refreshSeed(assets, kitten).isSuccess)
        assertEquals("uninstalled stays uninstalled", emptyList<String>(), installer.seeded)

        File(tmp.root, "engines/$kitten").mkdirs()
        File(tmp.root, "engines/$kitten/kitten.onnx").writeText("pre-1.2 model")
        assertTrue(installer.refreshSeed(assets, kitten).isSuccess)
        assertEquals(listOf(kitten), installer.seeded)
    }

    private val dir get() = File(tmp.root, "engines/$kitten")
    private fun seedBytes(path: String) = assets.open("engines-seed/$kitten/$path").use { it.readBytes() }

    /** A file the real copy would delete: present afterwards = no copy happened. */
    private fun mark() = File(dir, "marker").writeText("left alone")
    private fun copied() = !File(dir, "marker").exists()

    private fun ok(r: Result<Unit>) = assertTrue(r.exceptionOrNull()?.stackTraceToString(), r.isSuccess)

    private fun realInstaller() = EngineInstaller(
        filesDir = { tmp.root },
        engineHandle = { },
        httpFetcher = { _ -> throw IOException("no downloads in this test") },
        seedAssets = { assets },
    )

    @Test
    fun `the seed's SHA256SUMS lists every seed file with its hash`() {
        val listed = String(seedBytes(EngineInstaller.SEED_FINGERPRINT)).lines().filter { it.isNotBlank() }
            .associate { line -> line.split(Regex("\\s+")).let { it[1] to it[0] } }
        fun walk(path: String): List<String> {
            val children = assets.list("engines-seed/$kitten/$path".trimEnd('/')).orEmpty()
            return if (children.isEmpty()) listOf(path) else children.flatMap { walk(if (path.isEmpty()) it else "$path/$it") }
        }
        val files = walk("").filter { it != EngineInstaller.SEED_FINGERPRINT }.sorted()
        assertEquals("a seed file missing from SHA256SUMS, or a stale line", files, listed.keys.sorted())
        for (name in files) {
            val sha = MessageDigest.getInstance("SHA-256")
            assets.open("engines-seed/$kitten/$name").use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; sha.update(buf, 0, n) }
            }
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            assertEquals("$name: re-run scripts/kitten-stream/split.py and update SHA256SUMS", listed[name], hex)
        }
    }

    @Test
    fun `a fresh install copies the seed with its fingerprint`() = runTest {
        ok(realInstaller().seedFromAssets(assets, kitten))
        assertTrue(File(dir, "planner.onnx").isFile)
        assertTrue(File(dir, EngineInstaller.SEED_FINGERPRINT).readBytes()
            .contentEquals(seedBytes(EngineInstaller.SEED_FINGERPRINT)))
    }

    @Test
    fun `an update with the same seed copies nothing`() = runTest {
        val installer = realInstaller()
        ok(installer.seedFromAssets(assets, kitten))
        mark()
        assertTrue(installer.refreshSeed(assets, kitten).isSuccess)
        assertFalse(copied())
    }

    @Test
    fun `an update with a changed seed copies it once`() = runTest {
        val installer = realInstaller()
        ok(installer.seedFromAssets(assets, kitten))
        File(dir, EngineInstaller.SEED_FINGERPRINT).writeText("0000  planner.onnx\n") // the old seed's sums
        mark()
        assertTrue(installer.refreshSeed(assets, kitten).isSuccess)
        assertTrue("re-copied", copied())
        mark()
        assertTrue(installer.refreshSeed(assets, kitten).isSuccess)
        assertFalse("only once", copied())
    }

    @Test
    fun `an install from before the fingerprint is replaced`() = runTest {
        val installer = realInstaller()
        ok(installer.seedFromAssets(assets, kitten))
        File(dir, EngineInstaller.SEED_FINGERPRINT).delete() // seeded by 1.2, which had no SHA256SUMS
        mark()
        assertTrue(installer.refreshSeed(assets, kitten).isSuccess)
        assertTrue(copied())
    }

    @Test
    fun `a partial copy with the right fingerprint is replaced`() = runTest {
        val installer = realInstaller()
        ok(installer.seedFromAssets(assets, kitten))
        File(dir, "gen.onnx").delete()
        mark()
        assertTrue(installer.refreshSeed(assets, kitten).isSuccess)
        assertTrue(copied())
        assertTrue(File(dir, "gen.onnx").isFile)
    }
}
