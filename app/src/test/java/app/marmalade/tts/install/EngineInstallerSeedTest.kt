package app.marmalade.tts.install

import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import java.io.File
import java.io.IOException
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
 * uninstalled Kitten stays uninstalled. The copy itself is [seedFromAssets]
 * (recorded here, not run: the real seed is 57 MB).
 */
@RunWith(RobolectricTestRunner::class)
class EngineInstallerSeedTest {

    @get:Rule val tmp = TemporaryFolder()

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
}
