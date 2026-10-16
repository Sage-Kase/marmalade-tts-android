package app.marmalade.tts.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.ui.MarmaladeIcons
import app.marmalade.tts.ui.components.WorkingDots
import app.marmalade.tts.ui.screen.PreviewState
import app.marmalade.tts.ui.screen.StopIcon
import app.marmalade.tts.ui.screen.VoiceCreditLine
import app.marmalade.tts.ui.theme.MarmaladeMotion
import app.marmalade.tts.ui.theme.MarmaladeToast
import app.marmalade.tts.ui.theme.depth

// -----------------------------------------------------------------------------
// The voice step (#87, Max's pick: concept A of the walkthrough-87 lab). It
// replaced the "Create your primary alias" form (name, engine, voice, speed,
// effect, two buttons) with one list of the voices on the phone, each with a
// speaker to hear it (#86's sample), one already picked, and one button:
// "Use Alba". The alias it saves is named after the voice; speed and effects
// are left for the Aliases tab.
//
//   OnboardingViewModel.voiceGroups ──► engine sections (null: still probing)
//   OnboardingViewModel.selectedVoice ─► the soft highlight + tick, the button
//   OnboardingViewModel.previewState ──► which speaker shows stop
//
//   row tap      → vm.pickVoice(id)       (doesn't stop a playing sample)
//   speaker tap  → vm.togglePreview(…)    (hearing never picks)
//   "Use Alba"   → vm.useVoice()          → the battery step
// -----------------------------------------------------------------------------

/** Voices a section shows before "Show N more": the lead engine's, then the rest's. */
private const val LEAD_SECTION_SHOWN = 6
private const val OTHER_SECTION_SHOWN = 3

@Composable
internal fun ChooseVoiceStep(
    padding: PaddingValues,
    groups: List<VoiceGroup>?,
    selected: VoiceMeta?,
    previewState: PreviewState,
    aliasOnEntry: Boolean,
    onPrepare: () -> Unit,
    onPick: (String) -> Unit,
    onTogglePreview: (VoiceMeta, String) -> Unit,
    onStopPreview: () -> Unit,
    onUse: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    // Probe the disk each time the step opens; a sample stops when it goes.
    LaunchedEffect(Unit) { onPrepare() }
    DisposableEffect(Unit) { onDispose { onStopPreview() } }

    // Sections the user opened with "Show N more".
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val phrase = stringResource(R.string.voices_preview_phrase)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_voice_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_voice_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        when {
            groups == null -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { WorkingDots() }

            groups.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Only when even the built-in Kitten is missing (its seed and
                // its download both failed): Back leads to Retry.
                Text(
                    text = stringResource(R.string.onboarding_alias_no_voices),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
            }

            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                groups.forEachIndexed { index, group ->
                    val name = group.engine.name
                    val limit = if (index == 0) LEAD_SECTION_SHOWN else OTHER_SECTION_SHOWN
                    // Never fold away the picked voice, nor a lone extra row.
                    val selectedAt = group.voices.indexOfFirst { it.id == selected?.id }
                    val showAll = name in expanded ||
                        group.voices.size <= limit + 1 ||
                        selectedAt >= limit
                    val shown = if (showAll) group.voices else group.voices.take(limit)
                    item(key = "head-$name") {
                        SectionHeader(group, top = index > 0)
                    }
                    items(items = shown, key = { it.id }) { voice ->
                        VoiceChoiceRow(
                            voice = voice,
                            isSelected = voice.id == selected?.id,
                            isPlaying = (previewState as? PreviewState.Playing)?.voiceId == voice.id,
                            // An engine whose model went missing can't play a sample.
                            canPlay = (previewState as? PreviewState.ModelMissing)?.engineName != voice.engine,
                            onPick = { onPick(voice.id) },
                            onToggle = { onTogglePreview(voice, phrase.format(voice.spokenName)) },
                        )
                    }
                    if (!showAll) {
                        item(key = "more-$name") {
                            TextButton(onClick = { expanded = expanded + name }) {
                                val hidden = group.voices.size - shown.size
                                Text(pluralStringResource(R.plurals.onboarding_voice_show_more, hidden, hidden))
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onUse,
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth().animateContentSize(MarmaladeMotion.fast()),
        ) {
            // spokenName: no Kokoro flag emoji on the button or in TalkBack.
            Text(stringResource(R.string.onboarding_voice_use, selected?.spokenName.orEmpty()))
        }
        // An alias already existed when the step opened (sideloaded data, or
        // Back from a later step): move on without saving another.
        if (aliasOnEntry) {
            TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_alias_finish_setup))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** An engine's name over its voices, and the Recommended label on the phone check's engine. */
@Composable
private fun SectionHeader(group: VoiceGroup, top: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = if (top) 16.dp else 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.engine.displayName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (group.isRecommended) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.onboarding_recommended),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.depth.dim,
            )
        }
    }
}

/**
 * One voice: a speaker to hear it, its name (and credit line), and a tick
 * when picked. The picked row takes a soft wash, faint toast in light mode
 * like a ticked engine card (#83 M), never an accent border. Tapping the row
 * picks it; TalkBack reads it as a selectable item, "selected" when picked.
 */
@Composable
private fun VoiceChoiceRow(
    voice: VoiceMeta,
    isSelected: Boolean,
    isPlaying: Boolean,
    canPlay: Boolean,
    onPick: () -> Unit,
    onToggle: () -> Unit,
) {
    val depth = MaterialTheme.depth
    val wash = if (depth.isDark) depth.selected else MarmaladeToast.copy(alpha = LIGHT_TICK_WASH)
    val fill by animateColorAsState(
        targetValue = if (isSelected) wash.compositeOver(depth.page) else Color.Transparent,
        animationSpec = MarmaladeMotion.fast(),
        label = "voiceRowFill",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(fill)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onPick)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A separate tap target: hearing a voice never picks it.
        IconButton(onClick = onToggle, enabled = canPlay) {
            Icon(
                // A speaker, as in the alias editor's picker (#86).
                imageVector = if (isPlaying) StopIcon else MarmaladeIcons.Speak,
                contentDescription = if (isPlaying) {
                    stringResource(R.string.effects_stop_card, voice.spokenName)
                } else {
                    stringResource(R.string.voices_preview, voice.spokenName)
                },
                tint = when {
                    isPlaying -> MaterialTheme.colorScheme.primary
                    canPlay -> depth.faint
                    else -> depth.ghost
                },
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(text = voice.displayName, style = MaterialTheme.typography.bodyLarge)
            VoiceCreditLine(voice)
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                // The row's selected state already says it.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
