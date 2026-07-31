package com.cramsan.cmpbridge.httpserver

import com.cramsan.cmpbridge.HierarchyNode
import com.cramsan.cmpbridge.driver.BridgeDriver
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

private class FakeBridgeDriver : BridgeDriver {
    var lastClickTag: String? = null
    var lastSetText: Pair<String, String>? = null
    var lastScroll: Pair<String, Int>? = null
    var shouldFailClick = false

    override fun getHierarchy(): HierarchyNode = ROOT_NODE

    override fun click(tag: String) {
        if (shouldFailClick) error("Unknown tag: $tag")
        lastClickTag = tag
    }

    override fun setText(tag: String, text: String) {
        lastSetText = tag to text
    }

    override fun scroll(anchorTag: String, deltaY: Int) {
        lastScroll = anchorTag to deltaY
    }

    override fun screenshot(): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    override fun close() = Unit
}

class RoutesTest {
    @Test
    fun `GET hierarchy returns the driver's tree`() = testApplication {
        application { bridgeHttpModule(FakeBridgeDriver()) }
        val response = client.get("/hierarchy")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"width\":100.0"))
    }

    @Test
    fun `POST click delegates to the driver`() = testApplication {
        val driver = FakeBridgeDriver()
        application { bridgeHttpModule(driver) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/click") {
                contentType(ContentType.Application.Json)
                setBody("""{"tag":"my_tag"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("my_tag", driver.lastClickTag)
    }

    @Test
    fun `POST setText delegates to the driver`() = testApplication {
        val driver = FakeBridgeDriver()
        application { bridgeHttpModule(driver) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/setText") {
                contentType(ContentType.Application.Json)
                setBody("""{"tag":"my_tag","text":"hello"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("my_tag" to "hello", driver.lastSetText)
    }

    @Test
    fun `POST scroll delegates to the driver`() = testApplication {
        val driver = FakeBridgeDriver()
        application { bridgeHttpModule(driver) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/scroll") {
                contentType(ContentType.Application.Json)
                setBody("""{"anchorTag":"my_tag","deltaY":40}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("my_tag" to 40, driver.lastScroll)
    }

    @Test
    fun `POST click on a failing driver returns 400 with the error message`() = testApplication {
        val driver = FakeBridgeDriver().apply { shouldFailClick = true }
        application { bridgeHttpModule(driver) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/click") {
                contentType(ContentType.Application.Json)
                setBody("""{"tag":"missing"}""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unknown tag"))
    }

    @Test
    fun `GET screenshot returns PNG bytes`() = testApplication {
        application { bridgeHttpModule(FakeBridgeDriver()) }
        val response = client.get("/screenshot")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0x89.toByte(), response.bodyAsBytes()[0])
    }
}
