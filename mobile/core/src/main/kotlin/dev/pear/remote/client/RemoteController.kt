package dev.pear.remote.client

import dev.pear.remote.protocol.AppInfo
import dev.pear.remote.protocol.PairDevice
import dev.pear.remote.protocol.PairingPayload
import dev.pear.remote.protocol.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** Finds a paired desktop's current address (mDNS on Android), or null. */
fun interface EndpointResolver {
    suspend fun resolve(instanceId: String): Endpoint?
}

sealed interface ConnectionStatus {
    /** Not trying to connect (no desktop chosen, or the app stopped the connection). */
    data object Idle : ConnectionStatus
    data class Connecting(val desktop: DesktopCredentials, val attempt: Int) : ConnectionStatus
    data class Connected(val desktop: DesktopCredentials, val welcome: ServerMessage.Welcome) : ConnectionStatus
    /** Retryable failure; another attempt is scheduled. */
    data class Retrying(val desktop: DesktopCredentials, val failure: Failure, val attempt: Int, val delayMs: Long) :
        ConnectionStatus
    /** Needs the user: re-pair, update an app, or the desktop removed access. */
    data class Stopped(val desktop: DesktopCredentials?, val failure: Failure) : ConnectionStatus
}

/**
 * Keeps one session to the chosen desktop alive while [start]ed: reconnects
 * with backoff on retryable failures, re-resolves the address when the last
 * one is unreachable, and stops on failures the user has to act on.
 * It never connects on its own; the app decides when (SPEC: no background
 * connection when nothing is playing and the app is closed).
 */
class RemoteController(
    private val scope: CoroutineScope,
    private val store: CredentialStore,
    private val app: AppInfo,
    private val resolver: EndpointResolver = EndpointResolver { null },
    private val http: OkHttpClient = OkHttpClient(),
    private val clock: Clock = Clock.SYSTEM,
    private val backoffMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000, 30_000),
) {
    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _player = MutableStateFlow<PlayerSnapshot?>(null)
    val player: StateFlow<PlayerSnapshot?> = _player.asStateFlow()

    private val _queue = MutableStateFlow<QueueSnapshot?>(null)
    val queue: StateFlow<QueueSnapshot?> = _queue.asStateFlow()

    @Volatile var session: RemoteSession? = null
        private set

    @Volatile var queueVisible = false
        set(v) {
            field = v
            session?.queueVisible = v
        }

    private var loop: Job? = null
    private val wake = MutableStateFlow(0)
    private val pairing = Pairing(http, app)

    /** Connects to [instanceId] and keeps the connection up until [stop]. */
    @Synchronized
    fun start(instanceId: String) {
        val current = (loop?.takeIf { it.isActive })
        if (current != null && runningFor == instanceId) return
        stop()
        val creds = store.get(instanceId) ?: run {
            _status.value = ConnectionStatus.Stopped(null, Failure.Unauthorized)
            return
        }
        runningFor = instanceId
        loop = scope.launch { connectLoop(creds) }
    }

    @Volatile private var runningFor: String? = null

    /** Closes the connection and stops reconnecting. */
    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        runningFor = null
        session?.close()
        session = null
        _player.value = null
        _queue.value = null
        if (_status.value !is ConnectionStatus.Stopped) _status.value = ConnectionStatus.Idle
    }

    /** Skips the backoff wait (for a "Retry now" button, or when the network changes). */
    fun retryNow() {
        wake.value++
    }

    private suspend fun connectLoop(initial: DesktopCredentials) {
        var creds = initial
        var attempt = 0
        var triedResolve = false
        while (true) {
            attempt++
            _status.value = ConnectionStatus.Connecting(creds, attempt)
            val s = RemoteSession(scope, http, app, creds, creds.endpoint, clock)
            session = s
            s.queueVisible = queueVisible
            val mirror = scope.launch {
                launch { s.player.collect { _player.value = it } }
                launch { s.queue.collect { _queue.value = it } }
                launch {
                    s.state.collect { st ->
                        if (st is SessionState.Connected) {
                            // Remember the working address and the desktop's current name.
                            var updated = creds
                            if (st.welcome.name != creds.name) updated = updated.withName(st.welcome.name)
                            if (updated !== creds) {
                                store.put(updated)
                                creds = updated
                            }
                            _status.value = ConnectionStatus.Connected(creds, st.welcome)
                        }
                    }
                }
            }
            val ended = s.state.first { it is SessionState.Ended } as SessionState.Ended
            mirror.cancel()
            session = null
            _player.value = null
            _queue.value = null
            val failure = ended.failure
            val wasConnected = _status.value is ConnectionStatus.Connected
            if (wasConnected) {
                attempt = 0
                triedResolve = false
            }

            if (failure.forgetCredentials) store.remove(creds.instanceId)
            if (!failure.retryable) {
                _status.value = ConnectionStatus.Stopped(creds, failure)
                runningFor = null
                return
            }

            // Unreachable at the last address: maybe the desktop got a new IP.
            if (failure is Failure.Unreachable && !triedResolve) {
                triedResolve = true
                val found = withTimeoutOrNull(5_000) { resolver.resolve(creds.instanceId) }
                if (found != null && found != creds.endpoint) {
                    creds = creds.withEndpoint(found)
                    store.put(creds)
                    continue
                }
            }

            val wait = backoffMs[(attempt - 1).coerceIn(0, backoffMs.lastIndex)]
            _status.value = ConnectionStatus.Retrying(creds, failure, attempt, wait)
            val w = wake.value
            withTimeoutOrNull(wait) { wake.first { it != w } }
            triedResolve = false
        }
    }

    // ---------- pairing ----------

    /** Manual path, step 1. Show [PairingUri.shortFingerprint] of the result and ask the user to compare. */
    suspend fun probe(endpoint: Endpoint): ProbeOutcome = pairing.probe(endpoint)

    /** QR path. */
    suspend fun pairWithQr(payload: PairingPayload, device: PairDevice): PairingOutcome {
        qrCompatibility(payload)?.let { return PairingOutcome.Failed(it) }
        return finishPairing(
            pairing.pair(
                Endpoint(payload.host, payload.port), payload.fingerprint, device,
                PairingProof.Secret(payload.secret), payload.instanceId, payload.name,
            ),
        )
    }

    /** Manual path, step 2: only after the user confirmed [confirmedFingerprint] matches the desktop. */
    suspend fun pairWithCode(endpoint: Endpoint, confirmedFingerprint: String, code: String, device: PairDevice) =
        finishPairing(
            pairing.pair(endpoint, confirmedFingerprint, device, PairingProof.Code(code), null, endpoint.host),
        )

    private fun finishPairing(o: PairingOutcome): PairingOutcome {
        if (o is PairingOutcome.Paired) {
            // A re-pair of a known desktop replaces the old credentials.
            store.put(o.credentials)
            if (_status.value.let { it is ConnectionStatus.Stopped && it.desktop?.instanceId == o.credentials.instanceId }) {
                _status.value = ConnectionStatus.Idle
            }
        }
        return o
    }

    /** Forgets a desktop on this phone. (Revoking on the desktop is the desktop's job.) */
    fun forget(instanceId: String) {
        if (runningFor == instanceId) stop()
        store.remove(instanceId)
        if ((_status.value as? ConnectionStatus.Stopped)?.desktop?.instanceId == instanceId) {
            _status.value = ConnectionStatus.Idle
        }
    }
}
