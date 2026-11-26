package dev.pear.remote.client

import dev.pear.remote.protocol.ClosingReason
import dev.pear.remote.protocol.CloseCodes
import dev.pear.remote.protocol.ErrorCode

/** Why a connection ended. Each maps to one clear state in the UI. */
sealed interface Failure {
    /** Couldn't reach the desktop (off, asleep, wrong network, listener disabled). Retryable. */
    data class Unreachable(val detail: String) : Failure

    /** Connected, then the connection dropped without a protocol reason. Retryable. */
    data object ConnectionLost : Failure

    /** The desktop's key doesn't match the pin: "This computer's identity changed. Pair again." */
    data object IdentityChanged : Failure

    /** Our token was refused. The pairing is gone on the desktop. */
    data object Unauthorized : Failure

    /** Revoked on the desktop: "Access was removed on the computer." */
    data object Revoked : Failure

    /** The phone app is too old for this desktop. */
    data class UpdateApp(val min: Int?, val max: Int?) : Failure

    /** The desktop is too old for this phone app. */
    data class UpdateDesktop(val min: Int?, val max: Int?) : Failure

    /** Closed as incompatible without saying which side is too old. */
    data object Incompatible : Failure

    /** The desktop said it's shutting down or the remote was disabled. Retryable later. */
    data class DesktopClosed(val reason: ClosingReason) : Failure

    /** Another connection with this device's credentials replaced this one. */
    data object Replaced : Failure

    /** Closed for sending too many commands. */
    data object RateLimited : Failure

    /** The desktop or we violated the protocol. */
    data object ProtocolError : Failure

    /** Pairing ended without a result (closed, invalid, denied or timed out). */
    data class PairingFailed(val code: ErrorCode) : Failure

    /** The handshake timed out. Retryable. */
    data object Timeout : Failure

    /** Closed with a code we don't otherwise handle. Retryable. */
    data class Closed(val code: Int) : Failure

    /** True for failures where reconnecting later can succeed without the user doing anything. */
    val retryable: Boolean
        get() = when (this) {
            is Unreachable, ConnectionLost, Timeout, is Closed, RateLimited -> true
            is DesktopClosed -> reason != ClosingReason.REVOKED
            else -> false
        }

    /** True when the stored credentials for this desktop must be deleted. */
    val forgetCredentials: Boolean
        get() = this == Revoked || this == Unauthorized

    companion object {
        fun fromError(code: ErrorCode, min: Int?, max: Int?): Failure = when (code) {
            ErrorCode.UNAUTHORIZED -> Unauthorized
            ErrorCode.REVOKED -> Revoked
            ErrorCode.UPDATE_REQUIRED -> UpdateApp(min, max)
            ErrorCode.DESKTOP_UPDATE_REQUIRED -> UpdateDesktop(min, max)
            ErrorCode.PAIRING_CLOSED, ErrorCode.PAIRING_INVALID,
            ErrorCode.PAIRING_DENIED, ErrorCode.PAIRING_TIMEOUT -> PairingFailed(code)
            ErrorCode.RATE_LIMITED -> RateLimited
            else -> ProtocolError
        }

        fun fromClose(code: Int): Failure = when (code) {
            CloseCodes.NORMAL, CloseCodes.GOING_AWAY -> ConnectionLost
            CloseCodes.UNAUTHORIZED -> Unauthorized
            CloseCodes.REVOKED -> Revoked
            CloseCodes.REPLACED -> Replaced
            CloseCodes.VERSION_UNSUPPORTED -> Incompatible
            CloseCodes.RATE_LIMITED -> RateLimited
            CloseCodes.BAD_MESSAGE, CloseCodes.MESSAGE_TOO_BIG -> ProtocolError
            CloseCodes.HANDSHAKE_TIMEOUT -> Timeout
            else -> Closed(code)
        }
    }
}
