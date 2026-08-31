package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.BridgeCommand
import com.cramsan.cmpbridge.BridgeResponse
import com.cramsan.cmpbridge.HierarchyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val json = Json { ignoreUnknownKeys = true }

/**
 * Drives a real desktop app instance over the UI bridge's debug socket. Connection-only — never
 * launches anything, so [close] never touches a process. Pair [connect] with
 * [DesktopAppProcess.launch] (optionally via [ManagedBridgeDriver]) for a disposable instance.
 */
class DesktopBridgeDriver private constructor(
    private val host: String,
    private val port: Int,
    override val defaultTimeout: Duration,
    override val pollInterval: Duration,
) : BridgeDriver {
    private fun send(command: BridgeCommand): BridgeResponse {
        try {
            Socket(host, port).use { socket ->
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                writer.println(json.encodeToString(command))
                val line = reader.readLine()
                    ?: throw BridgeConnectionException("No response for command: $command")
                return json.decodeFromString(line)
            }
        } catch (e: IOException) {
            throw BridgeConnectionException(e.message ?: "Connection to the app failed", e)
        }
    }

    /** Sends [command], unwrapping the response to [T] or throwing (with the server's own message, if any). */
    private inline fun <reified T : BridgeResponse> sendTyped(command: BridgeCommand): T {
        val response = send(command)
        if (response is BridgeResponse.Failure) {
            // DesktopBridgeServer.unknownTag()/notVisibleTag() are the single source of these
            // exact prefixes.
            if (response.message.startsWith(UNKNOWN_TAG_PREFIX)) throw UnknownTagException(response.message)
            if (response.message.startsWith(NOT_VISIBLE_TAG_PREFIX)) throw TagNotVisibleException(response.message)
            error(response.message)
        }
        return response as? T ?: error("Unexpected response $response for command $command")
    }

    override fun getHierarchy(): HierarchyNode = sendTyped<BridgeResponse.Hierarchy>(BridgeCommand.GetHierarchy).root

    override fun click(tag: String) {
        sendTyped<BridgeResponse.Ack>(BridgeCommand.Click(tag))
    }

    /** The desktop bridge's `SetText` command already clicks the field before pasting into it. */
    override fun setText(tag: String, text: String) {
        sendTyped<BridgeResponse.Ack>(BridgeCommand.SetText(tag, text))
    }

    override fun scroll(anchorTag: String, deltaY: Int) {
        sendTyped<BridgeResponse.Ack>(BridgeCommand.Scroll(anchorTag, deltaY))
    }

    override fun screenshot(): ByteArray {
        val image = sendTyped<BridgeResponse.Image>(BridgeCommand.Screenshot)
        return Base64.getDecoder().decode(image.pngBase64)
    }

    /** Nothing to close: every command opens and closes its own socket, and no process is owned here. */
    override fun close() = Unit

    companion object {
        // Reachability-probe knobs for connect() itself — unrelated to BridgeDriver's own
        // waitForTagVisibility/waitForText polling (defaultTimeout/pollInterval below).
        private val CONNECT_TIMEOUT: Duration = 10.seconds
        private val POLL_INTERVAL: Duration = 250.milliseconds

        /** The exact prefix `DesktopBridgeServer.unknownTag()` always emits for a missing tag. */
        private const val UNKNOWN_TAG_PREFIX = "Unknown tag: "

        /** The exact prefix `DesktopBridgeServer.notVisibleTag()` always emits for a zero-bounds tag. */
        private const val NOT_VISIBLE_TAG_PREFIX = "Tag not visible: "

        /**
         * Attaches to an app instance that's already running with the bridge armed.
         * [defaultTimeout]/[pollInterval] become this driver's [BridgeDriver.defaultTimeout]/
         * [BridgeDriver.pollInterval] — override for a slower app/CI environment.
         */
        suspend fun connect(
            host: String = "127.0.0.1",
            port: Int = 8901,
            defaultTimeout: Duration = BridgeDriver.DEFAULT_TIMEOUT,
            pollInterval: Duration = BridgeDriver.DEFAULT_POLL_INTERVAL,
        ): DesktopBridgeDriver {
            waitUntilConnectable(host, port)
            return DesktopBridgeDriver(host, port, defaultTimeout, pollInterval)
        }

        private suspend fun waitUntilConnectable(host: String, port: Int) {
            try {
                withTimeout(CONNECT_TIMEOUT) {
                    while (true) {
                        // IOException only — a blanket Exception catch around a suspending call
                        // would also swallow this withTimeout's own TimeoutCancellationException.
                        try {
                            withContext(Dispatchers.IO) { Socket(host, port).close() }
                            return@withTimeout
                        } catch (_: IOException) {
                            delay(POLL_INTERVAL)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                throw BridgeConnectionException(
                    "Could not connect to a UI test bridge at $host:$port within $CONNECT_TIMEOUT — " +
                        "is the app running with CMP_BRIDGE_ENABLED=true (or -DcmpBridge.enabled=true)?",
                )
            }
        }
    }
}
