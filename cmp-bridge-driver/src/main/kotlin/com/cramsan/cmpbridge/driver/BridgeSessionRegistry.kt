// Deferred.getCompleted() (used below on already-isCompleted-checked entries) is marked
// experimental by kotlinx.coroutines, not because it's unstable, but because misuse (calling it
// on an incomplete Deferred) throws — every call site here guards with isCompleted first.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.cramsan.cmpbridge.driver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Caches one [BridgeDriver] per [BridgeTarget], connecting lazily on first use and evicting a
 * session once it's been idle for [maxIdle] or alive for [maxSession], whichever comes first —
 * so a caller resolves a target on every request/tool-call instead of a server binding to one
 * app at launch, without paying [WebBridgeDriver.connect]'s real-browser-launch cost on every
 * single call. `driverDefaultTimeout`/`driverPollInterval` become every connected driver's own
 * [BridgeDriver.defaultTimeout]/[BridgeDriver.pollInterval] — process-wide, not per-target, so
 * they can't fragment [BridgeTarget]'s session cache. [connect] and [nowMs] are seams for tests;
 * production code should leave them at their defaults.
 */
class BridgeSessionRegistry(
    private val maxIdle: Duration,
    private val maxSession: Duration,
    driverDefaultTimeout: Duration = BridgeDriver.DEFAULT_TIMEOUT,
    driverPollInterval: Duration = BridgeDriver.DEFAULT_POLL_INTERVAL,
    private val connect: suspend (BridgeTarget) -> BridgeDriver = {
        defaultConnect(it, driverDefaultTimeout, driverPollInterval)
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private class Session(val driver: BridgeDriver, val createdAt: Long) {
        @Volatile var lastUsedAt: Long = createdAt
    }

    // Deferred (not a plain Session) because resolve() needs to dedupe concurrent first-time
    // connects for the same never-seen target without calling the now-suspend `connect` from
    // inside a non-suspend-capable ConcurrentHashMap.computeIfAbsent lambda — see resolve().
    private val sessions = ConcurrentHashMap<BridgeTarget, CompletableDeferred<Session>>()
    private val sweeper: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cmp-bridge-session-sweeper").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(::evictExpired, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }

    /**
     * Returns the cached driver for [target], connecting and caching one if this is the first
     * call for it. Concurrent first calls for the same never-seen [target] connect only once —
     * every racer but one loses the atomic [ConcurrentHashMap.putIfAbsent] below and instead
     * awaits the winner's [CompletableDeferred]. A failed connect removes its own (and only its
     * own — the conditional [ConcurrentHashMap.remove] guards against a subsequent successful
     * reconnect for the same target) entry rather than poisoning the cache.
     */
    // Deliberate catch-all, including CancellationException: whatever connect(target) throws
    // (a driver exception, or the calling coroutine being cancelled mid-connect) must still clean
    // up this target's map entry and unblock any concurrent waiter before propagating unchanged.
    @Suppress("TooGenericExceptionCaught")
    suspend fun resolve(target: BridgeTarget): BridgeDriver {
        while (true) {
            sessions[target]?.let { existing ->
                val session = existing.await()
                session.lastUsedAt = nowMs()
                return session.driver
            }
            val deferred = CompletableDeferred<Session>()
            if (sessions.putIfAbsent(target, deferred) != null) continue
            try {
                val startedAt = nowMs()
                val session = Session(connect(target), startedAt)
                deferred.complete(session)
                return session.driver
            } catch (e: Throwable) {
                sessions.remove(target, deferred)
                deferred.completeExceptionally(e)
                throw e
            }
        }
    }

    /** Ends [target]'s session early, closing its driver. A no-op if there's no active session for it. */
    fun disconnect(target: BridgeTarget) {
        val deferred = sessions.remove(target) ?: return
        // A target whose connect is still in flight is left to finish on its own — its driver
        // never gets tracked/closed by this call, an accepted edge case (see class doc).
        if (deferred.isCompleted) deferred.getCompleted().driver.close()
    }

    /** Closes every cached session's driver and stops the eviction sweep. */
    override fun close() {
        sweeper.shutdownNow()
        sessions.keys.toList().forEach(::disconnect)
    }

    /**
     * Closes and removes any session past [maxIdle] since its last use or [maxSession] since it
     * was created. Runs on a fixed-interval background sweep; also callable directly (with an
     * injected [nowMs]) for deterministic tests. Skips any session whose connect is still in
     * flight — nothing to judge the age of yet.
     */
    internal fun evictExpired() {
        val now = nowMs()
        sessions.entries.removeIf { (_, deferred) ->
            if (!deferred.isCompleted) return@removeIf false
            val session = deferred.getCompleted()
            val idleFor = (now - session.lastUsedAt).milliseconds
            val ageFor = (now - session.createdAt).milliseconds
            val expired = idleFor >= maxIdle || ageFor >= maxSession
            if (expired) session.driver.close()
            expired
        }
    }

    companion object {
        private val SWEEP_INTERVAL_MS = 15.seconds.inWholeMilliseconds

        private suspend fun defaultConnect(
            target: BridgeTarget,
            defaultTimeout: Duration,
            pollInterval: Duration,
        ): BridgeDriver = when (target.platform) {
            "desktop" -> DesktopBridgeDriver.connect(target.host, target.port, defaultTimeout, pollInterval)

            "web" -> WebBridgeDriver.connect(
                target.url ?: throw InvalidTargetException("\"url\" is required when platform is \"web\""),
                defaultTimeout,
                pollInterval,
            )

            else -> throw InvalidTargetException("Unknown platform \"${target.platform}\"")
        }
    }
}
