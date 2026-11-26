package dev.pear.remote.app.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pear.remote.app.PearRemoteApp
import dev.pear.remote.app.R
import dev.pear.remote.client.CatalogPaging
import dev.pear.remote.client.CatalogSupport
import dev.pear.remote.client.ConnectionStatus
import dev.pear.remote.client.RemoteSession
import dev.pear.remote.client.Reply
import dev.pear.remote.protocol.BrowseTarget
import dev.pear.remote.protocol.CatalogAction
import dev.pear.remote.protocol.CatalogItem
import dev.pear.remote.protocol.ErrorCode
import dev.pear.remote.protocol.Fields
import dev.pear.remote.protocol.SearchFilter
import dev.pear.remote.protocol.ServerMessage
import dev.pear.remote.protocol.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class Tab { HOME, SEARCH, LIBRARY }

enum class LibraryFilter(val target: BrowseTarget, val label: Int) {
    PLAYLISTS(BrowseTarget.LIBRARY_PLAYLISTS, R.string.filter_playlists),
    SONGS(BrowseTarget.LIBRARY_SONGS, R.string.filter_songs),
    ALBUMS(BrowseTarget.LIBRARY_ALBUMS, R.string.filter_albums),
    ARTISTS(BrowseTarget.LIBRARY_ARTISTS, R.string.filter_artists),
}

val SearchFilter.label: Int
    get() = when (this) {
        SearchFilter.TOP -> R.string.filter_top
        SearchFilter.SONGS -> R.string.filter_songs
        SearchFilter.ALBUMS -> R.string.filter_albums
        SearchFilter.ARTISTS -> R.string.filter_artists
        SearchFilter.PLAYLISTS -> R.string.filter_playlists
        SearchFilter.VIDEOS -> R.string.filter_videos
    }

/** Why a page can't be shown. Each has its own text. */
enum class PageError { SIGNED_OUT, NOT_READY, UNAVAILABLE, OFFLINE, RATE_LIMITED, OTHER }

/** Where a page comes from, so it can be loaded again (also after `not_found`, SPEC 10.2). */
sealed interface PageSource {
    data class Browse(val target: BrowseTarget) : PageSource
    data class Search(val query: String, val filter: SearchFilter) : PageSource

    /**
     * Opened from an item or chip on [parent]. If the ref has expired, the
     * parent is reloaded quietly and the same entry is looked up again.
     */
    class Open(val ref: String, val title: String, val parent: PageHolder?, val locator: Locator?) : PageSource
}

/** Finds an item (section >= 0) or a chip (section == -1) on a page again after a reload. */
typealias Locator = CatalogPaging.Locator

/** One page on screen: what it shows, and its load state. Compose state, main thread only. */
class PageHolder(val source: PageSource) {
    var page by mutableStateOf<ServerMessage.CatalogPage?>(null)
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var error by mutableStateOf<PageError?>(null)

    /** For opened pages: the ref to open (replaced when the parent is reloaded). */
    var ref: String? = (source as? PageSource.Open)?.ref

    /** Kept here so going back to a page keeps its scroll position. */
    val listState = LazyListState()

    /** The desktop's track when the page was built, so `isCurrent` isn't trusted after the track changed. */
    var builtForVideoId: String? = null

    val title: String
        get() = page?.title?.takeIf { it.isNotBlank() } ?: (source as? PageSource.Open)?.title.orEmpty()
}

/**
 * State and actions for Home, Search, Library and detail pages. Talks to the
 * desktop only through the session's catalog commands, which are gated on
 * the negotiated version and capabilities. Nothing polls: pages load when
 * shown, on Retry, and again after a reconnect if they had failed.
 */
class BrowseViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PearRemoteApp
    private val controller = app.controller

    /** Catalog features of the connected desktop; the last known while reconnecting. */
    var support by mutableStateOf(CatalogSupport.NONE)
        private set

    /** True once a desktop has said what it supports, so the UI doesn't flip while connecting. */
    var supportKnown by mutableStateOf(false)
        private set

    var tab by mutableStateOf(Tab.HOME)
        private set

    private val stacks = mutableStateMapOf<Tab, List<PageHolder>>()

    var home by mutableStateOf(PageHolder(PageSource.Browse(BrowseTarget.HOME)))
        private set

    var libraryFilter by mutableStateOf(LibraryFilter.PLAYLISTS)
        private set
    private val library = mutableStateMapOf<LibraryFilter, PageHolder>()

    // Search
    var query by mutableStateOf("")
        private set
    var submitted by mutableStateOf<String?>(null)
        private set
    var filter by mutableStateOf(SearchFilter.TOP)
        private set
    val suggestions = mutableStateListOf<String>()
    var results by mutableStateOf<PageHolder?>(null)
        private set
    private var suggestJob: Job? = null

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages

    private var desktop: String? = null

    init {
        viewModelScope.launch {
            controller.status.collect { st ->
                val id = when (st) {
                    is ConnectionStatus.Connected -> st.desktop.instanceId
                    is ConnectionStatus.Connecting -> st.desktop.instanceId
                    is ConnectionStatus.Retrying -> st.desktop.instanceId
                    is ConnectionStatus.Stopped -> st.desktop?.instanceId
                    ConnectionStatus.Idle -> null
                }
                if (id != null && id != desktop) {
                    // Another desktop: its refs and pages are not ours.
                    desktop = id
                    reset()
                }
                if (st is ConnectionStatus.Connected) {
                    val s = CatalogSupport.of(st.welcome)
                    support = s
                    supportKnown = true
                    if (s.browse) onConnected()
                }
            }
        }
        // Pages that failed with not_ready load once the player is there.
        viewModelScope.launch {
            var had = false
            controller.player.collect { snap ->
                val has = snap != null
                if (has && !had) retryFailed(only = PageError.NOT_READY)
                had = has
            }
        }
    }

    private fun reset() {
        support = CatalogSupport.NONE
        supportKnown = false
        stacks.clear()
        home = PageHolder(PageSource.Browse(BrowseTarget.HOME))
        library.clear()
        results = null
        submitted = null
        suggestions.clear()
        tab = Tab.HOME
    }

    private fun onConnected() {
        if (home.page == null && !home.loading) load(home)
        retryFailed(only = null)
    }

    private fun allHolders(): List<PageHolder> =
        listOf(home) + library.values + listOfNotNull(results) + stacks.values.flatten()

    private fun retryFailed(only: PageError?) {
        allHolders().filter { it.error != null && (only == null || it.error == only) && !it.loading }.forEach { load(it) }
    }

    private val session: RemoteSession? get() = controller.session
    private val currentTrack: Track? get() = controller.player.value?.state?.track

    // ---------- navigation ----------

    fun selectTab(t: Tab) {
        if (t == tab) {
            // Tapping the current tab goes back to its top.
            stacks[t] = emptyList()
            return
        }
        tab = t
        if (t == Tab.LIBRARY) ensureLibrary()
    }

    fun stack(t: Tab): List<PageHolder> = stacks[t].orEmpty()

    /** Returns false when there's nothing to go back to inside the tabs. */
    fun back(): Boolean {
        val s = stacks[tab].orEmpty()
        if (s.isNotEmpty()) {
            stacks[tab] = s.dropLast(1)
            return true
        }
        if (tab == Tab.SEARCH && submitted != null) {
            clearSearch()
            return true
        }
        if (tab != Tab.HOME) {
            tab = Tab.HOME
            return true
        }
        return false
    }

    private fun push(h: PageHolder) {
        stacks[tab] = stacks[tab].orEmpty() + h
        load(h)
    }

    fun openLiked() = push(PageHolder(PageSource.Browse(BrowseTarget.LIBRARY_LIKED)))

    fun openChip(parent: PageHolder, index: Int) {
        val chip = parent.page?.chips?.getOrNull(index) ?: return
        push(PageHolder(PageSource.Open(chip.ref, chip.title, parent, Locator(-1, index, chip.title))))
    }

    // ---------- library ----------

    private fun libraryHolder(f: LibraryFilter): PageHolder =
        library[f] ?: PageHolder(PageSource.Browse(f.target)).also { library[f] = it }

    /** For composition: doesn't create anything ([selectLibrary] does). */
    fun libraryPage(f: LibraryFilter): PageHolder? = library[f]

    fun selectLibrary(f: LibraryFilter) {
        libraryFilter = f
        ensureLibrary()
    }

    private fun ensureLibrary() {
        val h = libraryHolder(libraryFilter)
        if (h.page == null && !h.loading && h.error == null) load(h)
    }

    // ---------- search ----------

    fun editQuery(q: String) {
        query = q
        suggestJob?.cancel()
        suggestions.clear()
        val trimmed = Fields.catalogQuery(q) ?: return
        if (!support.search) return
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            val r = session?.suggest(trimmed) ?: return@launch
            // Drop answers for a query the user has since changed.
            if (r is Reply.Ok && Fields.catalogQuery(query) == trimmed && submitted != query) {
                suggestions.clear()
                suggestions.addAll(r.value)
            }
        }
    }

    fun submitSearch(q: String = query) {
        val trimmed = Fields.catalogQuery(q) ?: return
        suggestJob?.cancel()
        suggestions.clear()
        query = trimmed
        submitted = trimmed
        stacks[Tab.SEARCH] = emptyList()
        results = PageHolder(PageSource.Search(trimmed, filter)).also { load(it) }
    }

    fun selectFilter(f: SearchFilter) {
        if (f == filter) return
        filter = f
        submitted?.let { results = PageHolder(PageSource.Search(it, f)).also { load(it) } }
    }

    fun clearSearch() {
        suggestJob?.cancel()
        suggestions.clear()
        query = ""
        submitted = null
        results = null
        stacks[Tab.SEARCH] = emptyList()
    }

    // ---------- loading ----------

    private suspend fun first(h: PageHolder): Reply<ServerMessage.CatalogPage> {
        val s = session ?: return Reply.NotDelivered
        return when (val src = h.source) {
            is PageSource.Browse -> s.browse(src.target)
            is PageSource.Search -> s.search(src.query, src.filter)
            is PageSource.Open -> s.open(h.ref ?: src.ref)
        }
    }

    private suspend fun more(h: PageHolder, cursor: String): Reply<ServerMessage.CatalogPage> {
        val s = session ?: return Reply.NotDelivered
        return when (val src = h.source) {
            is PageSource.Browse -> s.browse(src.target, cursor)
            is PageSource.Search -> s.search(src.query, src.filter, cursor)
            is PageSource.Open -> s.open(h.ref ?: src.ref, cursor)
        }
    }

    fun load(h: PageHolder) {
        if (h.loading) return
        viewModelScope.launch { loadNow(h, quiet = false) }
    }

    /**
     * Loads the first page. [quiet] keeps what's shown while loading (used
     * after not_found). On not_found an opened page asks its parent for a
     * fresh ref once.
     */
    private suspend fun loadNow(h: PageHolder, quiet: Boolean, retried: Boolean = false): Boolean {
        h.loading = true
        if (!quiet) h.error = null
        try {
            val r = first(h)
            if (r is Reply.Rejected && r.code == ErrorCode.NOT_FOUND && !retried && refreshRef(h)) {
                h.loading = false
                return loadNow(h, quiet, retried = true)
            }
            return when (r) {
                is Reply.Ok -> {
                    h.page = r.value
                    h.builtForVideoId = currentTrack?.videoId
                    h.error = null
                    true
                }
                else -> {
                    if (!quiet || h.page == null) h.error = errorOf(r)
                    false
                }
            }
        } finally {
            h.loading = false
        }
    }

    /** For an opened page whose ref expired: reload the parent and find the same entry again. */
    private suspend fun refreshRef(h: PageHolder): Boolean {
        val src = h.source as? PageSource.Open ?: return false
        val parent = src.parent ?: return false
        val loc = src.locator ?: return false
        if (!loadNow(parent, quiet = true)) return false
        val page = parent.page ?: return false
        val ref = if (loc.section < 0) {
            page.chips.getOrNull(loc.index)?.takeIf { it.title == loc.title }?.ref
                ?: page.chips.firstOrNull { it.title == loc.title }?.ref
        } else {
            CatalogPaging.find(page, loc)?.ref
        }
        if (ref == null) return false
        h.ref = ref
        return true
    }

    fun loadMore(h: PageHolder) {
        val page = h.page ?: return
        val cursor = page.continuation ?: return
        if (h.loadingMore || h.loading || !CatalogPaging.canLoadMore(page)) return
        h.loadingMore = true
        viewModelScope.launch {
            try {
                when (val r = more(h, cursor)) {
                    is Reply.Ok -> {
                        h.page = CatalogPaging.merge(h.page, r.value)
                    }
                    // The cursor expired: reload the page quietly once (SPEC 10.2).
                    is Reply.Rejected -> when (r.code) {
                        ErrorCode.NOT_FOUND -> {
                            loadNow(h, quiet = true)
                        }
                        // YouTube can't give more of this page: stop asking.
                        ErrorCode.UNAVAILABLE -> {
                            h.page = h.page?.copy(continuation = null)
                        }
                        else -> {}
                    }
                    // Not connected: the user can scroll again once it's back.
                    else -> Unit
                }
            } finally {
                h.loadingMore = false
            }
        }
    }

    private fun errorOf(r: Reply<*>): PageError = when (r) {
        is Reply.Rejected -> when (r.code) {
            ErrorCode.SIGNED_OUT -> PageError.SIGNED_OUT
            ErrorCode.NOT_READY -> PageError.NOT_READY
            ErrorCode.UNAVAILABLE -> PageError.UNAVAILABLE
            ErrorCode.RATE_LIMITED -> PageError.RATE_LIMITED
            else -> PageError.OTHER
        }
        Reply.NotDelivered -> PageError.OFFLINE
        Reply.NotAvailable, is Reply.Ok -> PageError.OTHER
    }

    // ---------- actions ----------

    /** A tap: songs, videos and episodes play; everything else opens. Only offered actions. */
    fun tap(from: PageHolder, item: CatalogItem) {
        val action = when {
            item.kind.isTrack && CatalogAction.PLAY in item.actions -> CatalogAction.PLAY
            CatalogAction.OPEN in item.actions -> CatalogAction.OPEN
            CatalogAction.PLAY in item.actions -> CatalogAction.PLAY
            else -> item.actions.first()
        }
        act(from, item, action)
    }

    fun act(from: PageHolder, item: CatalogItem, action: CatalogAction) {
        if (action !in item.actions) return
        if (action == CatalogAction.OPEN) {
            val loc = from.page?.let { CatalogPaging.locate(it, item.ref) }
            push(PageHolder(PageSource.Open(item.ref, item.title, from, loc)))
            return
        }
        val loc = from.page?.let { CatalogPaging.locate(it, item.ref) }
        play(from, action, item.ref) { page ->
            loc?.let { CatalogPaging.find(page, it) }?.takeIf { action in it.actions }?.ref
        }
    }

    /** Play or Shuffle from a detail page's header. */
    fun headerAction(h: PageHolder, action: CatalogAction) {
        val header = h.page?.header ?: return
        if (action !in header.actions) return
        play(h, action, header.ref) { page -> page.header?.takeIf { action in it.actions }?.ref }
    }

    /** catalog.play; on not_found reloads the page quietly and tries the fresh ref once. */
    private fun play(
        h: PageHolder,
        action: CatalogAction,
        ref: String,
        refAfterReload: (ServerMessage.CatalogPage) -> String?,
    ) {
        viewModelScope.launch {
            val s = session ?: return@launch report(Reply.NotDelivered)
            var r = s.catalogPlay(ref, action)
            if (r is Reply.Rejected && r.code == ErrorCode.NOT_FOUND) {
                val fresh = if (loadNow(h, quiet = true)) h.page?.let(refAfterReload) else null
                if (fresh == null) {
                    _messages.tryEmit(R.string.item_gone)
                    return@launch
                }
                r = session?.catalogPlay(fresh, action) ?: Reply.NotDelivered
            }
            if (r is Reply.Ok) {
                when (action) {
                    CatalogAction.PLAY_NEXT -> _messages.tryEmit(R.string.added_play_next)
                    CatalogAction.ADD_TO_QUEUE -> _messages.tryEmit(R.string.added_to_queue)
                    else -> Unit
                }
            } else {
                report(r)
            }
        }
    }

    private fun report(r: Reply<*>) {
        val msg = when (r) {
            is Reply.Ok -> return
            is Reply.Rejected -> when (r.code) {
                ErrorCode.SIGNED_OUT -> R.string.page_signed_out
                ErrorCode.NOT_READY -> R.string.page_not_ready
                ErrorCode.UNAVAILABLE -> R.string.page_unavailable
                ErrorCode.RATE_LIMITED -> R.string.page_rate_limited
                ErrorCode.NOT_FOUND -> R.string.item_gone
                else -> R.string.command_failed
            }
            Reply.NotDelivered -> R.string.command_failed
            Reply.NotAvailable -> R.string.browse_update_desktop
        }
        _messages.tryEmit(msg)
    }

    /**
     * Whether [item] is what's playing now. `isCurrent` is a snapshot from
     * when the page was built; after the track changed, match by title and
     * artist instead (pages carry no video IDs).
     */
    fun isNowPlaying(h: PageHolder, item: CatalogItem, track: Track?): Boolean {
        if (track == null || !item.kind.isTrack) return false
        if (track.videoId == h.builtForVideoId) return item.isCurrent
        return item.title == track.title && track.artist.isNotBlank() && item.subtitle.contains(track.artist)
    }

    private companion object {
        const val SUGGEST_DEBOUNCE_MS = 300L
    }
}
