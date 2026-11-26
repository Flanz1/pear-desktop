package dev.pear.remote.protocol

/*
 * Catalog (protocol 1.1, SPEC section 10): models shared by commands and
 * answers. Refs, cursors and thumbnail refs are opaque strings issued by the
 * desktop (Fields.isRef); the phone only sends back ones it received.
 */

enum class BrowseTarget(val wire: String) {
    HOME("home"),
    LIBRARY_PLAYLISTS("library.playlists"),
    LIBRARY_ALBUMS("library.albums"),
    LIBRARY_ARTISTS("library.artists"),
    LIBRARY_SONGS("library.songs"),
    LIBRARY_LIKED("library.liked"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

enum class SearchFilter(val wire: String) {
    TOP("top"),
    SONGS("songs"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    PLAYLISTS("playlists"),
    VIDEOS("videos"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

enum class CatalogItemKind(val wire: String) {
    SONG("song"),
    VIDEO("video"),
    ALBUM("album"),
    SINGLE("single"),
    PLAYLIST("playlist"),
    ARTIST("artist"),
    EPISODE("episode"),
    STATION("station"),
    ;

    /** Songs, videos and episodes play when tapped; everything else opens. */
    val isTrack get() = this == SONG || this == VIDEO || this == EPISODE

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

/** Item and header actions. [OPEN] goes with catalog.open, the rest with catalog.play. */
enum class CatalogAction(val wire: String) {
    OPEN("open"),
    PLAY("play"),
    SHUFFLE("shuffle"),
    PLAY_NEXT("playNext"),
    ADD_TO_QUEUE("addToQueue"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

enum class CatalogLayout(val wire: String) {
    CAROUSEL("carousel"),
    LIST("list"),
    GRID("grid"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

/** SPEC 10.4 continuation rule. */
enum class CatalogAppend(val wire: String) {
    /** Append the answer's sections after the page's sections. */
    SECTIONS("sections"),

    /** The answer has one section; append its items to the page's last section. */
    ITEMS("items"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

enum class HeaderKind(val wire: String) {
    ALBUM("album"),
    PLAYLIST("playlist"),
    ARTIST("artist"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

enum class ThumbMime(val wire: String) {
    JPEG("image/jpeg"),
    WEBP("image/webp"),
    PNG("image/png"),
    ;

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

data class CatalogItem(
    val ref: String,
    val kind: CatalogItemKind,
    val title: String,
    val subtitle: String,
    val durationSeconds: Double?,
    val explicit: Boolean,
    /** Non-empty, no duplicates. Only these may be offered. */
    val actions: List<CatalogAction>,
    val thumb: String?,
    /** Playing on the desktop when the page was built (songs, videos, episodes only). A snapshot. */
    val isCurrent: Boolean,
)

data class CatalogSection(val title: String?, val layout: CatalogLayout, val items: List<CatalogItem>)

data class CatalogChip(val title: String, val ref: String)

data class CatalogHeader(
    val kind: HeaderKind,
    val title: String,
    val subtitle: String,
    val thumb: String?,
    /** The page's own ref, for catalog.play with [actions]. */
    val ref: String,
    /** Subset of PLAY and SHUFFLE. */
    val actions: List<CatalogAction>,
)

/** Capability names (protocol/src/version.ts CAPABILITIES) and COMMAND_REQUIREMENTS. */
object Capabilities {
    const val CATALOG_BROWSE = "catalog.browse"
    const val CATALOG_SEARCH = "catalog.search"
    const val CATALOG_THUMBNAILS = "catalog.thumbnails"

    private class Requirement(val minor: Int, val capability: String)

    private val REQUIREMENTS = mapOf(
        "catalog.browse" to Requirement(1, CATALOG_BROWSE),
        "catalog.open" to Requirement(1, CATALOG_BROWSE),
        "catalog.play" to Requirement(1, CATALOG_BROWSE),
        "catalog.search" to Requirement(1, CATALOG_SEARCH),
        "catalog.suggest" to Requirement(1, CATALOG_SEARCH),
        "catalog.thumb" to Requirement(1, CATALOG_THUMBNAILS),
    )

    /**
     * isCommandAvailable(): whether the phone may send [type] in a session
     * negotiated at [version] with [capabilities] from welcome. A 1.0 desktop
     * answers unknown command types with bad_message (3rd one closes), so the
     * phone never "just tries".
     */
    fun isCommandAvailable(type: String, version: ProtocolVersion, capabilities: List<String>): Boolean {
        val req = REQUIREMENTS[type] ?: return true
        return version.minor >= req.minor && req.capability in capabilities
    }
}
