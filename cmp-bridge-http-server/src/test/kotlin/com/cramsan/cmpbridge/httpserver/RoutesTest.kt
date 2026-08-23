package com.cramsan.cmpbridge.httpserver

import com.cramsan.cmpbridge.HierarchyNode
import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.BridgeSessionRegistry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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

private const val TEST_SESSION_LIMIT_MS = 3_600_000L

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

private val TAGGED_NODE =
    ROOT_NODE.copy(
        children =
        listOf(
            HierarchyNode(
                testTag = "my_tag",
                role = null,
                text = "hello",
                contentDescription = null,
                x = 0f,
                y = 0f,
                width = 10f,
                height = 10f,
                enabled = true,
                actions = emptySet(),
                children = emptyList(),
            ),
        ),
    )

/** Bounds have settled (non-zero) but text is still null — the case waitForText guards against. */
private val SILENT_NODE =
    ROOT_NODE.copy(
        children =
        listOf(
            HierarchyNode(
                testTag = "silent_tag",
                role = null,
                text = null,
                contentDescription = null,
                x = 0f,
                y = 0f,
                width = 10f,
                height = 10f,
                enabled = true,
                actions = emptySet(),
                children = emptyList(),
            ),
        ),
    )

private class FakeBridgeDriver : BridgeDriver {
    var lastClickTag: String? = null
    var lastSetText: Pair<String, String>? = null
    var lastScroll: Pair<String, Int>? = null
    var shouldFailClick = false
    var tree: HierarchyNode = ROOT_NODE
    var closed = false

    override fun getHierarchy(): HierarchyNode = tree

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

    override fun close() {
        closed = true
    }
}

/** A registry whose [connect] always returns [driver], regardless of the request's target. */
private fun testRegistry(driver: BridgeDriver) = BridgeSessionRegistry(
    maxIdleMs = TEST_SESSION_LIMIT_MS,
    maxSessionMs = TEST_SESSION_LIMIT_MS,
    connect = { driver },
)

class RoutesTest {
    @Test
    fun `POST bridge with getHierarchy returns the driver's tree`() = testApplication {
        application { bridgeHttpModule(testRegistry(FakeBridgeDriver())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"desktop"},"operation":"getHierarchy"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"width\":100.0"))
    }

    @Test
    fun `POST bridge with click delegates to the driver`() = testApplication {
        val driver = FakeBridgeDriver()
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"desktop"},"operation":"click","payload":{"tag":"my_tag"}}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("my_tag", driver.lastClickTag)
    }

    @Test
    fun `POST bridge with setText delegates to the driver`() = testApplication {
        val driver = FakeBridgeDriver()
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"target":{"platform":"desktop"},"operation":"setText",""" +
                        """"payload":{"tag":"my_tag","text":"hello"}}""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("my_tag" to "hello", driver.lastSetText)
    }

    @Test
    fun `POST bridge with scroll delegates to the driver`() = testApplication {
        val driver = FakeBridgeDriver()
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"target":{"platform":"desktop"},"operation":"scroll",""" +
                        """"payload":{"anchorTag":"my_tag","deltaY":40}}""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("my_tag" to 40, driver.lastScroll)
    }

    @Test
    fun `POST bridge with screenshot returns PNG bytes`() = testApplication {
        application { bridgeHttpModule(testRegistry(FakeBridgeDriver())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"desktop"},"operation":"screenshot"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0x89.toByte(), response.bodyAsBytes()[0])
    }

    @Test
    fun `POST bridge with click on a failing driver returns 400 with the error message`() = testApplication {
        val driver = FakeBridgeDriver().apply { shouldFailClick = true }
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"desktop"},"operation":"click","payload":{"tag":"missing"}}""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unknown tag"))
    }

    @Test
    fun `POST bridge with waitForTag returns the node once it's already present`() = testApplication {
        val driver = FakeBridgeDriver().apply { tree = TAGGED_NODE }
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"target":{"platform":"desktop"},"operation":"waitForTag",""" +
                        """"payload":{"tag":"my_tag","timeoutMs":1000}}""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"testTag\":\"my_tag\""))
    }

    @Test
    fun `POST bridge with waitForTag on a tag that never appears returns 400 after the timeout`() = testApplication {
        application { bridgeHttpModule(testRegistry(FakeBridgeDriver())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"target":{"platform":"desktop"},"operation":"waitForTag",""" +
                        """"payload":{"tag":"missing","timeoutMs":200}}""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("did not appear"))
    }

    @Test
    fun `POST bridge with waitForText returns the node once its text is settled`() = testApplication {
        val driver = FakeBridgeDriver().apply { tree = TAGGED_NODE }
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"target":{"platform":"desktop"},"operation":"waitForText",""" +
                        """"payload":{"tag":"my_tag","timeoutMs":1000}}""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"text\":\"hello\""))
    }

    @Test
    fun `POST bridge with waitForText on a tag whose text stays null returns 400 after the timeout`() =
        testApplication {
            val driver = FakeBridgeDriver().apply { tree = SILENT_NODE }
            application { bridgeHttpModule(testRegistry(driver)) }
            val client = createClient { install(ContentNegotiation) { json() } }

            val response =
                client.post("/bridge") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"target":{"platform":"desktop"},"operation":"waitForText",""" +
                            """"payload":{"tag":"silent_tag","timeoutMs":200}}""",
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("never settled"))
        }

    @Test
    fun `POST bridge with waitForText on a tag that never appears returns 400 after the timeout`() = testApplication {
        application { bridgeHttpModule(testRegistry(FakeBridgeDriver())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"target":{"platform":"desktop"},"operation":"waitForText",""" +
                        """"payload":{"tag":"missing","timeoutMs":200}}""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("never settled"))
    }

    @Test
    fun `POST bridge with an unknown operation returns 400`() = testApplication {
        application { bridgeHttpModule(testRegistry(FakeBridgeDriver())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"desktop"},"operation":"bogus"}""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Unknown operation"))
    }

    @Test
    fun `POST bridge resolves the same target only once across repeated requests`() = testApplication {
        var connectCount = 0
        val registry = BridgeSessionRegistry(
            maxIdleMs = TEST_SESSION_LIMIT_MS,
            maxSessionMs = TEST_SESSION_LIMIT_MS,
            connect = {
                connectCount++
                FakeBridgeDriver()
            },
        )
        application { bridgeHttpModule(registry) }
        val client = createClient { install(ContentNegotiation) { json() } }

        repeat(2) {
            val response =
                client.post("/bridge") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"target":{"platform":"desktop"},"operation":"getHierarchy"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        assertEquals(1, connectCount)
    }

    @Test
    fun `POST bridge with disconnect closes the session so the next request reconnects`() = testApplication {
        var connectCount = 0
        val registry = BridgeSessionRegistry(
            maxIdleMs = TEST_SESSION_LIMIT_MS,
            maxSessionMs = TEST_SESSION_LIMIT_MS,
            connect = {
                connectCount++
                FakeBridgeDriver()
            },
        )
        application { bridgeHttpModule(registry) }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.post("/bridge") {
            contentType(ContentType.Application.Json)
            setBody("""{"target":{"platform":"desktop"},"operation":"getHierarchy"}""")
        }
        val disconnectResponse =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"desktop"},"operation":"disconnect"}""")
            }
        client.post("/bridge") {
            contentType(ContentType.Application.Json)
            setBody("""{"target":{"platform":"desktop"},"operation":"getHierarchy"}""")
        }

        assertEquals(HttpStatusCode.OK, disconnectResponse.status)
        assertEquals(2, connectCount)
    }
}
