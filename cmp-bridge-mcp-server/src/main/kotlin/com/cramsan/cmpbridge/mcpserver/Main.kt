package com.cramsan.cmpbridge.mcpserver

import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.BridgeSessionRegistry
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val DEFAULT_MAX_IDLE: Duration = 5.minutes
private val DEFAULT_MAX_SESSION: Duration = 30.minutes

/**
 * Serves MCP (stdio transport) tools that can attach to any number of already-running apps' UI
 * bridges over the process's lifetime — which app a given tool call targets is resolved per call
 * (see [BridgeSessionRegistry]), not at launch. Never launches an app itself.
 *
 * **Nothing may write to stdout except the MCP JSON-RPC stream itself.** [main] captures real
 * stdout first and redirects `System.out` to stderr immediately after, so any stray output lands
 * somewhere harmless instead of on the wire.
 */
private class BridgeMcpServerCommand(private val realStdout: PrintStream) :
    CliktCommand(
        name = "cmp-bridge-mcp-server",
    ) {
    private val options by SessionOptions()

    override fun run() {
        val registry = BridgeSessionRegistry(
            options.maxIdle,
            options.maxSession,
            options.defaultTimeout,
            options.pollInterval,
        )
        runBlocking {
            val server = buildServer(registry)
            val closed = CompletableDeferred<Unit>()
            server.onClose { closed.complete(Unit) }
            server.createSession(
                StdioServerTransport(
                    inputStream = System.`in`.asSource().buffered(),
                    outputStream = realStdout.asSink().buffered(),
                ),
            )
            closed.await()
            registry.close()
        }
    }
}

/**
 * Session cache limits for [BridgeSessionRegistry]. Duplicated verbatim in `cmp-bridge-http-server`
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

private fun buildServer(registry: BridgeSessionRegistry): Server {
    val server =
        Server(
            serverInfo = Implementation(name = "cmp-bridge", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        )
    server.registerBridgeTools(registry)
    return server
}

/** Entry point for the UI test bridge MCP server. */
fun main(args: Array<String>) {
    val realStdout = System.out
    System.setOut(System.err)
    BridgeMcpServerCommand(realStdout).main(args)
}
