package com.cramsan.cmpbridge.httpserver

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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private const val DEFAULT_SERVER_PORT = 8090
private const val DEFAULT_DESKTOP_PORT = 8901

/**
 * Serves an already-running app's UI interaction bridge over a local HTTP REST API — the app is
 * expected to already be running (desktop with `-PcmpBridge`, or a wasmJs dev server already up);
 * this process never launches one itself. See [BridgeExplorerOptions] for connection args.
 */
private class BridgeHttpServerCommand : CliktCommand(name = "cmp-bridge-http-server") {
    private val options by BridgeExplorerOptions()
    private val serverPort: Int by option("--server-port", help = "Port this HTTP server itself listens on")
        .int()
        .default(DEFAULT_SERVER_PORT)

    override fun run() {
        val driver = options.connect()
        val server = embeddedServer(Netty, port = serverPort) { bridgeHttpModule(driver) }
        Runtime.getRuntime().addShutdownHook(Thread { driver.close() })
        server.start(wait = true)
    }
}

/**
 * Connection args for attaching to an already-running app — never launches anything itself.
 * Duplicated verbatim in `cmp-bridge-mcp-server` rather than shared through a third module.
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

/** Entry point for the UI test bridge HTTP server. */
fun main(args: Array<String>) = BridgeHttpServerCommand().main(args)
