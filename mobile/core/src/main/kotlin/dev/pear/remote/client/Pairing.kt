package dev.pear.remote.client

import dev.pear.remote.protocol.AppInfo
import dev.pear.remote.protocol.ClientHandshake
import dev.pear.remote.protocol.Decoded
import dev.pear.remote.protocol.Limits
import dev.pear.remote.protocol.PairDevice
import dev.pear.remote.protocol.PairingPayload
import dev.pear.remote.protocol.ProtocolCodec
import dev.pear.remote.protocol.ProtocolVersion
import dev.pear.remote.protocol.ServerMessage
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

sealed interface PairingOutcome {
    data class Paired(val credentials: DesktopCredentials) : PairingOutcome
    data class Failed(val failure: Failure) : PairingOutcome
}

sealed interface ProbeOutcome {
    /** The desktop's SPKI fingerprint, to show (as a short fingerprint) for the user to compare. */
    data class Fingerprint(val fingerprint: String) : ProbeOutcome
    data class Failed(val failure: Failure) : ProbeOutcome
}

/** What the user proved they can see on the desktop screen. */
sealed interface PairingProof {
    /** From the QR code. */
    class Secret(val value: String) : PairingProof { override fun toString() = "Secret(<redacted>)" }
    /** Typed by the user, after confirming the short fingerprint. */
    class Code(val value: String) : PairingProof { override fun toString() = "Code(<redacted>)" }
}

internal class Pairing(private val http: OkHttpClient, private val app: AppInfo) {

    /**
     * Manual path, step 1: TLS handshake only, to learn the fingerprint.
     * Nothing is sent to the desktop.
     */
    suspend fun probe(endpoint: Endpoint): ProbeOutcome {
        val tm = FingerprintProbeTrustManager()
        val t = Transport(http, endpoint, tm)
        try {
            val result = withTimeoutOrNull(10_000) {
                for (ev in t.events) {
                    tm.observed?.let { return@withTimeoutOrNull ProbeOutcome.Fingerprint(it) }
                    when (ev) {
                        is WsEvent.Failed, is WsEvent.Closed -> return@withTimeoutOrNull ProbeOutcome.Failed(
                            (ev as? WsEvent.Failed)?.failure ?: Failure.ConnectionLost,
                        )
                        else -> Unit
                    }
                }
                null
            }
            tm.observed?.let { return ProbeOutcome.Fingerprint(it) }
            return result ?: ProbeOutcome.Failed(Failure.Timeout)
        } finally {
            t.cancel()
        }
    }

    /**
     * Sends `pair.request` over a connection pinned to [fingerprint] and waits
     * for the user to click Allow or Deny on the desktop (up to the 120 s window).
     *
     * [expectedInstanceId] is the QR code's `i`; the manual path passes null.
     * [desktopName] is what to call the desktop until its first `welcome`.
     */
    suspend fun pair(
        endpoint: Endpoint,
        fingerprint: String,
        device: PairDevice,
        proof: PairingProof,
        expectedInstanceId: String?,
        desktopName: String,
    ): PairingOutcome {
        val request = when (proof) {
            is PairingProof.Secret -> ClientHandshake.PairRequest.withSecret(ProtocolVersion.CURRENT, app, device, proof.value)
            is PairingProof.Code -> ClientHandshake.PairRequest.withCode(ProtocolVersion.CURRENT, app, device, proof.value)
        }
        val text = try {
            ProtocolCodec.encode(request)
        } catch (_: IllegalArgumentException) {
            return PairingOutcome.Failed(Failure.ProtocolError)
        }
        val t = Transport(http, endpoint, PinnedTrustManager(fingerprint))
        try {
            val outcome = withTimeoutOrNull(Limits.PAIRING_WINDOW_MS + 15_000) {
                for (ev in t.events) {
                    when (ev) {
                        WsEvent.Open -> if (!t.send(text)) return@withTimeoutOrNull fail(Failure.ConnectionLost)
                        is WsEvent.Text -> when (val d = ProtocolCodec.decodeServerMessage(ev.text)) {
                            is Decoded.Ok -> when (val m = d.value) {
                                is ServerMessage.PairResult -> {
                                    if (expectedInstanceId != null && m.instanceId != expectedInstanceId) {
                                        return@withTimeoutOrNull fail(Failure.ProtocolError)
                                    }
                                    return@withTimeoutOrNull PairingOutcome.Paired(
                                        DesktopCredentials(
                                            m.instanceId, m.deviceId, m.token, fingerprint,
                                            endpoint.host, endpoint.port, desktopName,
                                        ),
                                    )
                                }
                                is ServerMessage.Error ->
                                    return@withTimeoutOrNull fail(Failure.fromError(m.code, m.supported?.min, m.supported?.max))
                                else -> return@withTimeoutOrNull fail(Failure.ProtocolError)
                            }
                            else -> return@withTimeoutOrNull fail(Failure.ProtocolError)
                        }
                        WsEvent.Binary -> return@withTimeoutOrNull fail(Failure.ProtocolError)
                        is WsEvent.Closed -> return@withTimeoutOrNull fail(Failure.fromClose(ev.code))
                        is WsEvent.Failed -> return@withTimeoutOrNull fail(ev.failure)
                    }
                }
                null
            }
            return outcome ?: fail(Failure.Timeout)
        } finally {
            // After pair.result the desktop closes with 1000; closing first is fine too.
            t.close(1000)
        }
    }

    private fun fail(f: Failure) = PairingOutcome.Failed(f)
}

/** Checks the QR code's version list before connecting (SPEC 6.2, `v`). */
fun qrCompatibility(payload: PairingPayload): Failure? {
    val mine = ProtocolVersion.CURRENT.major
    if (mine in payload.majors) return null
    return if (payload.majors.max() < mine) {
        Failure.UpdateDesktop(payload.majors.min(), payload.majors.max())
    } else {
        Failure.UpdateApp(payload.majors.min(), payload.majors.max())
    }
}
