package app.marmalade.tts.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R
import app.marmalade.tts.ui.MarmaladeIcons
import app.marmalade.tts.ui.screen.CloudMark

/**
 * One row of a persona (alias) list, pre-resolved for display.
 *
 * The rows show exactly these three facts and nothing else, so the
 * resolution happens once in the ViewModel rather than per-recomposition in
 * the composable — [app.marmalade.tts.data.VoicePathResolver] consults the
 * cloud provider directory, which is not work to do inside a list item.
 */
data class Persona(
    /** Alias UUID; a raw voice id when the row stands in for a hand-picked voice. */
    val id: String,
    val name: String,
    /** "Kokoro · Adam" — model then voice. */
    val subtitle: String,
    /** Needs the network. Drives the cloud glyph; on-device gets none. */
    val isCloud: Boolean,
    val isPrimary: Boolean,
)

/**
 * Persona picker. Every saved persona with the same three facts the Speak
 * screen's voice row shows, plus (on the Speak screen) the escape hatches:
 * hand-pick a voice, or make a new persona. The reader uses it bare to pick
 * the alias an article is read in (#66). "New persona" lives here rather than as a permanent chip on
 * the main screen, where it competed with the personas themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSheet(
    personas: List<Persona>,
    activeId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /** The Speak screen's escape hatches; null hides them (the reader picks aliases only). */
    onPickVoice: (() -> Unit)? = null,
    onCreate: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.speak_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(personas, key = { it.id }) { persona ->
                ListItem(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(persona.name)
                            if (persona.isPrimary) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.speak_persona_primary),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = persona.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (persona.isCloud) {
                                Spacer(Modifier.width(5.dp))
                                CloudMark()
                            }
                        }
                    },
                    trailingContent = {
                        if (persona.id == activeId) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.speak_persona_selected),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier.clickable {
                        onPick(persona.id)
                        onDismiss()
                    },
                )
            }
        }
        if (onPickVoice != null || onCreate != null) HorizontalDivider()
        onPickVoice?.let { pickVoice ->
            ListItem(
                headlineContent = { Text(stringResource(R.string.speak_pick_voice_directly)) },
                leadingContent = {
                    Icon(MarmaladeIcons.Speak, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    pickVoice()
                    onDismiss()
                },
            )
        }
        onCreate?.let { create ->
            ListItem(
                headlineContent = { Text(stringResource(R.string.speak_new_persona)) },
                leadingContent = {
                    Icon(Icons.Filled.Add, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    create()
                    onDismiss()
                },
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}
