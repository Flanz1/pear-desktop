package dev.pear.remote

import dev.pear.remote.protocol.Negotiation
import dev.pear.remote.protocol.PairingUri
import dev.pear.remote.protocol.ProtocolCodec
import dev.pear.remote.protocol.ProtocolVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Contract tests against protocol/fixtures (SPEC section 9, "Phone"): the
 * Kotlin decoder accepts every valid entry and rejects every invalid one,
 * and encoding a decoded value gives back the same JSON.
 */
class ContractTest {
    private val dir = File(System.getProperty("pear.fixtures") ?: "../../protocol/fixtures")

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(File(dir, name).readText()).jsonObject

    @Test
    fun `every fixture file is covered`() {
        val covered = setOf(
            "client-command.json", "client-handshake.json", "handshake-envelope.json",
            "negotiation.json", "pairing-uri.json", "server-message.json",
        )
        val present = dir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name }.toSet()
        assertEquals(covered, present, "a fixture file was added or removed; update ContractTest")
    }

    private fun cases(
        file: String,
        decode: (JsonElement) -> Any?,
        encode: ((Any) -> JsonElement)?,
    ): List<DynamicTest> {
        val f = fixture(file)
        val valid = f["valid"]!!.jsonArray.map { it.jsonObject }
        val invalid = f["invalid"]!!.jsonArray.map { it.jsonObject }
        return valid.map { c ->
            DynamicTest.dynamicTest("$file accepts: ${c.name}") {
                val v = c["value"]!!
                assumeFalse(needsMinor1(v), "1.1-only entry; this client speaks 1.0")
                val decoded = decode(v)
                assertNotNull(decoded, "rejected a valid entry")
                if (encode != null) assertJsonEquals(v, encode(decoded!!))
                // And through the string path, as frames arrive.
                assertNotNull(decode(Json.parseToJsonElement(v.toString())))
            }
        } + invalid.map { c ->
            DynamicTest.dynamicTest("$file rejects: ${c.name}") {
                assertNull(decode(c["value"]!!), "accepted an invalid entry")
            }
        }
    }

    /**
     * Valid entries that only exist in sessions negotiated at 1.1 (SPEC 4.1):
     * catalog messages and commands, the three catalog error codes, and
     * `thumb` on Track/QueueItem. This client sends hello at 1.0, so a 1.1
     * desktop never sends these to it and its strict decoder rejects them.
     * Invalid entries still all run, so a 1.0 decoder must reject them too.
     */
    private fun needsMinor1(v: JsonElement): Boolean {
        val o = v as? JsonObject ?: return false
        val type = (o["type"] as? JsonPrimitive)?.content.orEmpty()
        val code = (o["code"] as? JsonPrimitive)?.content
        return type.startsWith("catalog.") ||
            (type == "error" && code in setOf("not_found", "signed_out", "unavailable")) ||
            hasKey(o, "thumb")
    }

    private fun hasKey(e: JsonElement, key: String): Boolean = when (e) {
        is JsonObject -> key in e || e.values.any { hasKey(it, key) }
        is JsonArray -> e.any { hasKey(it, key) }
        else -> false
    }

    private val JsonObject.name get() = this["name"]!!.jsonPrimitive.content

    @TestFactory
    fun serverMessages() = cases("server-message.json", ProtocolCodec::decodeServerMessage) {
        ProtocolCodec.toJson(it as dev.pear.remote.protocol.ServerMessage)
    }

    @TestFactory
    fun clientHandshakes() = cases("client-handshake.json", ProtocolCodec::decodeClientHandshake) {
        Json.parseToJsonElement(ProtocolCodec.encode(it as dev.pear.remote.protocol.ClientHandshake))
    }

    @TestFactory
    fun clientCommands() = cases("client-command.json", ProtocolCodec::decodeClientCommand) {
        Json.parseToJsonElement(ProtocolCodec.encode(it as dev.pear.remote.protocol.ClientCommand))
    }

    @TestFactory
    fun handshakeEnvelope() = cases("handshake-envelope.json", ProtocolCodec::decodeHandshakeEnvelope, null)

    @TestFactory
    fun negotiation(): List<DynamicTest> = fixture("negotiation.json")["cases"]!!.jsonArray.map { it.jsonObject }.map { c ->
        DynamicTest.dynamicTest("negotiation: ${c.name}") {
            val client = c["client"]!!.jsonObject.let {
                ProtocolVersion(it["major"]!!.jsonPrimitive.int, it["minor"]!!.jsonPrimitive.int)
            }
            val supported = c["supported"]!!.jsonObject.mapKeys { it.key.toInt() }.mapValues { it.value.jsonPrimitive.int }
            val expect = c["expect"]!!.jsonObject
            val actual = Negotiation.negotiate(client, supported)
            if (expect["ok"]!!.jsonPrimitive.content == "true") {
                val v = expect["version"]!!.jsonObject
                assertEquals(
                    Negotiation.Ok(ProtocolVersion(v["major"]!!.jsonPrimitive.int, v["minor"]!!.jsonPrimitive.int)),
                    actual,
                )
            } else {
                val s = expect["supported"]!!.jsonObject
                actual as Negotiation.Failed
                assertEquals(expect["code"]!!.jsonPrimitive.content, actual.code.wire)
                assertEquals(s["min"]!!.jsonPrimitive.int, actual.min)
                assertEquals(s["max"]!!.jsonPrimitive.int, actual.max)
            }
        }
    }

    @TestFactory
    fun pairingUris(): List<DynamicTest> {
        val f = fixture("pairing-uri.json")
        return f["valid"]!!.jsonArray.map { it.jsonObject }.flatMap { c ->
            val uri = c["uri"]!!.jsonPrimitive.content
            val p = c["payload"]!!.jsonObject
            listOf(
                DynamicTest.dynamicTest("pairing-uri accepts: ${c.name}") {
                    val parsed = PairingUri.parse(uri)
                    assertNotNull(parsed)
                    parsed!!
                    assertEquals(p["majors"]!!.jsonArray.map { it.jsonPrimitive.int }, parsed.majors)
                    assertEquals(p["host"]!!.jsonPrimitive.content, parsed.host)
                    assertEquals(p["port"]!!.jsonPrimitive.int, parsed.port)
                    assertEquals(p["fingerprint"]!!.jsonPrimitive.content, parsed.fingerprint)
                    assertEquals(p["secret"]!!.jsonPrimitive.content, parsed.secret)
                    assertEquals(p["instanceId"]!!.jsonPrimitive.content, parsed.instanceId)
                    assertEquals(p["name"]!!.jsonPrimitive.content, parsed.name)
                    assertTrue(p.keys == setOf("majors", "host", "port", "fingerprint", "secret", "instanceId", "name"))
                },
                DynamicTest.dynamicTest("pairing-uri round-trips: ${c.name}") {
                    val parsed = PairingUri.parse(uri)!!
                    assertEquals(parsed, PairingUri.parse(PairingUri.format(parsed)))
                },
            )
        } + f["invalid"]!!.jsonArray.map { it.jsonObject }.map { c ->
            DynamicTest.dynamicTest("pairing-uri rejects: ${c.name}") {
                assertNull(PairingUri.parse(c["uri"]!!.jsonPrimitive.content))
            }
        }
    }

    @Test
    fun `short fingerprint matches the spec example format`() {
        // SHA-256("test") starts 9f86d081884c7d65, the example in SPEC 6.1.
        val fp = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest("test".toByteArray()),
        )
        assertEquals("9F86-D081-884C-7D65", PairingUri.shortFingerprint(fp))
    }

    companion object {
        /** JSON equality where 213 and 213.0 are the same number, as in JavaScript. */
        fun assertJsonEquals(expected: JsonElement, actual: JsonElement) {
            assertTrue(same(expected, actual), "expected $expected\nactual   $actual")
        }

        private fun same(a: JsonElement, b: JsonElement): Boolean = when {
            a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
            a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a[it]!!, b[it]!!) }
            a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
            a is JsonPrimitive && b is JsonPrimitive ->
                if (a.isString || b.isString) {
                    a.isString == b.isString && a.content == b.content
                } else {
                    val x = a.content.toDoubleOrNull()
                    val y = b.content.toDoubleOrNull()
                    if (x != null && y != null) x == y else a.content == b.content
                }
            else -> false
        }
    }
}
