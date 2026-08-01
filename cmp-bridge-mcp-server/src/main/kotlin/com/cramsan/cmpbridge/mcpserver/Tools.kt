package com.cramsan.cmpbridge.mcpserver

import com.cramsan.cmpbridge.driver.BridgeDriver
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true }

/** Registers one MCP tool per [BridgeDriver] operation on [server]. */
fun Server.registerBridgeTools(driver: BridgeDriver) {
    registerGetHierarchyTool(driver)
    registerClickTool(driver)
    registerSetTextTool(driver)
    registerScrollTool(driver)
    registerScreenshotTool(driver)
}

private fun Server.registerGetHierarchyTool(driver: BridgeDriver) {
    addTool(
        name = "get_hierarchy",
        description = "Returns the app's current real UI semantics tree (roles, text, bounds, available actions).",
    ) { _ ->
        safeCall { CallToolResult(content = listOf(TextContent(text = json.encodeToString(driver.getHierarchy())))) }
    }
}

private fun Server.registerClickTool(driver: BridgeDriver) {
    addTool(
        name = "click",
        description = "Real synthetic click on the element with the given test tag.",
        inputSchema = stringPropertiesSchema("tag" to "The element's test tag"),
    ) { request ->
        safeCall {
            val tag = request.arguments.stringArg("tag")
            driver.click(tag)
            CallToolResult(content = listOf(TextContent(text = "Clicked \"$tag\".")))
        }
    }
}

private fun Server.registerSetTextTool(driver: BridgeDriver) {
    addTool(
        name = "set_text",
        description = "Clicks the element with the given test tag, then types text into it.",
        inputSchema = stringPropertiesSchema("tag" to "The element's test tag", "text" to "The text to type"),
    ) { request ->
        safeCall {
            val tag = request.arguments.stringArg("tag")
            val text = request.arguments.stringArg("text")
            driver.setText(tag, text)
            CallToolResult(content = listOf(TextContent(text = "Typed into \"$tag\".")))
        }
    }
}

private fun Server.registerScrollTool(driver: BridgeDriver) {
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
                put("anchorTag", buildJsonObject { put("type", "string") })
                put("deltaY", buildJsonObject { put("type", "integer") })
            },
            required = listOf("anchorTag", "deltaY"),
        ),
    ) { request ->
        safeCall {
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

private fun Server.registerScreenshotTool(driver: BridgeDriver) {
    addTool(
        name = "screenshot",
        description = "Captures a real screenshot of the app's current frame as a PNG image.",
    ) { _ ->
        safeCall {
            val png = driver.screenshot()
            val image = ImageContent(data = Base64.getEncoder().encodeToString(png), mimeType = "image/png")
            CallToolResult(content = listOf(image))
        }
    }
}

private fun stringPropertiesSchema(vararg properties: Pair<String, String>): ToolSchema = ToolSchema(
    properties =
    buildJsonObject {
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
    required = properties.map { it.first },
)

private fun JsonObject?.stringArg(name: String): String =
    this?.get(name)?.jsonPrimitive?.content ?: error("Missing \"$name\" argument")

/**
 * Converts a thrown [BridgeDriver] failure (unknown tag, timeout, ...) into an MCP tool-level
 * error rather than crashing the session.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun safeCall(block: suspend () -> CallToolResult): CallToolResult = try {
    block()
} catch (e: Exception) {
    CallToolResult(
        content = listOf(TextContent(text = e.message ?: e::class.simpleName ?: "Unknown error")),
        isError = true,
    )
}
