package app.marmalade.tts.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.marmalade.tts.R
import app.marmalade.tts.data.VoiceCredits
import app.marmalade.tts.data.db.VoiceMeta

/**
 * A voice row's credit line (#37), e.g. "Voice donor · CC0", or nothing for
 * a voice that deliberately has none ([VoiceCredits]). Placed after the
 * name and subtitle, so TalkBack reads it after the voice name.
 */
@Composable
internal fun VoiceCreditLine(voice: VoiceMeta) {
    val credit = VoiceCredits.forVoice(voice) ?: return
    val donor = stringResource(R.string.voice_credit_donor)
    val modelBy = credit.modelBy?.let { stringResource(R.string.voice_credit_model_by, it) }
    Text(
        text = credit.render(donor, modelBy),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
