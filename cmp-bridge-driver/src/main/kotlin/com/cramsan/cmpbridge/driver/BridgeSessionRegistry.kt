package com.cramsan.cmpbridge.driver

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Caches one [BridgeDriver] per [BridgeTarget], connecting lazily on first use and evicting a
 * session once it's been idle for [maxIdleMs] or alive for [maxSessionMs], whichever comes first
 * — so a caller resolves a target on every request/tool-call instead of a server binding to one
 * app at launch, without paying [WebBridgeDriver.connect]'s real-browser-launch cost on every
 * single call. [connect] and [nowMs] are seams for tests; production code should leave them at
 * their defaults.
 */
class BridgeSessionRegistry(
    private val maxIdleMs: Long,
    private val maxSessionMs: Long,
    private val connect: (BridgeTarget) -> BridgeDriver = ::defaultConnect,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private class Session(val driver: BridgeDriver, val createdAt: Long) {
        @Volatile var lastUsedAt: Long = createdAt
    }

    private val sessions = ConcurrentHashMap<BridgeTarget, Session>()
    private val sweeper: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cmp-bridge-session-sweeper").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(::evictExpired, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

    /**
     * Returns the cached driver for [target], connecting and caching one if this is the first
     * call for it. Concurrent first calls for the same never-seen [target] connect only once.
     */
    fun resolve(target: BridgeTarget): BridgeDriver {
        val now = nowMs()
        val session = sessions.computeIfAbsent(target) { Session(connect(it), now) }
        session.lastUsedAt = now
        return session.driver
    }

    /** Ends [target]'s session early, closing its driver. A no-op if there's no active session for it. */
    fun disconnect(target: BridgeTarget) {
        sessions.remove(target)?.driver?.close()
    }

    /** Closes every cached session's driver and stops the eviction sweep. */
    override fun close() {
        sweeper.shutdownNow()
        sessions.keys.toList().forEach(::disconnect)
    }

    /**
     * Closes and removes any session past [maxIdleMs] since its last use or [maxSessionMs] since
     * it was created. Runs on a fixed-interval background sweep; also callable directly (with an
     * injected [nowMs]) for deterministic tests.
     */
    internal fun evictExpired() {
        val now = nowMs()
        sessions.entries.removeIf { (_, session) ->
            val expired = now - session.lastUsedAt >= maxIdleMs || now - session.createdAt >= maxSessionMs
            if (expired) session.driver.close()
            expired
        }
    }

    companion object {
        private const val SWEEP_INTERVAL_MS = 15_000L

        private fun defaultConnect(target: BridgeTarget): BridgeDriver = when (target.platform) {
            "desktop" -> DesktopBridgeDriver.connect(target.host, target.port)

            "web" -> WebBridgeDriver.connect(
                target.url ?: throw InvalidTargetException("\"url\" is required when platform is \"web\""),
            )

            else -> throw InvalidTargetException("Unknown platform \"${target.platform}\"")
        }
    }
}
