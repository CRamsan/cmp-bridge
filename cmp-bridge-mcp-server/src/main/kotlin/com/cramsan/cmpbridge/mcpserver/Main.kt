package com.cramsan.cmpbridge.mcpserver

import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.DesktopBridgeDriver
import com.cramsan.cmpbridge.driver.WebBridgeDriver
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PrintStream

private const val DEFAULT_DESKTOP_PORT = 8901

/**
 * Serves an already-running app's UI interaction bridge over MCP (stdio transport) — the app is
 * expected to already be running, same as `cmp-bridge-http-server`; this process never
 * launches one itself. Registers one MCP tool per [BridgeDriver] operation.
 *
 * **Nothing may write to stdout except the MCP JSON-RPC stream itself** — confirmed live that the
 * MCP SDK's own `kotlin-logging` dependency prints an initialization banner straight to stdout
 * ("kotlin-logging: initializing...") before any protocol traffic even starts, which would
 * corrupt every MCP client's parsing. [main] captures the real stdout stream first and globally
 * redirects `System.out` to stderr immediately after, so that banner (or any other stray
 * `println`, present or future) lands somewhere harmless instead of on the wire.
 */
private class BridgeMcpServerCommand(private val realStdout: PrintStream) :
    CliktCommand(
        name = "cmp-bridge-mcp-server",
    ) {
    private val options by BridgeExplorerOptions()

    override fun run() {
        val driver = options.connect()
        runBlocking {
            val server = buildServer(driver)
            val closed = CompletableDeferred<Unit>()
            server.onClose { closed.complete(Unit) }
            server.createSession(
                StdioServerTransport(
                    inputStream = System.`in`.asSource().buffered(),
                    outputStream = realStdout.asSink().buffered(),
                ),
            )
            closed.await()
            driver.close()
        }
    }
}

/**
 * Shared connection args, identical across the HTTP and MCP servers: which already-running app
 * to attach to. Never launches anything itself — see [DesktopBridgeDriver.connect]/
 * [WebBridgeDriver.connect]. Duplicated verbatim in `cmp-bridge-http-server` rather than
 * pulled into a third shared module — a deliberate simplicity choice, same as not having a
 * separate "explorer service" abstraction (see the driving plan's rationale).
 */
internal class BridgeExplorerOptions : OptionGroup(name = "Bridge connection") {
    val platform: String by option("--platform", help = "\"desktop\" or \"web\"").choice("desktop", "web").required()
    val host: String by option("--host", help = "Desktop bridge host").default("127.0.0.1")
    val port: Int by option("--port", help = "Desktop bridge port").int().default(DEFAULT_DESKTOP_PORT)
    val url: String? by option(
        "--url",
        help = "URL of the already-running wasmJs dev server (required for --platform=web)",
    )

    fun connect(): BridgeDriver = when (platform) {
        "desktop" -> DesktopBridgeDriver.connect(host, port)
        "web" -> WebBridgeDriver.connect(url ?: error("--url is required when --platform=web"))
        else -> error("Unknown platform \"$platform\"")
    }
}

private fun buildServer(driver: BridgeDriver): Server {
    val server =
        Server(
            serverInfo = Implementation(name = "cmp-bridge", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        )
    server.registerBridgeTools(driver)
    return server
}

/** Entry point for the UI test bridge MCP server. */
fun main(args: Array<String>) {
    val realStdout = System.out
    System.setOut(System.err)
    BridgeMcpServerCommand(realStdout).main(args)
}
