package dev.pear.remote.protocol

/** Mirrors protocol/src/limits.ts. */
object Limits {
    const val CLIENT_MESSAGE_BYTES = 4 * 1024
    const val SERVER_MESSAGE_BYTES = 1024 * 1024
    const val HANDSHAKE_TIMEOUT_MS = 10_000L
    const val PAIRING_WINDOW_MS = 120_000L
    const val QUEUE_MAX_ITEMS = 500
    const val TEXT_MAX = 500
    const val DEVICE_NAME_MAX = 64
    const val DEFAULT_PORT = 26539
    const val WS_PATH = "/pear-remote/ws"
    const val MDNS_SERVICE_TYPE = "_pear-remote._tcp"

    /** The phone pings every 30 s; the desktop drops connections silent for 90 s. */
    const val PING_INTERVAL_MS = 30_000L
}

object CloseCodes {
    const val NORMAL = 1000
    const val GOING_AWAY = 1001
    const val MESSAGE_TOO_BIG = 1009
    const val BAD_MESSAGE = 4400
    const val UNAUTHORIZED = 4401
    const val REVOKED = 4403
    const val HANDSHAKE_TIMEOUT = 4408
    const val REPLACED = 4409
    const val VERSION_UNSUPPORTED = 4426
    const val RATE_LIMITED = 4429
}

data class ProtocolVersion(val major: Int, val minor: Int) {
    override fun toString() = "$major.$minor"

    companion object {
        /** The version this app implements. */
        val CURRENT = ProtocolVersion(1, 0)
    }
}

/** negotiate() from protocol/src/version.ts. The desktop runs it; the phone uses it in tests. */
sealed interface Negotiation {
    data class Ok(val version: ProtocolVersion) : Negotiation
    data class Failed(val code: ErrorCode, val min: Int, val max: Int) : Negotiation

    companion object {
        fun negotiate(client: ProtocolVersion, supported: Map<Int, Int>): Negotiation {
            val majors = supported.keys.sorted()
            val min = majors.first()
            val max = majors.last()
            val serverMinor = supported[client.major]
            if (serverMinor != null) {
                return Ok(ProtocolVersion(client.major, minOf(client.minor, serverMinor)))
            }
            return Failed(
                if (client.major < min) ErrorCode.UPDATE_REQUIRED else ErrorCode.DESKTOP_UPDATE_REQUIRED,
                min,
                max,
            )
        }
    }
}
