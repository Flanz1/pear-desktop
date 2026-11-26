package dev.pear.remote

import dev.pear.remote.client.Failure
import dev.pear.remote.client.PlayerSnapshot
import dev.pear.remote.client.qrCompatibility
import dev.pear.remote.protocol.CloseCodes
import dev.pear.remote.protocol.LanAddress
import dev.pear.remote.protocol.LikeStatus
import dev.pear.remote.protocol.PairingPayload
import dev.pear.remote.protocol.Playback
import dev.pear.remote.protocol.PlaybackStatus
import dev.pear.remote.protocol.PlayerState
import dev.pear.remote.protocol.RepeatMode
import dev.pear.remote.protocol.Track
import dev.pear.remote.protocol.VolumeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientUnitTest {
    private fun state(status: PlaybackStatus, pos: Double, dur: Double? = 100.0) = PlayerState(
        Track("dQw4w9WgXcQ", "t", "a", null, dur, null),
        Playback(status, pos),
        VolumeState(50, false),
        RepeatMode.NONE,
        false,
        LikeStatus.INDIFFERENT,
    )

    @Test
    fun `position advances only while playing and stops at the duration`() {
        val s = 1_000_000_000L
        assertEquals(12.5, PlayerSnapshot(state(PlaybackStatus.PLAYING, 10.0), 0).positionAt(2 * s + s / 2), 1e-9)
        assertEquals(10.0, PlayerSnapshot(state(PlaybackStatus.PAUSED, 10.0), 0).positionAt(5 * s))
        assertEquals(100.0, PlayerSnapshot(state(PlaybackStatus.PLAYING, 99.0), 0).positionAt(5 * s))
        assertEquals(15.0, PlayerSnapshot(state(PlaybackStatus.PLAYING, 10.0, null), 0).positionAt(5 * s), 1e-9)
    }

    @Test
    fun `close codes map to clear states`() {
        assertEquals(Failure.Unauthorized, Failure.fromClose(CloseCodes.UNAUTHORIZED))
        assertEquals(Failure.Revoked, Failure.fromClose(CloseCodes.REVOKED))
        assertEquals(Failure.Replaced, Failure.fromClose(CloseCodes.REPLACED))
        assertEquals(Failure.Incompatible, Failure.fromClose(CloseCodes.VERSION_UNSUPPORTED))
        assertTrue(Failure.fromClose(1006).retryable)
        assertFalse(Failure.Revoked.retryable)
        assertTrue(Failure.Revoked.forgetCredentials)
        assertFalse(Failure.IdentityChanged.forgetCredentials)
    }

    @Test
    fun `LAN address rules match isLanAddress`() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.20", "169.254.1.1", "fd12:3456:789a::1",
            "fe80::1", "::ffff:192.168.0.1").forEach { assertTrue(LanAddress.isLanAddress(it), it) }
        listOf("8.8.8.8", "127.0.0.1", "0.0.0.0", "172.32.0.1", "::1", "2001:db8::1", "192.168.01.1",
            "evil.example.com", "fe80::1%wlan0x:").forEach { assertFalse(LanAddress.isValidHost(it), it) }
        assertFalse(LanAddress.isValidHost("fe80::1%wlan0"))
    }

    @Test
    fun `QR version list is checked before connecting`() {
        fun p(vararg m: Int) = PairingPayload(m.toList(), "192.168.1.2", 26539, "A".repeat(43), "B".repeat(22),
            "5f0c6d1e-9a2b-4c3d-8e7f-6a5b4c3d2e1f", "x")
        assertNull(qrCompatibility(p(1, 2)))
        assertEquals(Failure.UpdateApp(2, 3), qrCompatibility(p(2, 3)))
        assertEquals(Failure.UpdateDesktop(0, 0), qrCompatibility(p(0)))
    }

    @Test
    fun `secrets are redacted from toString`() {
        val p = PairingPayload(listOf(1), "192.168.1.2", 26539, "A".repeat(43), "SECRETSECRETSECRETSECR",
            "5f0c6d1e-9a2b-4c3d-8e7f-6a5b4c3d2e1f", "x")
        assertFalse(p.toString().contains("SECRETSECRET"))
    }
}
