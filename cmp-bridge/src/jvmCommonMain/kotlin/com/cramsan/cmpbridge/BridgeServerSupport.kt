package com.cramsan.cmpbridge

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

internal val protocolJson = Json { ignoreUnknownKeys = true }

/**
 * Serves one client connection of the [BridgeCommand]/[BridgeResponse] line protocol until the
 * client disconnects, routing each decoded command through [handle]. Shared by every in-app server.
 */
@Suppress("TooGenericExceptionCaught") // a command that fails for any reason still owes the driver a response line
internal fun serveBridgeConnection(socket: Socket, handle: (BridgeCommand) -> BridgeResponse) {
    socket.use {
        val reader = BufferedReader(InputStreamReader(it.getInputStream()))
        val writer = PrintWriter(it.getOutputStream(), true)
        while (true) {
            val line = reader.readLine() ?: break
            val response =
                try {
                    handle(protocolJson.decodeFromString<BridgeCommand>(line))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BridgeResponse.Failure(e.message ?: e::class.simpleName ?: "command failed")
                }
            writer.println(protocolJson.encodeToString(response))
        }
    }
}

/** The outcome of resolving a tag to a node before dispatching input at it. */
internal sealed class NodeLookup {
    data class Found(val node: HierarchyNode) : NodeLookup()
    data object NotFound : NodeLookup()
    data object NotVisible : NodeLookup()
}

/**
 * Resolves [tag] within [root], distinguishing "not in the tree at all" from "present but
 * zero/off-screen bounds" (e.g. a `LazyColumn` item not yet scrolled into view) — see
 * https://github.com/CRamsan/cmp-bridge/issues/12.
 */
internal fun HierarchyNode.lookup(tag: String): NodeLookup {
    val node = find(tag) ?: return NodeLookup.NotFound
    return if (node.width <= 0f || node.height <= 0f) NodeLookup.NotVisible else NodeLookup.Found(node)
}

/** Turns a tag lookup outcome into the [BridgeResponse] for it, running [onFound] first if it resolved. */
internal inline fun NodeLookup.toResponse(tag: String, onFound: (HierarchyNode) -> Unit = {}): BridgeResponse =
    when (this) {
        is NodeLookup.Found -> {
            onFound(node)
            BridgeResponse.Ack
        }

        NodeLookup.NotFound -> BridgeResponse.Failure("${BridgeFailures.UNKNOWN_TAG_PREFIX}$tag")

        NodeLookup.NotVisible -> BridgeResponse.Failure("${BridgeFailures.NOT_VISIBLE_TAG_PREFIX}$tag")
    }
