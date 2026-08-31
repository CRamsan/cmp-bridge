package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.BridgeCommand
import com.cramsan.cmpbridge.BridgeResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true }

/**
 * A stand-in for [com.cramsan.cmpbridge.DesktopBridgeServer]: accepts connections in a loop (one
 * per command, matching the real server, and also absorbing [DesktopBridgeDriver.connect]'s own
 * connect-then-close reachability probe) and replies to each per the test's own [respond] — or not
 * at all, to simulate a server that drops the connection before responding.
 */
private class FakeBridgeSocketServer(private val respond: (BridgeCommand) -> BridgeResponse?) : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort

    private val thread = Thread({
        try {
            while (true) {
                serverSocket.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val line = reader.readLine() ?: return@use
                    val command = json.decodeFromString<BridgeCommand>(line)
                    val response = respond(command) ?: return@use
                    writer.println(json.encodeToString(response))
                }
            }
        } catch (_: java.io.IOException) {
            // serverSocket.close() (from close(), below) breaks the accept() loop this way.
        }
    }, "fake-bridge-socket-server").apply {
        isDaemon = true
        start()
    }

    override fun close() {
        serverSocket.close()
        thread.join(1_000)
    }
}

class DesktopBridgeDriverTest {
    private var server: FakeBridgeSocketServer? = null

    @AfterTest
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `click on an unknown tag throws UnknownTagException`() {
        server = FakeBridgeSocketServer { BridgeResponse.Failure("Unknown tag: my_tag") }
        val driver = DesktopBridgeDriver.connect(port = server!!.port)

        val error = runCatching { driver.click("my_tag") }.exceptionOrNull()

        assertTrue(error is UnknownTagException)
        assertTrue(error.message.orEmpty().contains("Unknown tag: my_tag"))
    }

    @Test
    fun `a generic command failure does not surface as UnknownTagException`() {
        server = FakeBridgeSocketServer { BridgeResponse.Failure("boom") }
        val driver = DesktopBridgeDriver.connect(port = server!!.port)

        val error = runCatching { driver.click("my_tag") }.exceptionOrNull()

        assertFalse(error is UnknownTagException)
        assertTrue(error is Exception)
    }

    @Test
    fun `a connection that closes without responding throws BridgeConnectionException`() {
        server = FakeBridgeSocketServer { null }
        val driver = DesktopBridgeDriver.connect(port = server!!.port)

        val error = runCatching { driver.click("my_tag") }.exceptionOrNull()

        assertTrue(error is BridgeConnectionException)
    }

    @Test
    fun `connect throws BridgeConnectionException when nothing is listening on the port`() {
        // No FakeBridgeSocketServer started — pick a port nothing is bound to.
        val unusedPort = ServerSocket(0).use { it.localPort }

        val error = runCatching { DesktopBridgeDriver.connect(port = unusedPort) }.exceptionOrNull()

        assertTrue(error is BridgeConnectionException)
    }

    @Test
    fun `connect defaults to BridgeDriver's own default timeout and poll interval`() {
        server = FakeBridgeSocketServer { BridgeResponse.Failure("boom") }
        val driver = DesktopBridgeDriver.connect(port = server!!.port)

        assertEquals(BridgeDriver.DEFAULT_TIMEOUT_MS, driver.defaultTimeoutMs)
        assertEquals(BridgeDriver.DEFAULT_POLL_INTERVAL_MS, driver.pollIntervalMs)
    }

    @Test
    fun `connect honors custom defaultTimeoutMs and pollIntervalMs`() {
        server = FakeBridgeSocketServer { BridgeResponse.Failure("boom") }
        val driver = DesktopBridgeDriver.connect(port = server!!.port, defaultTimeoutMs = 777L, pollIntervalMs = 33L)

        assertEquals(777L, driver.defaultTimeoutMs)
        assertEquals(33L, driver.pollIntervalMs)
    }
}
