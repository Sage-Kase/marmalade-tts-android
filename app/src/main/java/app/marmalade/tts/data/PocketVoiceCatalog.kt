package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta

/**
 * Static catalog of the predefined voices the app offers from the
 * `pocket-tts-en-v2026_09-mixed` bundle (engines release v27, ~100 MB
 * compressed, ~220 MB on-disk).
 *
 * Upstream pocket-tts defines 8 predefined voices; `cosette` (Expresso) and
 * `jean` (EARS) are **CC-BY-NC-4.0 (non-commercial)** and were dropped before
 * the public release so the store build distributes no non-commercial voice
 * data. Of the rest the bundle keeps `alba` (alba-mackenna, CC-BY-4.0),
 * `azelma`/`eponine`/`fantine` (VCTK, CC-BY-4.0) and `javert`
 * (voice-donations, CC0). `marius` (voice-donations, CC0) was dropped on
 * 2026-10-09 (issue #19) because it sounds much worse than the others; v21
 * still carried its WAV, v27 doesn't. [retiredVoices] moves anything that
 * pointed at it onto [DEFAULT_VOICE_ID].
 *
 * v27 adds five voices Max picked from issue #30's candidates (issue #38),
 * named by him: four CC0 donors to Kyutai's Unmute voice-donation project
 * ([KYUTAI_DONOR_VOICE_KEYS]; each WAV is the donor's published clip from
 * `kyutai/tts-voices` `voice-donations/`, unchanged) and `jenny`, a 26 s
 * reference cut from the Jenny (Dioco) TTS dataset, whose licence requires
 * the voice be called "Jenny". See `LICENSES/pocket-tts.md` for the
 * per-voice source/license map.
 *
 * Pocket TTS is a different beast from the sherpa-onnx-backed engines:
 *  - It runs on Microsoft `onnxruntime-android` directly (no sherpa-onnx).
 *  - It uses a 5-graph pipeline (text conditioner + flow_lm + flow + mimi
 *    encoder + mimi decoder) with Latent Space Diffusion inference.
 *  - The predefined voices are reference WAVs in `voices/<name>.wav`
 *    inside the bundle. On first use of each voice, the engine encodes
 *    the WAV through `mimi_encoder` to produce a `[numFrames, 1024]`
 *    embedding it caches on disk for subsequent runs.
 *  - Users can ALSO clone new voices from their own audio (recorder or
 *    file picker) — that path lands in v0.3.0 once the inference loop is
 *    in. Cloned voices get inserted into `voice_meta` at clone time.
 *
 * Voice IDs use the convention `"<engine>:<name>"`, where `<name>` is the
 * lowercase upstream voice key (also the bundle's `voices/<name>.wav`
 * filename). Display names are the capitalized form of the same key.
 */
object PocketVoiceCatalog {

    const val ENGINE = "pocket-tts-en-v2026_04"
    const val LANGUAGE = "en-US"

    /**
     * Sample rate documented in the bundle's `bundle.json` (`sample_rate: 24000`).
     * The mimi_decoder produces 24 kHz mono float samples — we convert to
     * PCM16 in the engine before the AudioTrack write.
     */
    const val SAMPLE_RATE = 24000

    /**
     * Pre-checked voice for first-launch / "any voice" requests. Alba is
     * upstream pocket-tts's documented default — a warm low-mid male
     * voice (~133 Hz median F0) that demos well across short and long
     * inputs.
     */
    const val DEFAULT_VOICE_ID = "pocket-tts-en-v2026_04:alba"

    fun voiceId(name: String): String = "$ENGINE:$name"

    /**
     * Whether the three VCTK-derived voices ([VCTK_VOICE_KEYS]) are offered.
     * Pending Max's call (issue #19); they stay for now. Flipping this to
     * false is the whole change: they leave [voices] and join
     * [retiredVoices], so every start moves aliases and the default voice
     * off them onto [DEFAULT_VOICE_ID] (`alba`, not VCTK).
     */
    const val OFFER_VCTK_VOICES = true

    /** Voice keys whose reference prompts come from the CSTR VCTK corpus. */
    val VCTK_VOICE_KEYS: Set<String> = setOf("azelma", "eponine", "fantine")

    /**
     * Voice keys added in the v27 bundle whose reference is a CC0 Kyutai
     * Unmute voice donation (issue #38): Kyutai donor `3973` → mary,
     * `spanish-limaperu` → rita, `thepolishdane` → stephen, `floyd2026` →
     * ramone. `javert` came from the same programme earlier.
     */
    val KYUTAI_DONOR_VOICE_KEYS: Set<String> = setOf("mary", "rita", "stephen", "ramone")

    /**
     * The predefined voices the app offers, in the order they appear in
     * `bundle.json#predefined_voices`. Order has no functional meaning for
     * Pocket (voices are addressed by name, not index — there's no
     * speaker-ID like in voices.bin), but keep the upstream order so the
     * voice picker stays stable across bundle refreshes. The upstream
     * `cosette` (Expresso) and `jean` (EARS) voices are CC-BY-NC-4.0 and
     * are intentionally absent — see the class doc.
     *
     * Gender annotations derived from autocorrelation-based F0 estimation
     * of the bundled `voices/<name>.wav` reference samples (median F0 over
     * voiced frames; threshold at ~150 Hz). The names don't reliably map
     * to the Les Misérables canonical genders — `alba` isn't a Les Mis
     * name at all, and the old bundle's `marius` (a male character, no longer
     * offered) had a female voice prompt. Trust the audio, not the name.
     * The v27 voices (issue #38) follow javert in the bundle's order; their
     * genders are the ones Max gave with the names.
     */
    val voices: List<VoiceMeta> = listOf(
        seed("alba", "male"),       //  ~133 Hz
        seed("azelma", "female"),   //  ~214 Hz
        seed("eponine", "female"),  //  ~164 Hz
        seed("fantine", "female"),  //  ~211 Hz
        seed("javert", "male"),     //   ~96 Hz
        seed("mary", "female"),     //  ~203 Hz, US
        seed("rita", "female"),     //  ~200 Hz, Latin American (Spanish reference)
        seed("stephen", "male"),    //  ~123 Hz, US
        seed("ramone", "male"),     //  ~134 Hz, England
        seed("jenny", "female"),    //  ~179 Hz, Irish
    ).filter { OFFER_VCTK_VOICES || it.id.substringAfter(':') !in VCTK_VOICE_KEYS }

    /**
     * Voices the app no longer offers (retired id → replacement id), applied
     * on every start by [migrateRetiredVoices]. `marius` went for quality
     * (issue #19); the VCTK voices join it when [OFFER_VCTK_VOICES] is false.
     */
    val retiredVoices: Map<String, String> =
        (setOf("marius") + if (OFFER_VCTK_VOICES) emptySet<String>() else VCTK_VOICE_KEYS)
            .associate { voiceId(it) to DEFAULT_VOICE_ID }

    /**
     * The picker's credit line per voice key (#37), from
     * `LICENSES/pocket-tts.md`'s per-voice table. The CC BY voices name
     * their source; the CC0 donors (javert and the v27 Kyutai donors) stay
     * anonymous. Jenny's licence asks for "Jenny (Dioco)" where practical
     * and is bespoke, so the line has no licence name. `marius` is absent:
     * no longer offered.
     */
    fun creditFor(voiceKey: String): VoiceCredit? = when (voiceKey) {
        "alba" -> VoiceCredit(source = "Alba Mackenna (Kyutai)", license = "CC BY 4.0")
        in VCTK_VOICE_KEYS -> VoiceCredit(source = "VCTK (University of Edinburgh)", license = "CC BY 4.0")
        "javert", in KYUTAI_DONOR_VOICE_KEYS -> VoiceCredit(donor = true, license = "CC0")
        "jenny" -> VoiceCredit(source = "Jenny (Dioco)")
        else -> null
    }

    private fun seed(name: String, gender: String): VoiceMeta = VoiceMeta(
        id = voiceId(name),
        engine = ENGINE,
        // The lowercase [name] is the machinery key (voice id, `voices/<name>.wav`
        // in the bundle); the UI gets the capitalized form.
        displayName = name.replaceFirstChar { it.uppercase() },
        languageCode = LANGUAGE,
        sampleRate = SAMPLE_RATE,
        gender = gender,
        isInstalled = false,
    )
}
