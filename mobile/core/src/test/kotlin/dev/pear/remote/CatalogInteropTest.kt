package dev.pear.remote

import dev.pear.remote.client.CatalogSupport
import dev.pear.remote.client.CommandResult
import dev.pear.remote.client.ConnectionStatus
import dev.pear.remote.client.Endpoint
import dev.pear.remote.client.InMemoryCredentialStore
import dev.pear.remote.client.PairingOutcome
import dev.pear.remote.client.RemoteController
import dev.pear.remote.client.RemoteSession
import dev.pear.remote.client.Reply
import dev.pear.remote.protocol.AppInfo
import dev.pear.remote.protocol.BrowseTarget
import dev.pear.remote.protocol.CatalogAction
import dev.pear.remote.protocol.ErrorCode
import dev.pear.remote.protocol.PairDevice
import dev.pear.remote.protocol.PairingPayload
import dev.pear.remote.protocol.ProtocolVersion
import dev.pear.remote.protocol.SearchFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.File

/**
 * Catalog (1.1) against the real desktop listener and catalog service
 * (mobile/interop/desktop-harness.ts --catalog, which has a synthetic page
 * bridge instead of YouTube). Covers gating against 1.0 sessions and
 * desktops without the catalog, the answers' shapes, not_found, not_ready,
 * and thumbnails (also while the player isn't loaded). Opt-in with -Pinterop.
 */
class CatalogInteropTest {
    private class Desktop(catalog: Boolean) {
        val proc: Process
        val reader: BufferedReader
        val endpoint: Endpoint
        val fingerprint: String
        val instanceId: String

        init {
            val repo = File(System.getProperty("pear.repoRoot"))
            val args = listOfNotNull("node", "mobile/interop/desktop-harness.ts", "--catalog".takeIf { catalog })
            proc = ProcessBuilder(args).directory(repo).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            reader = proc.inputStream.bufferedReader()
            val ready = Json.parseToJsonElement(reader.readLine()).jsonObject
            endpoint = Endpoint("127.0.0.1", ready.str("port").toInt())
            fingerprint = ready.str("fingerprint")
            instanceId = ready.str("instanceId")
        }

        fun cmd(json: String): JsonObject {
            proc.outputStream.write((json + "\n").toByteArray())
            proc.outputStream.flush()
            return Json.parseToJsonElement(reader.readLine()).jsonObject
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val desktops = mutableListOf<Desktop>()

    @BeforeEach
    fun enabled() {
        assumeTrue(System.getProperty("pear.interop") == "true", "interop test not enabled (-Pinterop)")
    }

    @AfterEach
    fun stop() {
        scope.cancel()
        desktops.forEach { it.proc.destroy() }
    }

    private suspend fun connect(catalog: Boolean, hello: ProtocolVersion = ProtocolVersion.CURRENT):
        Triple<Desktop, RemoteController, RemoteSession> {
        val d = Desktop(catalog).also { desktops += it }
        val c = RemoteController(scope, InMemoryCredentialStore(), AppInfo("pear-remote-android", "0.2.0-test"), backoffMs = listOf(200))
        c.helloVersion = hello
        val secret = d.cmd("""{"cmd":"openPairing"}""").str("secret")
        val payload = PairingPayload(listOf(1), d.endpoint.host, d.endpoint.port, d.fingerprint, secret, d.instanceId, "Test Desktop")
        assertTrue(c.pairWithQr(payload, PairDevice("Interop Phone", "JVM")) is PairingOutcome.Paired)
        c.start(d.instanceId)
        withTimeout(10_000) { c.status.first { it is ConnectionStatus.Connected } }
        withTimeout(10_000) { c.player.first { it != null } }
        return Triple(d, c, c.session!!)
    }

    @Test
    fun `1_1 session with catalog - browse, search, suggest, play, open errors, thumbnails`() = runBlocking<Unit> {
        val (d, c, s) = connect(catalog = true)
        assertEquals(ProtocolVersion(1, 1), s.welcome!!.protocol)
        assertEquals(CatalogSupport(browse = true, search = true, thumbnails = true), s.catalog)

        val trackThumb = c.player.value!!.state.track!!.thumb
        assertNotNull(trackThumb, "1.1 session with thumbnails: Track.thumb is a ref")
        assertEquals(24, trackThumb!!.length)

        val home = (s.browse(BrowseTarget.HOME) as Reply.Ok).value
        assertEquals(listOf("Listen again", "Mixed for you"), home.sections.map { it.title })
        assertEquals(listOf("Relax"), home.chips.map { it.title })
        assertNull(home.append)
        val song = home.sections[0].items[0]
        assertEquals(listOf(CatalogAction.PLAY, CatalogAction.PLAY_NEXT, CatalogAction.ADD_TO_QUEUE), song.actions)
        assertTrue(song.isCurrent, "the fake player plays this videoId")
        val mix = home.sections[1].items[0]
        assertFalse(mix.isCurrent)
        assertEquals(24, song.ref.length)

        assertEquals(Reply.Ok(Unit), s.catalogPlay(song.ref, CatalogAction.PLAY))
        assertEquals(Reply.Ok(Unit), s.catalogPlay(song.ref, CatalogAction.ADD_TO_QUEUE))
        // An action the item doesn't offer.
        assertEquals(Reply.Rejected(ErrorCode.NOT_FOUND), s.catalogPlay(song.ref, CatalogAction.SHUFFLE))
        // A well-formed ref the desktop never issued.
        assertEquals(Reply.Rejected(ErrorCode.NOT_FOUND), s.open("A".repeat(24)))

        val search = (s.search("  synthetic  ", SearchFilter.TOP) as Reply.Ok).value
        assertEquals("synthetic", search.title)
        assertEquals(Reply.Ok(listOf("syn one")), s.suggest("syn"))

        val thumb = (s.thumb(song.thumb!!) as Reply.Ok).value
        assertEquals(0x89.toByte(), thumb.bytes()[0])
        assertNotNull(c.thumbnails.load(trackThumb))
        assertNull(mix.thumb)

        // Player not loaded: catalog commands get not_ready, covers still work.
        d.cmd("""{"cmd":"setState","state":null}""")
        assertEquals(Reply.Rejected(ErrorCode.NOT_READY), s.browse(BrowseTarget.HOME))
        assertTrue(s.thumb(song.thumb!!) is Reply.Ok)

        val ops = d.cmd("""{"cmd":"catalogRequests"}""")["ops"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("browse", "play", "play", "search", "suggest"), ops)
        assertTrue(c.status.value is ConnectionStatus.Connected)
        c.stop()
    }

    @Test
    fun `1_0 session - catalog is never sent, the connection stays up`() = runBlocking<Unit> {
        val (d, c, s) = connect(catalog = true, hello = ProtocolVersion(1, 0))
        assertEquals(ProtocolVersion(1, 0), s.welcome!!.protocol)
        assertEquals(emptyList<String>(), s.welcome!!.capabilities)
        assertEquals(CatalogSupport.NONE, s.catalog)
        assertFalse(c.player.value!!.state.track!!.thumbPresent, "1.0 sessions carry no thumb key")
        // More than the 3 invalid messages that would close a 1.0 connection.
        repeat(5) {
            assertEquals(Reply.NotAvailable, s.browse(BrowseTarget.HOME))
            assertEquals(Reply.NotAvailable, s.thumb("A".repeat(24)))
        }
        assertEquals(CommandResult.Ok, s.togglePlay())
        assertTrue(c.status.value is ConnectionStatus.Connected)
        val ops = d.cmd("""{"cmd":"catalogRequests"}""")["ops"]!!.jsonArray
        assertTrue(ops.isEmpty())
        c.stop()
    }

    @Test
    fun `desktop without the catalog - 1_1 negotiated, no capabilities, nothing sent`() = runBlocking<Unit> {
        val (_, c, s) = connect(catalog = false)
        assertEquals(ProtocolVersion(1, 1), s.welcome!!.protocol)
        assertEquals(CatalogSupport.NONE, s.catalog)
        assertNull(c.player.value!!.state.track!!.thumb)
        assertEquals(Reply.NotAvailable, s.search("x", SearchFilter.SONGS))
        assertEquals(Reply.NotAvailable, s.catalogPlay("A".repeat(24), CatalogAction.PLAY))
        assertNull(c.thumbnails.load("A".repeat(24)))
        assertEquals(CommandResult.Ok, s.next())
        c.stop()
    }

    private companion object {
        fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    }
}
