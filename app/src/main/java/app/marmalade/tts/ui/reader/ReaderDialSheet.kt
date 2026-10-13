package app.marmalade.tts.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R
import app.marmalade.tts.ui.MarmaladeFilterChip
import app.marmalade.tts.ui.components.Persona
import app.marmalade.tts.ui.components.PersonaRow

// -----------------------------------------------------------------------------
// The alias dial's sheet (#67, Max's design): who reads this article, and how
// fast, in one place — the alias list, then a row of speed chips.
//
// The speed is an OVERRIDE. Reading starts at the reading alias's own speed;
// a chip picks an absolute speed that replaces it for this article (voice,
// effect and language still come from the alias). Switching alias brings the
// new alias's speed unless the user set one here, which then stays — until
// they pick the alias's own speed chip again (Narrator's speedOverride). When
// the alias's speed isn't a curated chip (an alias tuned to 1.1, say) it gets
// a chip of its own, in sorted position, so it is visibly selected at first
// and still there to go back to after picking another. See
// ReaderPlaybackController.setSpeed / pickAlias.
//
// Session-scoped by design (Max's second UX pass): this is how THIS article is
// read, not a setting. Nothing here touches SettingsRepository.
// -----------------------------------------------------------------------------

/** The offered speeds. Curated — the controller clamps a wider range. */
private val SPEED_CHOICES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * The chips to show while reading at [current] an article whose alias speaks
 * at [starting]: the curated set plus both, in sorted position — [starting] so
 * the way back to it never disappears, [current] so the selected chip always
 * exists.
 */
internal fun readerSpeedChoices(current: Float, starting: Float): List<Float> =
    (SPEED_CHOICES + starting + current).distinct().sorted()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    // Skip the half-expanded stop: the speed chips sit under the alias list
    // and must not open below the fold.
    ModalBottomSheet(
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
                HorizontalDivider()
            }
            SheetHeading(stringResource(R.string.reader_speed_title), Modifier.padding(top = 16.dp))
            // FlowRow, not Row: the alias's own speed can add a sixth chip,
            // which must wrap on a narrow phone rather than be clipped.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
            ) {
                for (choice in readerSpeedChoices(speed, startingSpeed)) {
                    MarmaladeFilterChip(
                        selected = choice == speed,
                        onClick = { onSpeedChange(choice) },
                        label = {
                            Text(stringResource(R.string.reader_speed_value, formatSpeed(choice)))
                        },
                    )
                }
            }
            // Below both halves: the alias pick is for this article only too.
            Text(
                text = stringResource(R.string.reader_speed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
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
