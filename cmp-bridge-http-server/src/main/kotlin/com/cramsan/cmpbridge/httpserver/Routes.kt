package com.cramsan.cmpbridge.httpserver

import com.cramsan.cmpbridge.driver.BridgeDriver
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
private data class ErrorResponse(val error: String)

/**
 * Envelope every request to `/bridge` is wrapped in: [operation] selects the [BridgeDriver] call
 * to make (`"getHierarchy"`, `"click"`, `"setText"`, `"scroll"`, `"screenshot"`, `"waitForTag"`,
 * or `"waitForText"`), [payload] is decoded into that operation's own argument type. Absent for
 * operations that take none.
 */
@Serializable
private data class BridgeRequest(val operation: String, val payload: JsonElement = JsonNull)

@Serializable
private data class ClickPayload(val tag: String)

@Serializable
private data class SetTextPayload(val tag: String, val text: String)

@Serializable
private data class ScrollPayload(val anchorTag: String, val deltaY: Int)

/** [timeoutMs] mirrors [BridgeDriver.waitForTag]'s own default. */
@Serializable
private data class WaitForTagPayload(val tag: String, val timeoutMs: Long = 15_000)

/** [timeoutMs] mirrors [BridgeDriver.waitForText]'s own default. */
@Serializable
private data class WaitForTextPayload(val tag: String, val timeoutMs: Long = 15_000)

private val payloadJson = Json { ignoreUnknownKeys = true }

/**
 * Wires [driver]'s five core operations, plus the [BridgeDriver.waitForTag] and
 * [BridgeDriver.waitForText] convenience helpers, up behind a single endpoint, `POST /bridge`,
 * dispatched on the request body's `operation` field — e.g. `{"operation": "setText", "payload":
 * {"tag": "...", "text": "..."}}`. A thin adapter only: every operation just calls straight into
 * [BridgeDriver], no business logic of its own.
 */
fun Application.bridgeHttpModule(driver: BridgeDriver) {
    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        // BridgeDriver reports failures (unknown tag, timeout, ...) — and an unrecognized
        // `operation` — as plain exceptions with a human-readable message, surfaced as-is rather
        // than a generic 500/stack trace.
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: cause::class.simpleName ?: "Unknown error"),
            )
        }
    }

    routing {
        post("/bridge") {
            val request = call.receive<BridgeRequest>()
            when (request.operation) {
                "getHierarchy" -> call.respond(driver.getHierarchy())

                "click" -> {
                    val payload = payloadJson.decodeFromJsonElement<ClickPayload>(request.payload)
                    driver.click(payload.tag)
                    call.respond(HttpStatusCode.OK)
                }

                "setText" -> {
                    val payload = payloadJson.decodeFromJsonElement<SetTextPayload>(request.payload)
                    driver.setText(payload.tag, payload.text)
                    call.respond(HttpStatusCode.OK)
                }

                "scroll" -> {
                    val payload = payloadJson.decodeFromJsonElement<ScrollPayload>(request.payload)
                    driver.scroll(payload.anchorTag, payload.deltaY)
                    call.respond(HttpStatusCode.OK)
                }

                "screenshot" -> call.respondBytes(driver.screenshot(), ContentType.Image.PNG)

                "waitForTag" -> {
                    val payload = payloadJson.decodeFromJsonElement<WaitForTagPayload>(request.payload)
                    call.respond(driver.waitForTag(payload.tag, payload.timeoutMs))
                }

                "waitForText" -> {
                    val payload = payloadJson.decodeFromJsonElement<WaitForTextPayload>(request.payload)
                    call.respond(driver.waitForText(payload.tag, payload.timeoutMs))
                }

                else -> error("Unknown operation \"${request.operation}\"")
            }
        }
    }
}
