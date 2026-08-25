// One function per registered tool is the natural shape of this file — it grows by exactly one
// function every time a BridgeDriver operation gets exposed as a tool, not from disorganization.
@file:Suppress("TooManyFunctions")

package com.cramsan.cmpbridge.mcpserver

import com.cramsan.cmpbridge.driver.BridgeSessionRegistry
import com.cramsan.cmpbridge.driver.BridgeTarget
import com.cramsan.cmpbridge.driver.TextComparator
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true }

/**
 * Registers one MCP tool per [com.cramsan.cmpbridge.driver.BridgeDriver] core operation, plus
 * `waitForTag`/`waitForText`/`waitForTagGone` and a `disconnect` operation to end a session early,
 * on [server]. Every tool takes the target app instance (`platform`, plus `host`/`port` or `url`)
 * as arguments and resolves a driver for it via [registry] on each call — see
 * [BridgeSessionRegistry] for how that's cached/evicted across calls.
 */
fun Server.registerBridgeTools(registry: BridgeSessionRegistry) {
    registerGetHierarchyTool(registry)
    registerClickTool(registry)
    registerSetTextTool(registry)
    registerScrollTool(registry)
    registerScreenshotTool(registry)
    registerWaitForTagTool(registry)
    registerWaitForTextTool(registry)
    registerWaitForTagGoneTool(registry)
    registerDisconnectTool(registry)
}

private fun Server.registerGetHierarchyTool(registry: BridgeSessionRegistry) {
    addTool(
        name = "get_hierarchy",
        description = "Returns the app's current real UI semantics tree (roles, text, bounds, available actions).",
        inputSchema = targetOnlySchema(),
    ) { request ->
        safeCall("get_hierarchy", request.arguments) {
            val driver = registry.resolve(request.arguments.toBridgeTarget())
            CallToolResult(content = listOf(TextContent(text = json.encodeToString(driver.getHierarchy()))))
        }
    }
}

private fun Server.registerClickTool(registry: BridgeSessionRegistry) {
    addTool(
        name = "click",
        description = "Real synthetic click on the element with the given test tag.",
        inputSchema = stringPropertiesSchema("tag" to "The element's test tag"),
    ) { request ->
        safeCall("click", request.arguments) {
            val driver = registry.resolve(request.arguments.toBridgeTarget())
            val tag = request.arguments.stringArg("tag")
            driver.click(tag)
            CallToolResult(content = listOf(TextContent(text = "Clicked \"$tag\".")))
        }
    }
}

private fun Server.registerSetTextTool(registry: BridgeSessionRegistry) {
    addTool(
        name = "set_text",
        description = "Clicks the element with the given test tag, then replaces its content with text " +
            "(\"\" clears it).",
        inputSchema = stringPropertiesSchema("tag" to "The element's test tag", "text" to "The text to type"),
    ) { request ->
        safeCall("set_text", request.arguments) {
            val driver = registry.resolve(request.arguments.toBridgeTarget())
            val tag = request.arguments.stringArg("tag")
            val text = request.arguments.stringArg("text")
            driver.setText(tag, text)
            CallToolResult(content = listOf(TextContent(text = "Typed into \"$tag\".")))
        }
    }
}

private fun Server.registerScrollTool(registry: BridgeSessionRegistry) {
    addTool(
        name = "scroll",
        description =
        "Synthesizes a scroll gesture centered on the given anchor tag's bounds. Positive deltaY " +
            "scrolls toward the end of the content; magnitude isn't equivalent across platforms, " +
            "so call this in a loop and re-check get_hierarchy rather than relying on one exact value.",
        inputSchema =
        ToolSchema(
            properties =
            buildJsonObject {
                putTargetProperties()
                put("anchorTag", buildJsonObject { put("type", "string") })
                put("deltaY", buildJsonObject { put("type", "integer") })
            },
            required = listOf("platform", "anchorTag", "deltaY"),
        ),
    ) { request ->
        safeCall("scroll", request.arguments) {
            val driver = registry.resolve(request.arguments.toBridgeTarget())
            val anchorTag = request.arguments.stringArg("anchorTag")
            val deltaY =
                request.arguments
                    ?.get("deltaY")
                    ?.jsonPrimitive
                    ?.int ?: error("Missing \"deltaY\" argument")
            driver.scroll(anchorTag, deltaY)
            CallToolResult(content = listOf(TextContent(text = "Scrolled at \"$anchorTag\".")))
        }
    }
}

private fun Server.registerScreenshotTool(registry: BridgeSessionRegistry) {
    addTool(
        name = "screenshot",
        description = "Captures a real screenshot of the app's current frame as a PNG image.",
        inputSchema = targetOnlySchema(),
    ) { request ->
        safeCall("screenshot", request.arguments) {
            val driver = registry.resolve(request.arguments.toBridgeTarget())
            val png = driver.screenshot()
            val image = ImageContent(data = Base64.getEncoder().encodeToString(png), mimeType = "image/png")
            CallToolResult(content = listOf(image))
        }
    }
}

private fun Server.registerWaitForTagTool(registry: BridgeSessionRegistry) {
    addTool(
        name = "wait_for_tag",
        description =
        "Polls the app's UI tree until the element with the given test tag appears (or errors " +
            "on timeout), instead of repeatedly calling get_hierarchy yourself while waiting for " +
            "something to show up.",
        inputSchema = tagAndTimeoutSchema(),
    ) { request ->
        safeCall("wait_for_tag", request.arguments) {
            val driver = registry.resolve(request.arguments.toBridgeTarget())
            val tag = request.arguments.stringArg("tag")
            val timeoutMs = request.arguments?.get("timeoutMs")?.jsonPrimitive?.long
            val node = if (timeoutMs != null) driver.waitForTag(tag, timeoutMs) else driver.waitForTag(tag)
            CallToolResult(content = listOf(TextContent(text = json.encodeToString(node))))
        }
    }
}

private fun Server.registerWaitForTextTool(registry: BridgeSessionRegistry) {
    addTool(
        name = "wait_for_text",
        description =
        "Polls the app's UI tree until the element with the given test tag's settled text " +
            "satisfies comparator (or errors on timeout). Use this for \"same screen, state " +
            "changed\" assertions, not just a new tag appearing — inline validation errors, " +
            "toggled badges, a counter's new value — since the app's own async state update " +
            "(e.g. a coroutine dispatch) may not have applied yet right after click/set_text " +
            "returns. Also guards against reading a freshly-appeared node's still-settling text, " +
            "unlike a raw get_hierarchy call.",
        inputSchema = waitForTextSchema(),
    ) { request ->
        safeCall("wait_for_text", request.arguments) {
            val driver = registry.resolve(request.arguments.toBridgeTarget())
            val tag = request.arguments.stringArg("tag")
            val comparator = json.decodeFromJsonElement<TextComparator>(
                request.arguments?.get("comparator") ?: error("Missing \"comparator\" argument"),
            )
            val timeoutMs = request.arguments?.get("timeoutMs")?.jsonPrimitive?.long
            val node =
                if (timeoutMs != null) {
                    driver.waitForText(tag, comparator, timeoutMs)
                } else {
                    driver.waitForText(tag, comparator)
                }
            CallToolResult(content = listOf(TextContent(text = json.encodeToString(node))))
        }
    }
}

private fun Server.registerWaitForTagGoneTool(registry: BridgeSessionRegistry) {
    addTool(
        name = "wait_for_tag_gone",
        description =
        "Polls the app's UI tree until the element with the given test tag is no longer found " +
            "(or errors on timeout) — a no-op if it's already gone. Covers \"same screen, state " +
            "changed\" cases like a success banner or dialog closing, the same way wait_for_text " +
            "covers a text change.",
        inputSchema = tagAndTimeoutSchema(),
    ) { request ->
        safeCall("wait_for_tag_gone", request.arguments) {
            val driver = registry.resolve(request.arguments.toBridgeTarget())
            val tag = request.arguments.stringArg("tag")
            val timeoutMs = request.arguments?.get("timeoutMs")?.jsonPrimitive?.long
            if (timeoutMs != null) driver.waitForTagGone(tag, timeoutMs) else driver.waitForTagGone(tag)
            CallToolResult(content = listOf(TextContent(text = "Tag \"$tag\" is gone.")))
        }
    }
}

private fun Server.registerDisconnectTool(registry: BridgeSessionRegistry) {
    addTool(
        name = "disconnect",
        description =
        "Ends a target's cached session early, closing its driver instead of waiting for it to " +
            "idle out or hit its max session time. A no-op if there's no active session for it.",
        inputSchema = targetOnlySchema(),
    ) { request ->
        safeCall("disconnect", request.arguments) {
            registry.disconnect(request.arguments.toBridgeTarget())
            CallToolResult(content = listOf(TextContent(text = "Disconnected.")))
        }
    }
}

/**
 * The target app instance every tool call resolves a driver for: `platform` (`"desktop"` or
 * `"web"`) is always required; `host`/`port` apply to desktop (defaulted like
 * [com.cramsan.cmpbridge.driver.DesktopBridgeDriver.connect]'s own), `url` is required for web.
 */
private fun JsonObjectBuilder.putTargetProperties() {
    put(
        "platform",
        buildJsonObject {
            put("type", "string")
            put("description", "\"desktop\" or \"web\"")
        },
    )
    put(
        "host",
        buildJsonObject {
            put("type", "string")
            put("description", "Desktop bridge host (default 127.0.0.1)")
        },
    )
    put(
        "port",
        buildJsonObject {
            put("type", "integer")
            put("description", "Desktop bridge port (default 8901)")
        },
    )
    put(
        "url",
        buildJsonObject {
            put("type", "string")
            put("description", "URL of the already-running wasmJs dev server (required when platform is \"web\")")
        },
    )
}

/** Schema for a tool that takes no arguments beyond the target app instance. */
private fun targetOnlySchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject { putTargetProperties() },
    required = listOf("platform"),
)

/**
 * Shared input schema for [registerWaitForTagTool], [registerWaitForTextTool], and
 * [registerWaitForTagGoneTool]: the target app instance, a required `tag`, plus optional
 * `timeoutMs`.
 */
private fun tagAndTimeoutSchema(): ToolSchema = ToolSchema(
    properties =
    buildJsonObject {
        putTargetProperties()
        put(
            "tag",
            buildJsonObject {
                put("type", "string")
                put("description", "The element's test tag")
            },
        )
        put(
            "timeoutMs",
            buildJsonObject {
                put("type", "integer")
                put("description", "Max time to wait, in milliseconds (default 15000)")
            },
        )
    },
    required = listOf("platform", "tag"),
)

/**
 * Input schema for [registerWaitForTextTool]: [tagAndTimeoutSchema]'s shape plus a required
 * `comparator`, mirroring [TextComparator]'s own sealed shape/`@SerialName`s so it decodes with the
 * same `json` instance used everywhere else in this file.
 */
private fun waitForTextSchema(): ToolSchema = ToolSchema(
    properties =
    buildJsonObject {
        putTargetProperties()
        put(
            "tag",
            buildJsonObject {
                put("type", "string")
                put("description", "The element's test tag")
            },
        )
        put("comparator", comparatorSchema())
        put(
            "timeoutMs",
            buildJsonObject {
                put("type", "integer")
                put("description", "Max time to wait, in milliseconds (default 15000)")
            },
        )
    },
    required = listOf("platform", "tag", "comparator"),
)

/**
 * JSON Schema for [TextComparator]: a discriminated union on `type`, one shape per variant's own
 * `@SerialName` and fields.
 */
private fun comparatorSchema(): JsonObject = buildJsonObject {
    put("description", "How to match the tag's settled text")
    put(
        "oneOf",
        JsonArray(
            listOf(
                comparatorVariantSchema("present"),
                comparatorVariantSchema("empty"),
                comparatorVariantSchema("equals", "value" to "The exact text to match"),
                comparatorVariantSchema("startsWith", "prefix" to "The prefix the text must start with"),
            ),
        ),
    )
}

private fun comparatorVariantSchema(type: String, vararg extraProperties: Pair<String, String>): JsonObject =
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("type", buildJsonObject { put("const", type) })
                extraProperties.forEach { (name, description) ->
                    put(
                        name,
                        buildJsonObject {
                            put("type", "string")
                            put("description", description)
                        },
                    )
                }
            },
        )
        putJsonArray("required") {
            add(JsonPrimitive("type"))
            extraProperties.forEach { (name, _) -> add(JsonPrimitive(name)) }
        }
    }

private fun stringPropertiesSchema(vararg properties: Pair<String, String>): ToolSchema = ToolSchema(
    properties =
    buildJsonObject {
        putTargetProperties()
        properties.forEach { (name, description) ->
            put(
                name,
                buildJsonObject {
                    put("type", "string")
                    put("description", description)
                },
            )
        }
    },
    required = listOf("platform") + properties.map { it.first },
)

private fun JsonObject?.stringArg(name: String): String =
    this?.get(name)?.jsonPrimitive?.content ?: error("Missing \"$name\" argument")

/** Parses the target fields (`platform`/`host`/`port`/`url`) every tool's arguments carry. */
private fun JsonObject?.toBridgeTarget(): BridgeTarget = BridgeTarget(
    platform = stringArg("platform"),
    host = this?.get("host")?.jsonPrimitive?.content ?: "127.0.0.1",
    port = this?.get("port")?.jsonPrimitive?.int ?: 8901,
    url = this?.get("url")?.jsonPrimitive?.content,
)

/**
 * Converts a thrown [com.cramsan.cmpbridge.driver.BridgeDriver]/[BridgeSessionRegistry] failure
 * (unknown tag, timeout, unresolvable target, ...) into an MCP tool-level error rather than
 * crashing the session, and logs [operation]/[arguments]/outcome to stderr (stdout is reserved
 * for the MCP JSON-RPC stream) so a session can be debugged after the fact.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun safeCall(
    operation: String,
    arguments: JsonObject?,
    block: suspend () -> CallToolResult,
): CallToolResult {
    System.err.println("[cmp-bridge-mcp] -> $operation ${arguments ?: JsonObject(emptyMap())}")
    return try {
        val result = block()
        System.err.println("[cmp-bridge-mcp] <- $operation ok")
        result
    } catch (e: Exception) {
        val message = e.message ?: e::class.simpleName ?: "Unknown error"
        System.err.println("[cmp-bridge-mcp] <- $operation failed: $message")
        CallToolResult(
            content = listOf(TextContent(text = message)),
            isError = true,
        )
    }
}
