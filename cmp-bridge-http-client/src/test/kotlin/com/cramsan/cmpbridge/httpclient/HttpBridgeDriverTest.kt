package com.cramsan.cmpbridge.httpclient

import com.cramsan.cmpbridge.HierarchyNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BASE_URL = "http://127.0.0.1:8090"

private val ROOT_NODE =
    HierarchyNode(
        testTag = null,
        role = null,
        text = null,
        contentDescription = null,
        x = 0f,
        y = 0f,
        width = 100f,
        height = 100f,
        enabled = true,
        actions = emptySet(),
        children = emptyList(),
    )

class HttpBridgeDriverTest {
    private fun driver(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpBridgeDriver {
        val client = HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json() } }
        return HttpBridgeDriver(BASE_URL, client)
    }

    private suspend fun HttpRequestData.bodyText(): String = body.toByteArray().decodeToString()

    @Test
    fun `getHierarchy posts the getHierarchy operation and decodes the tree`() {
        var requestBody: String? = null
        val driver =
            driver { request ->
                requestBody = request.bodyText()
                respond(
                    content = """{"testTag":null,"role":null,"text":null,"contentDescription":null,""" +
                        """"x":0.0,"y":0.0,"width":100.0,"height":100.0,"enabled":true,"actions":[],"children":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }

        val hierarchy = driver.getHierarchy()

        assertEquals(ROOT_NODE, hierarchy)
        assertEquals("""{"operation":"getHierarchy"}""", requestBody)
    }

    @Test
    fun `click posts the tag as payload`() {
        var requestBody: String? = null
        val driver =
            driver { request ->
                requestBody = request.bodyText()
                respond("", HttpStatusCode.OK)
            }

        driver.click("my_tag")

        assertEquals("""{"operation":"click","payload":{"tag":"my_tag"}}""", requestBody)
    }

    @Test
    fun `setText posts tag and text as payload`() {
        var requestBody: String? = null
        val driver =
            driver { request ->
                requestBody = request.bodyText()
                respond("", HttpStatusCode.OK)
            }

        driver.setText("my_tag", "hello")

        assertEquals("""{"operation":"setText","payload":{"tag":"my_tag","text":"hello"}}""", requestBody)
    }

    @Test
    fun `scroll posts anchorTag and deltaY as payload`() {
        var requestBody: String? = null
        val driver =
            driver { request ->
                requestBody = request.bodyText()
                respond("", HttpStatusCode.OK)
            }

        driver.scroll("my_tag", 40)

        assertEquals("""{"operation":"scroll","payload":{"anchorTag":"my_tag","deltaY":40}}""", requestBody)
    }

    @Test
    fun `screenshot decodes the response body as PNG bytes`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val driver = driver { respond(content = png, status = HttpStatusCode.OK) }

        assertTrue(png.contentEquals(driver.screenshot()))
    }

    @Test
    fun `a non-200 response is surfaced as the server's error message`() {
        val driver =
            driver {
                respondError(HttpStatusCode.BadRequest, content = """{"error":"Unknown tag: missing"}""")
            }

        val failure = assertFailsWith<IllegalStateException> { driver.click("missing") }
        assertEquals("Unknown tag: missing", failure.message)
    }
}
