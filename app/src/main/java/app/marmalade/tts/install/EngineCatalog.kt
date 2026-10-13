package app.marmalade.tts.install

import androidx.annotation.StringRes
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.R

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EngineInstaller.install(name)
//     │
//     ├── EngineCatalog.byName(name)  ──► EngineDescriptor
//     │                                       │
//     │                                       ├── archive: EngineArchive
//     │                                       │     (url, sha256, sizeBytes,
//     │                                       │      archiveRoot)
//     │                                       │
//     │                                       └── displayName, descriptionRes,
//     │                                           downloadSizeBytes,
//     │                                           installedSizeBytes,
//     │                                           licenseSummaryRes
//     │
//     ▼
//   Single HTTP GET → sha256 check → tar.gz/tar.bz2 extract → atomic rename
//
//   UI (Onboarding / EnginesScreen)
//     │
//     └── EngineCatalog.all  ──► render one card per engine, show
//                                 description, size, license summary
// -----------------------------------------------------------------------------

/**
 * Archive that makes up an engine bundle. The installer downloads it,
 * verifies [sha256], and extracts it into `${filesDir}/engines/<name>.tmp/`
 * before atomic-renaming to `${filesDir}/engines/<name>/`.
 *
 * @property url Absolute HTTPS URL to a tar.gz (or legacy tar.bz2) archive whose top-level
 *               directory (named per [archiveRoot]) contains the engine
 *               payload. The installer flattens the top-level directory
 *               during extraction so the on-device layout is
 *               `${filesDir}/engines/<name>/<file>` regardless of the
 *               archive's wrapper-dir name.
 * @property sha256 Hex-encoded SHA-256 of the archive bytes, lower case.
 * @property sizeBytes Expected archive size in bytes (for progress UI).
 * @property archiveRoot Name of the wrapper directory inside the archive
 *               to strip during extraction, with trailing `/`. Empty
 *               string means "extract entries as-is, no stripping" —
 *               use that for future archives that aren't wrapper-dir'd.
 *               For Kitten Direct this is `"kitten-direct-v0_8/"` (the
 *               directory name the bundle's tarball uses).
 * @property sameContentAs SHA-256s of earlier archives of this bundle that
 *               unpack to byte-identical files: the same tar in another
 *               container (#65: Pocket v27's tar.bz2 repacked as tar.gz). An
 *               install from one of them stays current, so nobody is asked
 *               to download the same files again.
 */
data class EngineArchive(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val archiveRoot: String = "",
    val sameContentAs: Set<String> = emptySet(),
) {
    /** Whether an install recorded with [installedSha256] holds this archive's files. */
    fun holdsSameFiles(installedSha256: String?): Boolean =
        installedSha256 != null && (installedSha256 == sha256 || installedSha256 in sameContentAs)
}

/**
 * Relative synthesis-speed tier — the hero axis on the engine-select card
 * (the "A3 spec columns" design). [meterFill] is how many of the card's
 * four meter segments render filled, and it doubles as the colour key: the
 * meter is coloured by fill count (4 = green, 3 = light green, 2 = amber,
 * 1 = red — see EngineSpecColumn.speedMeterColor), so a shorter bar is also
 * a hotter colour and a single red tick reads as "slow", never as "one of
 * four good".
 *
 * Tiers follow one same-phone bench (issue #18, Pixel 6a, 2026-10-09; RTF =
 * render time ÷ audio, B1 sentences): FASTEST = Kitten (0.26, first sound
 * 0.4 s) and Jenny (0.17, 0.7 s); FAST = Pocket (0.63, 0.55 s; the v27
 * bundle 0.49, 0.38 s, issue #38's same-phone re-bench, so still FAST);
 * MODERATE = Kokoro (0.87 published render, 2.1 s) and Kokoro German (0.83,
 * 3.1 s; its streaming v28 0.97, 0.8 s, issue #35), which barely keep up on
 * that phone with the screen off in the `foreground` cpuset (on the
 * reader's top-app cores since #52, v28 renders at 0.43). HEAVY is left for the Pocket
 * clean-reference build. Re-bench before moving an engine.
 */
enum class SpeedTier(val meterFill: Int) {
    FASTEST(4),
    FAST(3),
    MODERATE(2),
    HEAVY(1),
}

/**
 * Description of a downloadable engine bundle.
 *
 * Static metadata only — no I/O. The installer + UI both consume this; it
 * is the single source of truth for "what does the user have to download
 * to use engine X."
 *
 * @property name              Engine identifier (matches `VoiceMeta.engine`
 *                             and the directory under `${filesDir}/engines/`).
 *                             Stable across versions — part of the public
 *                             surface that aliases reference.
 * @property displayName       User-facing label (e.g. "Kitten TTS").
 * @property descriptionRes    String resource for the one-paragraph pitch
 *                             shown on the install consent card and the
 *                             engine detail screen. Keep it short.
 * @property downloadSizeBytes Compressed archive size on the wire — informs
 *                             the "this will download ~26 MB" copy. Equals
 *                             [archive.sizeBytes] by convention.
 * @property installedSizeBytes Approximate on-disk size after extraction.
 *                             Larger than [downloadSizeBytes] because the
 *                             archive is compressed.
 * @property isRecommended     True for the engine pre-checked in the
 *                             onboarding wizard. v0.1 only ships Kitten,
 *                             which is the recommended default.
 * @property developerOnly     True for diagnostic engines (currently the
 *                             Pocket clean-reference build) that aren't
 *                             meant for normal users. Hidden from the
 *                             user-facing engine lists (manage screen,
 *                             onboarding, alias + voice pickers) unless the
 *                             "show developer engines" setting is on — kept
 *                             in the catalog so existing aliases that point
 *                             at them still resolve + synthesize. Routing
 *                             ([byName]) never filters on this flag.
 * @property archive           Single downloadable archive that contains
 *                             every file in the engine bundle.
 * @property licenseNotice     Path inside the APK to the long-form notice
 *                             shown on the license expand panel.
 * @property licenseSummaryRes String resource for the one-liner shown on the
 *                             install card, e.g. "Includes GPL-3.0
 *                             components (espeak-ng)."
 * @property taglineRes        String resource for the short one-line pitch
 *                             shown on the spec-column engine card. This is
 *                             the residual prose after the structured
 *                             Speed/Quality/Languages chrome takes over the
 *                             card.
 * @property speedTier         Relative synthesis-speed tier — the hero axis
 *                             on the engine-select card.
 * @property quality           How good this engine sounds, on the same linear
 *                             [QualityGrade] scale as the voice packs. A
 *                             listening judgement owned by Max; agents never
 *                             change it (pinned by EngineCatalogTest).
 * @property languageCodes     BCP-47 codes this engine can speak. Size 1 →
 *                             the card shows the single language's name; size
 *                             > 1 → it shows "<n> languages" with an info
 *                             affordance that lists them. For Kokoro this must
 *                             match the distinct locales in
 *                             [app.marmalade.tts.data.KokoroDirectVoiceCatalog]
 *                             (pinned by EngineCatalogTest).
 */
data class EngineDescriptor(
    val name: String,
    val displayName: String,
    @StringRes val descriptionRes: Int,
    val downloadSizeBytes: Long,
    val installedSizeBytes: Long,
    val isRecommended: Boolean,
    val archive: EngineArchive,
    val licenseNotice: String,
    @StringRes val licenseSummaryRes: Int,
    @StringRes val taglineRes: Int,
    val speedTier: SpeedTier,
    val quality: QualityGrade,
    val languageCodes: List<String>,
    /**
     * Languages this engine can *phonemize* but has no trained voice for —
     * the "partial support" / accented-fallback tier (Max, 2026-09-20). The
     * existing voices read this text intelligibly but with an English/
     * multilingual accent (community-rated charming/tolerable, not native).
     * Kept distinct from [languageCodes] (native, backed by a trained voice)
     * precisely so the UI can label these as accented. Bare ISO-639-1 codes
     * (`"de"`), no region — there is no voice to carry one. Must not overlap
     * [languageCodes] and must not repeat.
     */
    val partialLanguageCodes: List<String> = emptyList(),
    val developerOnly: Boolean = false,
    /**
     * F-Droid-flavor exclusive: hidden from every user-facing list in the
     * Play build (Max, 2026-09-06 — the Play catalog is held to a tighter
     * quality bar). Only the Pocket developer twin carries it since Pocket
     * itself reached Play (Max, 2026-10-09, issue #29). Routing via
     * [EngineCatalog.byName] still resolves it, so nothing breaks if such
     * an engine is ever present on disk.
     */
    val fdroidOnly: Boolean = false,
    /**
     * Non-null for **pack-based** engines: the engine has no monolithic
     * bundle of its own, only per-language voice packs from
     * [VoicePackCatalog], and this is the [VoicePack.id] installed when the
     * user taps Install on the engine card.
     *
     * Consequences for such a descriptor:
     *  - [archive], [downloadSizeBytes] and [installedSizeBytes] mirror that
     *    default pack (so the install card shows a real download size);
     *  - [EngineInstaller.install] routes to
     *    [EngineInstaller.installPack] and the payload lands in
     *    `engines/<name>/packs/<packId>/`, never directly in
     *    `engines/<name>/`;
     *  - the engine's own layout check is "at least one valid pack on disk",
     *    and per-pack update detection lives on the pack, not here.
     */
    val defaultPackId: String? = null,
) {
    /** True when this engine installs per-language voice packs — see [defaultPackId]. */
    val isPackBased: Boolean get() = defaultPackId != null

    init {
        require(name.isNotBlank()) { "engine name must not be blank" }
        require(archive.url.isNotBlank()) { "engine $name has no archive url" }
        require(archive.sizeBytes > 0L) { "engine $name has zero-size archive" }
        require(partialLanguageCodes.toSet().size == partialLanguageCodes.size) {
            "engine $name has duplicate partialLanguageCodes"
        }
        require(partialLanguageCodes.none { it in languageCodes }) {
            "engine $name partialLanguageCodes overlap languageCodes"
        }
    }
}

/**
 * Static catalog of installable engines.
 *
 * Ships Kitten Direct (recommended default, baked offline), Kokoro Direct,
 * Pocket TTS and VITS Marmalade — all running on `onnxruntime-android`
 * directly. The list order is also the display order in the onboarding
 * wizard and Engines tab.
 *
 * Most entries carry one monolithic archive. VITS Marmalade is **pack-based**
 * ([EngineDescriptor.defaultPackId]): its payload is a per-language
 * [VoicePack] from [VoicePackCatalog].
 */
object EngineCatalog {

    // Unpacked sizes (sum of file sizes after tar extraction). Compute via
    //   find <extracted-dir> -type f -exec stat -c %s {} + | awk '{s+=$1} END {print s}'
    private const val KITTEN_DIRECT_INSTALLED_SIZE_BYTES: Long = 78_417_260L
    private const val KOKORO_DIRECT_INSTALLED_SIZE_BYTES: Long = 306_245_266L
    private const val KOKORO_DE_INSTALLED_SIZE_BYTES: Long = 170_513_746L
    // v27 bundle: english_2026-09 weights, symbolic-cache graphs, v21's
    // voices minus marius plus the five #38 voices (cosette/jean dropped
    // since v21 — CC-BY-NC-4.0), and each voice's precomputed embedding.
    private const val POCKET_TTS_INSTALLED_SIZE_BYTES: Long = 224_998_077L
    // The clean-reference dev engine still pins the older 8-voice v10 archive
    // (2026-04 weights, ~6 MB smaller); it's developer-only, so the small
    // estimate drift is immaterial.
    private const val POCKET_TTS_DEV_INSTALLED_SIZE_BYTES: Long = POCKET_TTS_INSTALLED_SIZE_BYTES

    /**
     * Kitten Direct v0.8 (`kitten-direct-v0_8`) — the 15M-param KittenML
     * acoustic model, 8 voices, run directly on
     * `onnxruntime-android`. Phonemization is espeak-ng
     * (GPL-3.0-or-later), compiled from source into the APK as
     * libespeak-ng.so and dlopen'd at runtime by the MIT JNI shim; the
     * espeak-ng-data is the app-level shared tree (SharedEspeakData) —
     * bundle copies are ignored legacy. (An earlier design
     * used a BSD-3 OpenPhonemizer ONNX to avoid GPL entirely; it was
     * dropped because IPA-convention mismatches degraded quality.)
     */
    private val KITTEN_DIRECT: EngineDescriptor = EngineDescriptor(
        name = "kitten-direct-v0_8",
        displayName = "Kitten Nano (v0.8)",
        descriptionRes = R.string.engine_kitten_desc,
        downloadSizeBytes = 64_218_626L,
        installedSizeBytes = KITTEN_DIRECT_INSTALLED_SIZE_BYTES,
        // Recommended + onboarding-preselected default: it's baked into the
        // APK (works instantly + offline on first run) and the fastest engine
        // on any device. Kokoro/Pocket are optional higher-quality downloads.
        // Baking is also an ACCESSIBILITY commitment, not just convenience
        // (Max, 2026-08-09): a blind user can pick Marmalade as their
        // TalkBack voice and have working speech from the very first boot,
        // no download step. Never un-bake Kitten without an equivalent
        // built-in voice.
        isRecommended = true,
        archive = EngineArchive(
            // v22: legacy libttsespeak.so removed (bundles carry no executable
            // code at rest) and espeak-ng-data rebuilt from the 1.52.0 tag —
            // the same tag compiled into the APK. tar.gz since v16 (~5× faster
            // on-device install than bzip2).
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v22/kitten-direct-v0_8.tar.gz",
            sha256 = "591e1e163f7804c9673c8d2b63d6eb5f43bb2f2b620580af84dd32c55b283199",
            sizeBytes = 64_218_626L,
            archiveRoot = "kitten-direct-v0_8/",
        ),
        licenseNotice = "LICENSES/kitten-direct.md",
        licenseSummaryRes = R.string.engine_kitten_license,
        taglineRes = R.string.engine_kitten_tagline,
        speedTier = SpeedTier.FASTEST,
        quality = QualityGrade.GOOD,
        languageCodes = listOf("en"),
    )

    /**
     * Kokoro Direct v1.0 (`kokoro-direct-v1_0`) — the 53-voice
     * multi-language KokoroML v1.0 acoustic model. The inference runs
     * on `onnxruntime-android` directly with the same Pocket-style
     * optimizations as KittenDirect (XNNPACK EP, thread autodetect,
     * direct ByteBuffers, etc.). Espeak-ng (GPL-3.0-or-later) is compiled
     * from source into the APK and dlopen'd at runtime by a tiny MIT
     * JNI shim; the espeak-ng-data is the app-level shared tree
     * (SharedEspeakData) — bundle copies are ignored legacy.
     *
     * v17 added `lexicon-zh.txt` (sherpa's pre-baked misaki+pypinyin Han→IPA
     * table) for Mandarin. v18 adds `openjtalk_dic/` (Open JTalk's
     * naist-jdic, BSD-3, ~103 MB extracted) for Japanese — kanji→kana reading
     * + cutlet-style IPA conversion, the G2P Kokoro v1.0 was trained with.
     * espeak now only handles the European languages it does well
     * (en/es/fr/it/hi/pt); zh and ja have dedicated bundled pipelines.
     *
     * v23 swaps the fp32 model for a selectively-quantized static-QDQ
     * int8 build of the SAME graph (per-channel, 118-node exclusion list
     * covering the vocoder's boundary convs, the ALBERT text encoder, and
     * the last-stage resblock convs; LSTMs stay fp32). Max ear-verified
     * lossless vs fp32 (2026-08-01); 150 MB model vs 326 MB, and measured
     * faster than fp32 on both x86 and the Pixel 8a. Recipe + sweep
     * tooling: scratch/kokoro-quant-experiments (REPORT.md).
     *
     * v26 (issues #13, #16, #25) ships the same int8 model cut for streaming,
     * in place of model.onnx, as ORT-format files ("Fixed" style, ARM):
     * gen.ort + genstats/<voice>.bin for all 53 voices (the generator), and
     * the planner as planner-rest.ort fed by a text encoder, textenc.ort
     * (mixed-int8 ALBERT, every language) or textenc-en.ort (Marmalade's
     * English student). It drops the unread phonemizer/ espeak-ng-data
     * (the APK carries its own); model-format.txt stays. An installed v23
     * keeps working (the engine renders its model.onnx whole) and shows as
     * Outdated until updated. Build: scratch/kokoro-v26/bundle/make_bundle.sh.
     * (v24 was prepared with a whole planner.ort and never published.)
     */
    private val KOKORO_DIRECT: EngineDescriptor = EngineDescriptor(
        name = "kokoro-direct-v1_0",
        displayName = "Kokoro (v1.0)",
        descriptionRes = R.string.engine_kokoro_desc,
        downloadSizeBytes = 194_966_520L,
        installedSizeBytes = KOKORO_DIRECT_INSTALLED_SIZE_BYTES,
        // No longer the default — Kitten is baked + recommended. Kokoro stays
        // the top optional download for best quality + widest language support.
        isRecommended = false,
        archive = EngineArchive(
            // v26: the streaming parts + per-voice generator stats, no phonemizer/
            // (see class doc above).
            // v23: selectively-quantized QDQ int8 model.
            // v22: legacy libttsespeak.so removed; espeak-ng-data rebuilt from
            // the 1.52.0 tag (see KITTEN_DIRECT). v18 added openjtalk_dic/
            // (Japanese G2P); v17 added lexicon-zh.txt (Mandarin).
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v26/kokoro-direct-v1_0.tar.gz",
            sha256 = "d7e2aff992264e2ea8b8b703aa9d6fc24ba7ef039633183f572dcc793cb31047",
            sizeBytes = 194_966_520L,
            archiveRoot = "kokoro-direct-v1_0/",
        ),
        licenseNotice = "LICENSES/kokoro-direct.md",
        licenseSummaryRes = R.string.engine_kokoro_license,
        taglineRes = R.string.engine_kokoro_tagline,
        speedTier = SpeedTier.MODERATE,
        quality = QualityGrade.EXCELLENT,
        // The nine distinct locales KokoroDirectVoiceCatalog exposes. American
        // and British English count separately, which is why it's 9 and not 8.
        // Ordered for the info dialog: English (US/UK), then Spanish, French,
        // Italian, Hindi, Portuguese (Brazil), Japanese, Mandarin. Pinned to
        // the voice catalog by EngineCatalogTest.
        languageCodes = listOf(
            "en-US", "en-GB", "es-ES", "fr-FR", "it-IT", "hi-IN", "pt-BR", "ja-JP", "zh-CN",
        ),
        // Partial-support tier (Max, 2026-09-20, v1.1.0): the multilingual
        // voices can phonemize German and Bulgarian — intelligible but
        // accented, no trained voice of their own. See [partialLanguageCodes].
        partialLanguageCodes = listOf("de", "bg"),
    )

    /**
     * Kokoro German (`kokoro-de-v1_0`) — Thorsten-Voice/Kokoro (Apache-2.0),
     * a German fine-tune of Kokoro-82M v1.0 with ONE trained German speaker
     * (Thorsten). A **separate** downloadable engine, not a voice pack for
     * `kokoro-direct-v1_0`: the fine-tune retrained the whole acoustic stack,
     * so it can never share weights or `voices.bin` with the multilingual
     * base — the two live side by side on disk.
     *
     * Exported to the same ONNX graph contract as `kokoro-direct-v1_0` and
     * quantized QDQ int8 (CPU-EP-only, no XNNPACK — the shared
     * [KokoroDirectEngine] `isQuantizedBundle` path). German is fixed:
     * phonemization always runs through the app's GermanG2P (espeak-ng `de` +
     * override lexicon), so the bundle ships no lexicon-zh.txt or
     * openjtalk_dic. Desktop gates all green (Whisper large-v3 corpus
     * WER 0.88%, the community-rated 4.5/5 reference).
     *
     * From bundle v28 (issue #35) that model ships cut into Kokoro's v26
     * streaming layout: planner-rest + textenc (Thorsten's own ALBERT with
     * mixed int8 weights) + gen + genstats/thorsten.bin, no model.onnx and
     * no textenc-en. An installed v25 keeps rendering whole until updated.
     */
    private val KOKORO_GERMAN: EngineDescriptor = EngineDescriptor(
        name = "kokoro-de-v1_0",
        displayName = "Kokoro German (Thorsten)",
        descriptionRes = R.string.engine_kokoro_de_desc,
        downloadSizeBytes = 153_045_045L,
        installedSizeBytes = KOKORO_DE_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v28/kokoro-de-v1_0.tar.gz",
            sha256 = "3a479a5076776376f9cc583e6c67aa28b60c964bb4ff378cdcb84df93afffe3e",
            sizeBytes = 153_045_045L,
            archiveRoot = "kokoro-de-v1_0/",
        ),
        licenseNotice = "LICENSES/kokoro-de.md",
        licenseSummaryRes = R.string.engine_kokoro_de_license,
        taglineRes = R.string.engine_kokoro_de_tagline,
        speedTier = SpeedTier.MODERATE,
        // Same grade as the base Kokoro — the German fine-tune gates at the
        // community-rated reference (WER 0.88%).
        quality = QualityGrade.EXCELLENT,
        // One native, trained German voice — no partial-support tier.
        languageCodes = listOf("de-DE"),
    )

    /**
     * Pocket TTS English (`pocket-tts-en-v2026_09-mixed`, engines release
     * v29: v27's files repacked as tar.gz, issue #65). Kyutai
     * Labs' Latent Space Diffusion model, run on Microsoft
     * `onnxruntime-android` directly (no sherpa-onnx in this path). 10
     * commercial-safe predefined voices: 5 of the upstream 8 (minus
     * `cosette` and `jean`, which are CC-BY-NC-4.0, and `marius`; see
     * below) and 5 added by issue #38.
     *
     * Selective quantization per upstream
     * [PR #147](https://github.com/kyutai-labs/pocket-tts/pull/147):
     * int8 only the transformer (`flow_lm_main`), keep the audio decoder
     * (`mimi_decoder`), encoder (`mimi_encoder`), flow head (`flow_lm_flow`)
     * and text conditioner at fp32. v9 of this bundle blanket-quantized
     * everything and the result was audibly tinny; the mixed variant is the
     * quality fix. The v21 bundle re-rolled the v10 mixed model with the two
     * non-commercial voices removed.
     *
     * v27 (issue #31) carries Kyutai's `english_2026-09` weights (a
     * robustness re-distillation of 2026-04; the flow head and the Mimi codec
     * are unchanged, so `flow_lm_flow` and the decoder are byte-identical to
     * v21) and ships the #15 "F" graphs with a symbolic cache axis as its
     * default `flow_lm_main`/`mimi_decoder` (`*_dyncache.onnx`, declared in
     * bundle.json). The engine name keeps `v2026_04`: it is the id aliases
     * and the default voice point at, and an install of v21 shows as
     * Outdated (archive sha differs) until the user updates, which replaces
     * the directory, voice_cache included (cached embeddings depend on the
     * weights). v27 also drops `marius.wav` and adds five voices (issue #38:
     * four CC0 Kyutai voice donations and Jenny), so their rows only become
     * pickable once v27 is installed (the layout check asks for every
     * offered voice's WAV). It is the first bundle to ship
     * `voice_cache/<voice>.emb`, each voice's embedding precomputed by
     * `scripts/pocket-bundle/make_voice_cache.py` from the bundle's own
     * encoder, so no phone encodes a built-in voice (first use used to peak
     * at +1.1 GB on a Pixel 6a, +2.65 GB for Jenny). An update replaces the
     * whole directory, so an older bundle's cache never meets new weights.
     *
     * v29 (issue #65) is v27's tar stream, byte for byte, recompressed with
     * gzip (`gzip -n -9`): Android unpacks gzip with native zlib, where the
     * tar.bz2 went through commons-compress's pure-Java bzip2, which was most
     * of the time an install took after the download. The download grows
     * from 104.7 MB to 118.5 MB. v27 is listed in `sameContentAs`, so a v27
     * install stays current and nobody downloads the same files again.
     *
     * Licensing: the Kyutai model **code** is MIT (not Apache-2.0) and its
     * **weights** are CC-BY-4.0 (huggingface.co/kyutai/pocket-tts). Each
     * predefined voice carries its own data license — the 10 in the bundle
     * are CC0 (`javert`, `mary`, `rita`, `stephen`, `ramone`), CC-BY-4.0
     * (`alba`, `azelma`, `eponine`, `fantine`) or the Jenny (Dioco) licence
     * (`jenny`: commercial use allowed, the voice must be called "Jenny");
     * attribution is in CREDITS.md. No
     * espeak dependency — Pocket phonemizes upstream of the ONNX export, so
     * this is the only catalog entry without GPL contamination.
     */
    private val POCKET_TTS_EN: EngineDescriptor = EngineDescriptor(
        name = "pocket-tts-en-v2026_04",
        displayName = "Pocket TTS (English, 2026-09)",
        descriptionRes = R.string.engine_pocket_desc,
        downloadSizeBytes = 118_458_315L,
        installedSizeBytes = POCKET_TTS_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v29/pocket-tts-en-v2026_09-mixed.tar.gz",
            sha256 = "dd276be0eff361f58ce1ef7ef3dd56c3cbeca1616892c172b7b45bf1f874f647",
            sizeBytes = 118_458_315L,
            archiveRoot = "pocket-tts-en/",
            // v27, the same files as tar.bz2.
            sameContentAs = setOf("00cf24b87111de48717c26a4dcc126f2c80de056b061d5de888dac653f8b1cdf"),
        ),
        licenseNotice = "LICENSES/pocket-tts.md",
        licenseSummaryRes = R.string.engine_pocket_license,
        taglineRes = R.string.engine_pocket_tagline,
        speedTier = SpeedTier.FAST,
        quality = QualityGrade.EXCELLENT,
        languageCodes = listOf("en"),
    )

    /**
     * Pocket TTS Clean Reference (`pocket-tts-en-v2026_04-dev`) — the
     * same bundle as [POCKET_TTS_EN], installed to its own directory,
     * driven by [app.marmalade.tts.engine.PocketDevEngine] which is a
     * deliberately perf-free port of upstream `pocket_tts/models/
     * tts_model.py`. Used to A/B against the production [POCKET_TTS_EN]
     * inference path for diagnostics (the chunk-start artefact saga,
     * P-AC). Developer-only so regular users aren't tempted to install
     * a slower diagnostic engine; the two installs live side-by-side
     * and can be installed/uninstalled independently. F-Droid only: the
     * regular Pocket ships on both flavors (Max, 2026-10-09, issue #29),
     * this diagnostic twin stays off Play.
     */
    private val POCKET_TTS_EN_DEV: EngineDescriptor = EngineDescriptor(
        name = "pocket-tts-en-v2026_04-dev",
        displayName = "Pocket TTS — Clean Reference",
        descriptionRes = R.string.engine_pocket_dev_desc,
        downloadSizeBytes = 98_264_623L,
        installedSizeBytes = POCKET_TTS_DEV_INSTALLED_SIZE_BYTES,
        isRecommended = false,
        developerOnly = true,
        archive = EngineArchive(
            url = "https://github.com/maxwhipw/marmalade-tts-android-engines/releases/download/v10/pocket-tts-en-v2026_04-mixed.tar.bz2",
            sha256 = "d4faf0e09e2c0f3f0f97221670e193893e5aa8f17e3812fb54ed3ef13fffc2f1",
            sizeBytes = 98_264_623L,
            archiveRoot = "pocket-tts-en/",
        ),
        licenseNotice = "LICENSES/pocket-tts.md",
        licenseSummaryRes = R.string.engine_pocket_dev_license,
        taglineRes = R.string.engine_pocket_dev_tagline,
        speedTier = SpeedTier.HEAVY,
        quality = QualityGrade.EXCELLENT,
        languageCodes = listOf("en"),
        fdroidOnly = true,
    )

    /**
     * VITS Marmalade v1 (`vits-marmalade-v1`) — Marmalade's own direct-ORT
     * runtime for Piper-class single-speaker VITS checkpoints, with one
     * downloadable [VoicePack] per language (see [VoicePackCatalog]).
     *
     * Pack-based rather than monolithic: each checkpoint is a self-contained
     * ~20 MB voice, so a user downloads only the language they speak. The
     * descriptor's archive/sizes mirror the default pack; installing the
     * engine installs that pack into `engines/vits-marmalade-v1/packs/`.
     *
     * **No Piper runtime code.** The maintained Piper fork
     * (OHF-Voice/piper1-gpl) is GPL-3.0 and is neither used nor shipped;
     * inference (phoneme→id mapping, the `input`/`input_lengths`/`scales`
     * tensor contract, PCM conversion) is written fresh against the
     * checkpoint's own config. Phonemization is the app's existing espeak-ng
     * integration, which is why the licence summary still discloses GPL.
     *
     * User-visible for v1.1 (Max, 2026-09-22): the engine and its pack UI are
     * built out and it ships exactly one reviewed voice pack — Jenny (Dioco).
     * The other nine packs stay developer-only via [VoicePack.released] until
     * Max signs them off. `fdroidOnly = false` — Play is a target, because the
     * exposed pack's weights and training data are permissive end to end.
     */
    private val VITS_MARMALADE: EngineDescriptor = run {
        val defaultPack = VoicePackCatalog.defaultPackFor(VoicePackCatalog.VITS_MARMALADE_ENGINE)
            ?: error("vits-marmalade-v1 has no voice packs in VoicePackCatalog")
        EngineDescriptor(
            name = VoicePackCatalog.VITS_MARMALADE_ENGINE,
            displayName = "VITS Marmalade",
            descriptionRes = R.string.engine_vits_desc,
            downloadSizeBytes = defaultPack.archive.sizeBytes,
            installedSizeBytes = defaultPack.installedSizeBytes,
            isRecommended = false,
            developerOnly = false,
            archive = defaultPack.archive,
            licenseNotice = defaultPack.licenseNotice,
            licenseSummaryRes = R.string.engine_vits_license,
            taglineRes = R.string.engine_vits_tagline,
            // The default pack (Jenny (Dioco)) is a single-speaker medium VITS
            // graph at 22.05 kHz, ~58 MB on the wire — heavier than an x_low
            // build but still far lighter than Kokoro's multi-lang model, and a
            // single graph. FASTEST stands: Max ships Jenny precisely because it
            // stays real-time on very slow devices.
            speedTier = SpeedTier.FASTEST,
            // Max's grade for the engine (2026-10-13), two below Pocket and
            // Kokoro. It may differ from the Jenny pack's own grade: the card
            // compares engines with each other, while a pack grade ranks the
            // packs that share this one VITS graph.
            quality = QualityGrade.BASIC,
            // The distinct locales the card advertises: the RELEASED packs only,
            // so the spec column matches what an ordinary user can actually
            // install (English for v1.1). Developer mode reaches the staged
            // packs through Configure → Voice packs, not this line. Pinned to
            // VoicePackCatalog by EngineCatalogTest.
            languageCodes = VoicePackCatalog.releasedForEngine(VoicePackCatalog.VITS_MARMALADE_ENGINE)
                .map { it.languageCode }
                .distinct(),
            defaultPackId = defaultPack.id,
        )
    }

    /**
     * Every engine the app knows how to install. Read-only.
     *
     * This is the canonical catalog order (grouped by family). User-facing
     * *display* order comes from [visibleTo], which sorts production engines
     * ahead of the developer-only ones.
     */
    val all: List<EngineDescriptor> = listOf(
        KITTEN_DIRECT,
        KOKORO_DIRECT,
        KOKORO_GERMAN,
        POCKET_TTS_EN,
        POCKET_TTS_EN_DEV,
        VITS_MARMALADE,
    )

    /** Lookup by [EngineDescriptor.name]. Returns null for unknown engines. */
    fun byName(name: String): EngineDescriptor? = all.firstOrNull { it.name == name }

    /** Names of the [EngineDescriptor.developerOnly] (diagnostic) engines. */
    val developerOnlyNames: Set<String> =
        all.filter { it.developerOnly }.mapTo(mutableSetOf()) { it.name }

    /**
     * The catalog filtered + ordered for user-facing lists. When
     * [showDeveloper] is false, [developerOnly] engines are dropped; when
     * true they're included but sorted *after* the production engines (a
     * stable sort, so each group keeps its [all] order). In the Play
     * [flavor], [EngineDescriptor.fdroidOnly] engines are dropped
     * unconditionally — even in developer mode — so the Play build's
     * catalog matches its store listing. Routing must keep using
     * [byName] — never this — so aliases on a hidden engine still
     * resolve.
     */
    fun visibleTo(
        showDeveloper: Boolean,
        flavor: String = BuildConfig.FLAVOR,
    ): List<EngineDescriptor> =
        (if (showDeveloper) all else all.filter { !it.developerOnly })
            .filter { flavor != "play" || !it.fdroidOnly }
            .sortedBy { it.developerOnly }

    /**
     * Whether the developer-mode setting reveals anything in [flavor]: a
     * developer-only engine that [visibleTo] shows there, or unreleased voice
     * packs ([VoicePackCatalog.showsUnreleased]). In the Play build neither
     * applies (the only developer engine is fdroidOnly), so Settings hides a
     * toggle that would do nothing.
     */
    fun developerModeRevealsAnything(flavor: String = BuildConfig.FLAVOR): Boolean =
        visibleTo(showDeveloper = true, flavor).any { it.developerOnly } ||
            VoicePackCatalog.showsUnreleased(showDeveloper = true, flavor)

    /**
     * Developer mode as it takes effect: the [stored] setting, but only in a
     * [flavor] where Advanced settings shows its toggle
     * ([developerModeRevealsAnything]). On Play the toggle is hidden, so a
     * `true` carried over from an F-Droid install's data (or set by a
     * tester) must not leave developer engines pickable with no way to turn
     * it off. [app.marmalade.tts.data.SettingsRepository.showDeveloperEngines]
     * applies this, so every reader sees the effective value.
     */
    fun effectiveDeveloperMode(stored: Boolean, flavor: String = BuildConfig.FLAVOR): Boolean =
        stored && developerModeRevealsAnything(flavor)
}
