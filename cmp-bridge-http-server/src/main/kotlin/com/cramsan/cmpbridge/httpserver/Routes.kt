package com.cramsan.cmpbridge.httpserver

import com.cramsan.cmpbridge.driver.BridgeConnectionException
import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.BridgeSessionRegistry
import com.cramsan.cmpbridge.driver.BridgeTarget
import com.cramsan.cmpbridge.driver.BridgeTimeoutException
import com.cramsan.cmpbridge.driver.InvalidTargetException
import com.cramsan.cmpbridge.driver.TagNotVisibleException
import com.cramsan.cmpbridge.driver.TagVisibility
import com.cramsan.cmpbridge.driver.TextComparator
import com.cramsan.cmpbridge.driver.UnknownTagException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
private data class ErrorResponse(val error: String)

private suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, cause: Throwable) {
    call.respond(status, ErrorResponse(cause.message ?: cause::class.simpleName ?: "Unknown error"))
}

/**
 * Envelope every request to `/bridge` is wrapped in: [target] selects which app instance to
 * attach to (resolved/cached via [BridgeSessionRegistry] — see there for eviction rules),
 * [operation] selects the [BridgeDriver] call to make (`"getHierarchy"`, `"click"`, `"setText"`,
 * `"scroll"`, `"screenshot"`, `"waitForTagVisibility"`, `"waitForText"`, or `"disconnect"` to end
 * the target's session early), and [payload] is decoded into that operation's own argument type.
 * Absent for operations that take none.
 */
@Serializable
private data class BridgeRequest(val target: BridgeTarget, val operation: String, val payload: JsonElement = JsonNull)

@Serializable
private data class ClickPayload(val tag: String)

@Serializable
private data class SetTextPayload(val tag: String, val text: String)

@Serializable
private data class ScrollPayload(val anchorTag: String, val deltaY: Int)

/**
 * [timeoutMs] left `null` defers to the resolved driver's own [BridgeDriver.defaultTimeoutMs]
 * (see [BridgeDriver.waitForTagVisibility]); [visibility] is required.
 */
@Serializable
private data class WaitForTagVisibilityPayload(
    val tag: String,
    val visibility: TagVisibility,
    val timeoutMs: Long? = null,
)

/**
 * [timeoutMs] left `null` defers to the resolved driver's own [BridgeDriver.defaultTimeoutMs]
 * (see [BridgeDriver.waitForText]); [comparator] is required.
 */
@Serializable
private data class WaitForTextPayload(val tag: String, val comparator: TextComparator, val timeoutMs: Long? = null)

private val payloadJson = Json { ignoreUnknownKeys = true }

/**
 * Wires [BridgeDriver]'s five core operations, plus the [BridgeDriver.waitForTagVisibility] and
 * [BridgeDriver.waitForText] convenience helpers and a `disconnect` operation to end a session
 * early, up behind a single endpoint, `POST /bridge`, dispatched on the request body's `operation`
 * field — e.g. `{"target": {"platform": "desktop"}, "operation": "setText", "payload": {"tag":
 * "...", "text": "..."}}`. [registry] resolves `target` to a driver per request (connecting and
 * caching lazily) — a thin adapter only, no business logic of its own beyond that resolution.
 */
fun Application.bridgeHttpModule(registry: BridgeSessionRegistry) {
    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        // BridgeDriver/BridgeSessionRegistry failures are typed (see BridgeDriverException's
        // subtypes) so each maps to the status code that best fits what actually went wrong,
        // rather than a uniform 400 — see README's "Driving an app over HTTP or MCP" section.
        exception<UnknownTagException> { call, cause -> respondError(call, HttpStatusCode.NotFound, cause) }
        exception<TagNotVisibleException> { call, cause -> respondError(call, HttpStatusCode.Conflict, cause) }
        exception<BridgeTimeoutException> { call, cause -> respondError(call, HttpStatusCode.GatewayTimeout, cause) }
        exception<BridgeConnectionException> { call, cause ->
            respondError(call, HttpStatusCode.ServiceUnavailable, cause)
        }
        exception<InvalidTargetException> { call, cause -> respondError(call, HttpStatusCode.BadRequest, cause) }
        // An unrecognized `operation`, malformed JSON, or a payload that doesn't decode into its
        // operation's expected shape — the request itself is at fault, not the app/driver.
        exception<IllegalArgumentException> { call, cause -> respondError(call, HttpStatusCode.BadRequest, cause) }
        exception<SerializationException> { call, cause -> respondError(call, HttpStatusCode.BadRequest, cause) }
        // call.receive<BridgeRequest>() decode failures (malformed/non-JSON body) surface as this
        // ktor-level wrapper rather than a bare SerializationException.
        exception<BadRequestException> { call, cause -> respondError(call, HttpStatusCode.BadRequest, cause) }
        // Anything else is unexpected/unmapped — a real server-side failure, not a client mistake.
        exception<Throwable> { call, cause -> respondError(call, HttpStatusCode.InternalServerError, cause) }
    }

    routing {
        post("/bridge") { handleBridgeRequest(call, registry) }
    }
}

/** Dispatches one decoded [BridgeRequest] to its [BridgeDriver] operation and writes the response. */
private suspend fun handleBridgeRequest(call: ApplicationCall, registry: BridgeSessionRegistry) {
    val request = call.receive<BridgeRequest>()
    logOperation(request) {
        if (request.operation == "disconnect") {
            registry.disconnect(request.target)
            call.respond(HttpStatusCode.OK)
            return@logOperation
        }

        val driver = registry.resolve(request.target)
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

            "waitForTagVisibility" -> {
                val payload = payloadJson.decodeFromJsonElement<WaitForTagVisibilityPayload>(request.payload)
                val timeoutMs = payload.timeoutMs
                val node =
                    if (timeoutMs != null) {
                        driver.waitForTagVisibility(payload.tag, payload.visibility, timeoutMs)
                    } else {
                        driver.waitForTagVisibility(payload.tag, payload.visibility)
                    }
                if (node != null) call.respond(node) else call.respond(HttpStatusCode.OK)
            }

            "waitForText" -> {
                val payload = payloadJson.decodeFromJsonElement<WaitForTextPayload>(request.payload)
                val timeoutMs = payload.timeoutMs
                val node =
                    if (timeoutMs != null) {
                        driver.waitForText(payload.tag, payload.comparator, timeoutMs)
                    } else {
                        driver.waitForText(payload.tag, payload.comparator)
                    }
                call.respond(node)
            }

            else -> throw IllegalArgumentException("Unknown operation \"${request.operation}\"")
        }
    }
}

/**
 * Logs [request]'s operation/target/payload to stderr *before* dispatching it, and its outcome
 * after — unlike a request-completion-only access log (e.g. [CallLogging]), this gives visibility
 * into a request that's still in flight (slow/stuck), not just ones that already finished.
 * Failures are logged then rethrown so [StatusPages] still turns them into the HTTP response.
 */
@Suppress("TooGenericExceptionCaught")
private inline fun logOperation(request: BridgeRequest, block: () -> Unit) {
    System.err.println(
        "[cmp-bridge-http] -> ${request.operation} target=${request.target} payload=${request.payload}",
    )
    try {
        block()
        System.err.println("[cmp-bridge-http] <- ${request.operation} ok")
    } catch (e: Exception) {
        System.err.println("[cmp-bridge-http] <- ${request.operation} failed: ${e.message ?: e::class.simpleName}")
        throw e
    }
}
