package dev.pear.remote

import dev.pear.remote.client.ConnectionStatus
import dev.pear.remote.client.CommandResult
import dev.pear.remote.client.Endpoint
import dev.pear.remote.client.Failure
import dev.pear.remote.client.InMemoryCredentialStore
import dev.pear.remote.client.PairingOutcome
import dev.pear.remote.client.ProbeOutcome
import dev.pear.remote.client.RemoteController
import dev.pear.remote.protocol.AppInfo
import dev.pear.remote.protocol.ClosingReason
import dev.pear.remote.protocol.ErrorCode
import dev.pear.remote.protocol.PairDevice
import dev.pear.remote.protocol.PairingPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.BufferedReader
import java.io.File

/**
 * Runs the Kotlin client against the real desktop listener
 * (mobile/interop/desktop-harness.ts, which wraps src/plugins/phone-remote).
 * Opt-in: needs Node 22+ and the repository's node_modules. Enable with
 * `./gradlew :core:test -Pinterop`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DesktopInteropTest {
    private lateinit var proc: Process
    private lateinit var reader: BufferedReader
    private lateinit var endpoint: Endpoint
    private lateinit var fingerprint: String
    private lateinit var instanceId: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = InMemoryCredentialStore()
    private val app = AppInfo("pear-remote-android", "0.1.0-test")
    private val device = PairDevice("Interop Phone", "JVM")
    private lateinit var controller: RemoteController

    @BeforeAll
    fun startDesktop() {
        assumeTrue(System.getProperty("pear.interop") == "true", "interop test not enabled (-Pinterop)")
        val repo = File(System.getProperty("pear.repoRoot"))
        proc = ProcessBuilder("node", "mobile/interop/desktop-harness.ts")
            .directory(repo)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        reader = proc.inputStream.bufferedReader()
        val ready = Json.parseToJsonElement(reader.readLine()).jsonObject
        endpoint = Endpoint("127.0.0.1", ready.int("port"))
        fingerprint = ready.str("fingerprint")
        instanceId = ready.str("instanceId")
        controller = RemoteController(scope, store, app, backoffMs = listOf(200))
    }

    @AfterAll
    fun stopDesktop() {
        scope.cancel()
        if (::proc.isInitialized) proc.destroy()
    }

    private fun cmd(json: String): JsonObject {
        proc.outputStream.write((json + "\n").toByteArray())
        proc.outputStream.flush()
        return Json.parseToJsonElement(reader.readLine()).jsonObject
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.content.toInt()

    private suspend fun <T> StateFlow<T>.await(ms: Long = 10_000, p: (T) -> Boolean): T =
        withTimeout(ms) { first(p) }

    private fun payload(secret: String) =
        PairingPayload(listOf(1), endpoint.host, endpoint.port, fingerprint, secret, instanceId, "Test Desktop")

    @Test @Order(1)
    fun `probe reads the pinned fingerprint without sending anything`() = runBlocking<Unit> {
        val r = controller.probe(endpoint)
        assertEquals(ProbeOutcome.Fingerprint(fingerprint), r)
    }

    @Test @Order(2)
    fun `wrong pin is reported as identity changed`() = runBlocking<Unit> {
        val secret = cmd("""{"cmd":"openPairing"}""").str("secret")
        val bad = PairingPayload(listOf(1), endpoint.host, endpoint.port, "A".repeat(43), secret, instanceId, "x")
        val r = controller.pairWithQr(bad, device)
        assertEquals(PairingOutcome.Failed(Failure.IdentityChanged), r)
    }

    @Test @Order(3)
    fun `wrong code is pairing_invalid, deny is pairing_denied`() = runBlocking<Unit> {
        cmd("""{"cmd":"openPairing"}""")
        val r = controller.pairWithCode(endpoint, fingerprint, "000000", device)
        // A 1-in-a-million chance the window's code is 000000; the harness would then pair.
        assertEquals(PairingOutcome.Failed(Failure.PairingFailed(ErrorCode.PAIRING_INVALID)), r)

        cmd("""{"cmd":"answer","value":false}""")
        val code = cmd("""{"cmd":"openPairing"}""").str("code")
        val denied = controller.pairWithCode(endpoint, fingerprint, code, device)
        assertEquals(PairingOutcome.Failed(Failure.PairingFailed(ErrorCode.PAIRING_DENIED)), denied)
        cmd("""{"cmd":"answer","value":true}""")
    }

    @Test @Order(4)
    fun `QR pairing stores credentials and the desktop records the device`() = runBlocking<Unit> {
        val secret = cmd("""{"cmd":"openPairing"}""").str("secret")
        val r = controller.pairWithQr(payload(secret), device)
        assertTrue(r is PairingOutcome.Paired, "got $r")
        val creds = (r as PairingOutcome.Paired).credentials
        assertEquals(instanceId, creds.instanceId)
        assertEquals(fingerprint, creds.fingerprint)
        assertTrue(!creds.toString().contains(creds.token))
        val devices = cmd("""{"cmd":"devices"}""")["devices"]!!.jsonArray.map { it.jsonObject }
        assertTrue(devices.any { it.str("deviceId") == creds.deviceId && it.str("name") == "Interop Phone" })
    }

    @Test @Order(5)
    fun `hello, state, commands and pushed events`() = runBlocking<Unit> {
        controller.start(instanceId)
        val connected = controller.status.await { it is ConnectionStatus.Connected } as ConnectionStatus.Connected
        assertEquals("Test Desktop", connected.welcome.name)
        val snap = controller.player.await { it != null }!!
        assertEquals("Song", snap.state.track?.title)

        val s = controller.session!!
        assertEquals(CommandResult.Ok, s.togglePlay())
        assertEquals(CommandResult.Ok, s.setVolume(70))
        assertEquals(CommandResult.Ok, s.seek(42.5))
        assertEquals(CommandResult.Ok, s.refreshQueue())
        val q = controller.queue.await { it != null }!!
        assertEquals(1, q.items.size)
        assertEquals(CommandResult.Ok, s.playQueueItem(q.items[0]))
        val stale = s.playQueueItem(q.items[0].copy(videoId = "AAAAAAAAAAA"))
        assertEquals(CommandResult.Rejected(ErrorCode.STALE_QUEUE), stale)

        val executed = cmd("""{"cmd":"executed"}""")["executed"]!!.jsonArray.map { it.jsonObject.str("type") }
        assertEquals(listOf("player.togglePlay", "player.setVolume", "player.seek", "queue.play"), executed)

        cmd(
            """{"cmd":"emit","msg":{"type":"track.changed","track":{"videoId":"dQw4w9WgXcQ","title":"Next song",""" +
                """"artist":"B","album":null,"durationSeconds":100,"thumbnailUrl":null}}}""",
        )
        controller.player.await { it?.state?.track?.title == "Next song" }
        cmd("""{"cmd":"emit","msg":{"type":"volume.changed","volume":{"volume":30,"muted":true}}}""")
        controller.player.await { it?.state?.volume?.muted == true }

        // A requested volume holds against stale echoes of earlier commands,
        // then the desktop's own value wins again.
        fun emitVolume(v: Int) =
            cmd("""{"cmd":"emit","msg":{"type":"volume.changed","volume":{"volume":$v,"muted":false}}}""")
        assertEquals(CommandResult.Ok, s.setVolume(40))
        assertEquals(40, controller.player.value?.state?.volume?.volume)
        emitVolume(35)
        controller.player.await { it?.state?.volume?.muted == false }
        assertEquals(40, controller.player.value?.state?.volume?.volume)
        emitVolume(40)
        emitVolume(55)
        controller.player.await { it?.state?.volume?.volume == 55 }
        assertEquals(CommandResult.Ok, s.setVolume(20))
        delay(1_700)
        emitVolume(25)
        controller.player.await { it?.state?.volume?.volume == 25 }
        Unit
    }

    @Test @Order(6)
    fun `player not loaded gives not_ready`() = runBlocking<Unit> {
        cmd("""{"cmd":"setState","state":null}""")
        assertEquals(CommandResult.Rejected(ErrorCode.NOT_READY), controller.session!!.next())
        Unit
    }

    @Test @Order(7)
    fun `revoking on the desktop removes access on the phone`() = runBlocking<Unit> {
        val creds = store.get(instanceId)!!
        cmd("""{"cmd":"revoke","deviceId":"${creds.deviceId}"}""")
        val st = controller.status.await { it is ConnectionStatus.Stopped } as ConnectionStatus.Stopped
        assertEquals(Failure.Revoked, st.failure)
        assertEquals(null, store.get(instanceId))
    }

    @Test @Order(8)
    fun `desktop disabling the listener is retryable`() = runBlocking<Unit> {
        val secret = cmd("""{"cmd":"openPairing"}""").str("secret")
        assertTrue(controller.pairWithQr(payload(secret), device) is PairingOutcome.Paired)
        controller.start(instanceId)
        controller.status.await { it is ConnectionStatus.Connected }
        cmd("""{"cmd":"stop"}""")
        val st = controller.status.await { it is ConnectionStatus.Retrying } as ConnectionStatus.Retrying
        assertEquals(Failure.DesktopClosed(ClosingReason.DISABLED), st.failure)
        controller.stop()
    }
}
