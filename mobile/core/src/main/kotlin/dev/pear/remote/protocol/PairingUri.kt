package dev.pear.remote.protocol

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Contents of the pairing QR code (PairingPayloadSchema). */
class PairingPayload(
    val majors: List<Int>,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val secret: String,
    val instanceId: String,
    val name: String,
) {
    override fun toString() =
        "PairingPayload(majors=$majors, host=$host, port=$port, fingerprint=$fingerprint, secret=<redacted>, " +
            "instanceId=$instanceId, name=$name)"

    override fun equals(other: Any?) = other is PairingPayload && majors == other.majors && host == other.host &&
        port == other.port && fingerprint == other.fingerprint && secret == other.secret &&
        instanceId == other.instanceId && name == other.name

    override fun hashCode() = instanceId.hashCode()
}

/** parsePairingUri / formatPairingUri / shortFingerprint from protocol/src/pairing.ts. */
object PairingUri {
    const val PREFIX = "pear-remote://pair?"
    private val KEYS = listOf("v", "h", "p", "fp", "s", "i", "n")
    private val MAJORS = Regex("^[0-9]{1,3}(,[0-9]{1,3}){0,7}$")
    private val PORT = Regex("^[0-9]{1,5}$")

    fun parse(uri: String): PairingPayload? {
        if (uri.length > 1024 || !uri.startsWith(PREFIX)) return null
        val params = parseQuery(uri.substring(PREFIX.length))
        val seen = params.map { it.first }
        if (seen.size != KEYS.size || seen.toSet().size != seen.size || !seen.all { it in KEYS }) return null
        val map = params.toMap()
        val v = map.getValue("v")
        val p = map.getValue("p")
        if (!MAJORS.matches(v) || !PORT.matches(p)) return null

        val majors = v.split(',').map { it.toInt() }
        val port = p.toInt()
        val host = map.getValue("h")
        val fingerprint = map.getValue("fp")
        val secret = map.getValue("s")
        val instanceId = map.getValue("i")
        val name = map.getValue("n")
        if (majors.any { it !in 0..999 } || majors.isEmpty() || majors.size > 8) return null
        if (!LanAddress.isValidHost(host)) return null
        if (port !in 1024..65535) return null
        if (!Fields.isSpkiFingerprint(fingerprint) || !Fields.isPairingSecret(secret)) return null
        if (!Fields.isUuid(instanceId) || !Fields.isText(name)) return null
        return PairingPayload(majors, host, port, fingerprint, secret, instanceId, name)
    }

    fun format(p: PairingPayload): String {
        val pairs = listOf(
            "v" to p.majors.joinToString(","),
            "h" to p.host,
            "p" to p.port.toString(),
            "fp" to p.fingerprint,
            "s" to p.secret,
            "i" to p.instanceId,
            "n" to p.name,
        )
        return PREFIX + pairs.joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v)}" }
    }

    /** "9F86-D081-884C-7D65": the first 8 bytes of the SPKI SHA-256 in hex, grouped by four. */
    fun shortFingerprint(fingerprint: String): String {
        val bytes = Base64.getUrlDecoder().decode(fingerprint)
        val hex = bytes.take(8).joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString("-")
    }

    /** application/x-www-form-urlencoded parsing, as URLSearchParams does it. */
    internal fun parseQuery(q: String): List<Pair<String, String>> =
        q.split('&').filter { it.isNotEmpty() }.map { seq ->
            val eq = seq.indexOf('=')
            if (eq < 0) formDecode(seq) to "" else formDecode(seq.substring(0, eq)) to formDecode(seq.substring(eq + 1))
        }

    private fun formDecode(s: String): String {
        val bytes = s.replace('+', ' ').encodeToByteArray()
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '%'.code.toByte() && i + 2 < bytes.size &&
                hex(bytes[i + 1]) >= 0 && hex(bytes[i + 2]) >= 0
            ) {
                out.write(hex(bytes[i + 1]) * 16 + hex(bytes[i + 2]))
                i += 3
            } else {
                out.write(b.toInt())
                i++
            }
        }
        // Invalid UTF-8 becomes U+FFFD, like the WHATWG decoder.
        return out.toByteArray().decodeToString()
    }

    private fun hex(b: Byte): Int = when (b.toInt().toChar()) {
        in '0'..'9' -> b - '0'.code.toByte()
        in 'a'..'f' -> b - 'a'.code.toByte() + 10
        in 'A'..'F' -> b - 'A'.code.toByte() + 10
        else -> -1
    }

    /** The WHATWG urlencoded serializer: unreserved *-._ kept, space as +, rest percent-encoded. */
    private fun formEncode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            when {
                c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                    c == '*'.code || c == '-'.code || c == '.'.code || c == '_'.code -> append(c.toChar())
                c == ' '.code -> append('+')
                else -> append('%').append("%02X".format(c))
            }
        }
    }
}
