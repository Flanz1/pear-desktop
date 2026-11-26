package dev.pear.remote

import dev.pear.remote.client.CatalogPaging
import dev.pear.remote.client.CatalogSupport
import dev.pear.remote.client.Clock
import dev.pear.remote.client.CommandPacer
import dev.pear.remote.client.Reply
import dev.pear.remote.client.Thumbnails
import dev.pear.remote.protocol.AppInfo
import dev.pear.remote.protocol.Capabilities
import dev.pear.remote.protocol.CatalogAction
import dev.pear.remote.protocol.CatalogAppend
import dev.pear.remote.protocol.CatalogItem
import dev.pear.remote.protocol.CatalogItemKind
import dev.pear.remote.protocol.CatalogLayout
import dev.pear.remote.protocol.CatalogSection
import dev.pear.remote.protocol.ClientCommand
import dev.pear.remote.protocol.Decoded
import dev.pear.remote.protocol.ErrorCode
import dev.pear.remote.protocol.Fields
import dev.pear.remote.protocol.ProtocolCodec
import dev.pear.remote.protocol.ProtocolVersion
import dev.pear.remote.protocol.ServerMessage
import dev.pear.remote.protocol.ThumbMime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogUnitTest {
    private val ref = "QUJDREVGR0hJSktMTU5PUFFS" // 24 chars, like the desktop's refs

    private fun welcome(minor: Int, vararg caps: String) = ServerMessage.Welcome(
        ProtocolVersion(1, minor), AppInfo("pear-desktop", "3.13.0"),
        "5f0c6d1e-9a2b-4c3d-8e7f-6a5b4c3d2e1f", "PC", caps.toList(),
    )

    @Test
    fun `hello is sent at 1_1`() {
        assertEquals(ProtocolVersion(1, 1), ProtocolVersion.CURRENT)
    }

    @Test
    fun `catalog commands need minor 1 and the capability`() {
        val all = listOf(Capabilities.CATALOG_BROWSE, Capabilities.CATALOG_SEARCH, Capabilities.CATALOG_THUMBNAILS)
        val v10 = ProtocolVersion(1, 0)
        val v11 = ProtocolVersion(1, 1)
        for (t in listOf("catalog.browse", "catalog.open", "catalog.play", "catalog.search", "catalog.suggest", "catalog.thumb")) {
            assertFalse(Capabilities.isCommandAvailable(t, v10, all), t)
            assertFalse(Capabilities.isCommandAvailable(t, v11, emptyList()), t)
            assertTrue(Capabilities.isCommandAvailable(t, v11, all), t)
        }
        assertTrue(Capabilities.isCommandAvailable("player.play", v10, emptyList()))
        assertFalse(Capabilities.isCommandAvailable("catalog.search", v11, listOf(Capabilities.CATALOG_BROWSE)))

        assertEquals(CatalogSupport.NONE, CatalogSupport.of(welcome(0)))
        assertEquals(CatalogSupport.NONE, CatalogSupport.of(welcome(1)))
        assertEquals(CatalogSupport(true, true, true), CatalogSupport.of(welcome(1, *all.toTypedArray())))
        assertEquals(CatalogSupport(true, false, false), CatalogSupport.of(welcome(1, Capabilities.CATALOG_BROWSE)))
        // Search without browse is a desktop bug; the phone doesn't offer it.
        assertEquals(CatalogSupport.NONE, CatalogSupport.of(welcome(1, Capabilities.CATALOG_SEARCH)))
    }

    @Test
    fun `queries are trimmed and checked like CatalogQuerySchema`() {
        assertEquals("abc", Fields.catalogQuery("  abc \t"))
        assertNull(Fields.catalogQuery("   "))
        assertNull(Fields.catalogQuery("a\u0007b"))
        assertNull(Fields.catalogQuery("q".repeat(151)))
        assertEquals("q".repeat(150), Fields.catalogQuery(" " + "q".repeat(150) + " "))
    }

    @Test
    fun `encoder refuses commands the desktop would reject`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.encode(ClientCommand.CatalogPlay("c1", ref, CatalogAction.OPEN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.encode(ClientCommand.CatalogOpen("c1", "https://i.ytimg.com/x", null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolCodec.encode(ClientCommand.CatalogThumb("c1", "short"))
        }
    }

    @Test
    fun `track and queue item accept thumb present, null and absent`() {
        val base = """"videoId":"dQw4w9WgXcQ","title":"t","artist":"a","album":null,"durationSeconds":1,"thumbnailUrl":null"""
        fun track(extra: String) =
            (ProtocolCodec.decodeServerMessage("""{"type":"track.changed","track":{$base$extra}}""") as Decoded.Ok)
                .value as ServerMessage.TrackChanged
        assertNull(track("").track!!.thumb)
        assertFalse(track("").track!!.thumbPresent)
        assertNull(track(""","thumb":null""").track!!.thumb)
        assertTrue(track(""","thumb":null""").track!!.thumbPresent)
        assertEquals(ref, track(""","thumb":"$ref"""").track!!.thumb)
        assertEquals(
            Decoded.Invalid,
            ProtocolCodec.decodeServerMessage("""{"type":"track.changed","track":{$base,"thumb":"https://x.y/z"}}"""),
        )
    }

    @Test
    fun `new error codes decode instead of failing the message`() {
        for (code in listOf("not_found", "signed_out", "unavailable")) {
            val d = ProtocolCodec.decodeServerMessage(
                """{"type":"error","id":"c4","code":"$code","message":"","supported":null}""",
            )
            assertEquals(code, ((d as Decoded.Ok).value as ServerMessage.Error).code.wire)
        }
    }

    // ---------- pacing ----------

    @Test
    fun `background requests keep half the burst for player commands`() {
        var now = 0L
        val pacer = CommandPacer(Clock { now })
        var sent = 0
        // Over one simulated second, background requests never take the bucket under the reserve.
        repeat(1000) {
            if (pacer.tryTakeBackground() == 0L) sent++
            assertTrue(pacer.available() >= 10.0 - 1e-9)
            now += 1_000_000 // 1 ms
        }
        assertTrue(sent in 5..7, "sent $sent in 1 s")
        // Player commands are never delayed, and a burst of them still fits in the desktop's bucket.
        repeat(10) { pacer.take() }
        assertTrue(pacer.available() >= 0.0)
        // With the reserve gone, background requests wait for refill.
        assertTrue(pacer.tryTakeBackground() > 0)
    }

    @Test
    fun `combined load stays under the desktop rate limit`() {
        // Simulate the desktop's bucket next to ours: volume drag at ~8/s plus thumbnails.
        var now = 0L
        val pacer = CommandPacer(Clock { now })
        var desktop = 20.0
        var last = 0L
        var rejected = 0
        fun desktopTake() {
            desktop = (desktop + (now - last) / 1e9 * 10).coerceAtMost(20.0)
            last = now
            if (desktop >= 1) desktop -= 1 else rejected++
        }
        for (ms in 0 until 10_000) {
            now = ms * 1_000_000L
            if (ms % 125 == 0) { pacer.take(); desktopTake() }
            if (pacer.tryTakeBackground() == 0L) desktopTake()
        }
        assertEquals(0, rejected)
    }

    // ---------- thumbnails ----------

    private fun thumb(bytes: ByteArray) =
        ServerMessage.CatalogThumb("c1", ThumbMime.PNG, java.util.Base64.getEncoder().encodeToString(bytes))

    @Test
    fun `thumbnails share one request per ref and cache the bytes`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val t = Thumbnails(backgroundScope) { calls++; gate.await(); Reply.Ok(thumb(byteArrayOf(1, 2, 3))) }
        val a = async { t.load(ref) }
        val b = async { t.load(ref) }
        runCurrent()
        gate.complete(Unit)
        assertArrayEquals(byteArrayOf(1, 2, 3), a.await())
        assertArrayEquals(byteArrayOf(1, 2, 3), b.await())
        assertArrayEquals(byteArrayOf(1, 2, 3), t.load(ref))
        assertEquals(1, calls)
    }

    @Test
    fun `a cover that scrolled away is not fetched`() = runTest {
        val started = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val t = Thumbnails(backgroundScope, maxInFlight = 1) { r ->
            started += r
            gate.await()
            Reply.Ok(thumb(byteArrayOf(9)))
        }
        val first = launch { t.load(ref) }
        runCurrent()
        val other = "B".repeat(24)
        val second = launch { t.load(other) } // waits for the single permit
        runCurrent()
        second.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        first.join()
        assertEquals(listOf(ref), started)
    }

    @Test
    fun `not_found and unavailable are not asked again, rate_limited is`() = runTest {
        var calls = 0
        var answer: Reply<ServerMessage.CatalogThumb> = Reply.Rejected(ErrorCode.NOT_FOUND)
        val t = Thumbnails(backgroundScope) { calls++; answer }
        assertNull(t.load(ref))
        assertNull(t.load(ref))
        assertEquals(1, calls)

        val other = "C".repeat(24)
        answer = Reply.Rejected(ErrorCode.RATE_LIMITED)
        assertNull(t.load(other))
        answer = Reply.Ok(thumb(byteArrayOf(7)))
        assertArrayEquals(byteArrayOf(7), t.load(other))
        assertEquals(3, calls)

        t.clear()
        assertNull(t.peek(other))
    }

    // ---------- paging ----------

    private fun item(n: Int) = CatalogItem(
        "R".repeat(20) + n.toString().padStart(4, '0'), CatalogItemKind.SONG, "Song $n", "", 1.0, false,
        listOf(CatalogAction.PLAY), null, false,
    )

    private fun page(append: CatalogAppend?, vararg sections: CatalogSection, cont: String? = null) =
        ServerMessage.CatalogPage("c1", "T", null, null, emptyList(), sections.toList(), append, cont)

    private fun sec(title: String?, vararg n: Int) =
        CatalogSection(title, CatalogLayout.LIST, n.map(::item))

    @Test
    fun `continuations join the page by the append rule`() {
        val first = page(null, sec("A", 1), sec(null, 2, 3), cont = "Q".repeat(24))
        val items = CatalogPaging.merge(first, page(CatalogAppend.ITEMS, sec(null, 4, 5), cont = "W".repeat(24)))
        assertEquals(listOf(1), items.sections[0].items.map { it.title.removePrefix("Song ").toInt() })
        assertEquals(listOf(2, 3, 4, 5), items.sections[1].items.map { it.title.removePrefix("Song ").toInt() })
        assertEquals("W".repeat(24), items.continuation)

        val more = CatalogPaging.merge(items, page(CatalogAppend.SECTIONS, sec("B", 6)))
        assertEquals(listOf("A", null, "B"), more.sections.map { it.title })
        assertNull(more.continuation)
        assertFalse(CatalogPaging.canLoadMore(more))

        // An empty continuation of a list page arrives as 'sections' with no sections.
        val empty = CatalogPaging.merge(items, page(CatalogAppend.SECTIONS))
        assertEquals(items.sections, empty.sections)
        assertNull(empty.continuation)

        // A first page replaces whatever was shown.
        val fresh = page(null, sec("Z", 9))
        assertEquals(fresh, CatalogPaging.merge(more, fresh))
    }

    @Test
    fun `loading more stops at 2000 items`() {
        val big = page(null, *Array(20) { s -> sec(null, *IntArray(100) { s * 100 + it }) }, cont = "Q".repeat(24))
        assertEquals(2000, CatalogPaging.itemCount(big))
        assertFalse(CatalogPaging.canLoadMore(big))
    }

    @Test
    fun `items are found again after a reload by position and title`() {
        val p1 = page(null, sec("A", 1, 2))
        val loc = CatalogPaging.locate(p1, item(2).ref)!!
        val reloaded = page(null, sec("A", 1, 2).let { s -> s.copy(items = s.items.map { it.copy(ref = "N" + it.ref.drop(1)) }) })
        assertEquals("N" + item(2).ref.drop(1), CatalogPaging.find(reloaded, loc)?.ref)
        assertNull(CatalogPaging.find(page(null, sec("A", 7)), loc))
    }
}
