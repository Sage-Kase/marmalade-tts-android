package app.marmalade.tts.data

import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.VoicePackCatalog

/**
 * The short credit line the voice pickers show under a voice (issue #37):
 * who recorded or released it, and the licence of its voice data. The full
 * texts stay in Settings → Open-source licenses, CREDITS.md and
 * LICENSES/; this puts the gist where people choose voices.
 *
 * Proper names and licence names are shown as written in every language;
 * only [donor]'s "Voice donor" and [modelBy]'s "model by" are translated.
 *
 * @property source  Who recorded or released the voice, e.g. "Alba Mackenna (Kyutai)".
 * @property license Short licence name of the voice data, e.g. "CC BY 4.0"; null when it is bespoke.
 * @property donor   An anonymous voice donor (shown as "Voice donor").
 * @property modelBy Who trained the model, when that is part of the attribution.
 */
data class VoiceCredit(
    val source: String? = null,
    val license: String? = null,
    val donor: Boolean = false,
    val modelBy: String? = null,
) {
    /**
     * The line, given the translated "Voice donor" and the translated
     * "model by <[modelBy]>" (null when [modelBy] is).
     */
    fun render(donorLabel: String, modelByPhrase: String?): String =
        listOfNotNull(
            donorLabel.takeIf { donor },
            source,
            modelByPhrase,
            license,
        ).joinToString(" · ")
}

/**
 * The credit for any voice row, or null on purpose:
 *
 *  - Pocket (and its developer twin): per voice ([PocketVoiceCatalog.creditFor]).
 *    A voice the user cloned from their own audio has none.
 *  - VITS Marmalade: per pack ([app.marmalade.tts.install.VoicePack.credit]).
 *  - Kokoro German: Thorsten Müller ([KokoroGermanVoiceCatalog.CREDIT]).
 *  - Kokoro and Kitten stock voices: none. Upstream documents no source for
 *    any of them (LICENSES/kokoro-direct.md, kitten-direct.md), so a line
 *    could only repeat the engine's Apache-2.0 licence, which the licences
 *    screen already shows. We don't invent provenance.
 *  - Cloud voices: none. The provider owns the voice; we can't vouch for it.
 */
object VoiceCredits {

    fun forVoice(voice: VoiceMeta): VoiceCredit? = when (voice.engine) {
        PocketVoiceCatalog.ENGINE, PocketDevVoiceCatalog.ENGINE ->
            PocketVoiceCatalog.creditFor(voice.id.substringAfter(':'))
        VitsVoiceCatalog.ENGINE ->
            VitsVoiceCatalog.packIdOf(voice.id)?.let { VoicePackCatalog.byId(it) }?.credit
        KokoroGermanVoiceCatalog.ENGINE -> KokoroGermanVoiceCatalog.CREDIT
        else -> null
    }
}
