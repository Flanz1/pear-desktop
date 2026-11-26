package dev.pear.remote.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.pear.remote.client.Endpoint
import dev.pear.remote.client.EndpointResolver
import dev.pear.remote.protocol.Fields
import dev.pear.remote.protocol.LanAddress
import dev.pear.remote.protocol.Limits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

/** A desktop seen on the network. Its `fp` TXT record is never trusted (SPEC 3). */
data class DiscoveredDesktop(val instanceId: String, val name: String, val endpoint: Endpoint, val majors: List<Int>)

/**
 * mDNS discovery of `_pear-remote._tcp` with Android's NsdManager, which
 * needs no extra permission. Runs only while someone holds a [acquire]
 * handle: the pairing screen, or a reconnect looking for a desktop's new
 * address. No background scanning.
 */
class NsdDiscovery(context: Context) : EndpointResolver {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private val _found = MutableStateFlow<Map<String, DiscoveredDesktop>>(emptyMap())
    val found: StateFlow<Map<String, DiscoveredDesktop>> = _found.asStateFlow()

    private var users = 0
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    /** Starts discovery until the returned handle is called. */
    fun acquire(): () -> Unit {
        main.post {
            if (users++ == 0) startDiscovery()
        }
        var released = false
        return {
            main.post {
                if (!released) {
                    released = true
                    if (--users == 0) stopDiscovery()
                }
            }
        }
    }

    override suspend fun resolve(instanceId: String): Endpoint? {
        _found.value[instanceId]?.let { return it.endpoint }
        val release = acquire()
        try {
            return withTimeoutOrNull(4_000) { found.first { instanceId in it } }?.get(instanceId)?.endpoint
        } finally {
            release()
        }
    }

    private fun startDiscovery() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { if (listener === this) listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { enqueueResolve(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    _found.value = _found.value.filterValues { it.name != info.serviceName }
                }
            }
        }
        listener = l
        try {
            nsd.discoverServices("${Limits.MDNS_SERVICE_TYPE}.", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
            listener = null
        }
    }

    private fun stopDiscovery() {
        listener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        listener = null
        resolveQueue.clear()
    }

    // Before API 34, NsdManager resolves one service at a time.
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        if (!resolving) resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = resolveQueue.removeFirstOrNull() ?: run {
            resolving = false
            return
        }
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post { resolveNext() }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        accept(serviceInfo)
                        resolveNext()
                    }
                }
            })
        } catch (_: Exception) {
            resolving = false
        }
    }

    private fun accept(info: NsdServiceInfo) {
        val attrs = info.attributes ?: return
        val id = attrs["id"]?.decodeToString() ?: return
        if (!Fields.isUuid(id)) return
        val majors = attrs["v"]?.decodeToString()?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        val address = pickAddress(info) ?: return
        val port = info.port.takeIf { it in 1024..65535 } ?: return
        val name = info.serviceName.take(Limits.TEXT_MAX)
        _found.value = _found.value + (id to DiscoveredDesktop(id, name, Endpoint(address, port), majors))
    }

    /** Prefer an IPv4 LAN address; IPv6 link-local needs a zone ID we can't put in a URL. */
    @Suppress("DEPRECATION")
    private fun pickAddress(info: NsdServiceInfo): String? {
        val all: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val candidates = all.sortedBy { if (it is Inet4Address) 0 else 1 }
        for (a in candidates) {
            val s = a.hostAddress?.substringBefore('%') ?: continue
            if (s.startsWith("fe80", ignoreCase = true)) continue
            if (LanAddress.isLanAddress(s)) return s
        }
        return null
    }
}
