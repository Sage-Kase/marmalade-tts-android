package app.marmalade.tts.perf

import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.KokoroGermanVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VitsVoiceCatalog
import app.marmalade.tts.install.EngineCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy coverage for [EngineRecommender] (issue #19): Pocket for phones
 * that keep up with it, Kitten for slower ones, Kokoro offered but only
 * recommended where Pocket isn't offered (no build since Pocket reached
 * Play, issue #29), and exactly one pill.
 *
 * Pure logic with no injected collaborators, so these tests construct the
 * probe directly — no DataStore, no fake SettingsRepository (whose
 * un-overridden flows never emit and would hang a `combine` for 60 s).
 *
 * Boundaries are probed with an epsilon on either side rather than at the
 * exact threshold: predictions arrive via a floating-point multiply.
 */
class EngineRecommenderTest {

    private val kitten = KittenDirectVoiceCatalog.ENGINE
    private val kokoro = KokoroDirectVoiceCatalog.ENGINE
    private val kokoroDe = KokoroGermanVoiceCatalog.ENGINE
    private val pocket = PocketVoiceCatalog.ENGINE
    private val vits = VitsVoiceCatalog.ENGINE

    private val fdroid = EngineCatalog.visibleTo(showDeveloper = false, flavor = "fdroid").map { it.name }
    private val play = EngineCatalog.visibleTo(showDeveloper = false, flavor = "play").map { it.name }

    private fun cost(engine: String) = EngineRecommender.REFERENCE_COST.getValue(engine)

    /** A measured probe whose predicted RTF for [engine] is [rtf]. */
    private fun probeFor(engine: String, rtf: Double) = DeviceProbe(
        measuredKittenRtf = rtf / cost(engine),
        computeScore = EngineRecommender.ANCHOR_COMPUTE_SCORE,
    )

    /** The reference phone (Pixel 6a) as the app measured it. */
    private val referencePhone = DeviceProbe(EngineRecommender.ANCHOR_PROBE_RTF, EngineRecommender.ANCHOR_COMPUTE_SCORE)

    /** A phone twice as fast as the reference. */
    private val fastPhone = DeviceProbe(EngineRecommender.ANCHOR_PROBE_RTF / 2, 20.0)

    /** A phone three times as slow as the reference. */
    private val slowPhone = DeviceProbe(EngineRecommender.ANCHOR_PROBE_RTF * 3, 3.4)

    private fun EngineRecommendation.pills() = fits.filterValues { it == EngineFit.RECOMMENDED }.keys

    // -- the flavors' builds ------------------------------------------------------

    /** A build that doesn't offer Pocket: where Kokoro stands in. */
    private val noPocket = fdroid - pocket

    @Test
    fun bothFlavorsOfferPocket() {
        // Issue #29 (Max, 2026-10-09): Pocket ships on Play too, so the two
        // builds take the same recommendation.
        assertTrue(pocket in fdroid)
        assertTrue(pocket in play)
        assertEquals(listOf(pocket, kitten), EngineRecommender.candidates(fdroid))
        assertEquals(listOf(pocket, kitten), EngineRecommender.candidates(play))
        assertEquals(listOf(kokoro, kitten), EngineRecommender.candidates(noPocket))
    }

    // -- F-Droid: Pocket or Kitten --------------------------------------------------

    @Test
    fun fdroidReferencePhoneRecommendsPocket() {
        val rec = EngineRecommender.recommend(referencePhone, fdroid)!!
        assertEquals(pocket, rec.recommended)
        assertEquals(EngineFit.RECOMMENDED, rec.fits[pocket])
        assertEquals(EngineFit.FINE, rec.fits[kitten])
        assertEquals(setOf(pocket), rec.pills())
    }

    @Test
    fun fdroidFastPhoneStillRecommendsPocketNotKokoro() {
        val rec = EngineRecommender.recommend(fastPhone, fdroid)!!
        assertEquals(pocket, rec.recommended)
        // Kokoro is offered, plainly, however fast the phone.
        assertEquals(EngineFit.FINE, rec.fits[kokoro])
        assertEquals(setOf(pocket), rec.pills())
    }

    @Test
    fun fdroidSlowPhoneRecommendsKittenAndWarnsOnTheHeavyEngines() {
        val rec = EngineRecommender.recommend(slowPhone, fdroid)!!
        assertEquals(kitten, rec.recommended)
        assertEquals(EngineFit.RECOMMENDED, rec.fits[kitten])
        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[pocket])
        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[kokoro])
        assertEquals(setOf(kitten), rec.pills())
    }

    @Test
    fun pocketTakesThePillExactlyUpToTheComfortLine() {
        val eps = 1e-6
        val under = EngineRecommender.recommend(probeFor(pocket, EngineRecommender.RECOMMENDED_MAX_RTF - eps), fdroid)!!
        assertEquals(pocket, under.recommended)
        val over = EngineRecommender.recommend(probeFor(pocket, EngineRecommender.RECOMMENDED_MAX_RTF + eps), fdroid)!!
        assertEquals(kitten, over.recommended)
        assertEquals(EngineFit.FINE, over.fits[pocket])
    }

    @Test
    fun theComfortLineIsTheSpeedWarningsHeadroom() {
        // A recommended engine never shows the speed-up warning at 1.0x.
        assertEquals(SpeedPerfWarning.EFFECTIVE_RTF_THRESHOLD, EngineRecommender.RECOMMENDED_MAX_RTF, 0.0)
    }

    @Test
    fun mayBeSlowStartsPastTheFineLine() {
        val eps = 1e-6
        assertEquals(
            EngineFit.FINE,
            EngineRecommender.recommend(probeFor(kokoro, EngineRecommender.FINE_MAX_RTF - eps), fdroid)!!.fits[kokoro],
        )
        assertEquals(
            EngineFit.MAY_BE_SLOW,
            EngineRecommender.recommend(probeFor(kokoro, EngineRecommender.FINE_MAX_RTF + eps), fdroid)!!.fits[kokoro],
        )
    }

    // -- Play: the same as F-Droid ------------------------------------------------

    @Test
    fun playReferencePhoneRecommendsPocket() {
        // The Pixel 6a: Pocket (v27) predicted at 0.35, under the comfort line.
        val rec = EngineRecommender.recommend(referencePhone, play)!!
        assertEquals(pocket, rec.recommended)
        assertEquals(setOf(pocket), rec.pills())
        assertEquals(0.35, EngineRecommender.predictedRtf(pocket, referencePhone)!!, 0.005)
        assertEquals(EngineRecommender.recommend(referencePhone, fdroid), rec)
    }

    @Test
    fun playFastPhoneRecommendsPocketNotKokoro() {
        val rec = EngineRecommender.recommend(fastPhone, play)!!
        assertEquals(pocket, rec.recommended)
        assertEquals(EngineFit.FINE, rec.fits[kokoro])
    }

    @Test
    fun playSlowPhoneRecommendsKitten() {
        val rec = EngineRecommender.recommend(slowPhone, play)!!
        assertEquals(kitten, rec.recommended)
        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[pocket])
        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[kokoro])
    }

    // -- a build without Pocket: Kokoro or Kitten -----------------------------------

    @Test
    fun withoutPocketAFastPhoneRecommendsKokoro() {
        val rec = EngineRecommender.recommend(fastPhone, noPocket)!!
        assertEquals(kokoro, rec.recommended)
        assertFalse(pocket in rec.fits)
        assertEquals(setOf(kokoro), rec.pills())
    }

    @Test
    fun withoutPocketTheReferencePhoneRecommendsKokoro() {
        // On the top-app cores (#52) Kokoro predicts 0.46 on a Pixel 6a.
        val rec = EngineRecommender.recommend(referencePhone, noPocket)!!
        assertEquals(kokoro, rec.recommended)
        assertEquals(setOf(kokoro), rec.pills())
    }

    @Test
    fun withoutPocketTheSlowPhoneRecommendsKitten() {
        val rec = EngineRecommender.recommend(slowPhone, noPocket)!!
        assertEquals(kitten, rec.recommended)
        assertEquals(EngineFit.MAY_BE_SLOW, rec.fits[kokoro])
    }

    // -- invariants -------------------------------------------------------------

    @Test
    fun kittenIsNeverWarnedAbout() {
        val rec = EngineRecommender.recommend(DeviceProbe(4.0, null), fdroid)!!
        assertEquals(EngineFit.RECOMMENDED, rec.fits[kitten])
    }

    @Test
    fun everyOfferedModelledEngineGetsAFit() {
        val rec = EngineRecommender.recommend(referencePhone, fdroid)!!
        for (engine in listOf(kitten, kokoro, kokoroDe, pocket, vits).filter { it in fdroid }) {
            assertTrue("$engine has no fit", engine in rec.fits)
        }
    }

    @Test
    fun predictionsScaleTheReferenceCosts() {
        for ((engine, cost) in EngineRecommender.REFERENCE_COST) {
            assertEquals(
                EngineRecommender.ANCHOR_PROBE_RTF * cost,
                EngineRecommender.predictedRtf(engine, referencePhone)!!,
                1e-9,
            )
        }
        assertNull(EngineRecommender.predictedRtf("no-such-engine", referencePhone))
    }

    @Test
    fun theEnginesRankAsMeasured() {
        // #18, Pixel 6a: Kitten fastest; Pocket faster than either Kokoro.
        assertTrue(cost(kitten) < cost(pocket))
        assertTrue(cost(pocket) < cost(kokoro))
        assertTrue(cost(pocket) < cost(kokoroDe))
    }

    // -- Tier 1 (no measurement) ------------------------------------------------

    @Test
    fun tier1AtTheAnchorScoreIsTheAnchorPlusConservatism() {
        assertEquals(
            EngineRecommender.ANCHOR_PROBE_RTF * EngineRecommender.TIER1_CONSERVATISM,
            EngineRecommender.probeKittenRtf(DeviceProbe(null, EngineRecommender.ANCHOR_COMPUTE_SCORE))!!,
            1e-9,
        )
    }

    @Test
    fun tier1IsInverselyProportionalToScore() {
        val full = EngineRecommender.probeKittenRtf(DeviceProbe(null, EngineRecommender.ANCHOR_COMPUTE_SCORE))!!
        val half = EngineRecommender.probeKittenRtf(DeviceProbe(null, EngineRecommender.ANCHOR_COMPUTE_SCORE / 2))!!
        assertEquals(2.0, half / full, 1e-9)
    }

    @Test
    fun aMeasurementBeatsTheScore() {
        assertEquals(0.5, EngineRecommender.probeKittenRtf(DeviceProbe(0.5, 1000.0))!!, 0.0)
    }

    @Test
    fun noSignalMeansNoRecommendation() {
        assertNull(EngineRecommender.recommend(DeviceProbe(null, null), fdroid))
        assertNull(EngineRecommender.recommend(DeviceProbe(null, 0.0), fdroid))
        assertNull(EngineRecommender.recommend(DeviceProbe(null, -1.0), fdroid))
        assertNull(EngineRecommender.predictedRtf(kitten, DeviceProbe(null, null)))
    }
}
