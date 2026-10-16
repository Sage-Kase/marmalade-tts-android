package app.marmalade.tts.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R
import app.marmalade.tts.ui.components.MarmaladeSheet
import app.marmalade.tts.ui.components.Persona
import app.marmalade.tts.ui.components.PersonaRow
import app.marmalade.tts.ui.components.SpeedSlider
import app.marmalade.tts.ui.theme.depth

// -----------------------------------------------------------------------------
// The alias dial's sheet (#67, Max's design): who reads this article, and how
// fast, in one place — the alias list, then the alias editor's speed slider
// (#71, SpeedSlider), so speed looks and works the same across the app.
//
// The speed is an OVERRIDE. Reading starts at the reading alias's own speed;
// the slider sets an absolute speed that replaces it for this article (voice,
// effect and language still come from the alias). Switching alias brings the
// new alias's speed unless the user set one here, which then stays — until
// they slide back to the alias's own speed (Narrator's speedOverride). The
// slider always has a stop there, even off its 0.1× grid. It applies on
// release, so a drag sends the Narrator one speed, not every step. See
// ReaderPlaybackController.setSpeed / pickAlias.
//
// Session-scoped by design (Max's second UX pass): this is how THIS article is
// read, not a setting. Nothing here touches SettingsRepository.
// -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderDialSheet(
    aliases: List<Persona>,
    /** The alias reading the article; null for an installed voice no alias uses. */
    activeAliasId: String?,
    speed: Float,
    startingSpeed: Float,
    showPerfWarning: Boolean,
    onAliasPick: (String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    // Skip the half-expanded stop: the speed slider sits under the alias list
    // and must not open below the fold.
    MarmaladeSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (aliases.isNotEmpty()) {
                SheetHeading(stringResource(R.string.speak_sheet_title), Modifier.padding(bottom = 4.dp))
                // Scrolls by itself when there are many aliases, so the
                // speed below stays on screen. The sheet stays open on a
                // pick: the speed it brings shows at once (the override rule).
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(aliases, key = { it.id }) { alias ->
                        PersonaRow(
                            persona = alias,
                            active = alias.id == activeAliasId,
                            onClick = { onAliasPick(alias.id) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.depth.line)
            }
            // The thumb follows the drag here; the Narrator hears the speed
            // on release. Re-keyed on [speed], so it shows each new speed (an
            // alias pick, say) and never snaps back while the release lands.
            var shown by remember(speed) { mutableFloatStateOf(speed) }
            val shownText = stringResource(R.string.reader_speed_value, formatSpeed(shown))
            val title = stringResource(R.string.reader_speed_title)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, end = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetHeading(title, Modifier.weight(1f))
                Text(shownText, style = MaterialTheme.typography.titleMedium)
            }
            SpeedSlider(
                value = shown,
                onValueChange = { shown = it },
                onValueChangeFinished = { onSpeedChange(shown) },
                label = title,
                valueText = shownText,
                extraStop = startingSpeed,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // Below both halves: the alias pick is for this article only too.
            Text(
                text = stringResource(R.string.reader_speed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
            )
            // The perf warning fires when the chosen speed outruns the reading
            // engine's measured/predicted RTF — the ViewModel does that
            // resolution (see ReaderViewModel.showSpeedWarning), the same one
            // that puts the engine in chunk mode (#54). Same copy as the alias
            // editor's slider warning.
            if (showPerfWarning) {
                // The ⚠️ lead is composed here, not baked into the string,
                // which the alias editor shares.
                Text(
                    text = "⚠️ " + stringResource(R.string.alias_speed_perf_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .padding(horizontal = 24.dp)
            .semantics { heading() },
    )
}

/** "1", "1.25" — a whole number loses its ".0" so the chips stay narrow. */
internal fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
