package dev.pear.remote.client

import dev.pear.remote.protocol.Limits
import dev.pear.remote.protocol.ProtocolCodec
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

/** Where a desktop listens. [host] is an IP literal (from the QR code, mDNS, or typed by the user). */
data class Endpoint(val host: String, val port: Int = Limits.DEFAULT_PORT) {
    fun url(): String {
        val h = if (host.contains(':')) "[$host]" else host
        return "wss://$h:$port${Limits.WS_PATH}"
    }

    override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

/** Events from one WebSocket, delivered in order through a channel. */
internal sealed interface WsEvent {
    data object Open : WsEvent
    data class Text(val text: String) : WsEvent
    data object Binary : WsEvent
    data class Closed(val code: Int) : WsEvent
    data class Failed(val failure: Failure) : WsEvent
}

/**
 * One WebSocket to the desktop, pinned to a key. Thin wrapper over OkHttp
 * that turns callbacks into a channel so the session can process them on
 * one coroutine.
 */
internal class Transport(
    base: OkHttpClient,
    endpoint: Endpoint,
    trustManager: X509TrustManager,
) {
    val events = Channel<WsEvent>(Channel.UNLIMITED)
    private val socket: WebSocket

    init {
        val client = base.newBuilder()
            .sslSocketFactory(sslSocketFactory(trustManager), trustManager)
            // The SPKI pin replaces hostname verification (SPEC 2.1).
            .hostnameVerifier { _, _ -> true }
            // The desktop is on the LAN; never route it through a proxy.
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(Limits.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder().url(endpoint.url()).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.trySend(WsEvent.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(WsEvent.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.trySend(WsEvent.Binary)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code.takeIf { it in 1000..4999 && it != 1005 && it != 1006 } ?: 1000, null)
                events.trySend(WsEvent.Closed(code))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(WsEvent.Closed(code))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(WsEvent.Failed(classify(t, response)))
            }
        })
    }

    /** Returns false if the socket is closing or the send buffer is full. */
    fun send(text: String): Boolean {
        check(ProtocolCodec.utf8Length(text) <= Limits.CLIENT_MESSAGE_BYTES)
        return socket.send(text)
    }

    fun close(code: Int = 1000) {
        if (!socket.close(code, null)) socket.cancel()
    }

    fun cancel() = socket.cancel()

    private fun classify(t: Throwable, response: Response?): Failure {
        var c: Throwable? = t
        while (c != null) {
            if (c is PinMismatchException) return Failure.IdentityChanged
            c = c.cause
        }
        if (response != null) {
            // Upgrade refused: wrong path or not a Pear desktop.
            return Failure.Unreachable("HTTP ${response.code}")
        }
        if (t is SSLHandshakeException) return Failure.Unreachable("TLS handshake failed")
        // Exception messages may contain addresses but never secrets. Keep
        // only the class name anyway, so nothing unexpected reaches logs.
        return Failure.Unreachable(t.javaClass.simpleName)
    }
}

/** True if [host] is an IP literal (no DNS lookups happen for it). */
fun isIpLiteral(host: String): Boolean =
    dev.pear.remote.protocol.LanAddress.parseIpv4(host) != null || dev.pear.remote.protocol.LanAddress.isIpv6(host)

