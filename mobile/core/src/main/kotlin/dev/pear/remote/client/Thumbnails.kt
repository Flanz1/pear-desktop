package dev.pear.remote.client

import dev.pear.remote.protocol.ErrorCode
import dev.pear.remote.protocol.Limits
import dev.pear.remote.protocol.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Cover thumbnails fetched with catalog.thumb over the paired connection
 * (SPEC 10.6). Nothing here touches the network itself: [fetch] goes
 * through the session, which paces thumbnail requests under the rate limit.
 *
 * - Concurrent loads of one ref share one request.
 * - At most [maxInFlight] requests are outstanding.
 * - When every caller waiting for a ref goes away (the row scrolled off),
 *   its request is cancelled, so only covers on screen are fetched.
 * - Image bytes are kept in an LRU by ref. Refs the desktop answered with
 *   not_found or unavailable aren't asked for again.
 */
class Thumbnails(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 400,
    private val maxInFlight: Int = 3,
    private val fetch: suspend (ref: String) -> Reply<ServerMessage.CatalogThumb>,
) {
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > maxEntries
    }
    private val failed = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > maxEntries
    }
    private val inFlight = HashMap<String, Pending>()
    private val permits = Semaphore(maxInFlight)
    private var generation = 0

    private class Pending(val deferred: Deferred<ByteArray?>) {
        var waiters = 0
    }

    /** Cached bytes, without fetching. */
    fun peek(ref: String): ByteArray? = synchronized(lock) { cache[ref] }

    /**
     * The image bytes for [ref] (JPEG, WebP or PNG, at most 24 KiB), or null
     * if there is none or it couldn't be fetched now. Cancellable.
     */
    suspend fun load(ref: String): ByteArray? {
        val p = synchronized(lock) {
            cache[ref]?.let { return it }
            if (failed.containsKey(ref)) return null
            val gen = generation
            inFlight.getOrPut(ref) {
                Pending(scope.async(start = CoroutineStart.LAZY) { fetchOne(ref, gen) })
            }.also { it.waiters++ }
        }
        try {
            p.deferred.start()
            return p.deferred.await()
        } finally {
            synchronized(lock) {
                p.waiters--
                if (p.waiters == 0 && !p.deferred.isCompleted) {
                    p.deferred.cancel()
                    if (inFlight[ref] === p) inFlight.remove(ref)
                }
            }
        }
    }

    private suspend fun fetchOne(ref: String, gen: Int): ByteArray? {
        try {
            val r = permits.withPermit { fetch(ref) }
            val bytes = when (r) {
                is Reply.Ok -> runCatching { r.value.bytes() }.getOrNull()?.takeIf { it.size <= Limits.THUMB_MAX_BYTES }
                is Reply.Rejected -> {
                    if (r.code == ErrorCode.NOT_FOUND || r.code == ErrorCode.UNAVAILABLE) markFailed(ref, gen)
                    null
                }
                // Rate limited, disconnected or not offered: try again next time the row shows.
                Reply.NotDelivered, Reply.NotAvailable -> null
            }
            if (bytes != null) synchronized(lock) { if (gen == generation) cache[ref] = bytes }
            return bytes
        } finally {
            synchronized(lock) { inFlight.remove(ref) }
        }
    }

    private fun markFailed(ref: String, gen: Int) = synchronized(lock) {
        if (gen == generation) failed[ref] = Unit
    }

    /** The bytes didn't decode (or were too large in pixels): don't fetch this ref again. */
    fun reportBad(ref: String) = synchronized(lock) {
        cache.remove(ref)
        failed[ref] = Unit
    }

    /** Forget everything, e.g. when switching desktops (refs are per desktop). */
    fun clear() = synchronized(lock) {
        generation++
        cache.clear()
        failed.clear()
        inFlight.values.forEach { it.deferred.cancel() }
        inFlight.clear()
    }
}
