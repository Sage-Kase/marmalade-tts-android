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
     * (Tensor G1), 2026-10-09, issue #18 (`~/coding/scratch/engine-speed-18/`,
     * lab `engine-speed-lab.html`). Engines were rendered by the silent bench
     * service with the screen off, as when listening: RTF = render time ÷
     * audio over a whole set, the mean of the B1 sentences and a
     * 3-paragraph set at 1.0×, two runs each in A-B-B-A order:
     *
     * | engine | sentences | paragraphs | mean | ÷ 0.206 |
     * |---|---|---|---|---|
     * | Kitten (Bella) | 0.256 | 0.251 | 0.253 | 1.23 |
     * | Jenny | 0.170 | 0.157 | 0.163 | 0.79 |
     * | Pocket (Alba, Javert) | 0.640, 0.619 | 0.529, 0.526 | 0.579 | 2.81 |
     * | Kokoro (Heart, v23 whole-model render) | 0.866 | 0.800 | 0.833 | 4.04 |
     * | Kokoro German (Thorsten, German text) | 0.832 | 0.835 | 0.834 | 4.05 |
     *
     * Kitten costs more than 1: the probe runs in the app with setup on
     * screen, where Android lets it use the fast cores; the same sentence
     * in the screen-off service reads 0.267. Kokoro is its published
     * bundle's render; the streaming bundle (#13, unpublished) costs 4.66
     * on this phone. Replaces the Pixel 8a-era ratios (Kokoro 1.5× Kitten,
     * Pocket 2× Kokoro), which predate #13, #15 and #17.
     */
    val REFERENCE_COST: Map<String, Double> = mapOf(
        KITTEN to 1.23,
        KOKORO to 4.04,
        KOKORO_DE to 4.05,
        POCKET to 2.81,
        VITS to 0.79,
    )

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
     * the pill: normal speed with the same 20% headroom the speed-up warning
     * keeps ([SpeedPerfWarning.EFFECTIVE_RTF_THRESHOLD]), so a recommended
     * engine never warns at 1.0×.
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
     * probe carries no signal or the engine isn't in [REFERENCE_COST]. The
     * speed-up warning uses it before measured RTFs accrue.
     */
    fun predictedRtf(engineName: String, probe: DeviceProbe): Double? {
        val cost = REFERENCE_COST[engineName] ?: return null
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
        val rtf = { engine: String -> kittenRtf * REFERENCE_COST.getValue(engine) }
        val pick = candidates(offered).first { it == KITTEN || rtf(it) <= RECOMMENDED_MAX_RTF }
        val fits = (offered.filter { it in REFERENCE_COST } + KITTEN).associateWith { engine ->
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
