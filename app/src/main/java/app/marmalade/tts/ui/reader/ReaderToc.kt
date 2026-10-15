package app.marmalade.tts.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.marmalade.tts.R
import app.marmalade.tts.reader.ArticleBlock
import app.marmalade.tts.ui.theme.depth

// -----------------------------------------------------------------------------
// Table of contents — the article's own headings, as a way to jump.
//
//   blocks[] ──► tocEntriesOf() ──► TocEntry(blockIndex, text, depth)
//                     │
//                     └── empty unless the page really has a structure
//                         (MIN_HEADINGS), because a "contents" button that
//                         opens a list of one is worse than no button.
//
//   Tapping an entry is tapping that block in the article: same seek, same
//   auto-scroll, so the ToC adds a way in and no second behaviour to maintain.
//   (One exception: with TalkBack on there is no auto-scroll, so a ToC pick
//   scrolls explicitly — see FollowSpokenBlock.)
//
// The two functions are plain Kotlin so the visibility rule and the
// current-section arithmetic are unit-testable off-UI.
// -----------------------------------------------------------------------------

/**
 * One heading, ready to draw. [blockIndex] indexes the article's blocks (what
 * playback speaks); [depth] is 0 for the shallowest heading in this article,
 * so a page whose sections are all `h2` doesn't render uniformly indented.
 */
data class TocEntry(
    val blockIndex: Int,
    val text: String,
    val depth: Int,
)

/**
 * Headings of [blocks], or empty when the article has no structure worth
 * navigating — fewer than [MIN_TOC_HEADINGS] of them.
 */
fun tocEntriesOf(blocks: List<ArticleBlock>): List<TocEntry> {
    val headings = blocks.withIndex().filter { it.value is ArticleBlock.Heading }
    if (headings.size < MIN_TOC_HEADINGS) return emptyList()
    val minLevel = headings.minOf { (it.value as ArticleBlock.Heading).level }
    return headings.map { (index, block) ->
        val heading = block as ArticleBlock.Heading
        TocEntry(
            blockIndex = index,
            text = heading.text,
            depth = heading.level - minLevel,
        )
    }
}

/**
 * The entry the reader is inside: the last heading at or before
 * [currentBlockIndex]. Null before the first heading, or when nothing is being
 * spoken — the article's preamble belongs to no section.
 */
fun currentTocEntry(entries: List<TocEntry>, currentBlockIndex: Int?): TocEntry? {
    val index = currentBlockIndex ?: return null
    return entries.lastOrNull { it.blockIndex <= index }
}

/** Below this many headings a page isn't structured, it just has a subhead. */
const val MIN_TOC_HEADINGS = 2

/** Indent per heading level. Two levels deep is already the practical maximum. */
private val TOC_INDENT = 16.dp

/**
 * The contents as a drawer from the END edge (#66; was a bottom sheet) — the
 * right on a left-to-right layout, the left on a right-to-left one —
 * wrapping the reader's [content]. Opened by the top bar's contents button;
 * closed by a pick, a tap on the scrim, a swipe back toward the edge, or Back.
 * Swiping does not open it: the page scrolls, and the edge is the system's
 * back gesture.
 *
 * Material's [ModalNavigationDrawer] only opens from the start edge, so it
 * runs in the mirrored layout direction and both the drawer and the reader
 * get the real one back.
 */
@Composable
fun ReaderTocDrawer(
    drawerState: DrawerState,
    entries: List<TocEntry>,
    currentBlockIndex: Int?,
    onEntryTapped: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val mirrored = if (direction == LayoutDirection.Ltr) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides mirrored) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            scrimColor = MaterialTheme.depth.scrim,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    // Rounded on the side facing the page; no status-bar
                    // inset: AppRoot's Scaffold already sits below it. The
                    // sheet step of the surface ramp, untinted, like the app's
                    // bottom sheets.
                    ModalDrawerSheet(
                        drawerShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                        drawerContainerColor = MaterialTheme.depth.sheet,
                        drawerTonalElevation = 0.dp,
                        windowInsets = WindowInsets(0),
                    ) {
                        TocList(entries, currentBlockIndex, onEntryTapped)
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides direction, content = content)
        }
    }
}

@Composable
private fun TocList(
    entries: List<TocEntry>,
    currentBlockIndex: Int?,
    onEntryTapped: (Int) -> Unit,
) {
    val current = currentTocEntry(entries, currentBlockIndex)
    // The section being read gets the toast wash (the lab's active row) in
    // place of Material's secondary-container pill; the rest stay quiet ink.
    val itemColors = NavigationDrawerItemDefaults.colors(
        selectedContainerColor = MaterialTheme.depth.selected,
        unselectedContainerColor = Color.Transparent,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
    )
    val readFromHere = stringResource(R.string.reader_read_from_here)
    // Opens on the section being read, a little of the one before showing.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (entries.indexOf(current) - 1).coerceAtLeast(0),
    )
    Column(modifier = Modifier.fillMaxHeight().navigationBarsPadding()) {
        Text(
            text = stringResource(R.string.reader_toc_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 12.dp)
                .semantics { heading() },
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            items(entries, key = { it.blockIndex }) { entry ->
                val isCurrent = entry.blockIndex == current?.blockIndex
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = entry.text,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    // `selected` also tells TalkBack which section is being read.
                    selected = isCurrent,
                    onClick = { onEntryTapped(entry.blockIndex) },
                    colors = itemColors,
                    modifier = Modifier
                        .padding(start = TOC_INDENT * entry.depth)
                        .semantics {
                            onClick(label = readFromHere) {
                                onEntryTapped(entry.blockIndex)
                                true
                            }
                        },
                )
            }
        }
    }
}
