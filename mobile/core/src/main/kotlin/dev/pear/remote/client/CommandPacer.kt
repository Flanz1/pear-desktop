package dev.pear.remote.client

import dev.pear.remote.protocol.Limits
import kotlinx.coroutines.delay

/**
 * A local copy of the desktop's per-connection token bucket (SPEC 8: burst
 * 20, 10 per second). Commands the user triggers always go out at once and
 * just take a token. Background requests (thumbnails) wait until the bucket
 * holds more than [reserve] tokens, so they never use the headroom player
 * commands need, and they never go faster than [backgroundPerSecond].
 *
 * Thread-safe. The model starts full, like the desktop's bucket for a new
 * connection, so it can only be more cautious than the desktop.
 */
class CommandPacer(
    private val clock: Clock,
    private val burst: Int = Limits.COMMAND_BURST,
    private val perSecond: Int = Limits.COMMANDS_PER_SECOND,
    private val reserve: Int = burst / 2,
    backgroundPerSecond: Int = 6,
) {
    private var tokens = burst.toDouble()
    private var last = clock.nanos()
    private var lastBackground = Long.MIN_VALUE
    private val backgroundIntervalNanos = 1_000_000_000L / backgroundPerSecond

    @Synchronized
    private fun refill(now: Long) {
        val elapsed = (now - last).coerceAtLeast(0) / 1e9
        tokens = (tokens + elapsed * perSecond).coerceAtMost(burst.toDouble())
        last = now
    }

    /** A user command: never waits. */
    @Synchronized
    fun take() {
        refill(clock.nanos())
        tokens = (tokens - 1).coerceAtLeast(0.0)
    }

    /** Tokens available now (for tests). */
    @Synchronized
    fun available(): Double {
        refill(clock.nanos())
        return tokens
    }

    /** Returns 0 if a background command may go now (and takes its token), else nanoseconds to wait. */
    @Synchronized
    internal fun tryTakeBackground(): Long {
        val now = clock.nanos()
        refill(now)
        val sinceLast = if (lastBackground == Long.MIN_VALUE) Long.MAX_VALUE else now - lastBackground
        val spacing = (backgroundIntervalNanos - sinceLast).coerceAtLeast(0)
        val need = reserve + 1 - tokens
        val forTokens = if (need <= 0) 0L else (need / perSecond * 1e9).toLong() + 1
        val wait = maxOf(spacing, forTokens)
        if (wait > 0) return wait
        tokens -= 1
        lastBackground = now
        return 0
    }

    /** Waits (cancellably) until a background command may go, then takes its token. */
    suspend fun takeBackground() {
        while (true) {
            val wait = tryTakeBackground()
            if (wait == 0L) return
            delay((wait + 999_999) / 1_000_000)
        }
    }
}
