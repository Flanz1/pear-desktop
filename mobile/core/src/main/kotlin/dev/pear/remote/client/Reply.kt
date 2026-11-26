package dev.pear.remote.client

import dev.pear.remote.protocol.ErrorCode

/** Outcome of a request that has a typed answer (catalog commands). */
sealed interface Reply<out T> {
    data class Ok<T>(val value: T) : Reply<T>

    /** The desktop answered with an error. */
    data class Rejected(val code: ErrorCode) : Reply<Nothing>

    /** Not connected, the connection ended, or no answer in time. */
    data object NotDelivered : Reply<Nothing>

    /**
     * The command isn't available in this session (negotiated 1.0, or the
     * desktop didn't advertise the capability). Nothing was sent.
     */
    data object NotAvailable : Reply<Nothing>
}

inline fun <T, R> Reply<T>.map(f: (T) -> R): Reply<R> = when (this) {
    is Reply.Ok -> Reply.Ok(f(value))
    is Reply.Rejected -> this
    Reply.NotDelivered -> Reply.NotDelivered
    Reply.NotAvailable -> Reply.NotAvailable
}
