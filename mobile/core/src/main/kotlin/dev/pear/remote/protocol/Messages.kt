package dev.pear.remote.protocol

/*
 * Kotlin models for protocol v1 (protocol/src/client.ts and server.ts).
 * Secrets (tokens, pairing secrets and codes) are redacted from toString()
 * so they can't end up in logs or crash reports by accident.
 */

enum class RepeatMode { NONE, ALL, ONE }

enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }

enum class PlaybackStatus(val wire: String) {
    PLAYING("playing"),
    PAUSED("paused"),
    BUFFERING("buffering"),
    STOPPED("stopped"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

enum class ErrorCode(val wire: String) {
    BAD_MESSAGE("bad_message"),
    UNKNOWN_TYPE("unknown_type"),
    UNAUTHORIZED("unauthorized"),
    REVOKED("revoked"),
    UPDATE_REQUIRED("update_required"),
    DESKTOP_UPDATE_REQUIRED("desktop_update_required"),
    PAIRING_CLOSED("pairing_closed"),
    PAIRING_INVALID("pairing_invalid"),
    PAIRING_DENIED("pairing_denied"),
    PAIRING_TIMEOUT("pairing_timeout"),
    RATE_LIMITED("rate_limited"),
    NOT_READY("not_ready"),
    STALE_QUEUE("stale_queue"),
    INTERNAL("internal"),

    /** 1.1, catalog commands only: ref or cursor unknown, expired, or not valid for this action. */
    NOT_FOUND("not_found"),

    /** 1.1, catalog commands only: needs a signed-in YouTube Music account on the desktop. */
    SIGNED_OUT("signed_out"),

    /** 1.1, catalog commands only: YouTube failed or returned something the desktop can't read. */
    UNAVAILABLE("unavailable"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

enum class ClosingReason(val wire: String) {
    SHUTDOWN("shutdown"),
    DISABLED("disabled"),
    REVOKED("revoked"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

data class AppInfo(val name: String, val version: String)

data class Track(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Double?,
    val thumbnailUrl: String?,
    /** 1.1: thumbnail ref for catalog.thumb, or null. Never a URL. */
    val thumb: String? = null,
    /** Whether the `thumb` key was on the wire (absent in 1.0 sessions); kept for exact round trips. */
    val thumbPresent: Boolean = thumb != null,
)

data class Playback(val status: PlaybackStatus, val positionSeconds: Double)

data class VolumeState(val volume: Int, val muted: Boolean)

data class PlayerState(
    val track: Track?,
    val playback: Playback,
    val volume: VolumeState,
    val repeat: RepeatMode,
    val shuffle: Boolean,
    val like: LikeStatus,
)

data class QueueItem(
    val index: Int,
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Double?,
    val thumbnailUrl: String?,
    val isCurrent: Boolean,
    /** 1.1: same rule as [Track.thumb]. */
    val thumb: String? = null,
    val thumbPresent: Boolean = thumb != null,
)

data class SupportedRange(val min: Int, val max: Int)

sealed interface ServerMessage {
    data class Welcome(
        val protocol: ProtocolVersion,
        val server: AppInfo,
        val instanceId: String,
        val name: String,
        val capabilities: List<String>,
    ) : ServerMessage

    data class Error(
        val id: String?,
        val code: ErrorCode,
        val message: String,
        val supported: SupportedRange?,
    ) : ServerMessage

    class PairResult(val deviceId: String, val token: String, val instanceId: String) : ServerMessage {
        override fun toString() = "PairResult(deviceId=$deviceId, token=<redacted>, instanceId=$instanceId)"
        override fun equals(other: Any?) =
            other is PairResult && other.deviceId == deviceId && other.token == token && other.instanceId == instanceId
        override fun hashCode() = deviceId.hashCode()
    }

    data class Result(val id: String) : ServerMessage
    data class State(val id: String?, val state: PlayerState) : ServerMessage
    data class TrackChanged(val track: Track?) : ServerMessage
    data class PlaybackChanged(val playback: Playback) : ServerMessage
    data class VolumeChanged(val volume: VolumeState) : ServerMessage
    data class RepeatChanged(val repeat: RepeatMode) : ServerMessage
    data class ShuffleChanged(val shuffle: Boolean) : ServerMessage
    data class LikeChanged(val like: LikeStatus) : ServerMessage
    data object QueueChanged : ServerMessage
    data class Queue(val id: String, val items: List<QueueItem>, val truncated: Boolean) : ServerMessage
    data class ServerClosing(val reason: ClosingReason) : ServerMessage

    // ---- 1.1, only in answer to catalog commands ----

    data class CatalogPage(
        val id: String,
        val title: String,
        val subtitle: String?,
        val header: CatalogHeader?,
        val chips: List<CatalogChip>,
        val sections: List<CatalogSection>,
        /** How this answer joins the page already shown; null for a first page. */
        val append: CatalogAppend?,
        /** Cursor for the next page, or null when there is no more. */
        val continuation: String?,
    ) : ServerMessage

    data class CatalogSuggestions(val id: String, val items: List<String>) : ServerMessage

    /** [data] is standard base64; decoded it is at most [Limits.THUMB_MAX_BYTES]. */
    class CatalogThumb(val id: String, val mime: ThumbMime, val data: String) : ServerMessage {
        fun bytes(): ByteArray = java.util.Base64.getDecoder().decode(data)
        override fun toString() = "CatalogThumb(id=$id, mime=${mime.wire}, ${data.length} chars)"
        override fun equals(other: Any?) = other is CatalogThumb && other.id == id && other.mime == mime && other.data == data
        override fun hashCode() = data.hashCode()
    }
}

data class PairDevice(val name: String, val model: String?)

sealed interface ClientHandshake {
    val protocol: ProtocolVersion
    val client: AppInfo

    class Hello(
        override val protocol: ProtocolVersion,
        override val client: AppInfo,
        val deviceId: String,
        val token: String,
    ) : ClientHandshake {
        override fun toString() = "Hello(protocol=$protocol, client=$client, deviceId=$deviceId, token=<redacted>)"
        override fun equals(other: Any?) = other is Hello && other.protocol == protocol &&
            other.client == client && other.deviceId == deviceId && other.token == token
        override fun hashCode() = deviceId.hashCode()
    }

    /** Exactly one of [secret] (QR path) and [code] (manual path) is set. */
    class PairRequest private constructor(
        override val protocol: ProtocolVersion,
        override val client: AppInfo,
        val device: PairDevice,
        val secret: String?,
        val code: String?,
    ) : ClientHandshake {
        override fun toString() = "PairRequest(protocol=$protocol, client=$client, device=$device, " +
            (if (secret != null) "secret=<redacted>)" else "code=<redacted>)")
        override fun equals(other: Any?) = other is PairRequest && other.protocol == protocol &&
            other.client == client && other.device == device && other.secret == secret && other.code == code
        override fun hashCode() = device.hashCode()

        companion object {
            fun withSecret(protocol: ProtocolVersion, client: AppInfo, device: PairDevice, secret: String) =
                PairRequest(protocol, client, device, secret, null)
            fun withCode(protocol: ProtocolVersion, client: AppInfo, device: PairDevice, code: String) =
                PairRequest(protocol, client, device, null, code)
        }
    }
}

sealed interface ClientCommand {
    val id: String
    val type: String

    data class Play(override val id: String) : ClientCommand { override val type get() = "player.play" }
    data class Pause(override val id: String) : ClientCommand { override val type get() = "player.pause" }
    data class TogglePlay(override val id: String) : ClientCommand { override val type get() = "player.togglePlay" }
    data class Next(override val id: String) : ClientCommand { override val type get() = "player.next" }
    data class Previous(override val id: String) : ClientCommand { override val type get() = "player.previous" }
    data class Seek(override val id: String, val positionSeconds: Double) : ClientCommand {
        override val type get() = "player.seek"
    }
    data class SetVolume(override val id: String, val volume: Int) : ClientCommand {
        override val type get() = "player.setVolume"
    }
    data class SetMuted(override val id: String, val muted: Boolean) : ClientCommand {
        override val type get() = "player.setMuted"
    }
    data class ToggleShuffle(override val id: String) : ClientCommand { override val type get() = "player.toggleShuffle" }
    data class SetRepeat(override val id: String, val mode: RepeatMode) : ClientCommand {
        override val type get() = "player.setRepeat"
    }
    data class SetLike(override val id: String, val status: LikeStatus) : ClientCommand {
        override val type get() = "track.setLike"
    }
    data class StateGet(override val id: String) : ClientCommand { override val type get() = "state.get" }
    data class QueueGet(override val id: String) : ClientCommand { override val type get() = "queue.get" }
    data class QueuePlay(override val id: String, val index: Int, val videoId: String) : ClientCommand {
        override val type get() = "queue.play"
    }

    // ---- 1.1 catalog, gated with Capabilities.isCommandAvailable ----

    data class CatalogBrowse(override val id: String, val target: BrowseTarget, val continuation: String?) :
        ClientCommand {
        override val type get() = "catalog.browse"
    }
    data class CatalogOpen(override val id: String, val ref: String, val continuation: String?) : ClientCommand {
        override val type get() = "catalog.open"
    }
    /** [query] is sent trimmed; see [Fields.catalogQuery]. */
    data class CatalogSearch(
        override val id: String,
        val query: String,
        val filter: SearchFilter,
        val continuation: String?,
    ) : ClientCommand {
        override val type get() = "catalog.search"
    }
    data class CatalogSuggest(override val id: String, val query: String) : ClientCommand {
        override val type get() = "catalog.suggest"
    }
    /** [action] is never [CatalogAction.OPEN]; the encoder refuses it. */
    data class CatalogPlay(override val id: String, val ref: String, val action: CatalogAction) : ClientCommand {
        override val type get() = "catalog.play"
    }
    data class CatalogThumb(override val id: String, val thumb: String) : ClientCommand {
        override val type get() = "catalog.thumb"
    }
}
