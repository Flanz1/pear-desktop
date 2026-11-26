package dev.pear.remote.client

import dev.pear.remote.protocol.AppInfo
import dev.pear.remote.protocol.ClientCommand
import dev.pear.remote.protocol.ClientHandshake
import dev.pear.remote.protocol.CloseCodes
import dev.pear.remote.protocol.Decoded
import dev.pear.remote.protocol.ErrorCode
import dev.pear.remote.protocol.LikeStatus
import dev.pear.remote.protocol.Limits
import dev.pear.remote.protocol.PlaybackStatus
import dev.pear.remote.protocol.PlayerState
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
                            ProtocolVersion.CURRENT, app, credentials.deviceId, credentials.token,
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
                    if (msg.instanceId != credentials.instanceId || msg.protocol.major != ProtocolVersion.CURRENT.major) {
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
                _player.value = PlayerSnapshot(msg.state, now)
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
            is ServerMessage.VolumeChanged -> update(now) { copy(volume = msg.volume) }
            is ServerMessage.RepeatChanged -> update(now) { copy(repeat = msg.repeat) }
            is ServerMessage.ShuffleChanged -> update(now) { copy(shuffle = msg.shuffle) }
            is ServerMessage.LikeChanged -> update(now) { copy(like = msg.like) }
            is ServerMessage.ServerClosing -> end(closingFailure(msg))
            is ServerMessage.Welcome, is ServerMessage.PairResult -> {
                // Not valid after the handshake.
                if (++invalidCount >= 3) {
                    transport.close(1002)
                    end(Failure.ProtocolError)
                }
            }
        }
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
     * Sends a command and waits for its answer (result, typed reply or error).
     * Never throws and never waits longer than [timeoutMs].
     */
    suspend fun send(build: (id: String) -> ClientCommand, timeoutMs: Long = 10_000): CommandResult {
        if (_state.value !is SessionState.Connected) return CommandResult.NotDelivered
        val cmd = build(nextId())
        val answer = CompletableDeferred<ServerMessage?>()
        pending[cmd.id] = answer
        val text = try {
            ProtocolCodec.encode(cmd)
        } catch (_: IllegalArgumentException) {
            pending.remove(cmd.id)
            return CommandResult.Rejected(ErrorCode.BAD_MESSAGE)
        }
        if (!transport.send(text)) {
            pending.remove(cmd.id)
            return CommandResult.NotDelivered
        }
        if (_state.value is SessionState.Ended) answer.complete(null)
        val msg = try {
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            pending.remove(cmd.id)
        }
        return when (msg) {
            null -> CommandResult.NotDelivered
            is ServerMessage.Error -> CommandResult.Rejected(msg.code)
            else -> CommandResult.Ok
        }
    }

    suspend fun play() = send(ClientCommand::Play)
    suspend fun pause() = send(ClientCommand::Pause)
    suspend fun togglePlay() = send(ClientCommand::TogglePlay)
    suspend fun next() = send(ClientCommand::Next)
    suspend fun previous() = send(ClientCommand::Previous)
    suspend fun seek(seconds: Double) = send({ ClientCommand.Seek(it, seconds.coerceIn(0.0, 86_400.0)) })
    suspend fun setVolume(v: Int) = send({ ClientCommand.SetVolume(it, v.coerceIn(0, 100)) })
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
}
