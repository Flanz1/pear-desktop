package dev.pear.remote.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Outcome of decoding one frame, like ParseResult in protocol/src/parse.ts. */
sealed interface Decoded<out T> {
    data class Ok<T>(val value: T) : Decoded<T>
    data object TooLarge : Decoded<Nothing>
    data object NotJson : Decoded<Nothing>
    data object Invalid : Decoded<Nothing>
}

internal class InvalidMessage : Exception() {
    // No stack trace or message: decoding failures are expected and the
    // input may contain secrets.
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Strict JSON codec for protocol v1. Unknown keys, unknown types, wrong JSON
 * types and out-of-range values are all rejected, matching the zod schemas.
 */
object ProtocolCodec {
    private val json = Json

    // ---------- decoding ----------

    fun decodeServerMessage(raw: String): Decoded<ServerMessage> =
        decodeWith(raw, Limits.SERVER_MESSAGE_BYTES, ::serverMessage)

    fun decodeClientHandshake(raw: String): Decoded<ClientHandshake> =
        decodeWith(raw, Limits.CLIENT_MESSAGE_BYTES, ::clientHandshake)

    fun decodeClientCommand(raw: String): Decoded<ClientCommand> =
        decodeWith(raw, Limits.CLIENT_MESSAGE_BYTES, ::clientCommand)

    /** HandshakeEnvelopeSchema: `type` hello|pair.request and a valid `protocol`; other keys allowed. */
    fun decodeHandshakeEnvelope(element: JsonElement): Pair<String, ProtocolVersion>? = try {
        val o = element as? JsonObject ?: throw InvalidMessage()
        val r = Reader(o)
        val type = r.str("type")
        if (type != "hello" && type != "pair.request") throw InvalidMessage()
        type to version(r.obj("protocol"))
    } catch (_: InvalidMessage) {
        null
    }

    fun decodeServerMessage(element: JsonElement): ServerMessage? = tree(element, ::serverMessage)
    fun decodeClientHandshake(element: JsonElement): ClientHandshake? = tree(element, ::clientHandshake)
    fun decodeClientCommand(element: JsonElement): ClientCommand? = tree(element, ::clientCommand)

    private fun <T> tree(element: JsonElement, f: (JsonObject) -> T): T? = try {
        f(element as? JsonObject ?: throw InvalidMessage())
    } catch (_: InvalidMessage) {
        null
    }

    private fun <T> decodeWith(raw: String, maxBytes: Int, f: (JsonObject) -> T): Decoded<T> {
        if (utf8Length(raw) > maxBytes) return Decoded.TooLarge
        val element = try {
            json.parseToJsonElement(raw)
        } catch (_: Exception) {
            return Decoded.NotJson
        }
        return tree(element, f)?.let { Decoded.Ok(it) } ?: Decoded.Invalid
    }

    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return n
    }

    private fun serverMessage(o: JsonObject): ServerMessage {
        val r = Reader(o)
        return when (r.str("type")) {
            "welcome" -> {
                r.keys("type", "protocol", "server", "instanceId", "name", "capabilities")
                val caps = r.arr("capabilities", max = 64).map { str(it, Fields::isCapability) }
                ServerMessage.Welcome(
                    version(r.obj("protocol")),
                    appInfo(r.obj("server")),
                    r.str("instanceId", Fields::isUuid),
                    r.str("name", Fields::isText),
                    caps,
                )
            }
            "error" -> {
                r.keys("type", "id", "code", "message", "supported")
                ServerMessage.Error(
                    r.strOrNull("id", Fields::isRequestId),
                    ErrorCode.fromWire(r.str("code")) ?: throw InvalidMessage(),
                    r.str("message", Fields::isText),
                    r.objOrNull("supported")?.let {
                        val s = Reader(it)
                        s.keys("min", "max")
                        SupportedRange(s.int("min", 0, Int.MAX_VALUE), s.int("max", 0, Int.MAX_VALUE))
                    },
                )
            }
            "pair.result" -> {
                r.keys("type", "deviceId", "token", "instanceId")
                ServerMessage.PairResult(
                    r.str("deviceId", Fields::isUuid),
                    r.str("token", Fields::isToken),
                    r.str("instanceId", Fields::isUuid),
                )
            }
            "result" -> {
                r.keys("type", "id")
                ServerMessage.Result(r.str("id", Fields::isRequestId))
            }
            "state" -> {
                r.keys("type", "id", "state")
                ServerMessage.State(r.strOrNull("id", Fields::isRequestId), playerState(r.obj("state")))
            }
            "track.changed" -> {
                r.keys("type", "track")
                ServerMessage.TrackChanged(r.objOrNull("track")?.let(::track))
            }
            "playback.changed" -> {
                r.keys("type", "playback")
                ServerMessage.PlaybackChanged(playback(r.obj("playback")))
            }
            "volume.changed" -> {
                r.keys("type", "volume")
                ServerMessage.VolumeChanged(volumeState(r.obj("volume")))
            }
            "repeat.changed" -> {
                r.keys("type", "repeat")
                ServerMessage.RepeatChanged(repeat(r.str("repeat")))
            }
            "shuffle.changed" -> {
                r.keys("type", "shuffle")
                ServerMessage.ShuffleChanged(r.bool("shuffle"))
            }
            "like.changed" -> {
                r.keys("type", "like")
                ServerMessage.LikeChanged(like(r.str("like")))
            }
            "queue.changed" -> {
                r.keys("type")
                ServerMessage.QueueChanged
            }
            "queue" -> {
                r.keys("type", "id", "items", "truncated")
                ServerMessage.Queue(
                    r.str("id", Fields::isRequestId),
                    r.arr("items", max = Limits.QUEUE_MAX_ITEMS).map { queueItem(it as? JsonObject ?: throw InvalidMessage()) },
                    r.bool("truncated"),
                )
            }
            "server.closing" -> {
                r.keys("type", "reason")
                ServerMessage.ServerClosing(ClosingReason.fromWire(r.str("reason")) ?: throw InvalidMessage())
            }
            "catalog.page" -> catalogPage(r)
            "catalog.suggestions" -> {
                r.keys("type", "id", "items")
                ServerMessage.CatalogSuggestions(
                    r.str("id", Fields::isRequestId),
                    r.arr("items", max = Limits.CATALOG_SUGGESTIONS_MAX).map { str(it, Fields::isSuggestion) },
                )
            }
            "catalog.thumb" -> {
                r.keys("type", "id", "mime", "data")
                val id = r.str("id", Fields::isRequestId)
                val mime = ThumbMime.fromWire(r.str("mime")) ?: throw InvalidMessage()
                val data = r.str("data") {
                    it.length in 4..THUMB_MAX_BASE64 && Fields.isBase64(it)
                }
                ServerMessage.CatalogThumb(id, mime, data)
            }
            else -> throw InvalidMessage()
        }
    }

    /** Longest base64 text for a thumbnail of at most THUMB_MAX_BYTES (zod: ceil(n / 3) * 4). */
    private const val THUMB_MAX_BASE64 = (Limits.THUMB_MAX_BYTES + 2) / 3 * 4

    private fun catalogPage(r: Reader): ServerMessage.CatalogPage {
        r.keys("type", "id", "title", "subtitle", "header", "chips", "sections", "append", "continuation")
        val page = ServerMessage.CatalogPage(
            id = r.str("id", Fields::isRequestId),
            title = r.str("title", Fields::isText),
            subtitle = r.strOrNull("subtitle", Fields::isText),
            header = r.objOrNull("header")?.let(::catalogHeader),
            chips = r.arr("chips", max = Limits.CATALOG_CHIPS_MAX).map { catalogChip(it.asObject()) },
            sections = r.arr("sections", max = Limits.CATALOG_SECTIONS_MAX).map { catalogSection(it.asObject()) },
            append = r.strOrNull("append")?.let { CatalogAppend.fromWire(it) ?: throw InvalidMessage() },
            continuation = r.strOrNull("continuation", Fields::isRef),
        )
        // The two refinements of CatalogPageSchema.
        if (page.append == CatalogAppend.ITEMS && page.sections.size != 1) throw InvalidMessage()
        if (page.append != null && (page.header != null || page.chips.isNotEmpty())) throw InvalidMessage()
        return page
    }

    private fun catalogHeader(o: JsonObject): CatalogHeader {
        val r = Reader(o)
        r.keys("kind", "title", "subtitle", "thumb", "ref", "actions")
        return CatalogHeader(
            HeaderKind.fromWire(r.str("kind")) ?: throw InvalidMessage(),
            r.str("title", Fields::isText),
            r.str("subtitle", Fields::isText),
            r.strOrNull("thumb", Fields::isRef),
            r.str("ref", Fields::isRef),
            actions(r.arr("actions", max = 2), allowed = HEADER_ACTIONS, min = 0),
        )
    }

    private val HEADER_ACTIONS = setOf(CatalogAction.PLAY, CatalogAction.SHUFFLE)

    private fun actions(a: JsonArray, allowed: Set<CatalogAction>, min: Int): List<CatalogAction> {
        val list = a.map { e -> CatalogAction.fromWire(str(e) { true })?.takeIf { it in allowed } ?: throw InvalidMessage() }
        if (list.size < min || list.toSet().size != list.size) throw InvalidMessage()
        return list
    }

    private fun catalogChip(o: JsonObject): CatalogChip {
        val r = Reader(o)
        r.keys("title", "ref")
        return CatalogChip(r.str("title", Fields::isText), r.str("ref", Fields::isRef))
    }

    private fun catalogSection(o: JsonObject): CatalogSection {
        val r = Reader(o)
        r.keys("title", "layout", "items")
        val items = r.arr("items", max = Limits.CATALOG_ITEMS_MAX).map { catalogItem(it.asObject()) }
        if (items.isEmpty()) throw InvalidMessage()
        return CatalogSection(
            r.strOrNull("title", Fields::isText),
            CatalogLayout.fromWire(r.str("layout")) ?: throw InvalidMessage(),
            items,
        )
    }

    private fun catalogItem(o: JsonObject): CatalogItem {
        val r = Reader(o)
        r.keys("ref", "kind", "title", "subtitle", "durationSeconds", "explicit", "actions", "thumb", "isCurrent")
        return CatalogItem(
            ref = r.str("ref", Fields::isRef),
            kind = CatalogItemKind.fromWire(r.str("kind")) ?: throw InvalidMessage(),
            title = r.str("title", Fields::isText),
            subtitle = r.str("subtitle", Fields::isText),
            durationSeconds = r.secondsOrNull("durationSeconds"),
            explicit = r.bool("explicit"),
            actions = actions(
                r.arr("actions", max = CatalogAction.entries.size),
                allowed = CatalogAction.entries.toSet(),
                min = 1,
            ),
            thumb = r.strOrNull("thumb", Fields::isRef),
            isCurrent = r.bool("isCurrent"),
        )
    }

    private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: throw InvalidMessage()

    private fun clientHandshake(o: JsonObject): ClientHandshake {
        val r = Reader(o)
        return when (r.str("type")) {
            "hello" -> {
                r.keys("type", "protocol", "client", "deviceId", "token")
                ClientHandshake.Hello(
                    version(r.obj("protocol")),
                    appInfo(r.obj("client")),
                    r.str("deviceId", Fields::isUuid),
                    r.str("token", Fields::isToken),
                )
            }
            "pair.request" -> {
                val hasSecret = o.containsKey("secret")
                if (hasSecret) {
                    r.keys("type", "protocol", "client", "device", "secret")
                } else {
                    r.keys("type", "protocol", "client", "device", "code")
                }
                val protocol = version(r.obj("protocol"))
                val client = appInfo(r.obj("client"))
                val device = pairDevice(r.obj("device"))
                if (hasSecret) {
                    ClientHandshake.PairRequest.withSecret(protocol, client, device, r.str("secret", Fields::isPairingSecret))
                } else {
                    ClientHandshake.PairRequest.withCode(protocol, client, device, r.str("code", Fields::isPairingCode))
                }
            }
            else -> throw InvalidMessage()
        }
    }

    private fun clientCommand(o: JsonObject): ClientCommand {
        val r = Reader(o)
        val type = r.str("type")
        val extra: List<String> = when (type) {
            "player.seek" -> listOf("positionSeconds")
            "player.setVolume" -> listOf("volume")
            "player.setMuted" -> listOf("muted")
            "player.setRepeat" -> listOf("mode")
            "track.setLike" -> listOf("status")
            "queue.play" -> listOf("index", "videoId")
            "catalog.browse" -> listOf("target", "continuation")
            "catalog.open" -> listOf("ref", "continuation")
            "catalog.search" -> listOf("query", "filter", "continuation")
            "catalog.suggest" -> listOf("query")
            "catalog.play" -> listOf("ref", "action")
            "catalog.thumb" -> listOf("thumb")
            else -> emptyList()
        }
        r.keys(*(listOf("type", "id") + extra).toTypedArray())
        val id = r.str("id", Fields::isRequestId)
        return when (type) {
            "player.play" -> ClientCommand.Play(id)
            "player.pause" -> ClientCommand.Pause(id)
            "player.togglePlay" -> ClientCommand.TogglePlay(id)
            "player.next" -> ClientCommand.Next(id)
            "player.previous" -> ClientCommand.Previous(id)
            "player.seek" -> ClientCommand.Seek(id, r.seconds("positionSeconds"))
            "player.setVolume" -> ClientCommand.SetVolume(id, r.int("volume", 0, 100))
            "player.setMuted" -> ClientCommand.SetMuted(id, r.bool("muted"))
            "player.toggleShuffle" -> ClientCommand.ToggleShuffle(id)
            "player.setRepeat" -> ClientCommand.SetRepeat(id, repeat(r.str("mode")))
            "track.setLike" -> ClientCommand.SetLike(id, like(r.str("status")))
            "state.get" -> ClientCommand.StateGet(id)
            "queue.get" -> ClientCommand.QueueGet(id)
            "queue.play" -> ClientCommand.QueuePlay(id, r.int("index", 0, 9999), r.str("videoId", Fields::isVideoId))
            "catalog.browse" -> ClientCommand.CatalogBrowse(
                id,
                BrowseTarget.fromWire(r.str("target")) ?: throw InvalidMessage(),
                r.strOrNull("continuation", Fields::isRef),
            )
            "catalog.open" -> ClientCommand.CatalogOpen(
                id, r.str("ref", Fields::isRef), r.strOrNull("continuation", Fields::isRef),
            )
            "catalog.search" -> ClientCommand.CatalogSearch(
                id,
                Fields.catalogQuery(r.str("query")) ?: throw InvalidMessage(),
                SearchFilter.fromWire(r.str("filter")) ?: throw InvalidMessage(),
                r.strOrNull("continuation", Fields::isRef),
            )
            "catalog.suggest" -> ClientCommand.CatalogSuggest(
                id, Fields.catalogQuery(r.str("query")) ?: throw InvalidMessage(),
            )
            "catalog.play" -> ClientCommand.CatalogPlay(
                id,
                r.str("ref", Fields::isRef),
                CatalogAction.fromWire(r.str("action"))?.takeIf { it != CatalogAction.OPEN } ?: throw InvalidMessage(),
            )
            "catalog.thumb" -> ClientCommand.CatalogThumb(id, r.str("thumb", Fields::isRef))
            else -> throw InvalidMessage()
        }
    }

    private fun version(o: JsonObject): ProtocolVersion {
        val r = Reader(o)
        r.keys("major", "minor")
        return ProtocolVersion(r.int("major", 0, 999), r.int("minor", 0, 999))
    }

    private fun appInfo(o: JsonObject): AppInfo {
        val r = Reader(o)
        r.keys("name", "version")
        return AppInfo(r.str("name", Fields::isAppName), r.str("version", Fields::isAppVersion))
    }

    private fun pairDevice(o: JsonObject): PairDevice {
        val r = Reader(o)
        r.keys("name", "model")
        val name = Fields.deviceName(r.str("name")) ?: throw InvalidMessage()
        val model = r.strOrNull("model")?.let { Fields.deviceName(it) ?: throw InvalidMessage() }
        return PairDevice(name, model)
    }

    private fun track(o: JsonObject): Track {
        val r = Reader(o)
        val hasThumb = r.keysWithOptional("thumb", "videoId", "title", "artist", "album", "durationSeconds", "thumbnailUrl")
        return Track(
            r.str("videoId", Fields::isVideoId),
            r.str("title", Fields::isText),
            r.str("artist", Fields::isText),
            r.strOrNull("album", Fields::isText),
            r.secondsOrNull("durationSeconds"),
            r.strOrNull("thumbnailUrl", Fields::isHttpsUrl),
            thumb = if (hasThumb) r.strOrNull("thumb", Fields::isRef) else null,
            thumbPresent = hasThumb,
        )
    }

    private fun playback(o: JsonObject): Playback {
        val r = Reader(o)
        r.keys("status", "positionSeconds")
        return Playback(
            PlaybackStatus.fromWire(r.str("status")) ?: throw InvalidMessage(),
            r.seconds("positionSeconds"),
        )
    }

    private fun volumeState(o: JsonObject): VolumeState {
        val r = Reader(o)
        r.keys("volume", "muted")
        return VolumeState(r.int("volume", 0, 100), r.bool("muted"))
    }

    private fun playerState(o: JsonObject): PlayerState {
        val r = Reader(o)
        r.keys("track", "playback", "volume", "repeat", "shuffle", "like")
        return PlayerState(
            r.objOrNull("track")?.let(::track),
            playback(r.obj("playback")),
            volumeState(r.obj("volume")),
            repeat(r.str("repeat")),
            r.bool("shuffle"),
            like(r.str("like")),
        )
    }

    private fun queueItem(o: JsonObject): QueueItem {
        val r = Reader(o)
        val hasThumb = r.keysWithOptional(
            "thumb", "index", "videoId", "title", "artist", "durationSeconds", "thumbnailUrl", "isCurrent",
        )
        return QueueItem(
            r.int("index", 0, 9999),
            r.str("videoId", Fields::isVideoId),
            r.str("title", Fields::isText),
            r.str("artist", Fields::isText),
            r.secondsOrNull("durationSeconds"),
            r.strOrNull("thumbnailUrl", Fields::isHttpsUrl),
            r.bool("isCurrent"),
            thumb = if (hasThumb) r.strOrNull("thumb", Fields::isRef) else null,
            thumbPresent = hasThumb,
        )
    }

    private fun repeat(s: String) = RepeatMode.entries.firstOrNull { it.name == s } ?: throw InvalidMessage()
    private fun like(s: String) = LikeStatus.entries.firstOrNull { it.name == s } ?: throw InvalidMessage()

    private fun str(e: JsonElement, check: (String) -> Boolean): String {
        val p = e as? JsonPrimitive ?: throw InvalidMessage()
        if (!p.isString || !check(p.content)) throw InvalidMessage()
        return p.content
    }

    /** Typed access to one JSON object, throwing [InvalidMessage] on any mismatch. */
    private class Reader(private val o: JsonObject) {
        /** strictObject: exactly these keys. Every v1 field is required (nullable fields too). */
        fun keys(vararg expected: String) {
            if (o.size != expected.size || !expected.all { o.containsKey(it) }) throw InvalidMessage()
        }

        /**
         * Like [keys], plus one optional key (`thumb` on Track/QueueItem is
         * present in 1.1 sessions and absent in 1.0). Returns whether it's there.
         */
        fun keysWithOptional(optional: String, vararg required: String): Boolean {
            val has = o.containsKey(optional)
            if (has) keys(*required, optional) else keys(*required)
            return has
        }

        private fun get(k: String): JsonElement = o[k] ?: throw InvalidMessage()

        fun str(k: String, check: (String) -> Boolean = { true }): String = str(get(k), check)

        fun strOrNull(k: String, check: (String) -> Boolean = { true }): String? {
            val e = get(k)
            return if (e is JsonNull) null else str(e, check)
        }

        fun bool(k: String): Boolean {
            val p = get(k) as? JsonPrimitive ?: throw InvalidMessage()
            if (p.isString) throw InvalidMessage()
            return when (p.content) {
                "true" -> true
                "false" -> false
                else -> throw InvalidMessage()
            }
        }

        private fun number(e: JsonElement): Double {
            val p = e as? JsonPrimitive ?: throw InvalidMessage()
            if (p.isString || p is JsonNull) throw InvalidMessage()
            val d = p.content.toDoubleOrNull() ?: throw InvalidMessage()
            if (!d.isFinite()) throw InvalidMessage()
            return d
        }

        fun int(k: String, min: Int, max: Int): Int {
            val d = number(get(k))
            if (d != Math.floor(d) || d < min || d > max) throw InvalidMessage()
            return d.toInt()
        }

        fun seconds(k: String): Double {
            val d = number(get(k))
            if (d < 0 || d > 86_400) throw InvalidMessage()
            return d
        }

        fun secondsOrNull(k: String): Double? = if (get(k) is JsonNull) null else seconds(k)

        fun obj(k: String): JsonObject = get(k) as? JsonObject ?: throw InvalidMessage()

        fun objOrNull(k: String): JsonObject? = when (val e = get(k)) {
            is JsonNull -> null
            is JsonObject -> e
            else -> throw InvalidMessage()
        }

        fun arr(k: String, max: Int): JsonArray {
            val a = get(k) as? JsonArray ?: throw InvalidMessage()
            if (a.size > max) throw InvalidMessage()
            return a
        }
    }

    // ---------- encoding ----------

    /**
     * Encodes a handshake. Throws [IllegalArgumentException] if the value
     * wouldn't pass the desktop's validation, so a bug can't send garbage.
     */
    fun encode(h: ClientHandshake): String = checked(toJson(h)) { decodeClientHandshake(it) }

    fun encode(c: ClientCommand): String = checked(toJson(c)) { decodeClientCommand(it) }

    private fun checked(o: JsonObject, validate: (JsonElement) -> Any?): String {
        require(validate(o) != null) { "message would fail validation" }
        val s = o.toString()
        require(utf8Length(s) <= Limits.CLIENT_MESSAGE_BYTES) { "message too large" }
        return s
    }

    fun toJson(h: ClientHandshake): JsonObject = when (h) {
        is ClientHandshake.Hello -> buildJsonObject {
            put("type", JsonPrimitive("hello"))
            put("protocol", versionJson(h.protocol))
            put("client", appInfoJson(h.client))
            put("deviceId", JsonPrimitive(h.deviceId))
            put("token", JsonPrimitive(h.token))
        }
        is ClientHandshake.PairRequest -> buildJsonObject {
            put("type", JsonPrimitive("pair.request"))
            put("protocol", versionJson(h.protocol))
            put("client", appInfoJson(h.client))
            put("device", buildJsonObject {
                put("name", JsonPrimitive(h.device.name))
                put("model", h.device.model?.let(::JsonPrimitive) ?: JsonNull)
            })
            h.secret?.let { put("secret", JsonPrimitive(it)) }
            h.code?.let { put("code", JsonPrimitive(it)) }
        }
    }

    fun toJson(c: ClientCommand): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(c.type))
        put("id", JsonPrimitive(c.id))
        when (c) {
            is ClientCommand.Seek -> put("positionSeconds", num(c.positionSeconds))
            is ClientCommand.SetVolume -> put("volume", JsonPrimitive(c.volume))
            is ClientCommand.SetMuted -> put("muted", JsonPrimitive(c.muted))
            is ClientCommand.SetRepeat -> put("mode", JsonPrimitive(c.mode.name))
            is ClientCommand.SetLike -> put("status", JsonPrimitive(c.status.name))
            is ClientCommand.QueuePlay -> {
                put("index", JsonPrimitive(c.index))
                put("videoId", JsonPrimitive(c.videoId))
            }
            is ClientCommand.CatalogBrowse -> {
                put("target", JsonPrimitive(c.target.wire))
                put("continuation", c.continuation.json())
            }
            is ClientCommand.CatalogOpen -> {
                put("ref", JsonPrimitive(c.ref))
                put("continuation", c.continuation.json())
            }
            is ClientCommand.CatalogSearch -> {
                put("query", JsonPrimitive(c.query))
                put("filter", JsonPrimitive(c.filter.wire))
                put("continuation", c.continuation.json())
            }
            is ClientCommand.CatalogSuggest -> put("query", JsonPrimitive(c.query))
            is ClientCommand.CatalogPlay -> {
                put("ref", JsonPrimitive(c.ref))
                put("action", JsonPrimitive(c.action.wire))
            }
            is ClientCommand.CatalogThumb -> put("thumb", JsonPrimitive(c.thumb))
            else -> Unit
        }
    }

    /** Server messages are encoded only by tests (fixture round trips). */
    fun toJson(m: ServerMessage): JsonObject = buildJsonObject {
        fun type(t: String) = put("type", JsonPrimitive(t))
        when (m) {
            is ServerMessage.Welcome -> {
                type("welcome")
                put("protocol", versionJson(m.protocol))
                put("server", appInfoJson(m.server))
                put("instanceId", JsonPrimitive(m.instanceId))
                put("name", JsonPrimitive(m.name))
                put("capabilities", buildJsonArray { m.capabilities.forEach { add(JsonPrimitive(it)) } })
            }
            is ServerMessage.Error -> {
                type("error")
                put("id", m.id?.let(::JsonPrimitive) ?: JsonNull)
                put("code", JsonPrimitive(m.code.wire))
                put("message", JsonPrimitive(m.message))
                put("supported", m.supported?.let {
                    buildJsonObject {
                        put("min", JsonPrimitive(it.min))
                        put("max", JsonPrimitive(it.max))
                    }
                } ?: JsonNull)
            }
            is ServerMessage.PairResult -> {
                type("pair.result")
                put("deviceId", JsonPrimitive(m.deviceId))
                put("token", JsonPrimitive(m.token))
                put("instanceId", JsonPrimitive(m.instanceId))
            }
            is ServerMessage.Result -> {
                type("result")
                put("id", JsonPrimitive(m.id))
            }
            is ServerMessage.State -> {
                type("state")
                put("id", m.id?.let(::JsonPrimitive) ?: JsonNull)
                put("state", buildJsonObject {
                    put("track", m.state.track?.let(::trackJson) ?: JsonNull)
                    put("playback", playbackJson(m.state.playback))
                    put("volume", volumeJson(m.state.volume))
                    put("repeat", JsonPrimitive(m.state.repeat.name))
                    put("shuffle", JsonPrimitive(m.state.shuffle))
                    put("like", JsonPrimitive(m.state.like.name))
                })
            }
            is ServerMessage.TrackChanged -> {
                type("track.changed")
                put("track", m.track?.let(::trackJson) ?: JsonNull)
            }
            is ServerMessage.PlaybackChanged -> {
                type("playback.changed")
                put("playback", playbackJson(m.playback))
            }
            is ServerMessage.VolumeChanged -> {
                type("volume.changed")
                put("volume", volumeJson(m.volume))
            }
            is ServerMessage.RepeatChanged -> {
                type("repeat.changed")
                put("repeat", JsonPrimitive(m.repeat.name))
            }
            is ServerMessage.ShuffleChanged -> {
                type("shuffle.changed")
                put("shuffle", JsonPrimitive(m.shuffle))
            }
            is ServerMessage.LikeChanged -> {
                type("like.changed")
                put("like", JsonPrimitive(m.like.name))
            }
            ServerMessage.QueueChanged -> type("queue.changed")
            is ServerMessage.Queue -> {
                type("queue")
                put("id", JsonPrimitive(m.id))
                put("items", buildJsonArray {
                    m.items.forEach { q ->
                        add(buildJsonObject {
                            put("index", JsonPrimitive(q.index))
                            put("videoId", JsonPrimitive(q.videoId))
                            put("title", JsonPrimitive(q.title))
                            put("artist", JsonPrimitive(q.artist))
                            put("durationSeconds", q.durationSeconds?.let(::num) ?: JsonNull)
                            put("thumbnailUrl", q.thumbnailUrl?.let(::JsonPrimitive) ?: JsonNull)
                            put("isCurrent", JsonPrimitive(q.isCurrent))
                            if (q.thumbPresent) put("thumb", q.thumb.json())
                        })
                    }
                })
                put("truncated", JsonPrimitive(m.truncated))
            }
            is ServerMessage.ServerClosing -> {
                type("server.closing")
                put("reason", JsonPrimitive(m.reason.wire))
            }
            is ServerMessage.CatalogPage -> {
                type("catalog.page")
                put("id", JsonPrimitive(m.id))
                put("title", JsonPrimitive(m.title))
                put("subtitle", m.subtitle.json())
                put("header", m.header?.let { h ->
                    buildJsonObject {
                        put("kind", JsonPrimitive(h.kind.wire))
                        put("title", JsonPrimitive(h.title))
                        put("subtitle", JsonPrimitive(h.subtitle))
                        put("thumb", h.thumb.json())
                        put("ref", JsonPrimitive(h.ref))
                        put("actions", buildJsonArray { h.actions.forEach { add(JsonPrimitive(it.wire)) } })
                    }
                } ?: JsonNull)
                put("chips", buildJsonArray {
                    m.chips.forEach { c ->
                        add(buildJsonObject {
                            put("title", JsonPrimitive(c.title))
                            put("ref", JsonPrimitive(c.ref))
                        })
                    }
                })
                put("sections", buildJsonArray {
                    m.sections.forEach { sec ->
                        add(buildJsonObject {
                            put("title", sec.title.json())
                            put("layout", JsonPrimitive(sec.layout.wire))
                            put("items", buildJsonArray { sec.items.forEach { add(itemJson(it)) } })
                        })
                    }
                })
                put("append", m.append?.wire.json())
                put("continuation", m.continuation.json())
            }
            is ServerMessage.CatalogSuggestions -> {
                type("catalog.suggestions")
                put("id", JsonPrimitive(m.id))
                put("items", buildJsonArray { m.items.forEach { add(JsonPrimitive(it)) } })
            }
            is ServerMessage.CatalogThumb -> {
                type("catalog.thumb")
                put("id", JsonPrimitive(m.id))
                put("mime", JsonPrimitive(m.mime.wire))
                put("data", JsonPrimitive(m.data))
            }
        }
    }

    private fun itemJson(i: CatalogItem) = buildJsonObject {
        put("ref", JsonPrimitive(i.ref))
        put("kind", JsonPrimitive(i.kind.wire))
        put("title", JsonPrimitive(i.title))
        put("subtitle", JsonPrimitive(i.subtitle))
        put("durationSeconds", i.durationSeconds?.let(::num) ?: JsonNull)
        put("explicit", JsonPrimitive(i.explicit))
        put("actions", buildJsonArray { i.actions.forEach { add(JsonPrimitive(it.wire)) } })
        put("thumb", i.thumb.json())
        put("isCurrent", JsonPrimitive(i.isCurrent))
    }

    private fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private fun versionJson(v: ProtocolVersion) = buildJsonObject {
        put("major", JsonPrimitive(v.major))
        put("minor", JsonPrimitive(v.minor))
    }

    private fun appInfoJson(a: AppInfo) = buildJsonObject {
        put("name", JsonPrimitive(a.name))
        put("version", JsonPrimitive(a.version))
    }

    private fun trackJson(t: Track) = buildJsonObject {
        put("videoId", JsonPrimitive(t.videoId))
        put("title", JsonPrimitive(t.title))
        put("artist", JsonPrimitive(t.artist))
        put("album", t.album?.let(::JsonPrimitive) ?: JsonNull)
        put("durationSeconds", t.durationSeconds?.let(::num) ?: JsonNull)
        put("thumbnailUrl", t.thumbnailUrl?.let(::JsonPrimitive) ?: JsonNull)
        if (t.thumbPresent) put("thumb", t.thumb.json())
    }

    private fun playbackJson(p: Playback) = buildJsonObject {
        put("status", JsonPrimitive(p.status.wire))
        put("positionSeconds", num(p.positionSeconds))
    }

    private fun volumeJson(v: VolumeState) = buildJsonObject {
        put("volume", JsonPrimitive(v.volume))
        put("muted", JsonPrimitive(v.muted))
    }

    /** Whole numbers are written without a fraction, as JavaScript does. */
    private fun num(d: Double): JsonPrimitive =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
}
