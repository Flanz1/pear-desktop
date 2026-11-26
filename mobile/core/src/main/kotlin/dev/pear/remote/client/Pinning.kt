package dev.pear.remote.client

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** SHA-256 over the DER SubjectPublicKeyInfo, base64url without padding (43 chars). */
fun spkiFingerprint(cert: X509Certificate): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

/** Thrown from the TLS handshake when the desktop's key doesn't match the pin. */
class PinMismatchException(val actual: String) : CertificateException("certificate pin mismatch")

/**
 * Trusts exactly one key: the leaf certificate's SPKI must match [pin].
 * There is no fallback to system trust (SPEC 2.1). Hostname verification is
 * replaced by the pin, so callers pair this with a hostname verifier that
 * accepts any name.
 */
class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
        // Validity dates are deliberately not checked: the pin is what we
        // trust, the desktop may reissue an expired certificate for the same
        // key (SPEC 2.1), and phone clocks are often wrong.
        val actual = spkiFingerprint(leaf)
        if (!MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) throw PinMismatchException(actual)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * For the manual pairing path only: records the desktop's fingerprint and
 * then fails the handshake on purpose, so nothing is sent over a connection
 * the user hasn't confirmed. The phone shows the short fingerprint, and
 * only after the user confirms it does it connect again with a
 * [PinnedTrustManager] for that fingerprint.
 */
class FingerprintProbeTrustManager : X509TrustManager {
    @Volatile var observed: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
        observed = spkiFingerprint(leaf)
        throw CertificateException("fingerprint probe")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun sslSocketFactory(tm: X509TrustManager): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }.socketFactory
