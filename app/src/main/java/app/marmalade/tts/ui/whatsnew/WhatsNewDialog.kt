package app.marmalade.tts.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R

/**
 * The "What's new" pop-up (#41): a modal over the Speak screen once per
 * release (see [WhatsNew.decide]), and on demand from Settings → About.
 *
 * Every way out — "Got it", back, a tap outside — calls [onDismiss].
 * For TalkBack the title is a heading and the notes are a list ("list,
 * 4 items"), each item read as one unit without its bullet.
 */
@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    val items = stringArrayResource(R.array.whats_new_items)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.whats_new_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            // Scrolls at large font sizes, where the notes outgrow the dialog.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .semantics {
                        collectionInfo = CollectionInfo(rowCount = items.size, columnCount = 1)
                    },
            ) {
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            collectionItemInfo = CollectionItemInfo(
                                rowIndex = index,
                                rowSpan = 1,
                                columnIndex = 0,
                                columnSpan = 1,
                            )
                        },
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clearAndSetSemantics {},
                        )
                        Text(text = item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.whats_new_got_it))
            }
        },
    )
}
