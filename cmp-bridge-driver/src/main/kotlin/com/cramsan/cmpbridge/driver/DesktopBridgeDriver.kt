package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.BridgeCommand
import com.cramsan.cmpbridge.BridgeResponse
import com.cramsan.cmpbridge.HierarchyNode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true }

/**
 * Drives a real desktop app instance over the UI bridge's debug socket. Connection-only — never
 * launches anything, so [close] never touches a process. Pair [connect] with
 * [DesktopAppProcess.launch] (optionally via [ManagedBridgeDriver]) for a disposable instance.
 */
class DesktopBridgeDriver private constructor(private val host: String, private val port: Int) : BridgeDriver {
    private fun send(command: BridgeCommand): BridgeResponse {
        Socket(host, port).use { socket ->
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer.println(json.encodeToString(command))
            val line = reader.readLine() ?: error("No response for command: $command")
            return json.decodeFromString(line)
        }
    }

    /** Sends [command], unwrapping the response to [T] or throwing (with the server's own message, if any). */
    private inline fun <reified T : BridgeResponse> sendTyped(command: BridgeCommand): T {
        val response = send(command)
        if (response is BridgeResponse.Failure) error(response.message)
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
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 250L

        /** Attaches to an app instance that's already running with the bridge armed. */
        fun connect(host: String = "127.0.0.1", port: Int = 8901): DesktopBridgeDriver {
            waitUntilConnectable(host, port)
            return DesktopBridgeDriver(host, port)
        }

        private fun waitUntilConnectable(host: String, port: Int) {
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket(host, port).close()
                    return
                } catch (_: Exception) {
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            }
            error(
                "Could not connect to a UI test bridge at $host:$port within ${CONNECT_TIMEOUT_MS}ms — " +
                    "is the app running with CMP_BRIDGE_ENABLED=true (or -DcmpBridge.enabled=true)?",
            )
        }
    }
}
