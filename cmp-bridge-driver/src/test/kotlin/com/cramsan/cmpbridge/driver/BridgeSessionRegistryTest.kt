package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.HierarchyNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val TEST_LIMIT_MS = 60_000L
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
    fun `resolve reuses the same driver for repeated calls on the same target`() {
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdleMs = TEST_LIMIT_MS,
            maxSessionMs = TEST_LIMIT_MS,
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
    fun `resolve connects a separate driver per distinct target`() {
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdleMs = TEST_LIMIT_MS,
            maxSessionMs = TEST_LIMIT_MS,
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
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdleMs = TEST_LIMIT_MS,
            maxSessionMs = TEST_LIMIT_MS,
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
                registry.resolve(DESKTOP_TARGET)
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
    fun `evictExpired closes and removes a session idle past maxIdleMs`() {
        val clock = AtomicLong(0)
        val driver = FakeBridgeDriver()
        val registry = BridgeSessionRegistry(
            maxIdleMs = 1_000L,
            maxSessionMs = TEST_LIMIT_MS,
            connect = { driver },
            nowMs = clock::get,
        )
        registry.resolve(DESKTOP_TARGET)

        clock.set(1_001L)
        registry.evictExpired()

        assertTrue(driver.closed)
    }

    @Test
    fun `resolve after idle eviction reconnects`() {
        val clock = AtomicLong(0)
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdleMs = 1_000L,
            maxSessionMs = TEST_LIMIT_MS,
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
    fun `evictExpired closes a session past maxSessionMs even if recently used`() {
        val clock = AtomicLong(0)
        val driver = FakeBridgeDriver()
        val registry = BridgeSessionRegistry(
            maxIdleMs = TEST_LIMIT_MS,
            maxSessionMs = 1_000L,
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
    fun `disconnect closes and removes an active session`() {
        val driver = FakeBridgeDriver()
        val registry = BridgeSessionRegistry(
            maxIdleMs = TEST_LIMIT_MS,
            maxSessionMs = TEST_LIMIT_MS,
            connect = { driver },
        )
        registry.resolve(DESKTOP_TARGET)

        registry.disconnect(DESKTOP_TARGET)

        assertTrue(driver.closed)
    }

    @Test
    fun `disconnect is a no-op for a target with no active session`() {
        val registry = BridgeSessionRegistry(maxIdleMs = TEST_LIMIT_MS, maxSessionMs = TEST_LIMIT_MS)
        registry.disconnect(DESKTOP_TARGET)
    }

    @Test
    fun `resolve after disconnect reconnects`() {
        val connectCount = AtomicInteger(0)
        val registry = BridgeSessionRegistry(
            maxIdleMs = TEST_LIMIT_MS,
            maxSessionMs = TEST_LIMIT_MS,
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
    fun `close closes every cached session`() {
        val driverA = FakeBridgeDriver()
        val driverB = FakeBridgeDriver()
        val drivers = mapOf(DESKTOP_TARGET to driverA, OTHER_DESKTOP_TARGET to driverB)
        val registry = BridgeSessionRegistry(
            maxIdleMs = TEST_LIMIT_MS,
            maxSessionMs = TEST_LIMIT_MS,
            connect = { drivers.getValue(it) },
        )
        registry.resolve(DESKTOP_TARGET)
        registry.resolve(OTHER_DESKTOP_TARGET)

        registry.close()

        assertTrue(driverA.closed)
        assertTrue(driverB.closed)
    }

    @Test
    fun `default connect dispatches on platform and requires url for web`() {
        val registry = BridgeSessionRegistry(maxIdleMs = TEST_LIMIT_MS, maxSessionMs = TEST_LIMIT_MS)
        val error = runCatching { registry.resolve(BridgeTarget(platform = "web")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error.message.orEmpty().contains("url"))
    }

    @Test
    fun `default connect rejects an unknown platform`() {
        val registry = BridgeSessionRegistry(maxIdleMs = TEST_LIMIT_MS, maxSessionMs = TEST_LIMIT_MS)
        val error = runCatching { registry.resolve(BridgeTarget(platform = "bogus")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(error.message.isNullOrBlank())
    }
}
