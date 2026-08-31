package com.cramsan.cmpbridge.httpserver

import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.BridgeSessionRegistry
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private const val DEFAULT_SERVER_PORT = 8090
private const val DEFAULT_MAX_IDLE_MS = 300_000L
private const val DEFAULT_MAX_SESSION_MS = 1_800_000L

/**
 * Serves a local HTTP REST API that can attach to any number of already-running apps' UI
 * interaction bridges over its lifetime — which app a given request targets is resolved per
 * request (see [BridgeSessionRegistry]), not at launch. Never launches an app itself.
 */
private class BridgeHttpServerCommand : CliktCommand(name = "cmp-bridge-http-server") {
    private val options by SessionOptions()
    private val serverPort: Int by option("--server-port", help = "Port this HTTP server itself listens on")
        .int()
        .default(DEFAULT_SERVER_PORT)

    override fun run() {
        val registry = BridgeSessionRegistry(
            options.maxIdleMs,
            options.maxSessionMs,
            options.defaultTimeoutMs,
            options.pollIntervalMs,
        )
        val server = embeddedServer(Netty, port = serverPort) { bridgeHttpModule(registry) }
        Runtime.getRuntime().addShutdownHook(Thread { registry.close() })
        server.start(wait = true)
    }
}

/**
 * Session cache limits for [BridgeSessionRegistry]. Duplicated verbatim in `cmp-bridge-mcp-server`
 * rather than shared through a third module.
 */
internal class SessionOptions : OptionGroup(name = "Session limits") {
    val maxIdleMs: Long by option(
        "--max-idle-ms",
        help = "Close a target's session after this long unused",
    ).long().default(DEFAULT_MAX_IDLE_MS)
    val maxSessionMs: Long by option(
        "--max-session-ms",
        help = "Close a target's session after this long since it was first opened, regardless of use",
    ).long().default(DEFAULT_MAX_SESSION_MS)
    val defaultTimeoutMs: Long by option(
        "--default-timeout-ms",
        help = "Default waitForTagVisibility/waitForText timeout for a call that doesn't pass its own timeoutMs",
    ).long().default(BridgeDriver.DEFAULT_TIMEOUT_MS)
    val pollIntervalMs: Long by option(
        "--poll-interval-ms",
        help = "Interval between hierarchy polls in waitForTagVisibility/waitForText",
    ).long().default(BridgeDriver.DEFAULT_POLL_INTERVAL_MS)
}

/** Entry point for the UI test bridge HTTP server. */
fun main(args: Array<String>) = BridgeHttpServerCommand().main(args)
