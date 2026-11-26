package dev.pear.remote.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pear.remote.app.R
import dev.pear.remote.protocol.CatalogAction
import dev.pear.remote.protocol.CatalogItem
import dev.pear.remote.protocol.CatalogItemKind
import dev.pear.remote.protocol.CatalogLayout
import dev.pear.remote.protocol.Track

/** What a row or tile needs from the screen. */
class ItemCallbacks(
    val tap: (PageHolder, CatalogItem) -> Unit,
    val more: (PageHolder, CatalogItem) -> Unit,
    val act: (PageHolder, CatalogItem, CatalogAction) -> Unit,
    val nowPlaying: (PageHolder, CatalogItem) -> Boolean,
)

val CatalogAction.label: Int
    get() = when (this) {
        CatalogAction.OPEN -> R.string.action_open
        CatalogAction.PLAY -> R.string.action_play
        CatalogAction.SHUFFLE -> R.string.action_shuffle
        CatalogAction.PLAY_NEXT -> R.string.action_play_next
        CatalogAction.ADD_TO_QUEUE -> R.string.action_add_to_queue
    }

/** Actions for the long-press / 3-dot sheet: everything the item offers, in a fixed order. */
fun sheetActions(item: CatalogItem): List<CatalogAction> =
    listOf(CatalogAction.PLAY, CatalogAction.SHUFFLE, CatalogAction.PLAY_NEXT, CatalogAction.ADD_TO_QUEUE, CatalogAction.OPEN)
        .filter { it in item.actions }

private fun hasSheet(item: CatalogItem) = item.actions.size > 1

/**
 * Taps, long-presses and TalkBack actions for one item. TalkBack users get
 * every sheet action directly as custom actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.itemGestures(h: PageHolder, item: CatalogItem, cb: ItemCallbacks): Modifier {
    val tapLabel = stringResource(
        if (item.kind.isTrack && CatalogAction.PLAY in item.actions) R.string.item_play_label else R.string.item_open_label,
        item.title,
    )
    val moreLabel = stringResource(R.string.more_options, item.title)
    val actions = sheetActions(item).map { a ->
        val label = stringResource(a.label)
        CustomAccessibilityAction(label) { cb.act(h, item, a); true }
    }
    return this
        .combinedClickable(
            onClickLabel = tapLabel,
            onLongClickLabel = if (hasSheet(item)) moreLabel else null,
            onLongClick = if (hasSheet(item)) ({ cb.more(h, item) }) else null,
            role = Role.Button,
            onClick = { cb.tap(h, item) },
        )
        .semantics { if (actions.size > 1) customActions = actions }
}

@Composable
private fun NowPlayingLabel() {
    // Text and icon, not colour alone.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Equalizer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.now_playing_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Subtitle(item: CatalogItem, maxLines: Int = 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.explicit) {
            val label = stringResource(R.string.explicit)
            Surface(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                contentColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.padding(end = 4.dp).semantics { contentDescription = label },
            ) {
                Text("E", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 3.dp))
            }
        }
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A list row: cover, title, subtitle, "Now playing", 3-dot menu. */
@Composable
fun ItemRow(h: PageHolder, item: CatalogItem, cb: ItemCallbacks) {
    val playing = cb.nowPlaying(h, item)
    val round = item.kind == CatalogItemKind.ARTIST
    ListItem(
        modifier = Modifier.heightIn(min = 64.dp).itemGestures(h, item, cb),
        leadingContent = {
            Cover(item.thumb, Modifier.size(48.dp).clip(if (round) CircleShape else RoundedCornerShape(4.dp)))
        },
        headlineContent = {
            Text(
                item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.Bold else null,
            )
        },
        supportingContent = {
            Column {
                if (playing) NowPlayingLabel()
                if (item.subtitle.isNotEmpty() || item.explicit) Subtitle(item)
            }
        },
        trailingContent = if (hasSheet(item)) {
            {
                IconButton(onClick = { cb.more(h, item) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options, item.title))
                }
            }
        } else {
            null
        },
    )
}

/** A square tile for carousels and grids. */
@Composable
fun ItemTile(h: PageHolder, item: CatalogItem, cb: ItemCallbacks, width: Dp? = 140.dp) {
    val playing = cb.nowPlaying(h, item)
    val round = item.kind == CatalogItemKind.ARTIST
    Column(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clip(RoundedCornerShape(8.dp)).itemGestures(h, item, cb).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Cover(
            item.thumb,
            Modifier.fillMaxWidth().aspectRatio(1f).clip(if (round) CircleShape else RoundedCornerShape(6.dp)),
        )
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (playing) NowPlayingLabel()
        if (item.subtitle.isNotEmpty() || item.explicit) Subtitle(item, maxLines = 1)
    }
}

/** Search's top result: a larger card. */
@Composable
fun TopResultCard(h: PageHolder, item: CatalogItem, cb: ItemCallbacks) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.itemGestures(h, item, cb).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.search_top_result),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val round = item.kind == CatalogItemKind.ARTIST
                Cover(item.thumb, Modifier.size(88.dp).clip(if (round) CircleShape else RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (cb.nowPlaying(h, item)) NowPlayingLabel()
                    Subtitle(item, maxLines = 2)
                }
            }
            val quick = listOf(CatalogAction.PLAY, CatalogAction.SHUFFLE).filter { it in item.actions }
            if (quick.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.forEach { a -> ActionButton(a, primary = a == quick.first()) { cb.act(h, item, a) } }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(a: CatalogAction, primary: Boolean, onClick: () -> Unit) {
    val icon = if (a == CatalogAction.SHUFFLE) Icons.Filled.Shuffle else Icons.Filled.PlayArrow
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(a.label))
    }
    if (primary) {
        Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { content() }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { content() }
    }
}

/** Header of an album, playlist or artist page: cover, title, Play and Shuffle. */
@Composable
fun DetailHeader(h: PageHolder, onAction: (CatalogAction) -> Unit) {
    val header = h.page?.header ?: return
    val round = header.kind == dev.pear.remote.protocol.HeaderKind.ARTIST
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cover(header.thumb, Modifier.size(192.dp).clip(if (round) CircleShape else RoundedCornerShape(8.dp)))
        Text(
            header.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (header.subtitle.isNotEmpty()) {
            Text(
                header.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val actions = listOf(CatalogAction.PLAY, CatalogAction.SHUFFLE).filter { it in header.actions }
        if (actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                actions.forEach { a -> ActionButton(a, primary = a == actions.first()) { onAction(a) } }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** Mood and genre chips (catalog.open on the chip's ref). */
@Composable
fun ChipsRow(h: PageHolder, onChip: (Int) -> Unit) {
    val chips = h.page?.chips.orEmpty()
    if (chips.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(chips) { i, chip ->
            FilterChip(
                selected = false,
                onClick = { onChip(i) },
                label = { Text(chip.title, maxLines = 1) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/**
 * The sections of a page into a lazy list: carousels as rows of tiles,
 * lists as rows, grids as a two-row horizontal grid on feeds or as rows of
 * tiles on single-section pages (library albums).
 */
fun LazyListScope.sections(
    h: PageHolder,
    cb: ItemCallbacks,
    columns: Int,
    topResult: Boolean = false,
) {
    val page = h.page ?: return
    val single = page.sections.size == 1
    page.sections.forEachIndexed { si, sec ->
        var items = sec.items
        if (topResult && si == 0) {
            val first = items.first()
            item(key = "top") { TopResultCard(h, first, cb) }
            items = items.drop(1)
            if (items.isEmpty()) return@forEachIndexed
        }
        sec.title?.takeIf { it.isNotBlank() && !(topResult && si == 0) }?.let { t -> item(key = "t$si") { SectionTitle(t) } }
        when {
            sec.layout == CatalogLayout.LIST -> itemsIndexed(items, key = { i, _ -> "s$si-$i" }) { _, it -> ItemRow(h, it, cb) }
            sec.layout == CatalogLayout.GRID && single -> {
                val rows = items.chunked(columns)
                itemsIndexed(rows, key = { i, _ -> "g$si-$i" }) { _, row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { Box(Modifier.weight(1f)) { ItemTile(h, it, cb, width = null) } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            sec.layout == CatalogLayout.GRID -> item(key = "s$si") {
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.fillMaxWidth().height(GRID_ROW_HEIGHT * 2),
                ) {
                    itemsIndexed(items, key = { i, _ -> "s$si-$i" }) { _, it -> ItemTile(h, it, cb, width = 120.dp) }
                }
            }
            else -> item(key = "s$si") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(items, key = { i, _ -> "s$si-$i" }) { _, it ->
                        ItemTile(h, it, cb)
                    }
                }
            }
        }
    }
}

private val GRID_ROW_HEIGHT = 196.dp

/** Loads the next page when the list gets near its end (no polling: driven by scrolling). */
@Composable
fun LoadMoreWhenNearEnd(state: LazyListState, h: PageHolder, onLoadMore: () -> Unit) {
    val nearEnd by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - 4
        }
    }
    val cursor = h.page?.continuation
    LaunchedEffect(nearEnd, cursor) { if (nearEnd && cursor != null) onLoadMore() }
}

/** Loading, error and empty states of a page. Returns true if it drew one (nothing else to show). */
@Composable
fun PageState(
    h: PageHolder,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    signedOutText: Int = R.string.page_signed_out,
    emptyText: String? = null,
): Boolean {
    val page = h.page
    val err = h.error
    val text: String = when {
        page == null && h.loading -> {
            val label = stringResource(R.string.loading)
            Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = label })
            }
            return true
        }
        page == null && err != null -> stringResource(
            when (err) {
                PageError.SIGNED_OUT -> signedOutText
                PageError.NOT_READY -> R.string.page_not_ready
                PageError.UNAVAILABLE -> R.string.page_unavailable
                PageError.OFFLINE -> R.string.page_offline
                PageError.RATE_LIMITED -> R.string.page_rate_limited
                PageError.OTHER -> R.string.page_failed
            },
        )
        page == null -> return true
        page.sections.isEmpty() && page.header == null && page.chips.isEmpty() ->
            emptyText ?: stringResource(R.string.page_empty)
        else -> return false
    }
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (page == null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
        }
    }
    return page == null
}

/** A whole page: optional header and chips, sections, load-more and states. */
@Composable
fun PageList(
    h: PageHolder,
    vm: BrowseViewModel,
    cb: ItemCallbacks,
    modifier: Modifier = Modifier,
    state: LazyListState,
    topResult: Boolean = false,
    signedOutText: Int = R.string.page_signed_out,
    emptyText: String? = null,
    leading: (LazyListScope.() -> Unit)? = null,
) {
    LoadMoreWhenNearEnd(state, h) { vm.loadMore(h) }
    val columns = 3
    LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
        leading?.invoke(this)
        if (h.page?.header != null) item(key = "header") { DetailHeader(h) { vm.headerAction(h, it) } }
        if (h.page?.chips?.isNotEmpty() == true) item(key = "chips") { ChipsRow(h) { vm.openChip(h, it) } }
        item(key = "state") {
            PageState(h, onRetry = { vm.load(h) }, signedOutText = signedOutText, emptyText = emptyText)
        }
        sections(h, cb, columns, topResult)
        if (h.loadingMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                }
            }
        }
    }
}

/** Long-press / 3-dot sheet with the actions the item offers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(item: CatalogItem, onAction: (CatalogAction) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(item.thumb, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotEmpty()) Subtitle(item)
            }
        }
        sheetActions(item).forEach { a ->
            ListItem(
                modifier = Modifier.heightIn(min = 56.dp).clickable(role = Role.Button) { onAction(a) },
                leadingContent = {
                    Icon(
                        when (a) {
                            CatalogAction.PLAY -> Icons.Filled.PlayArrow
                            CatalogAction.SHUFFLE -> Icons.Filled.Shuffle
                            CatalogAction.PLAY_NEXT -> Icons.AutoMirrored.Filled.PlaylistPlay
                            CatalogAction.ADD_TO_QUEUE -> Icons.AutoMirrored.Filled.PlaylistAdd
                            CatalogAction.OPEN -> Icons.AutoMirrored.Filled.QueueMusic
                        },
                        contentDescription = null,
                    )
                },
                headlineContent = { Text(stringResource(a.label)) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Small helper so callers don't depend on Track directly. */
fun nowPlayingCallback(vm: BrowseViewModel, track: Track?): (PageHolder, CatalogItem) -> Boolean =
    { h, item -> vm.isNowPlaying(h, item, track) }
