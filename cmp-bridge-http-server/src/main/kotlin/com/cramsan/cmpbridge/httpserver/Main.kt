package com.cramsan.cmpbridge.httpserver

import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.BridgeSessionRegistry
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val DEFAULT_SERVER_PORT = 8090
private val DEFAULT_MAX_IDLE: Duration = 5.minutes
private val DEFAULT_MAX_SESSION: Duration = 30.minutes

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
            options.maxIdle,
            options.maxSession,
            options.defaultTimeout,
            options.pollInterval,
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
    // CLI values are still plain millisecond integers (no existing Clikt Duration type or
    // convert{} precedent to build a new flag syntax on) — .convert{} maps them to Duration
    // immediately so the rest of the app never touches a raw millis Long.
    val maxIdle: Duration by option(
        "--max-idle-ms",
        help = "Close a target's session after this long unused",
    ).long().convert { it.milliseconds }.default(DEFAULT_MAX_IDLE)
    val maxSession: Duration by option(
        "--max-session-ms",
        help = "Close a target's session after this long since it was first opened, regardless of use",
    ).long().convert { it.milliseconds }.default(DEFAULT_MAX_SESSION)
    val defaultTimeout: Duration by option(
        "--default-timeout-ms",
        help = "Default waitForTagVisibility/waitForText timeout for a call that doesn't pass its own timeoutMs",
    ).long().convert { it.milliseconds }.default(BridgeDriver.DEFAULT_TIMEOUT)
    val pollInterval: Duration by option(
        "--poll-interval-ms",
        help = "Interval between hierarchy polls in waitForTagVisibility/waitForText",
    ).long().convert { it.milliseconds }.default(BridgeDriver.DEFAULT_POLL_INTERVAL)
}

/** Entry point for the UI test bridge HTTP server. */
fun main(args: Array<String>) = BridgeHttpServerCommand().main(args)
