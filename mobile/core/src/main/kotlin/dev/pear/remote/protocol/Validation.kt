package dev.pear.remote.protocol

import java.net.URI

/**
 * Field constraints from protocol/src/common.ts and pairing.ts. Each one
 * mirrors a zod schema exactly, because both sides validate strictly.
 */
object Fields {
    private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    private val SPKI = Regex("^[A-Za-z0-9_-]{43}$")
    private val SECRET = Regex("^[A-Za-z0-9_-]{22}$")
    private val CODE = Regex("^[0-9]{6}$")
    private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val APP_NAME = Regex("^[a-z0-9-]{1,64}$")
    private val APP_VERSION = Regex("^[0-9A-Za-z.+-]{1,32}$")
    private val CAPABILITY = Regex("^[a-z0-9.-]{1,64}$")

    // zod 4 z.uuid(): RFC 9562 variant and version 1-8, plus the nil and max UUIDs.
    private val UUID = Regex(
        "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}" +
            "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$",
    )

    fun isToken(s: String) = TOKEN.matches(s)
    fun isSpkiFingerprint(s: String) = SPKI.matches(s)
    fun isPairingSecret(s: String) = SECRET.matches(s)
    fun isPairingCode(s: String) = CODE.matches(s)
    fun isRequestId(s: String) = REQUEST_ID.matches(s)
    fun isVideoId(s: String) = VIDEO_ID.matches(s)
    fun isUuid(s: String) = UUID.matches(s)
    fun isAppName(s: String) = APP_NAME.matches(s)
    fun isAppVersion(s: String) = APP_VERSION.matches(s)
    fun isCapability(s: String) = CAPABILITY.matches(s)

    /** TextSchema: at most 500 UTF-16 code units, like JavaScript's length. */
    fun isText(s: String) = s.length <= Limits.TEXT_MAX

    /**
     * DeviceNameSchema trims, then requires 1 to 64 characters. Returns the
     * trimmed value, or null if invalid. Uses JavaScript's definition of
     * whitespace for trim().
     */
    fun deviceName(s: String): String? {
        val t = s.trim { isJsWhitespace(it) }
        return if (t.length in 1..Limits.DEVICE_NAME_MAX) t else null
    }

    private fun isJsWhitespace(c: Char): Boolean =
        c == '\u0009' || c == '\u000B' || c == '\u000C' || c == ' ' || c == ' ' ||
            c == '﻿' || c == '\n' || c == '\r' || c == ' ' || c == ' ' ||
            Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /**
     * HttpsUrlSchema: at most 2048 chars, starts with https://, parses as an
     * absolute URL with a host. The phone only ever loads https images.
     */
    fun isHttpsUrl(s: String): Boolean {
        if (s.length > 2048) return false
        val trimmed = s.trim()
        if (!trimmed.regionMatches(0, "https://", 0, 8, ignoreCase = true)) return false
        if (trimmed.any { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' }) return false
        return try {
            val uri = URI(trimmed.replace(Regex("[\\t\\n\\r]"), ""))
            uri.scheme.equals("https", ignoreCase = true) && !uri.rawAuthority.isNullOrEmpty()
        } catch (_: Exception) {
            // java.net.URI is stricter than WHATWG URL for some unusual but
            // legal characters. Rejecting those only hides a thumbnail.
            false
        }
    }
}

/**
 * isLanAddress from protocol/src/pairing.ts: RFC 1918, IPv4 link-local,
 * IPv6 unique-local and link-local. IPv4-mapped IPv6 is checked as IPv4.
 * Loopback, unspecified and public addresses are refused.
 */
object LanAddress {
    private val CHARSET = Regex("^[0-9A-Fa-f.:]+$")

    /** LanAddressSchema: an IP literal with no zone ID that is a LAN address. */
    fun isValidHost(s: String): Boolean = s.length <= 64 && CHARSET.matches(s) && isLanAddress(s)

    fun isLanAddress(address: String): Boolean {
        val lower = address.lowercase()
        val a = Regex("^::ffff:(?=\\d+\\.)").replace(lower, "")
        val noZone = Regex("%[0-9a-z._-]+$").replace(a, "")
        parseIpv4(noZone)?.let { (p0, p1) ->
            return p0 == 10 ||
                (p0 == 172 && p1 in 16..31) ||
                (p0 == 192 && p1 == 168) ||
                (p0 == 169 && p1 == 254)
        }
        if (isIpv6(noZone)) {
            return Regex("^f[cd][0-9a-f]{2}:").containsMatchIn(a) ||
                Regex("^fe[89ab][0-9a-f]:").containsMatchIn(a)
        }
        return false
    }

    /** Strict dotted-decimal IPv4 as Node's isIP accepts it (no leading zeros). */
    fun parseIpv4(s: String): Pair<Int, Int>? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { p ->
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            p.toInt().takeIf { it <= 255 } ?: return null
        }
        return nums[0] to nums[1]
    }

    /** IPv6 literal (RFC 4291 text form, optional trailing dotted IPv4), no zone. */
    fun isIpv6(s: String): Boolean {
        if (s.isEmpty() || s.length > 45) return false
        if (!s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return false
        val doubleColon = s.indexOf("::")
        if (doubleColon >= 0 && s.indexOf("::", doubleColon + 1) >= 0) return false
        if (s.contains(":::")) return false

        fun groups(part: String): List<String>? =
            if (part.isEmpty()) emptyList() else part.split(':').takeIf { gs -> gs.none { it.isEmpty() } }

        val head: List<String>
        val tail: List<String>
        if (doubleColon >= 0) {
            head = groups(s.substring(0, doubleColon)) ?: return false
            tail = groups(s.substring(doubleColon + 2)) ?: return false
        } else {
            head = groups(s) ?: return false
            tail = emptyList()
        }
        val all = head + tail
        var units = 0
        all.forEachIndexed { i, g ->
            if (g.contains('.')) {
                if (i != all.lastIndex || parseIpv4(g) == null) return false
                units += 2
            } else {
                if (g.length > 4) return false
                units += 1
            }
        }
        return if (doubleColon >= 0) units <= 7 else units == 8
    }
}
