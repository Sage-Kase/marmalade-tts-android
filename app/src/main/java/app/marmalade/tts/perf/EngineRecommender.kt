package app.marmalade.tts.perf

import app.marmalade.tts.data.KittenDirectVoiceCatalog
import app.marmalade.tts.data.KokoroDirectVoiceCatalog
import app.marmalade.tts.data.KokoroGermanVoiceCatalog
import app.marmalade.tts.data.PocketVoiceCatalog
import app.marmalade.tts.data.VitsVoiceCatalog

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   DeviceCapability.probe()  ──►  DeviceProbe(measuredKittenRtf, computeScore)
//                                       │
//                                       ▼
//                    EngineRecommender.recommend(probe, offered engines)
//                                       │
//                                       ▼
//                     EngineRecommendation(fits, recommended)
//                                       │
//                                       ▼
//              OnboardingViewModel.engines ──► Recommended / "May be slow"
//                                              labels (card order is fixed)
// -----------------------------------------------------------------------------

/** How well one engine is expected to run on this device. */
enum class EngineFit {
    /** Best pick for this phone — gets the Recommended pill. */
    RECOMMENDED,

    /** Runs acceptably. No label; the card is offered plainly. */
    FINE,

    /** Predicted to synthesize slower than comfortable — warn before download. */
    MAY_BE_SLOW,
}

/**
 * Per-engine fit for this device. Fits move labels only — card order is
 * always the catalog's (Max, 2026-08-08: the verdict moves the pill, not
 * the cards).
 */
data class EngineRecommendation(
    val fits: Map<String, EngineFit>,
    /** The one engine wearing [EngineFit.RECOMMENDED]. */
    val recommended: String,
    /** The probe's Kitten RTF the predictions were scaled from (measured or Tier-1). */
    val probeKittenRtf: Double,
)

/**
 * Turns a device probe into "which engine should this phone install".
 *
 * Pure logic — no Android types, no I/O, no injection. Everything the
 * decision needs arrives in [DeviceProbe] and the set of engines the build
 * offers, which is what makes the boundaries cheap to pin in unit tests.
 *
 * **Model** (borrowed from Marmalade STT's `SpeedBudget`, 2026-10-09): every
 * engine's cost is a fixed ratio to one cheap measurement, taken once on a
 * reference phone ([REFERENCE_COST]); this phone's measurement scales them
 * all. Relative costs travel between phones far better than absolute
 * speeds do (same ORT CPU kernels, same cores). Here the measurement is the
 * onboarding probe's own Kitten RTF ([DeviceCapability]), so the ratios are
 * expressed in it.
 *
 * **Policy** (issue #19, Max 2026-10-09: "recommend Pocket to most people
 * … Kitten for people with slower phones; people know what Kokoro is"):
 * the first of [candidates] predicted to keep up comfortably
 * ([RECOMMENDED_MAX_RTF]) takes the pill, else the baked Kitten. Pocket is
 * the candidate where the build offers it — both flavors since issue #29
 * (Max, 2026-10-09); a build without it would fall back to Kokoro.
 */
object EngineRecommender {

    /**
     * Each engine's warm render RTF on the reference phone ÷ the probe's
     * Kitten RTF there ([ANCHOR_PROBE_RTF]). Reference: **Pixel 6a**
     * (Tensor G1). The costs model in-app listening with the screen off,
     * which since #52 runs on the `top-app` cores, like system TTS: the
     * reader holds a TTS session to its own engine while it plays
     * (TopAppHold), and Android's TTS manager binds every engine with
     * BIND_SCHEDULE_LIKE_TOP_APP (the 6a's two X1 cores included).
     *
     * Method (#18, re-based in #52, `~/coding/scratch/mtts-52/`): the
     * silent bench service, screen off, renders the B1 sentences and a
     * 3-paragraph set at 1.0× (RTF = render time ÷ audio over a set). #52
     * ran each engine with the reader's TTS session held and without, in
     * A-B-B-A order on one build (Kitten's last run was repeated at the
     * end of the session), and scaled the earlier screen-off costs
     * (#18; Pocket #38, Kokoro #50) by the ratio:
     *
     * | engine | without (sentences / paragraphs) | with | ratio | was | cost |
     * |---|---|---|---|---|---|
     * | Kitten (Bella) | 0.259 / 0.228 | 0.132 / 0.114 | 0.505 | 1.23 | 0.62 |
     * | Jenny | 0.173 / 0.152 | 0.085 / 0.067 | 0.468 | 0.79 | 0.37 |
     * | Pocket (Alba, v21 bundle) | 0.715 / 0.504 | 0.456 / 0.412 | 0.712 | 2.39 | 1.70 |
     * | Kokoro (Heart, v26 streaming) | 1.064 / 0.858 | 0.440 / 0.480 | 0.479 | 4.69 | 2.25 |
     * | Kokoro German (Thorsten, German text) | 0.834 / 0.850 | 0.408 / 0.448 | 0.508 | 4.05 | 2.06 |
     *
     * On the 6a these predict Kokoro 0.46 and Kokoro German 0.42, as system
     * TTS measured them in #50 (0.40–0.43 through the release gate), and
     * the in-app reader's own screen-off reads agree (#52: Kokoro 0.51 with
     * the session, 0.95 without, where it stalled 3–4 times per set at
     * 1.0×; with the session, never). Pocket gains least from the X1s (its decoder and AR loop
     * each keep one core busy). Kitten costs less than 1: the probe renders
     * one short, cold sentence during setup, where the bench's warm sets
     * render faster.
     *
     * Before #52 the costs modelled the slower `foreground` cpuset (CPUs
     * 0–5, no X1) that a screen-off foreground service gets, about twice
     * as slow (#50 measured both placements). Android 11 and older still
     * read that way (the reader's session needs the Android 12 TTS
     * manager), so they keep those costs: [FOREGROUND_COST].
     *
     * Pocket's 2.39 was the v27 bundle's render (#38: v21 → v27 ratio
     * 0.486 / 0.572 on the same phone, scaled from #18's v21 cost 2.81);
     * the debug build here has v21, and the ratio carries over. Kokoro's
     * 4.69 was #50's re-measure of v26 (#25 had read 4.17; #49's later-chunk
     * cut costs nothing).
     *
     * Kokoro German is its v28 streaming bundle (#35, 2026-10-10,
     * `~/coding/scratch/kokoro-de-k35/phone/`; the table row above is v25).
     * Without the session, #18's method on German B1 sentences and three
     * Hesse paragraphs read v28 at 0.981 / 0.963 against v25's 0.853 /
     * 0.850 in the same session: 4.05 × 0.972 / 0.852 = 4.62, its
     * [FOREGROUND_COST]. #52's A/B on v28, the same texts, A-B-B-A: without
     * 1.000 / 0.875, with 0.434 / 0.427, ratio 0.459, so 4.62 × 0.459 = 2.12
     * (v25's 2.06 × 1.03: on the fast cores v28 renders about as fast as
     * v25 did).
     */
    val REFERENCE_COST: Map<String, Double> = mapOf(
        KITTEN to 0.62,
        KOKORO to 2.25,
        KOKORO_DE to 2.12,
        POCKET to 1.70,
        VITS to 0.37,
    )

    /**
     * The pre-#52 costs, for phones whose reader can't hold its TTS session
     * ([DeviceProbe.readerOnFastCores] false: Android 11 and older) and so
     * renders in the `foreground` cpuset with the screen off. Same method
     * without the session (#18; Pocket v27 #38, Kokoro #50, Kokoro German v28
     * #35).
     */
    val FOREGROUND_COST: Map<String, Double> = mapOf(
        KITTEN to 1.23,
        KOKORO to 4.69,
        KOKORO_DE to 4.62,
        POCKET to 2.39,
        VITS to 0.79,
    )

    /** The cost table for where this phone's in-app reader runs. */
    fun costs(probe: DeviceProbe): Map<String, Double> =
        if (probe.readerOnFastCores) REFERENCE_COST else FOREGROUND_COST

    /**
     * The onboarding probe's Kitten RTF on the reference phone, measured in
     * the app (onboarding open, screen on): median of 5, 0.192–0.211. The
     * Tier-1 anchor and the unit of [REFERENCE_COST].
     */
    const val ANCHOR_PROBE_RTF: Double = 0.206

    /**
     * Tier-1 compute score of the reference phone. The Pixel 6a's Tensor G1
     * has 2× Cortex-X1 @ 2.80 GHz + 2× Cortex-A76 @ 2.25 GHz outside its
     * efficiency cluster: `2×2.802 + 2×2.253 = 10.11`. An unmeasured
     * phone's probe RTF is modelled as inversely proportional to its score.
     */
    const val ANCHOR_COMPUTE_SCORE: Double = 10.11

    /**
     * Tier-1 pessimism factor. The heuristic ignores memory bandwidth,
     * thermal headroom and ORT kernel coverage, all of which cut the wrong
     * way on cheap silicon — so an unmeasured device is quoted 15% slower
     * than the raw ratio says. A device wrongly warned can still install;
     * a device wrongly praised gets a stuttering first impression.
     */
    const val TIER1_CONSERVATISM: Double = 1.15

    /**
     * Up to this predicted RTF an engine keeps up comfortably and may take
     * the pill: normal speed below the speed-up warning's line
     * ([SpeedPerfWarning.EFFECTIVE_RTF_THRESHOLD]), so a recommended engine
     * never warns, or plays whole pieces, at 1.0×. 0.8 until #54 lowered
     * the warning to 0.6: Pocket's pill now needs a probe Kitten RTF up to
     * 0.35 (was 0.47; the 6a's is 0.21).
     */
    const val RECOMMENDED_MAX_RTF: Double = SpeedPerfWarning.EFFECTIVE_RTF_THRESHOLD

    /** Up to this predicted RTF the engine is offered without a warning. */
    const val FINE_MAX_RTF: Double = 0.9

    /**
     * The engines that may take the pill, best first, for a build offering
     * [offered]: Pocket where offered, else Kokoro; the baked Kitten always
     * last, as the floor.
     */
    fun candidates(offered: Collection<String>): List<String> {
        val heavy = if (POCKET in offered) POCKET else KOKORO.takeIf { it in offered }
        return listOfNotNull(heavy, KITTEN)
    }

    /**
     * The probe's Kitten RTF for this device: measured, or estimated from
     * its compute score; null when the probe carries no usable signal (no
     * measurement and unreadable sysfs) — callers then fall back to the
     * catalog's static flags.
     */
    fun probeKittenRtf(probe: DeviceProbe): Double? {
        probe.measuredKittenRtf?.let { return it }
        val score = probe.computeScore ?: return null
        if (score <= 0.0) return null
        return ANCHOR_PROBE_RTF * (ANCHOR_COMPUTE_SCORE / score) * TIER1_CONSERVATISM
    }

    /**
     * Predicted warm RTF for [engineName] on this device, or null when the
     * probe carries no signal or the engine isn't modelled ([costs]). The
     * speed-up warning uses it before measured RTFs accrue; setup's fits
     * ([recommend]) only ever use it.
     */
    fun predictedRtf(engineName: String, probe: DeviceProbe): Double? {
        val cost = costs(probe)[engineName] ?: return null
        return probeKittenRtf(probe)?.let { it * cost }
    }

    /**
     * Fits for the modelled engines in [offered], or null when the probe
     * says nothing (see [probeKittenRtf]). Exactly one engine wears
     * [EngineFit.RECOMMENDED] (the first of [candidates] under
     * [RECOMMENDED_MAX_RTF], else Kitten). Kitten is never warned about;
     * every other engine is [EngineFit.FINE] or [EngineFit.MAY_BE_SLOW].
     */
    fun recommend(probe: DeviceProbe, offered: Collection<String>): EngineRecommendation? {
        val kittenRtf = probeKittenRtf(probe) ?: return null
        val cost = costs(probe)
        val rtf = { engine: String -> kittenRtf * cost.getValue(engine) }
        val pick = candidates(offered).first { it == KITTEN || rtf(it) <= RECOMMENDED_MAX_RTF }
        val fits = (offered.filter { it in cost } + KITTEN).associateWith { engine ->
            when {
                engine == pick -> EngineFit.RECOMMENDED
                engine == KITTEN || rtf(engine) <= FINE_MAX_RTF -> EngineFit.FINE
                else -> EngineFit.MAY_BE_SLOW
            }
        }
        return EngineRecommendation(fits, recommended = pick, probeKittenRtf = kittenRtf)
    }

    private const val KITTEN = KittenDirectVoiceCatalog.ENGINE
    private const val KOKORO = KokoroDirectVoiceCatalog.ENGINE
    private const val KOKORO_DE = KokoroGermanVoiceCatalog.ENGINE
    private const val POCKET = PocketVoiceCatalog.ENGINE
    private const val VITS = VitsVoiceCatalog.ENGINE
}
