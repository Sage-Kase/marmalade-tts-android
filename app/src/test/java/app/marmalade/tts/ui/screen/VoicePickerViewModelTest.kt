package app.marmalade.tts.ui.screen

import androidx.lifecycle.SavedStateHandle
import app.marmalade.tts.audio.SpeechPlayer
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.VitsVoiceCatalog
import app.marmalade.tts.data.VoiceLatencySource
import app.marmalade.tts.data.VoicePathResolver
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.install.VoicePackCatalog
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Coverage for [VoicePickerViewModel].
 *
 * The interesting behaviour to pin: selecting a voice writes to the
 * settings repository (so the Speak screen picks up the change), and the
 * preview path resolves through the SpeechPlayer with the canned phrase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun selectVoiceWritesToSettings() = runTest {
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(settings = settings)

        vm.selectVoice("kitten-direct-v0_8:Kiki")

        assertEquals("kitten-direct-v0_8:Kiki", settings.defaultVoiceId.first())
    }

    @Test
    fun selectedIdReflectsCurrentSetting() = runTest {
        val settings = FakeSettings(initialId = "kitten-direct-v0_8:Leo")
        val vm = newViewModel(settings = settings)

        // stateIn(Eagerly) + an UnconfinedTestDispatcher means the first
        // emission has already landed by the time we read .value.
        assertEquals("kitten-direct-v0_8:Leo", vm.selectedId.value)
    }

    @Test
    fun previewSendsCannedPhraseThroughPlayer() = runTest {
        val player = RecordingPlayer()
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(settings = settings, player = player)

        vm.togglePreview(bella)

        assertEquals(1, player.calls.size)
        val (text, voiceId) = player.calls.single()
        assertEquals("kitten-direct-v0_8:Bella", voiceId)
        // Phrase contains the voice's name so the user hears who is speaking.
        assertTrue("expected name in '$text'", text.contains("Bella"))
    }

    // -- Sample play/stop and the cloud guard (#88) ----------------------------

    private val bella = KittenDirectVoiceCatalog.voices.first { it.displayName == "Bella" }
    private val leo = KittenDirectVoiceCatalog.voices.first { it.displayName == "Leo" }

    /** A VM whose samples play until stopped, so Playing is observable. */
    private fun previewingViewModel(player: RecordingPlayer): VoicePickerViewModel {
        player.holdUntilCancel = true
        return newViewModel(
            settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID),
            player = player,
        )
    }

    @Test
    fun `a second tap on the playing voice stops it`() = runTest {
        val player = RecordingPlayer()
        val vm = previewingViewModel(player)

        vm.togglePreview(bella)
        assertEquals(PreviewState.Playing(bella.id), vm.previewState.value)
        val cancelsBefore = player.cancelCount
        vm.togglePreview(bella)

        assertEquals(PreviewState.Idle, vm.previewState.value)
        assertEquals(cancelsBefore + 1, player.cancelCount)
        assertEquals("the stop tap speaks nothing", 1, player.calls.size)
    }

    @Test
    fun `another voice takes over from the one playing`() = runTest {
        val player = RecordingPlayer()
        val vm = previewingViewModel(player)

        vm.togglePreview(bella)
        val cancelsBefore = player.cancelCount
        vm.togglePreview(leo)

        assertEquals(PreviewState.Playing(leo.id), vm.previewState.value)
        assertTrue("Bella's sample is cancelled", player.cancelCount > cancelsBefore)
        assertEquals(listOf(bella.id, leo.id), player.calls.map { it.voiceId })
    }

    @Test
    fun `picking a voice stops its sample`() = runTest {
        val player = RecordingPlayer()
        val vm = previewingViewModel(player)
        vm.togglePreview(bella)

        vm.selectVoice(leo.id)

        assertEquals(PreviewState.Idle, vm.previewState.value)
    }

    @Test
    fun `cloud voices get no sample`() = runTest {
        // A cloud sample would spend the user's provider credit unasked.
        val player = RecordingPlayer()
        val vm = previewingViewModel(player)

        assertFalse(vm.canPreview(PICKER_CLOUD_VOICE))
        assertTrue(vm.canPreview(bella))
        vm.togglePreview(PICKER_CLOUD_VOICE)

        assertTrue("nothing may reach a provider", player.calls.isEmpty())
        assertEquals(PreviewState.Idle, vm.previewState.value)
    }

    @Test
    fun `stopping with nothing playing leaves the player alone`() = runTest {
        // cancel() stops the player's in-flight request, which with no sample
        // playing is someone else's (a Speak-screen read).
        val player = RecordingPlayer()
        val vm = previewingViewModel(player)

        vm.stopPreview()
        vm.selectVoice(leo.id)

        assertEquals(0, player.cancelCount)
    }

    @Test
    fun `a vits voice is listed only when its own pack is installed`() = runTest {
        // The promotion blocker this closes: the engine verifies as installed
        // from ONE pack, so an engine-level filter offered all 26 voices and
        // picking an absent one failed at synthesis.
        // The installed pack is the released one, so this holds in both
        // flavors. Developer mode is on so that, in F-Droid, the release gate
        // isn't what hides the other packs' voices: only the disk check is.
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        settings.setShowDeveloperEngines(true)
        val vm = newViewModel(
            settings = settings,
            voices = VitsVoiceCatalog.voices,
            installer = PickerFakeInstaller(
                installedEngines = setOf(VitsVoiceCatalog.ENGINE),
                installedPacks = setOf(RELEASED_PACK),
            ),
        )

        val listed = vm.voices.first { it.isNotEmpty() }

        assertEquals(
            listOf(VitsVoiceCatalog.voiceId(RELEASED_PACK)),
            listed.map { it.id },
        )
    }

    @Test
    fun `installing a multi-speaker pack lists every one of its speakers`() = runTest {
        // Every multi-speaker pack is still unreleased, which the Play build
        // never lists, developer mode or not. The released pack installed
        // beside it gives the list something to show in both flavors, so the
        // test can wait for the probe and then check the flavor's rule.
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        settings.setShowDeveloperEngines(true)
        val vm = newViewModel(
            settings = settings,
            voices = VitsVoiceCatalog.voices,
            installer = PickerFakeInstaller(
                installedEngines = setOf(VitsVoiceCatalog.ENGINE),
                installedPacks = setOf(RELEASED_PACK, "kk-issai-high"),
            ),
        )

        val listed = vm.voices.first { it.isNotEmpty() }

        val kazakh = listed.filter { VitsVoiceCatalog.packIdOf(it.id) == "kk-issai-high" }
        val expectedKazakh = if (VoicePackCatalog.showsUnreleased(showDeveloper = true)) 6 else 0
        assertEquals(expectedKazakh, kazakh.size)
        assertEquals(1 + expectedKazakh, listed.size)
    }

    @Test
    fun `an installed engine with no packs lists no voices at all`() = runTest {
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        settings.setShowDeveloperEngines(true)
        val vm = newViewModel(
            settings = settings,
            voices = VitsVoiceCatalog.voices,
            installer = PickerFakeInstaller(
                installedEngines = setOf(VitsVoiceCatalog.ENGINE),
                installedPacks = emptySet(),
            ),
        )

        // The probe has run (installedEngines is non-empty) and still nothing
        // is pickable — which is the honest answer, not an empty-because-
        // unprobed list.
        vm.installedEngines.first { it.isNotEmpty() }
        assertTrue(vm.voices.value.isEmpty())
    }

    private fun newViewModel(
        settings: SettingsRepository,
        player: SpeechPlayer = RecordingPlayer(),
        voices: List<VoiceMeta> = KittenDirectVoiceCatalog.voices,
        installer: EngineInstaller = PickerFakeInstaller(),
    ): VoicePickerViewModel {
        val dao = FakeDao(voices = voices)
        return VoicePickerViewModel(
            voiceDao = dao,
            settings = settings,
            synthesizer = player,
            installer = installer,
            // No cloud providers configured — on-device voices resolve
            // entirely from EngineCatalog, which is what these tests use.
            voicePaths = VoicePathResolver { null },
            latencySource = VoiceLatencySource { flowOf(emptyMap()) },
            savedStateHandle = SavedStateHandle(),
        )
    }

    @Test
    fun `tree groups the installed voices under their engine`() = runTest {
        val vm = newViewModel(settings = FakeSettings(initialId = "kitten-direct-v0_8:Kiki"))

        val tree = vm.voiceTree.first { it.isNotEmpty() }

        // One on-device engine → one source, and its single model level is
        // the degenerate one the drill-down skips.
        assertEquals(1, tree.size)
        assertTrue(tree.single().models.size == 1)
        assertEquals(KittenDirectVoiceCatalog.voices.size, tree.single().voiceCount)
    }

    @Test
    fun `drilling into a single-model source lands straight on its voices`() = runTest {
        val vm = newViewModel(settings = FakeSettings(initialId = "kitten-direct-v0_8:Kiki"))
        val source = vm.voiceTree.first { it.isNotEmpty() }.single()

        vm.selectSource(source.name)

        // Model auto-selected, so the screen renders the voice list rather
        // than a one-item model list.
        assertEquals(source.name, vm.pickerState.value.source)
        assertEquals(source.models.single().name, vm.pickerState.value.model)
    }

    @Test
    fun `back unwinds a skipped model level in one step`() = runTest {
        val vm = newViewModel(settings = FakeSettings(initialId = "kitten-direct-v0_8:Kiki"))
        val source = vm.voiceTree.first { it.isNotEmpty() }.single()
        vm.selectSource(source.name)

        assertTrue(vm.drillBack())

        // Both levels cleared — stopping at "source set, model null" would
        // show a one-item list the user never chose from.
        assertEquals(null, vm.pickerState.value.source)
        assertEquals(null, vm.pickerState.value.model)
        // At the top level Back belongs to the screen, not the drill-down.
        assertTrue(!vm.drillBack())
    }

    @Test
    fun `search clears before the hierarchy unwinds`() = runTest {
        val vm = newViewModel(settings = FakeSettings(initialId = "kitten-direct-v0_8:Kiki"))
        val source = vm.voiceTree.first { it.isNotEmpty() }.single()
        vm.selectSource(source.name)
        vm.onQueryChange("bel")

        assertTrue(vm.drillBack())

        assertEquals("", vm.pickerState.value.query)
        assertEquals(source.name, vm.pickerState.value.source)
    }

    private companion object {
        /** The one pack every flavor lists, developer mode or not. */
        val RELEASED_PACK = VoicePackCatalog.EN_JENNY_DIOCO_MEDIUM.id
    }
}

/**
 * Test double for [EngineInstaller]: stubs out file I/O + HTTP and lets
 * the caller declare which engines should report installed. v0.1.18's
 * voice-filter logic calls `verify(engineName)` on every catalog engine
 * at VM init; this fake answers that without standing up the real
 * installer (which needs disk + DI scaffolding).
 */
private class PickerFakeInstaller(
    private val installedEngines: Set<String> = setOf("kitten-direct-v0_8", "kokoro-direct-v1_0"),
    /**
     * Voice packs that should report installed. A pack-based engine verifies as
     * installed as soon as one pack is present, so the two sets are
     * independent: `engines` says the runtime is there, `packs` says which
     * languages are.
     */
    private val installedPacks: Set<String> = emptySet(),
) : EngineInstaller(
    filesDir = { java.io.File("/tmp/voicepicker-test-unused") },
    engineHandle = { /* no-op release */ },
    httpFetcher = { _ -> throw java.io.IOException("not used in this test") },
) {
    override suspend fun verify(engineName: String): InstallState =
        if (engineName in installedEngines) InstallState.Installed else InstallState.NotInstalled

    override suspend fun verifyPack(packId: String): InstallState =
        if (packId in installedPacks) InstallState.Installed else InstallState.NotInstalled
}

/** A cloud voice row; [VoicePathResolver] recognises it from the id alone. */
private val PICKER_CLOUD_VOICE = VoiceMeta(
    id = CloudApiVoiceCatalog.voiceId("venice", "tts-kokoro", "af_sky"),
    engine = CloudApiVoiceCatalog.ENGINE,
    displayName = "af_sky",
    languageCode = "en-US",
    sampleRate = 24_000,
    gender = null,
)
