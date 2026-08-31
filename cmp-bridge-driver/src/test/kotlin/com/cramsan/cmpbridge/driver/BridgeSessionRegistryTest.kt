package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.HierarchyNode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val TEST_LIMIT: Duration = 60_000.milliseconds
private val ANY_NODE =
    HierarchyNode(
        testTag = null,
        role = null,
        text = null,
        contentDescription = null,
        x = 0f,
        y = 0f,
        width = 0f,
        height = 0f,
        enabled = true,
        actions = emptySet(),
        children = emptyList(),
    )

private class FakeBridgeDriver : BridgeDriver {
    var closed = false

    override fun getHierarchy(): HierarchyNode = ANY_NODE
    override fun click(tag: String) = Unit
    override fun setText(tag: String, text: String) = Unit
    override fun scroll(anchorTag: String, deltaY: Int) = Unit
    override fun screenshot(): ByteArray = byteArrayOf()
    override fun close() {
        closed = true
    }
}

private val DESKTOP_TARGET = BridgeTarget(platform = "desktop")
private val OTHER_DESKTOP_TARGET = BridgeTarget(platform = "desktop", port = 9000)

class BridgeSessionRegistryTest {
    @Test
    fun `resolve reuses the same driver for repeated calls on the same target`() = runTest {
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdle = TEST_LIMIT,
            maxSession = TEST_LIMIT,
            connect = {
                connectCount.incrementAndGet()
                FakeBridgeDriver()
            },
        )

        val first = registry.resolve(DESKTOP_TARGET)
        val second = registry.resolve(DESKTOP_TARGET)

        assertSame(first, second)
        assertEquals(1, connectCount.get())
    }

    @Test
    fun `resolve connects a separate driver per distinct target`() = runTest {
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdle = TEST_LIMIT,
            maxSession = TEST_LIMIT,
            connect = {
                connectCount.incrementAndGet()
                FakeBridgeDriver()
            },
        )

        val a = registry.resolve(DESKTOP_TARGET)
        val b = registry.resolve(OTHER_DESKTOP_TARGET)

        assertTrue(a !== b)
        assertEquals(2, connectCount.get())
    }

    @Test
    fun `concurrent first resolves for the same never-seen target connect only once`() {
        // Real OS threads racing into a suspend resolve(), not virtual time — runBlocking per
        // pooled task, not runTest, which isn't meant to arbitrate real thread-level concurrency.
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdle = TEST_LIMIT,
            maxSession = TEST_LIMIT,
            connect = {
                connectCount.incrementAndGet()
                Thread.sleep(50)
                FakeBridgeDriver()
            },
        )
        val threadCount = 8
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threadCount)

        val drivers = (1..threadCount).map {
            pool.submit<BridgeDriver> {
                ready.countDown()
                start.await()
                runBlocking { registry.resolve(DESKTOP_TARGET) }
            }
        }
        ready.await()
        start.countDown()
        val results = drivers.map { it.get() }
        pool.shutdown()

        assertEquals(1, connectCount.get())
        assertTrue(results.all { it === results.first() })
    }

    @Test
    fun `evictExpired closes and removes a session idle past maxIdle`() = runTest {
        val clock = AtomicLong(0)
        val driver = FakeBridgeDriver()
        val registry = BridgeSessionRegistry(
            maxIdle = 1_000.milliseconds,
            maxSession = TEST_LIMIT,
            connect = { driver },
            nowMs = clock::get,
        )
        registry.resolve(DESKTOP_TARGET)

        clock.set(1_001L)
        registry.evictExpired()

        assertTrue(driver.closed)
    }

    @Test
    fun `resolve after idle eviction reconnects`() = runTest {
        val clock = AtomicLong(0)
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdle = 1_000.milliseconds,
            maxSession = TEST_LIMIT,
            connect = {
                connectCount.incrementAndGet()
                FakeBridgeDriver()
            },
            nowMs = clock::get,
        )

        registry.resolve(DESKTOP_TARGET)
        clock.set(1_001L)
        registry.evictExpired()
        registry.resolve(DESKTOP_TARGET)

        assertEquals(2, connectCount.get())
    }

    @Test
    fun `evictExpired closes a session past maxSession even if recently used`() = runTest {
        val clock = AtomicLong(0)
        val driver = FakeBridgeDriver()
        val registry = BridgeSessionRegistry(
            maxIdle = TEST_LIMIT,
            maxSession = 1_000.milliseconds,
            connect = { driver },
            nowMs = clock::get,
        )
        registry.resolve(DESKTOP_TARGET)

        // Touch it right before its max session time would expire — idle time alone wouldn't
        // evict it, but total session age should.
        clock.set(900L)
        registry.resolve(DESKTOP_TARGET)
        clock.set(1_001L)
        registry.evictExpired()

        assertTrue(driver.closed)
    }

    @Test
    fun `disconnect closes and removes an active session`() = runTest {
        val driver = FakeBridgeDriver()
        val registry = BridgeSessionRegistry(
            maxIdle = TEST_LIMIT,
            maxSession = TEST_LIMIT,
            connect = { driver },
        )
        registry.resolve(DESKTOP_TARGET)

        registry.disconnect(DESKTOP_TARGET)

        assertTrue(driver.closed)
    }

    @Test
    fun `disconnect is a no-op for a target with no active session`() {
        val registry = BridgeSessionRegistry(maxIdle = TEST_LIMIT, maxSession = TEST_LIMIT)
        registry.disconnect(DESKTOP_TARGET)
    }

    @Test
    fun `resolve after disconnect reconnects`() = runTest {
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdle = TEST_LIMIT,
            maxSession = TEST_LIMIT,
            connect = {
                connectCount.incrementAndGet()
                FakeBridgeDriver()
            },
        )

        registry.resolve(DESKTOP_TARGET)
        registry.disconnect(DESKTOP_TARGET)
        registry.resolve(DESKTOP_TARGET)

        assertEquals(2, connectCount.get())
    }

    @Test
    fun `close closes every cached session`() = runTest {
        val driverA = FakeBridgeDriver()
        val driverB = FakeBridgeDriver()
        val drivers = mapOf(DESKTOP_TARGET to driverA, OTHER_DESKTOP_TARGET to driverB)
        val registry = BridgeSessionRegistry(
            maxIdle = TEST_LIMIT,
            maxSession = TEST_LIMIT,
            connect = { drivers.getValue(it) },
        )
        registry.resolve(DESKTOP_TARGET)
        registry.resolve(OTHER_DESKTOP_TARGET)

        registry.close()

        assertTrue(driverA.closed)
        assertTrue(driverB.closed)
    }

    @Test
    fun `default connect dispatches on platform and requires url for web`() = runTest {
        val registry = BridgeSessionRegistry(maxIdle = TEST_LIMIT, maxSession = TEST_LIMIT)
        val error = runCatching { registry.resolve(BridgeTarget(platform = "web")) }.exceptionOrNull()
        assertTrue(error is InvalidTargetException)
        assertTrue(error.message.orEmpty().contains("url"))
    }

    @Test
    fun `default connect rejects an unknown platform`() = runTest {
        val registry = BridgeSessionRegistry(maxIdle = TEST_LIMIT, maxSession = TEST_LIMIT)
        val error = runCatching { registry.resolve(BridgeTarget(platform = "bogus")) }.exceptionOrNull()
        assertTrue(error is InvalidTargetException)
        assertFalse(error.message.isNullOrBlank())
    }

    @Test
    fun `resolve propagates a BridgeConnectionException thrown by connect unchanged`() = runTest {
        val thrown = BridgeConnectionException("could not reach app")
        val registry = BridgeSessionRegistry(
            maxIdle = TEST_LIMIT,
            maxSession = TEST_LIMIT,
            connect = { throw thrown },
        )
        val error = runCatching { registry.resolve(DESKTOP_TARGET) }.exceptionOrNull()
        assertSame(thrown, error)
    }

    @Test
    fun `driverDefaultTimeout and driverPollInterval reach a real default-connected desktop driver`() = runBlocking {
        // A bare accept-and-close loop is enough to satisfy DesktopBridgeDriver.connect's own
        // reachability probe — no need to speak the bridge protocol for this test.
        val serverSocket = ServerSocket(0)
        val acceptThread = Thread({
            try {
                while (true) serverSocket.accept().close()
            } catch (_: IOException) {
                // serverSocket.close() (below) breaks the accept() loop this way.
            }
        }, "fake-desktop-socket-server").apply {
            isDaemon = true
            start()
        }
        try {
            val registry = BridgeSessionRegistry(
                maxIdle = TEST_LIMIT,
                maxSession = TEST_LIMIT,
                driverDefaultTimeout = 777.milliseconds,
                driverPollInterval = 33.milliseconds,
            )

            val driver = registry.resolve(BridgeTarget(platform = "desktop", port = serverSocket.localPort))

            assertEquals(777.milliseconds, driver.defaultTimeout)
            assertEquals(33.milliseconds, driver.pollInterval)
        } finally {
            serverSocket.close()
            acceptThread.join(1_000)
        }
    }
}
