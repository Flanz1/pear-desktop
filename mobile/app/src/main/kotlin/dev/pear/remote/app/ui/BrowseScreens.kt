package dev.pear.remote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pear.remote.app.R
import dev.pear.remote.client.PlayerSnapshot
import dev.pear.remote.protocol.CatalogItem
import dev.pear.remote.protocol.PlaybackStatus
import dev.pear.remote.protocol.SearchFilter

/**
 * Home, Search and Library with a mini player above the navigation bar.
 * Shown when the desktop offers the catalog (protocol 1.1 with
 * catalog.browse); otherwise the app shows the plain remote as before.
 */
@Composable
fun MainShell(remote: RemoteViewModel, browse: BrowseViewModel, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val snap by remote.player.collectAsState()
    val status by remote.status.collectAsState()
    var sheet by remember { mutableStateOf<Pair<PageHolder, CatalogItem>?>(null) }
    val track = snap?.state?.track
    val cb = ItemCallbacks(
        tap = browse::tap,
        more = { h, item -> sheet = h to item },
        act = browse::act,
        nowPlaying = { h, item -> browse.isNowPlaying(h, item, track) },
    )
    val tabs = Tab.entries.filter { it != Tab.SEARCH || browse.support.search }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        // The activity's scaffold already applied the system bar insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                snap?.let { s -> if (s.state.track != null) MiniPlayer(s, remote) }
                NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                    tabs.forEach { t ->
                        val selected = browse.tab == t
                        NavigationBarItem(
                            selected = selected,
                            onClick = { browse.selectTab(t) },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
                                        Tab.SEARCH -> Icons.Filled.Search
                                        Tab.LIBRARY -> Icons.Filled.LibraryMusic
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.padding(horizontal = 16.dp)) { StatusBanner(status, remote) }
            val top = browse.stack(browse.tab).lastOrNull()
            Box(Modifier.weight(1f)) {
                when {
                    top != null -> DetailScreen(top, browse, cb)
                    browse.tab == Tab.HOME -> HomeScreen(remote, browse, cb)
                    browse.tab == Tab.SEARCH -> SearchScreen(browse, cb)
                    else -> LibraryScreen(remote, browse, cb)
                }
            }
        }
    }

    sheet?.let { (h, item) ->
        ItemSheet(
            item,
            onAction = { a ->
                sheet = null
                browse.act(h, item, a)
            },
            onDismiss = { sheet = null },
        )
    }
}

private val Tab.label: Int
    get() = when (this) {
        Tab.HOME -> R.string.tab_home
        Tab.SEARCH -> R.string.tab_search
        Tab.LIBRARY -> R.string.tab_library
    }

/** Cover, title, artist, play/pause and next. Tapping it opens Now Playing. */
@Composable
fun MiniPlayer(s: PlayerSnapshot, vm: RemoteViewModel) {
    val track = s.state.track ?: return
    val st = s.state.playback.status
    val playing = st == PlaybackStatus.PLAYING || st == PlaybackStatus.BUFFERING
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = stringResource(R.string.mini_player_open), role = Role.Button) {
                    vm.open(Screen.NowPlaying)
                }
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(track.thumb, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { vm.command { togglePlay() } }) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.remote_pause else R.string.remote_play),
                )
            }
            IconButton(onClick = { vm.command { next() } }) {
                Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.remote_next))
            }
        }
    }
}

/** A plain top bar (Material 3's TopAppBar is still experimental). */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
    }
}

@Composable
private fun DesktopsButton(remote: RemoteViewModel) {
    IconButton(onClick = { remote.open(Screen.Desktops) }) {
        Icon(Icons.Filled.Computer, contentDescription = stringResource(R.string.remote_computers))
    }
}

@Composable
fun HomeScreen(remote: RemoteViewModel, browse: BrowseViewModel, cb: ItemCallbacks) {
    val h = browse.home
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.tab_home)) { DesktopsButton(remote) }
        PageList(h, browse, cb, Modifier.weight(1f), state = h.listState)
    }
}

@Composable
fun DetailScreen(h: PageHolder, browse: BrowseViewModel, cb: ItemCallbacks) {
    Column(Modifier.fillMaxSize()) {
        TopBar(h.title, onBack = { browse.back() })
        PageList(h, browse, cb, Modifier.weight(1f), state = h.listState)
    }
}

@Composable
fun SearchScreen(browse: BrowseViewModel, cb: ItemCallbacks) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = browse.query,
            onValueChange = browse::editQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            label = { Text(stringResource(R.string.tab_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (browse.query.isNotEmpty()) {
                {
                    IconButton(onClick = browse::clearSearch) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                browse.submitSearch()
                keyboard?.hide()
            }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val showSuggestions = browse.suggestions.isNotEmpty() && browse.query != browse.submitted
        val results = browse.results
        when {
            showSuggestions -> LazyColumn(Modifier.weight(1f)) {
                items(browse.suggestions.toList()) { s ->
                    ListItem(
                        modifier = Modifier
                            .heightIn(min = 56.dp)
                            .clickable(onClickLabel = stringResource(R.string.search_suggestion, s), role = Role.Button) {
                                browse.submitSearch(s)
                                keyboard?.hide()
                            },
                        leadingContent = { Icon(Icons.Filled.Search, contentDescription = null) },
                        headlineContent = { Text(s, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            results != null -> {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(SearchFilter.entries.toList()) { f ->
                        FilterChip(
                            selected = browse.filter == f,
                            onClick = { browse.selectFilter(f) },
                            label = { Text(stringResource(f.label)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                PageList(
                    results,
                    browse,
                    cb,
                    Modifier.weight(1f),
                    state = results.listState,
                    topResult = browse.filter == SearchFilter.TOP,
                    emptyText = stringResource(R.string.search_no_results, browse.submitted.orEmpty()),
                )
            }
            else -> Text(
                stringResource(R.string.search_prompt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
fun LibraryScreen(remote: RemoteViewModel, browse: BrowseViewModel, cb: ItemCallbacks) {
    val f = browse.libraryFilter
    LaunchedEffect(f) { browse.selectLibrary(f) }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.tab_library)) { DesktopsButton(remote) }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LibraryFilter.entries.toList()) { lf ->
                FilterChip(
                    selected = f == lf,
                    onClick = { browse.selectLibrary(lf) },
                    label = { Text(stringResource(lf.label)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        val h = browse.libraryPage(f) ?: return@Column
        val showLiked = f == LibraryFilter.PLAYLISTS && h.error != PageError.SIGNED_OUT
        PageList(
            h,
            browse,
            cb,
            Modifier.weight(1f),
            state = h.listState,
            signedOutText = R.string.library_signed_out,
            leading = {
                if (showLiked) item(key = "liked") { LikedSongsRow { browse.openLiked() } }
            },
        )
    }
}

/** Liked songs, pinned first in the library's playlists. */
@Composable
private fun LikedSongsRow(onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick),
        leadingContent = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Favorite, contentDescription = null) }
            }
        },
        headlineContent = { Text(stringResource(R.string.library_liked)) },
        supportingContent = { Text(stringResource(R.string.library_liked_subtitle)) },
    )
}
