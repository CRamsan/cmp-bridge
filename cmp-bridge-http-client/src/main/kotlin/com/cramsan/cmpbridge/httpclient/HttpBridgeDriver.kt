package com.cramsan.cmpbridge.httpclient

import com.cramsan.cmpbridge.HierarchyNode
import com.cramsan.cmpbridge.driver.BridgeDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import java.net.Socket
import java.net.URI

private val json = Json { ignoreUnknownKeys = true }

/** Mirrors `cmp-bridge-http-server`'s private `BridgeRequest` envelope — kept in sync by hand. */
@Serializable
private data class BridgeRequest(val operation: String, val payload: JsonElement = JsonNull)

/** Mirrors `cmp-bridge-http-server`'s private `ErrorResponse`. */
@Serializable
private data class ErrorResponse(val error: String)

@Serializable
private data class ClickPayload(val tag: String)

@Serializable
private data class SetTextPayload(val tag: String, val text: String)

@Serializable
private data class ScrollPayload(val anchorTag: String, val deltaY: Int)

/**
 * Drives a running app's UI bridge through a `cmp-bridge-http-server` instance standing in front
 * of it, rather than connecting to the app directly — the same [BridgeDriver] contract as the
 * desktop/web drivers in `cmp-bridge-driver`, so callers can swap between them without touching
 * test code. Every call is a `POST {baseUrl}/bridge` with the same request envelope the server
 * expects: `{"operation": "...", "payload": {...}}`.
 *
 * Connection-only, like the other [BridgeDriver] implementations — never launches the http-server
 * process itself, so [close] only tears down this driver's own [HttpClient].
 */
class HttpBridgeDriver internal constructor(private val baseUrl: String, private val client: HttpClient) :
    BridgeDriver {

    override fun getHierarchy(): HierarchyNode = runBlocking {
        json.decodeFromString(execute("getHierarchy").bodyAsText())
    }

    override fun click(tag: String) {
        runBlocking { execute("click", ClickPayload(tag)) }
    }

    override fun setText(tag: String, text: String) {
        runBlocking { execute("setText", SetTextPayload(tag, text)) }
    }

    override fun scroll(anchorTag: String, deltaY: Int) {
        runBlocking { execute("scroll", ScrollPayload(anchorTag, deltaY)) }
    }

    override fun screenshot(): ByteArray = runBlocking { execute("screenshot").bodyAsBytes() }

    override fun close() = client.close()

    private suspend fun execute(operation: String): HttpResponse = post(BridgeRequest(operation))

    private suspend inline fun <reified P> execute(operation: String, payload: P): HttpResponse =
        post(BridgeRequest(operation, json.encodeToJsonElement(payload)))

    /** Posts [request] to `/bridge`, translating a non-200 response into a thrown [IllegalStateException]. */
    private suspend fun post(request: BridgeRequest): HttpResponse {
        val response =
            client.post("$baseUrl/bridge") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
        if (response.status != HttpStatusCode.OK) {
            val message =
                runCatching { json.decodeFromString<ErrorResponse>(response.bodyAsText()).error }
                    .getOrDefault("HTTP ${response.status}")
            error(message)
        }
        return response
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 250L

        /**
         * Attaches to a `cmp-bridge-http-server` instance already running at [baseUrl], e.g.
         * `"http://127.0.0.1:8090"`.
         */
        fun connect(baseUrl: String): HttpBridgeDriver {
            val normalized = baseUrl.trimEnd('/')
            waitUntilConnectable(URI(normalized))
            val client = HttpClient(CIO) { install(ContentNegotiation) { json() } }
            return HttpBridgeDriver(normalized, client)
        }

        private fun waitUntilConnectable(uri: URI) {
            val host = uri.host ?: error("Invalid base URL \"$uri\": missing host")
            val port = uri.port.takeIf { it != -1 } ?: error("Invalid base URL \"$uri\": missing port")
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket(host, port).close()
                    return
                } catch (_: Exception) {
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            }
            error(
                "Could not connect to a cmp-bridge-http-server at $uri within ${CONNECT_TIMEOUT_MS}ms — " +
                    "is it running there?",
            )
        }
    }
}
