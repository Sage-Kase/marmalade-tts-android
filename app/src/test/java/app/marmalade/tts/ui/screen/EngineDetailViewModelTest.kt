package app.marmalade.tts.ui.screen

import androidx.lifecycle.SavedStateHandle
import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.install.EngineFilesDir
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.install.VoicePackAction
import app.marmalade.tts.install.VoicePackCatalog
import app.marmalade.tts.preprocessing.EngineProfiles
import app.marmalade.tts.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * State-transition coverage for [EngineDetailViewModel].
 *
 * These cases were ported from the pre-v0.1.11 SettingsViewModelTest when
 * the per-engine preprocessing rules UI moved to the detail screen. The
 * write-back lifecycle (`toggleRule` / `resetRules`) is worth testing
 * because it's a "read latest, mutate by one member, write back" pattern
 * that's easy to break with a stale-StateFlow-read bug — same risk as
 * before, just hosted on a new VM.
 *
 * Also exercises:
 * - navArg routing via [SavedStateHandle] (a programming error would
 *   silently make every engine look like "kitten-direct-v0_8").
 * - install-state flow-through (the screen consumes this for its status
 *   header — regressing it would render every card "not installed").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun enabledRules_initially_reflects_default_profile() = runTest {
        val vm = newViewModel(engineName = "kitten-direct-v0_8")
        val emitted = vm.enabledRules.filter { it.isNotEmpty() }.first()
        assertEquals(EngineProfiles.defaultsFor("kitten-direct-v0_8"), emitted)
    }

    @Test
    fun toggleRule_off_removes_rule_from_set() = runTest {
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(engineName = "kitten-direct-v0_8", settings = settings)
        // Let the StateFlow seed with the default set first.
        vm.enabledRules.filter { it.isNotEmpty() }.first()

        vm.toggleRule(rule = "currency", enabled = false)

        val persisted = settings.enabledRules("kitten-direct-v0_8")
            .filter { "currency" !in it }
            .first()
        assertFalse("currency must be removed from persisted set", "currency" in persisted)
        // Spot-check an untouched rule remains.
        assertTrue("number must remain in persisted set", "number" in persisted)
    }

    @Test
    fun toggleRule_on_adds_rule_to_set() = runTest {
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        // Seed empty so we have something to add to. ("user disabled all".)
        settings.setEnabledRules("kitten-direct-v0_8", emptySet())
        val vm = newViewModel(engineName = "kitten-direct-v0_8", settings = settings)
        vm.enabledRules.first()  // let it settle

        vm.toggleRule(rule = "html", enabled = true)

        val persisted = settings.enabledRules("kitten-direct-v0_8")
            .filter { "html" in it }
            .first()
        assertTrue("html should be added to stored set", "html" in persisted)
    }

    @Test
    fun resetRules_restores_default_profile() = runTest {
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        // Stash a non-default state.
        settings.setEnabledRules("kitten-direct-v0_8", setOf("emoji"))
        val vm = newViewModel(engineName = "kitten-direct-v0_8", settings = settings)
        vm.enabledRules.first()

        vm.resetRules()

        val persisted = settings.enabledRules("kitten-direct-v0_8").first()
        assertEquals(EngineProfiles.defaultsFor("kitten-direct-v0_8"), persisted)
    }

    @Test
    fun toggleRule_off_then_on_round_trips_to_same_set() = runTest {
        // No-side-state invariant: off+on lands on the starting set.
        val settings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID)
        val vm = newViewModel(engineName = "kitten-direct-v0_8", settings = settings)
        val before = settings.enabledRules("kitten-direct-v0_8").first()

        vm.toggleRule(rule = "currency", enabled = false)
        settings.enabledRules("kitten-direct-v0_8").filter { "currency" !in it }.first()
        vm.toggleRule(rule = "currency", enabled = true)
        val after = settings.enabledRules("kitten-direct-v0_8").filter { "currency" in it }.first()

        assertEquals(before, after)
    }

    @Test
    fun engineName_is_honored_from_nav_arg() = runTest {
        // The VM picks up the engine name from SavedStateHandle["name"].
        // Use "kokoro-direct-v1_0" — its default profile is intentionally different
        // from "kitten-direct-v0_8" (Kokoro skips number / abbreviation / ordinal),
        // so a misrouted VM that ignored the arg would show kitten's
        // defaults and this assertion would catch it.
        val vm = newViewModel(engineName = "kokoro-direct-v1_0")
        assertEquals("kokoro-direct-v1_0", vm.engineName)

        val emitted = vm.enabledRules.filter { it.isNotEmpty() }.first()
        assertEquals(EngineProfiles.defaultsFor("kokoro-direct-v1_0"), emitted)
    }

    @Test
    fun installState_flows_through_from_installer() = runTest {
        // The detail screen renders its status header off the VM's
        // installState StateFlow. We seed an on-disk engine directory so
        // the installer's internal stateFlow() initialises to Installed,
        // then assert that the VM's StateFlow surfaces that value.
        val tmpDir = createTempDirAndEngine("kitten-direct-v0_8")
        val installer = FakeInstaller(tmpDir)
        val vm = newViewModel(engineName = "kitten-direct-v0_8", installer = installer)

        val seen = vm.installState.filter { it is InstallState.Installed }.first()
        assertEquals(InstallState.Installed, seen)
    }

    // -- Voice packs ----------------------------------------------------------

    @Test
    fun packGroups_listEveryVisiblePackBeforeAnyProbe() = runTest {
        // The section must render its full list immediately; waiting for the
        // disk probes would flash an empty "Voice packs" heading.
        val vm = newViewModel(engineName = VoicePackCatalog.VITS_MARMALADE_ENGINE)

        val groups = vm.packGroups.value

        assertEquals(visiblePacks().size, groups.sumOf { it.rows.size })
        assertTrue(groups.all { group -> group.rows.none { it.isUsable } })
        assertEquals(0, vm.packSummary.value.installedCount)
    }

    @Test
    fun refreshPacks_marksTheOnesOnDiskInstalled() = runTest {
        val installer = FakeInstaller(installedPacks = setOf(RELEASED_PACK))
        val vm = newViewModel(
            engineName = VoicePackCatalog.VITS_MARMALADE_ENGINE,
            installer = installer,
        )

        vm.refreshPacks()

        val summary = vm.packSummary.first { it.installedCount == 1 }
        assertEquals(visiblePacks().size, summary.packCount)
        // Read through the flow, not `.value`: these StateFlows are
        // WhileSubscribed, so an unsubscribed `.value` is still the initial
        // (all-NotInstalled) snapshot.
        val installed = vm.packGroups
            .map { groups -> groups.flatMap { it.rows }.filter { it.isUsable } }
            .first { it.isNotEmpty() }
        assertEquals(listOf(RELEASED_PACK), installed.map { it.pack.id })
    }

    @Test
    fun installPack_endsInstalledAndLeavesOtherPacksAlone() = runTest {
        val installer = FakeInstaller()
        val vm = newViewModel(
            engineName = VoicePackCatalog.VITS_MARMALADE_ENGINE,
            installer = installer,
        )

        vm.installPack(RELEASED_PACK)

        val row = vm.packGroups
            .map { groups -> groups.flatMap { it.rows }.first { it.pack.id == RELEASED_PACK } }
            .first { it.state is InstallState.Installed }
        assertEquals(VoicePackAction.UNINSTALL, row.action)
        // Exactly one pack moved — the whole point of per-pack installs.
        assertEquals(setOf(RELEASED_PACK), installer.installedPacks)
        assertEquals(1, vm.packSummary.first { it.installedCount > 0 }.installedCount)
    }

    @Test
    fun installPack_failureSurfacesTheReasonOnTheRow() = runTest {
        // A silent failure would leave the row on its spinner forever.
        val installer = FakeInstaller()
        installer.packInstallFailure = "sha256 mismatch"
        val vm = newViewModel(
            engineName = VoicePackCatalog.VITS_MARMALADE_ENGINE,
            installer = installer,
        )

        vm.installPack(RELEASED_PACK)

        val row = vm.packGroups
            .map { groups -> groups.flatMap { it.rows }.first { it.pack.id == RELEASED_PACK } }
            .first { it.state is InstallState.Failed }
        assertEquals("sha256 mismatch", row.failure?.reason)
        assertEquals(VoicePackAction.RETRY, row.action)
        assertFalse(row.isUsable)
    }

    @Test
    fun uninstallPack_returnsTheRowToInstallable() = runTest {
        val installer = FakeInstaller(installedPacks = setOf(RELEASED_PACK))
        val vm = newViewModel(
            engineName = VoicePackCatalog.VITS_MARMALADE_ENGINE,
            installer = installer,
        )
        vm.refreshPacks()
        vm.packSummary.first { it.installedCount == 1 }

        vm.uninstallPack(RELEASED_PACK)

        val row = vm.packGroups
            .map { groups -> groups.flatMap { it.rows }.first { it.pack.id == RELEASED_PACK } }
            .first { it.state is InstallState.NotInstalled }
        assertEquals(VoicePackAction.INSTALL, row.action)
        assertFalse(installer.installedPacks.contains(RELEASED_PACK))
    }

    @Test
    fun aDownloadStartedOnAnEarlierVisitStillShowsItsProgress() = runTest {
        // U5: leave the page mid-download and come back. The download keeps
        // running, so the new visit's row must show it (no Install button to
        // queue a second download) — even after the screen re-probes the disk.
        val gate = java.util.concurrent.CountDownLatch(1)
        val installer = BlockedDownloadInstaller(gate)
        val packId = RELEASED_PACK
        val firstVisit = newViewModel(VoicePackCatalog.VITS_MARMALADE_ENGINE, installer = installer)
        firstVisit.installPack(packId)
        installer.packState(packId).first { it is InstallState.Downloading }

        val secondVisit = newViewModel(VoicePackCatalog.VITS_MARMALADE_ENGINE, installer = installer)
        secondVisit.refreshPacks()

        val busy = secondVisit.packGroups
            .map { groups -> groups.flatMap { it.rows }.first { it.pack.id == packId } }
            .first { it.isBusy }
        assertNull("no action while the download runs", busy.action)

        // The download then fails; the second visit hears about it too.
        gate.countDown()
        val failed = secondVisit.packGroups
            .map { groups -> groups.flatMap { it.rows }.first { it.pack.id == packId } }
            .first { it.state is InstallState.Failed }
        assertEquals(VoicePackAction.RETRY, failed.action)
    }

    @Test
    fun withDeveloperModeOffThePackSectionShowsOnlyReleasedPacks() = runTest {
        // The release gate at the pack-management surface: an ordinary user
        // configuring the VITS engine sees exactly the released pack (Jenny),
        // never the nine staged ones.
        val installer = FakeInstaller()
        val vm = newViewModel(
            engineName = VoicePackCatalog.VITS_MARMALADE_ENGINE,
            installer = installer,
            developerEngines = false,
        )
        vm.refreshPacks()

        // The StateFlow's initial value is computed with the debug-build
        // default (all packs); wait for the released-only steady state that
        // the developer-off setting drives it to.
        val rows = vm.packGroups
            .map { groups -> groups.flatMap { it.rows } }
            .first { it.size == 1 }
        assertEquals(listOf(RELEASED_PACK), rows.map { it.pack.id })
        assertEquals(1, vm.packSummary.first { it.packCount == 1 }.packCount)
    }

    @Test
    fun aNonPackEngineHasNoPackRows() = runTest {
        // The screen keys its whole section off this being empty.
        val vm = newViewModel(engineName = "kokoro-direct-v1_0")

        assertTrue(vm.packGroups.value.isEmpty())
        assertEquals(0, vm.packSummary.value.packCount)
    }

    private fun createTempDirAndEngine(engineName: String): java.io.File {
        // Build the directory layout EngineInstaller.stateFlow() probes
        // ("$filesDir/engines/$engineName" being a directory triggers the
        // Installed initial value). The contents don't matter for the VM
        // test — we never call verify() through to verifyLayout().
        val root = tempRoot("engine-detail-test-")
        java.io.File(root, "engines/$engineName").mkdirs()
        return root
    }

    // -- helpers --------------------------------------------------------------

    /**
     * The packs the VITS section lists in developer mode under this build's
     * flavor: the whole catalog on F-Droid, released packs only on Play.
     */
    private fun visiblePacks() =
        VoicePackCatalog.forEngine(VoicePackCatalog.VITS_MARMALADE_ENGINE)
            .filter { it.released || VoicePackCatalog.showsUnreleased(showDeveloper = true) }

    private fun newViewModel(
        engineName: String,
        settings: FakeSettings = FakeSettings(initialId = KittenDirectVoiceCatalog.DEFAULT_VOICE_ID),
        installer: EngineInstaller = FakeInstaller(),
        // Developer mode on, so the F-Droid build lists the staged packs too.
        // The Play build never lists them, so the pack-mechanics cases use
        // the released pack ([RELEASED_PACK]) to pass under both flavors; the
        // release gate itself is covered in VoicePackRowsTest.
        developerEngines: Boolean = true,
    ): EngineDetailViewModel {
        if (developerEngines) {
            kotlinx.coroutines.runBlocking { settings.setShowDeveloperEngines(true) }
        }
        val savedState = SavedStateHandle(mapOf(EngineDetailViewModel.NAV_ARG_NAME to engineName))
        return EngineDetailViewModel(
            settings = settings,
            installer = installer,
            savedStateHandle = savedState,
        )
    }

    private companion object {
        /** The one pack every flavor lists, developer mode or not. */
        val RELEASED_PACK = VoicePackCatalog.EN_JENNY_DIOCO_MEDIUM.id
    }
}

/** A fresh temp dir, deleted with its contents when the test JVM exits. */
private fun tempRoot(prefix: String): java.io.File =
    kotlin.io.path.createTempDirectory(prefix).toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }

/**
 * The real install pipeline over a fetcher that blocks until [gate] opens and
 * then drops the connection — a download that's genuinely in flight in the
 * installer, independent of any one ViewModel.
 */
private class BlockedDownloadInstaller(
    gate: java.util.concurrent.CountDownLatch,
) : EngineInstaller(
    filesDir = tempRoot("engine-detail-dl-").let { temp -> EngineFilesDir { temp } },
    engineHandle = { /* no-op release */ },
    httpFetcher = { _ ->
        gate.await()
        throw java.io.IOException("connection reset")
    },
) {
    // The real pack is tens of MB; don't let the test machine's free space
    // decide the outcome.
    override fun usableSpaceBytes(dir: java.io.File): Long = Long.MAX_VALUE
}

/**
 * In-memory [EngineInstaller] for the detail-screen VM tests.
 *
 * The base class's `state()` and `stateFlow()` are final + private — we
 * can't override them. Instead we route the base class through a
 * caller-supplied [filesRoot] so the initial state computed by the base
 * class's `stateFlow()` reflects the on-disk engine directory we set up
 * in the test. Pre-creating `${filesRoot}/engines/<name>/` makes the
 * initial state `Installed`; not creating it leaves it `NotInstalled`.
 *
 * `install()` and `uninstall()` are open in the base class — override
 * them with deterministic no-ops to avoid an HTTP fetch.
 */
private class FakeInstaller(
    filesRoot: java.io.File = tempRoot("engine-detail-"),
    installedPacks: Set<String> = emptySet(),
) : EngineInstaller(
    filesDir = { filesRoot },
    engineHandle = { /* no-op release */ },
    httpFetcher = { _ -> throw java.io.IOException("not used") },
) {
    override suspend fun install(
        engineName: String,
        onProgress: (InstallState.Downloading) -> Unit,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun uninstall(engineName: String): Result<Unit> =
        Result.success(Unit)

    /** Pack ids this fake reports as present on disk. Mutated by installPack. */
    val installedPacks: MutableSet<String> = installedPacks.toMutableSet()

    /** When non-null, installPack fails with this message instead of succeeding. */
    var packInstallFailure: String? = null

    override suspend fun verifyPack(packId: String): InstallState =
        if (packId in installedPacks) InstallState.Installed else InstallState.NotInstalled

    override suspend fun installPack(
        packId: String,
        onProgress: (InstallState.Downloading) -> Unit,
    ): Result<Unit> {
        packInstallFailure?.let { return Result.failure(java.io.IOException(it)) }
        installedPacks += packId
        return Result.success(Unit)
    }

    override suspend fun uninstallPack(packId: String): Result<Unit> {
        installedPacks -= packId
        return Result.success(Unit)
    }
}
