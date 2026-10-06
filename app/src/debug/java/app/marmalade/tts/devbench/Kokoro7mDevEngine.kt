package app.marmalade.tts.devbench

import android.content.Context
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.engine.kokoro.KokoroDirectEngine
import app.marmalade.tts.phonemizer.EspeakPhonemizer
import app.marmalade.tts.phonemizer.SharedEspeakData
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// -----------------------------------------------------------------------------
// Kokoro7mDevEngine — Kokoro-7M-Distill, DEBUG BUILDS ONLY (issue #1, "B1")
// -----------------------------------------------------------------------------
//
// oddadmix/Kokoro-7M-Distill (Apache-2.0) is a 7.48M-param, single-voice
// student of Kokoro-82M. Exported with the same recipe as kokoro-de-v1_0
// (sherpa's KModelForONNX, opset 14), it has the kokoro-direct graph contract
// exactly — tokens int64 [1,N] / style float32 [1,256] / speed float32 [1] →
// audio, 24 kHz — and the same 178-token vocab, so [KokoroDirectEngine] runs
// it unchanged. Like [app.marmalade.tts.engine.kokoro.KokoroGermanEngine] this
// subclass only redirects the catalog-shaped seams:
//
//   * one style pack (`af_msa`, the pack the student was distilled against)
//     → voices.bin is [1, 510, 256] and [speakerIdFor] is always 0;
//   * English only → [espeakVoiceFor] is always "en-us";
//   * English IPA is tied espeak rewritten to misaki's spellings
//     ([englishPhonemes], [EspeakToMisaki]). The student was distilled on
//     misaki only and garbles plain espeak (issue #4).
//
// The model is SIDELOADED, never downloaded: the bench pushes model.onnx,
// voices.bin and tokens.txt into `${filesDir}/engines/kokoro-7m-dev/` with
// `adb` + `run-as`. That directory is its own, so the installed Kokoro engine
// is never touched.
//
// Deliberately NOT in EngineRegistry, EngineCatalog or any voice catalog: this
// file lives in the debug source set, so release builds (Play and F-Droid,
// developer mode included) cannot contain it, and nothing in the app routes
// to it. Only [EngineBenchService] calls it.
// -----------------------------------------------------------------------------

@Singleton
class Kokoro7mDevEngine @Inject constructor(
    @ApplicationContext ctx: Context,
    settings: SettingsRepository,
    sharedEspeak: SharedEspeakData,
) : KokoroDirectEngine(ctx, settings, sharedEspeak) {

    override val engineName: String = ENGINE

    /** Single style pack: voices.bin holds exactly one style table (row 0). */
    override fun speakerIdFor(voiceKey: String): Int = 0

    /** English only. */
    override fun espeakVoiceFor(voiceKey: String): String = "en-us"

    /**
     * Tied espeak rewritten to misaki's spellings, the only ones the student
     * was distilled on (issue #4: 31–34% → 3.5–5.5% WER). See [EspeakToMisaki].
     */
    override fun englishPhonemes(phon: EspeakPhonemizer, text: String, lang: String): String =
        EspeakToMisaki.convert(phon.phonemize(text, lang, tie = true))

    override val warmupVoiceKey: String = VOICE_KEY

    companion object {
        /** Engine name, and the bundle directory under `${filesDir}/engines/`. */
        const val ENGINE = "kokoro-7m-dev"
        const val VOICE_KEY = "af_msa"
        const val VOICE_ID = "$ENGINE:$VOICE_KEY"
    }
}
