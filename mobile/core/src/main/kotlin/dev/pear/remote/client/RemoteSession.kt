package dev.pear.remote.client

import dev.pear.remote.protocol.AppInfo
import dev.pear.remote.protocol.BrowseTarget
import dev.pear.remote.protocol.Capabilities
import dev.pear.remote.protocol.CatalogAction
import dev.pear.remote.protocol.Fields
import dev.pear.remote.protocol.SearchFilter
import dev.pear.remote.protocol.ClientCommand
import dev.pear.remote.protocol.ClientHandshake
import dev.pear.remote.protocol.CloseCodes
import dev.pear.remote.protocol.Decoded
import dev.pear.remote.protocol.ErrorCode
import dev.pear.remote.protocol.LikeStatus
import dev.pear.remote.protocol.Limits
import dev.pear.remote.protocol.PlaybackStatus
import dev.pear.remote.protocol.PlayerState
import dev.pear.remote.protocol.VolumeState
import dev.pear.remote.protocol.ProtocolCodec
import dev.pear.remote.protocol.ProtocolVersion
import dev.pear.remote.protocol.QueueItem
import dev.pear.remote.protocol.RepeatMode
import dev.pear.remote.protocol.ServerMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

/** Player state plus when it arrived, for position interpolation (SPEC 7.2). */
data class PlayerSnapshot(val state: PlayerState, val positionAnchorNanos: Long) {
    /** Displayed position: advances from receipt time while playing, capped at the duration. */
    fun positionAt(nowNanos: Long): Double {
        val p = state.playback
        if (p.status != PlaybackStatus.PLAYING) return p.positionSeconds
        val elapsed = (nowNanos - positionAnchorNanos).coerceAtLeast(0) / 1e9
        val pos = p.positionSeconds + elapsed
        val dur = state.track?.durationSeconds
        return if (dur != null) pos.coerceAtMost(dur) else pos
    }
}

data class QueueSnapshot(val items: List<QueueItem>, val truncated: Boolean, val stale: Boolean)

sealed interface SessionState {
    data object Connecting : SessionState
    data class Connected(val welcome: ServerMessage.Welcome) : SessionState
    data class Ended(val failure: Failure) : SessionState
}

sealed interface CommandResult {
    data object Ok : CommandResult
    data class Rejected(val code: ErrorCode) : CommandResult
    /** Not connected, or no answer in time. */
    data object NotDelivered : CommandResult
    /** Not available in this session (see [Reply.NotAvailable]). Nothing was sent. */
    data object NotAvailable : CommandResult
}

/** What the desktop's catalog offers in this session (SPEC 10.1), from welcome. */
data class CatalogSupport(val browse: Boolean, val search: Boolean, val thumbnails: Boolean) {
    companion object {
        val NONE = CatalogSupport(browse = false, search = false, thumbnails = false)

        fun of(welcome: ServerMessage.Welcome): CatalogSupport {
            fun has(type: String) = Capabilities.isCommandAvailable(type, welcome.protocol, welcome.capabilities)
            val browse = has("catalog.browse")
            // A desktop never advertises search or thumbnails without browse (SPEC 10.1).
            return CatalogSupport(browse, browse && has("catalog.search"), browse && has("catalog.thumb"))
        }
    }
}

/** Monotonic clock, injectable for tests. */
fun interface Clock {
    fun nanos(): Long

    companion object {
        val SYSTEM = Clock { System.nanoTime() }
    }
}

/**
 * One authenticated connection to a paired desktop: `hello`, then events
 * and commands until it ends. It never reconnects by itself; see
 * [RemoteController] for that.
 */
class RemoteSession internal constructor(
    private val scope: CoroutineScope,
    http: OkHttpClient,
    private val app: AppInfo,
    val credentials: DesktopCredentials,
    endpoint: Endpoint,
    private val clock: Clock,
    /** The version sent in hello. Only tests use anything but [ProtocolVersion.CURRENT]. */
    private val helloVersion: ProtocolVersion = ProtocolVersion.CURRENT,
) {
    private val transport = Transport(http, endpoint, PinnedTrustManager(credentials.fingerprint))

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _player = MutableStateFlow<PlayerSnapshot?>(null)
    val player: StateFlow<PlayerSnapshot?> = _player.asStateFlow()

    private val _queue = MutableStateFlow<QueueSnapshot?>(null)
    val queue: StateFlow<QueueSnapshot?> = _queue.asStateFlow()

    /** Set while the queue screen is visible: queue.changed then triggers queue.get. */
    @Volatile var queueVisible: Boolean = false
        set(value) {
            field = value
            if (value && state.value is SessionState.Connected) scope.launch { refreshQueue() }
        }

    private val pacer = CommandPacer(clock)

    /** The negotiated session, once welcomed. */
    val welcome: ServerMessage.Welcome? get() = (_state.value as? SessionState.Connected)?.welcome

    /** Catalog features of this session; [CatalogSupport.NONE] before welcome and with 1.0 desktops. */
    val catalog: CatalogSupport get() = welcome?.let(CatalogSupport::of) ?: CatalogSupport.NONE

    private val ids = AtomicLong()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<ServerMessage?>>()
    private var invalidCount = 0
    private val job: Job

    init {
        job = scope.launch { run() }
    }

    private suspend fun run() {
        val welcomed = CompletableDeferred<Unit>()
        val timeout = scope.launch {
            // The desktop closes us after 10 s without a handshake; allow a
            // bit more for connect + TLS on a slow network.
            kotlinx.coroutines.delay(Limits.HANDSHAKE_TIMEOUT_MS + 5_000)
            if (!welcomed.isCompleted) end(Failure.Timeout)
        }
        try {
            for (ev in transport.events) {
                when (ev) {
                    WsEvent.Open -> {
                        val hello = ClientHandshake.Hello(
                            helloVersion, app, credentials.deviceId, credentials.token,
                        )
                        if (!transport.send(ProtocolCodec.encode(hello))) end(Failure.ConnectionLost)
                    }
                    is WsEvent.Text -> handleText(ev.text, welcomed)
                    WsEvent.Binary -> {
                        transport.close(1003)
                        end(Failure.ProtocolError)
                    }
                    is WsEvent.Closed -> end(Failure.fromClose(ev.code))
                    is WsEvent.Failed -> end(ev.failure)
                }
                if (_state.value is SessionState.Ended) break
            }
        } finally {
            timeout.cancel()
            failPending()
        }
    }

    private suspend fun handleText(text: String, welcomed: CompletableDeferred<Unit>) {
        val connected = _state.value is SessionState.Connected
        val msg = when (val d = ProtocolCodec.decodeServerMessage(text)) {
            is Decoded.Ok -> d.value
            Decoded.TooLarge -> {
                transport.close(CloseCodes.MESSAGE_TOO_BIG)
                end(Failure.ProtocolError)
                return
            }
            else -> {
                // Before welcome anything invalid is fatal. After it, tolerate
                // a couple of bad frames (mirrors the desktop's rule).
                if (!connected || ++invalidCount >= 3) {
                    transport.close(1002)
                    end(Failure.ProtocolError)
                }
                return
            }
        }
        if (!connected) {
            when (msg) {
                is ServerMessage.Welcome -> {
                    // The desktop answers with min(ours, its) minor: never newer than what we sent.
                    val newer = msg.protocol.major != helloVersion.major || msg.protocol.minor > helloVersion.minor
                    if (msg.instanceId != credentials.instanceId || newer) {
                        transport.close(1000)
                        end(if (msg.instanceId != credentials.instanceId) Failure.IdentityChanged else Failure.ProtocolError)
                        return
                    }
                    welcomed.complete(Unit)
                    _state.value = SessionState.Connected(msg)
                }
                is ServerMessage.Error -> end(Failure.fromError(msg.code, msg.supported?.min, msg.supported?.max))
                is ServerMessage.ServerClosing -> end(closingFailure(msg))
                else -> {
                    transport.close(1002)
                    end(Failure.ProtocolError)
                }
            }
            return
        }
        apply(msg)
    }

    private fun closingFailure(m: ServerMessage.ServerClosing) =
        if (m.reason == dev.pear.remote.protocol.ClosingReason.REVOKED) Failure.Revoked else Failure.DesktopClosed(m.reason)

    private suspend fun apply(msg: ServerMessage) {
        val now = clock.nanos()
        when (msg) {
            is ServerMessage.State -> {
                _player.value = PlayerSnapshot(msg.state.copy(volume = reconcileVolume(msg.state.volume, now)), now)
                msg.id?.let { pending.remove(it)?.complete(msg) }
            }
            is ServerMessage.Result -> pending.remove(msg.id)?.complete(msg)
            is ServerMessage.Error -> {
                val id = msg.id
                if (id != null) {
                    pending.remove(id)?.complete(msg)
                } else if (msg.code == ErrorCode.UNAUTHORIZED || msg.code == ErrorCode.REVOKED) {
                    end(Failure.fromError(msg.code, null, null))
                }
            }
            is ServerMessage.Queue -> {
                _queue.value = QueueSnapshot(msg.items, msg.truncated, stale = false)
                pending.remove(msg.id)?.complete(msg)
            }
            ServerMessage.QueueChanged -> {
                _queue.value = _queue.value?.copy(stale = true)
                if (queueVisible) scope.launch { refreshQueue() }
            }
            is ServerMessage.TrackChanged -> update(now, resetAnchor = true) {
                // A new track restarts at 0 until playback.changed says otherwise.
                copy(track = msg.track, playback = playback.copy(positionSeconds = 0.0))
            }
            is ServerMessage.PlaybackChanged -> update(now, resetAnchor = true) { copy(playback = msg.playback) }
            is ServerMessage.VolumeChanged -> {
                val v = reconcileVolume(msg.volume, now)
                update(now) { copy(volume = v) }
            }
            is ServerMessage.RepeatChanged -> update(now) { copy(repeat = msg.repeat) }
            is ServerMessage.ShuffleChanged -> update(now) { copy(shuffle = msg.shuffle) }
            is ServerMessage.LikeChanged -> update(now) { copy(like = msg.like) }
            is ServerMessage.ServerClosing -> end(closingFailure(msg))
            is ServerMessage.CatalogPage -> pending.remove(msg.id)?.complete(msg)
            is ServerMessage.CatalogSuggestions -> pending.remove(msg.id)?.complete(msg)
            is ServerMessage.CatalogThumb -> pending.remove(msg.id)?.complete(msg)
            is ServerMessage.Welcome, is ServerMessage.PairResult -> {
                // Not valid after the handshake.
                if (++invalidCount >= 3) {
                    transport.close(1002)
                    end(Failure.ProtocolError)
                }
            }
        }
    }

    // The volume the user last asked for. Echoes of earlier, in-between
    // setVolume commands (a slider drag sends several) keep arriving after
    // it, so the shown volume holds this value until the desktop reports it
    // back or VOLUME_HOLD_NANOS pass, whichever comes first. Then the
    // desktop's value wins, so a real change on the desktop still shows.
    @Volatile private var volumeIntent: Int? = null
    @Volatile private var volumeIntentUntil = 0L

    private fun reconcileVolume(incoming: VolumeState, now: Long): VolumeState {
        val want = volumeIntent ?: return incoming
        if (incoming.volume == want || now - volumeIntentUntil >= 0) {
            volumeIntent = null
            return incoming
        }
        return incoming.copy(volume = want)
    }

    private inline fun update(now: Long, resetAnchor: Boolean = false, f: PlayerState.() -> PlayerState) {
        val cur = _player.value ?: return
        if (resetAnchor) {
            _player.value = PlayerSnapshot(cur.state.f(), now)
        } else {
            // Keep interpolating from the old anchor: only other fields changed.
            _player.value = cur.copy(state = cur.state.f())
        }
    }

    private fun end(failure: Failure) {
        if (_state.value is SessionState.Ended) return
        _state.value = SessionState.Ended(failure)
        transport.cancel()
        transport.events.close()
        failPending()
    }

    private fun failPending() {
        pending.values.forEach { it.complete(null) }
        pending.clear()
    }

    /** Closes the connection on purpose (app closed, user switched desktops). */
    fun close() {
        if (_state.value is SessionState.Ended) return
        transport.close(1000)
        end(Failure.ConnectionLost)
        job.cancel()
    }

    // ---------- commands ----------

    private fun nextId() = "c" + ids.incrementAndGet()

    /**
     * Sends a command and waits for its answer (result, typed reply or
     * error). Never throws and never waits longer than [timeoutMs] after
     * sending. Commands not available in this session (SPEC 4.1) are never
     * sent: a 1.0 desktop would count them as invalid messages.
     * [background] requests wait for spare rate-limit tokens first.
     */
    private suspend fun exchange(
        build: (id: String) -> ClientCommand,
        timeoutMs: Long,
        background: Boolean = false,
    ): Reply<ServerMessage> {
        val w = welcome ?: return Reply.NotDelivered
        val cmd = build(nextId())
        if (!Capabilities.isCommandAvailable(cmd.type, w.protocol, w.capabilities)) return Reply.NotAvailable
        val text = try {
            ProtocolCodec.encode(cmd)
        } catch (_: IllegalArgumentException) {
            return Reply.Rejected(ErrorCode.BAD_MESSAGE)
        }
        if (background) pacer.takeBackground() else pacer.take()
        if (_state.value !is SessionState.Connected) return Reply.NotDelivered
        val answer = CompletableDeferred<ServerMessage?>()
        pending[cmd.id] = answer
        try {
            if (!transport.send(text)) return Reply.NotDelivered
            if (_state.value is SessionState.Ended) return Reply.NotDelivered
            return when (val msg = withTimeoutOrNull(timeoutMs) { answer.await() }) {
                null -> Reply.NotDelivered
                is ServerMessage.Error -> Reply.Rejected(msg.code)
                else -> Reply.Ok(msg)
            }
        } finally {
            pending.remove(cmd.id)
        }
    }

    /** Sends a command that is answered by `result` (or a typed reply that is ignored here). */
    suspend fun send(build: (id: String) -> ClientCommand, timeoutMs: Long = 10_000): CommandResult =
        when (val r = exchange(build, timeoutMs)) {
            is Reply.Ok -> CommandResult.Ok
            is Reply.Rejected -> CommandResult.Rejected(r.code)
            Reply.NotDelivered -> CommandResult.NotDelivered
            Reply.NotAvailable -> CommandResult.NotAvailable
        }

    private suspend inline fun <reified T : ServerMessage> typed(
        noinline build: (id: String) -> ClientCommand,
        timeoutMs: Long,
        background: Boolean = false,
    ): Reply<T> = when (val r = exchange(build, timeoutMs, background)) {
        // Any other answer type to this id is a desktop bug; treat it like no answer.
        is Reply.Ok -> (r.value as? T)?.let { Reply.Ok(it) } ?: Reply.NotDelivered
        is Reply.Rejected -> r
        Reply.NotDelivered -> Reply.NotDelivered
        Reply.NotAvailable -> Reply.NotAvailable
    }

    // ---------- catalog (1.1) ----------

    /** catalog.browse. Pass the last page's continuation to load more. */
    suspend fun browse(target: BrowseTarget, continuation: String? = null): Reply<ServerMessage.CatalogPage> =
        typed({ ClientCommand.CatalogBrowse(it, target, continuation) }, PAGE_TIMEOUT_MS)

    /** catalog.open on an item with the `open` action, or a chip. */
    suspend fun open(ref: String, continuation: String? = null): Reply<ServerMessage.CatalogPage> =
        typed({ ClientCommand.CatalogOpen(it, ref, continuation) }, PAGE_TIMEOUT_MS)

    /** catalog.search. [query] is trimmed here; an invalid query is refused without sending. */
    suspend fun search(
        query: String,
        filter: SearchFilter,
        continuation: String? = null,
    ): Reply<ServerMessage.CatalogPage> {
        val q = Fields.catalogQuery(query) ?: return Reply.Rejected(ErrorCode.BAD_MESSAGE)
        return typed({ ClientCommand.CatalogSearch(it, q, filter, continuation) }, PAGE_TIMEOUT_MS)
    }

    /** catalog.suggest. The caller debounces keystrokes and drops answers for stale queries. */
    suspend fun suggest(query: String): Reply<List<String>> {
        val q = Fields.catalogQuery(query) ?: return Reply.Rejected(ErrorCode.BAD_MESSAGE)
        return typed<ServerMessage.CatalogSuggestions>({ ClientCommand.CatalogSuggest(it, q) }, SUGGEST_TIMEOUT_MS)
            .map { it.items }
    }

    /** catalog.play with one of the item's (or header's) actions other than open. */
    suspend fun catalogPlay(ref: String, action: CatalogAction): Reply<Unit> {
        if (action == CatalogAction.OPEN) return Reply.Rejected(ErrorCode.BAD_MESSAGE)
        return typed<ServerMessage.Result>({ ClientCommand.CatalogPlay(it, ref, action) }, PLAY_TIMEOUT_MS).map { }
    }

    /** catalog.thumb, paced under the rate limit (waits for spare tokens; cancellable while waiting). */
    suspend fun thumb(ref: String): Reply<ServerMessage.CatalogThumb> =
        typed({ ClientCommand.CatalogThumb(it, ref) }, THUMB_TIMEOUT_MS, background = true)

    suspend fun play() = send(ClientCommand::Play)
    suspend fun pause() = send(ClientCommand::Pause)
    suspend fun togglePlay() = send(ClientCommand::TogglePlay)
    suspend fun next() = send(ClientCommand::Next)
    suspend fun previous() = send(ClientCommand::Previous)
    suspend fun seek(seconds: Double) = send({ ClientCommand.Seek(it, seconds.coerceIn(0.0, 86_400.0)) })
    suspend fun setVolume(v: Int): CommandResult {
        val target = v.coerceIn(0, 100)
        val now = clock.nanos()
        volumeIntent = target
        volumeIntentUntil = now + VOLUME_HOLD_NANOS
        update(now) { copy(volume = volume.copy(volume = target)) }
        return send({ ClientCommand.SetVolume(it, target) })
    }
    suspend fun setMuted(m: Boolean) = send({ ClientCommand.SetMuted(it, m) })
    suspend fun toggleShuffle() = send(ClientCommand::ToggleShuffle)
    suspend fun setRepeat(mode: RepeatMode) = send({ ClientCommand.SetRepeat(it, mode) })
    suspend fun setLike(status: LikeStatus) = send({ ClientCommand.SetLike(it, status) })
    suspend fun refreshState() = send(ClientCommand::StateGet)
    suspend fun refreshQueue() = send(ClientCommand::QueueGet)

    /** Plays a queue item. On stale_queue, refreshes the queue so the user sees the current one. */
    suspend fun playQueueItem(item: QueueItem): CommandResult {
        val r = send({ ClientCommand.QueuePlay(it, item.index, item.videoId) })
        if (r == CommandResult.Rejected(ErrorCode.STALE_QUEUE)) refreshQueue()
        return r
    }

    private companion object {
        // Catalog answers need a YouTube request on the desktop.
        const val PAGE_TIMEOUT_MS = 20_000L
        const val SUGGEST_TIMEOUT_MS = 5_000L
        const val PLAY_TIMEOUT_MS = 15_000L
        const val THUMB_TIMEOUT_MS = 10_000L

        // How long a requested volume is shown before the desktop's value wins.
        const val VOLUME_HOLD_NANOS = 1_500_000_000L
    }
}
