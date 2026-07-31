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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
private data class ErrorResponse(val error: String)

@Serializable
private data class ClickRequest(val tag: String)

@Serializable
private data class SetTextRequest(val tag: String, val text: String)

@Serializable
private data class ScrollRequest(val anchorTag: String, val deltaY: Int)

/**
 * Wires [driver]'s five operations up as plain REST endpoints — `GET /hierarchy`,
 * `POST /click`/`/setText`/`/scroll`, `GET /screenshot`. A thin adapter only: every request just
 * calls straight into [BridgeDriver], no business logic of its own.
 */
fun Application.bridgeHttpModule(driver: BridgeDriver) {
    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        // BridgeDriver reports failures (unknown tag, timeout, ...) as plain exceptions with a
        // human-readable message — surfaced as-is rather than a generic 500/stack trace.
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: cause::class.simpleName ?: "Unknown error"),
            )
        }
    }

    routing {
        get("/hierarchy") {
            call.respond(driver.getHierarchy())
        }

        post("/click") {
            val request = call.receive<ClickRequest>()
            driver.click(request.tag)
            call.respond(HttpStatusCode.OK)
        }

        post("/setText") {
            val request = call.receive<SetTextRequest>()
            driver.setText(request.tag, request.text)
            call.respond(HttpStatusCode.OK)
        }

        post("/scroll") {
            val request = call.receive<ScrollRequest>()
            driver.scroll(request.anchorTag, request.deltaY)
            call.respond(HttpStatusCode.OK)
        }

        get("/screenshot") {
            call.respondBytes(driver.screenshot(), ContentType.Image.PNG)
        }
    }
}
