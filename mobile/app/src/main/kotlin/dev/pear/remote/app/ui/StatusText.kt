package dev.pear.remote.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pear.remote.app.R
import dev.pear.remote.client.Failure
import dev.pear.remote.protocol.ClosingReason
import dev.pear.remote.protocol.ErrorCode

/** User-facing text for every failure (SPEC 7.3: the phone shows its own text per code). */
@Composable
fun failureText(f: Failure, name: String, viaQr: Boolean = false): String = when (f) {
    is Failure.Unreachable, Failure.ConnectionLost, Failure.Timeout, is Failure.Closed ->
        stringResource(R.string.status_unreachable, name)
    Failure.IdentityChanged -> stringResource(R.string.status_identity_changed)
    Failure.Revoked -> stringResource(R.string.status_revoked)
    Failure.Unauthorized -> stringResource(R.string.status_unauthorized, name)
    is Failure.UpdateApp -> stringResource(R.string.status_update_app)
    is Failure.UpdateDesktop, Failure.Incompatible -> stringResource(R.string.status_update_desktop)
    is Failure.DesktopClosed -> if (f.reason == ClosingReason.SHUTDOWN) {
        stringResource(R.string.status_desktop_shutdown, name)
    } else {
        stringResource(R.string.status_desktop_disabled, name)
    }
    Failure.Replaced -> stringResource(R.string.status_replaced, name)
    Failure.RateLimited -> stringResource(R.string.status_rate_limited)
    Failure.ProtocolError -> stringResource(R.string.status_protocol_error, name)
    is Failure.PairingFailed -> when (f.code) {
        ErrorCode.PAIRING_CLOSED -> stringResource(R.string.pair_closed)
        ErrorCode.PAIRING_INVALID -> stringResource(if (viaQr) R.string.pair_invalid_qr else R.string.pair_invalid)
        ErrorCode.PAIRING_DENIED -> stringResource(R.string.pair_denied)
        else -> stringResource(R.string.pair_timeout)
    }
}

/** Failures the user fixes by pairing again. */
val Failure.needsPairing: Boolean
    get() = this == Failure.IdentityChanged || this == Failure.Revoked || this == Failure.Unauthorized
