package com.cramsan.cmpbridge.driver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BridgeDriverExceptionsTest {
    @Test
    fun `UnknownTagException is a BridgeDriverException and preserves its message`() {
        val e = UnknownTagException("Unknown tag: my_tag")
        assertTrue(e is BridgeDriverException)
        assertTrue(e is Exception)
        assertEquals("Unknown tag: my_tag", e.message)
    }

    @Test
    fun `BridgeTimeoutException is a BridgeDriverException and preserves its message`() {
        val e = BridgeTimeoutException("Tag \"my_tag\" did not appear within 1000ms")
        assertTrue(e is BridgeDriverException)
        assertEquals("Tag \"my_tag\" did not appear within 1000ms", e.message)
    }

    @Test
    fun `InvalidTargetException is a BridgeDriverException and preserves its message`() {
        val e = InvalidTargetException("Unknown platform \"bogus\"")
        assertTrue(e is BridgeDriverException)
        assertEquals("Unknown platform \"bogus\"", e.message)
    }

    @Test
    fun `BridgeConnectionException is a BridgeDriverException and preserves its message`() {
        val e = BridgeConnectionException("Connection to the app failed")
        assertTrue(e is BridgeDriverException)
        assertEquals("Connection to the app failed", e.message)
    }

    @Test
    fun `BridgeConnectionException preserves a wrapped cause`() {
        val cause = java.io.IOException("connection reset")
        val e = BridgeConnectionException("Connection to the app failed", cause)
        assertSame(cause, e.cause)
    }

    @Test
    fun `BridgeConnectionException defaults to no cause`() {
        val e = BridgeConnectionException("Connection to the app failed")
        assertEquals(null, e.cause)
    }
}
