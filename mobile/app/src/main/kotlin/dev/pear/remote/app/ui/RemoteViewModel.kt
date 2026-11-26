package dev.pear.remote.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pear.remote.app.PearRemoteApp
import dev.pear.remote.client.CommandResult
import dev.pear.remote.client.ConnectionStatus
import dev.pear.remote.client.DesktopCredentials
import dev.pear.remote.client.Endpoint
import dev.pear.remote.client.Failure
import dev.pear.remote.client.PairingOutcome
import dev.pear.remote.client.ProbeOutcome
import dev.pear.remote.client.RemoteSession
import dev.pear.remote.protocol.LanAddress
import dev.pear.remote.protocol.Limits
import dev.pear.remote.protocol.PairingPayload
import dev.pear.remote.protocol.PairingUri
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Desktops : Screen
    data object Pair : Screen
    data object Remote : Screen
    data object Queue : Screen
}

/** Steps of the pairing flow (SPEC 6.3). */
sealed interface PairStep {
    data object Choose : PairStep
    data object Scanning : PairStep
    data class Manual(val host: String = "", val port: String = Limits.DEFAULT_PORT.toString(), val error: Int? = null) :
        PairStep
    data object Probing : PairStep
    data class ConfirmFingerprint(val endpoint: Endpoint, val fingerprint: String) : PairStep {
        val short get() = PairingUri.shortFingerprint(fingerprint)
    }
    data class EnterCode(val endpoint: Endpoint, val fingerprint: String, val code: String = "") : PairStep
    data class Waiting(val name: String?) : PairStep
    data class Done(val name: String) : PairStep
    /** [message] is a string resource; [failure] for connection-type failures. */
    data class Failed(val message: Int? = null, val failure: Failure? = null, val viaQr: Boolean = false) : PairStep
}

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PearRemoteApp
    private val controller = app.controller
    private val policy = app.policy

    val status: StateFlow<ConnectionStatus> = controller.status
    val player = controller.player
    val queue = controller.queue
    val discovered = app.discovery.found

    private val _desktops = MutableStateFlow(app.store.all())
    val desktops: StateFlow<List<DesktopCredentials>> = _desktops.asStateFlow()

    val selected = policy.selected

    var screen by mutableStateOf(
        if (policy.selected.value != null && app.store.get(policy.selected.value!!) != null) Screen.Remote else Screen.Desktops,
    )
        private set

    var pairStep by mutableStateOf<PairStep>(PairStep.Choose)
        private set

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** One-off messages (string resources) for a snackbar. */
    val messages: SharedFlow<Int> = _messages

    private var pairJob: Job? = null
    private var releaseDiscovery: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            // Credentials change on pair, revoke and forget; keep the list current.
            status.collect { refreshDesktops() }
        }
    }

    private fun refreshDesktops() {
        _desktops.value = app.store.all()
    }

    // ---------- navigation ----------

    fun open(s: Screen) {
        if (s == Screen.Pair) startPairing()
        if (screen == Screen.Pair && s != Screen.Pair) stopPairing()
        controller.queueVisible = s == Screen.Queue
        screen = s
    }

    /** Returns false when the system should handle Back (leave the app). */
    fun back(): Boolean = when (screen) {
        Screen.Queue -> { open(Screen.Remote); true }
        Screen.Pair -> {
            if (pairStep !is PairStep.Choose && pairStep !is PairStep.Waiting) {
                pairJob?.cancel()
                pairStep = PairStep.Choose
            } else {
                open(if (selected.value != null) Screen.Remote else Screen.Desktops)
            }
            true
        }
        Screen.Remote -> false
        Screen.Desktops -> if (selected.value != null && app.store.get(selected.value!!) != null) {
            open(Screen.Remote); true
        } else {
            false
        }
    }

    fun select(d: DesktopCredentials) {
        policy.select(d.instanceId)
        open(Screen.Remote)
    }

    fun forget(d: DesktopCredentials) {
        controller.forget(d.instanceId)
        if (selected.value == d.instanceId) policy.select(null)
        refreshDesktops()
    }

    fun retry() = policy.reconnect()

    // ---------- commands ----------

    fun command(block: suspend RemoteSession.() -> CommandResult) {
        val s = controller.session ?: return
        viewModelScope.launch {
            when (val r = s.block()) {
                CommandResult.Ok -> Unit
                CommandResult.NotDelivered -> _messages.tryEmit(dev.pear.remote.app.R.string.command_failed)
                is CommandResult.Rejected -> when (r.code) {
                    dev.pear.remote.protocol.ErrorCode.NOT_READY -> _messages.tryEmit(dev.pear.remote.app.R.string.remote_not_ready)
                    dev.pear.remote.protocol.ErrorCode.STALE_QUEUE -> _messages.tryEmit(dev.pear.remote.app.R.string.queue_changed)
                    else -> _messages.tryEmit(dev.pear.remote.app.R.string.command_failed)
                }
            }
        }
    }

    // ---------- pairing ----------

    private fun startPairing() {
        pairStep = PairStep.Choose
        if (releaseDiscovery == null) releaseDiscovery = app.discovery.acquire()
    }

    private fun stopPairing() {
        pairJob?.cancel()
        releaseDiscovery?.invoke()
        releaseDiscovery = null
    }

    fun pairScan() { pairStep = PairStep.Scanning }

    fun pairManual(host: String = "", port: Int = Limits.DEFAULT_PORT) {
        pairStep = PairStep.Manual(host, port.toString())
    }

    fun pairFailed(message: Int) { pairStep = PairStep.Failed(message) }

    fun pairRestart() {
        pairJob?.cancel()
        pairStep = PairStep.Choose
    }

    fun editManual(host: String, port: String) {
        (pairStep as? PairStep.Manual)?.let { pairStep = it.copy(host = host.trim(), port = port.trim(), error = null) }
    }

    /** Manual path, step 1: connect, read the fingerprint, send nothing. */
    fun submitManual() {
        val m = pairStep as? PairStep.Manual ?: return
        val host = m.host.removePrefix("[").removeSuffix("]")
        val port = m.port.toIntOrNull()
        if (!LanAddress.isValidHost(host)) {
            pairStep = m.copy(error = dev.pear.remote.app.R.string.pair_bad_host)
            return
        }
        if (port == null || port !in 1024..65535) {
            pairStep = m.copy(error = dev.pear.remote.app.R.string.pair_bad_port)
            return
        }
        val endpoint = Endpoint(host, port)
        pairStep = PairStep.Probing
        pairJob = viewModelScope.launch {
            pairStep = when (val r = controller.probe(endpoint)) {
                is ProbeOutcome.Fingerprint -> PairStep.ConfirmFingerprint(endpoint, r.fingerprint)
                is ProbeOutcome.Failed -> PairStep.Failed(failure = r.failure)
            }
        }
    }

    fun confirmFingerprint(matches: Boolean) {
        val c = pairStep as? PairStep.ConfirmFingerprint ?: return
        pairStep = if (matches) {
            PairStep.EnterCode(c.endpoint, c.fingerprint)
        } else {
            PairStep.Failed(dev.pear.remote.app.R.string.pair_fingerprint_mismatch)
        }
    }

    fun editCode(code: String) {
        (pairStep as? PairStep.EnterCode)?.let { pairStep = it.copy(code = code.filter(Char::isDigit).take(6)) }
    }

    fun submitCode() {
        val e = pairStep as? PairStep.EnterCode ?: return
        if (e.code.length != 6) return
        pairStep = PairStep.Waiting(null)
        pairJob = viewModelScope.launch {
            finish(controller.pairWithCode(e.endpoint, e.fingerprint, e.code, app.pairDevice()), viaQr = false)
        }
    }

    /** QR path: the scanner hands over the raw text. */
    fun onQrScanned(text: String) {
        if (pairStep !is PairStep.Scanning) return
        val payload: PairingPayload = PairingUri.parse(text) ?: run {
            pairStep = PairStep.Failed(dev.pear.remote.app.R.string.pair_not_a_code)
            return
        }
        pairStep = PairStep.Waiting(payload.name)
        pairJob = viewModelScope.launch {
            finish(controller.pairWithQr(payload, app.pairDevice()), viaQr = true)
        }
    }

    private fun finish(o: PairingOutcome, viaQr: Boolean) {
        refreshDesktops()
        pairStep = when (o) {
            is PairingOutcome.Paired -> {
                policy.select(o.credentials.instanceId)
                PairStep.Done(o.credentials.name)
            }
            is PairingOutcome.Failed -> PairStep.Failed(failure = o.failure, viaQr = viaQr)
        }
    }

    override fun onCleared() {
        stopPairing()
        controller.queueVisible = false
    }
}
