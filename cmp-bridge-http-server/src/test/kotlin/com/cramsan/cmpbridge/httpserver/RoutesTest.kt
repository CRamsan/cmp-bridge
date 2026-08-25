package com.cramsan.cmpbridge.httpserver

import com.cramsan.cmpbridge.HierarchyNode
import com.cramsan.cmpbridge.driver.BridgeConnectionException
import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.BridgeSessionRegistry
import com.cramsan.cmpbridge.driver.UnknownTagException
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

/** A failure type the StatusPages block has no dedicated handler for, to exercise its 500 fallback. */
private class UnmappedDriverFailure(message: String) : Exception(message)

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
    var clickFailure: (() -> Nothing)? = null
    var tree: HierarchyNode = ROOT_NODE
    var closed = false

    override fun getHierarchy(): HierarchyNode = tree

    override fun click(tag: String) {
        clickFailure?.invoke()
        if (shouldFailClick) throw UnknownTagException("Unknown tag: $tag")
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

/** A registry using the real default connect logic, to exercise its own target validation. */
private fun defaultConnectRegistry() = BridgeSessionRegistry(
    maxIdleMs = TEST_SESSION_LIMIT_MS,
    maxSessionMs = TEST_SESSION_LIMIT_MS,
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
    fun `POST bridge with click on an unknown tag returns 404 with the error message`() = testApplication {
        val driver = FakeBridgeDriver().apply { shouldFailClick = true }
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"desktop"},"operation":"click","payload":{"tag":"missing"}}""")
            }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("Unknown tag"))
    }

    @Test
    fun `POST bridge with click on a driver-level connection failure returns 503`() = testApplication {
        val driver = FakeBridgeDriver().apply {
            clickFailure = { throw BridgeConnectionException("Connection to the app failed") }
        }
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"desktop"},"operation":"click","payload":{"tag":"my_tag"}}""")
            }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("Connection to the app failed"))
    }

    @Test
    fun `POST bridge with an unexpected driver failure returns 500`() = testApplication {
        val driver = FakeBridgeDriver().apply {
            clickFailure = { throw UnmappedDriverFailure("boom") }
        }
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"desktop"},"operation":"click","payload":{"tag":"my_tag"}}""")
            }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(response.bodyAsText().contains("boom"))
    }

    @Test
    fun `POST bridge with an unknown platform target returns 400`() = testApplication {
        application { bridgeHttpModule(defaultConnectRegistry()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"bogus"},"operation":"getHierarchy"}""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("platform"))
    }

    @Test
    fun `POST bridge with a web target missing url returns 400`() = testApplication {
        application { bridgeHttpModule(defaultConnectRegistry()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""{"target":{"platform":"web"},"operation":"getHierarchy"}""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("url"))
    }

    @Test
    fun `POST bridge with a malformed JSON body returns 400`() = testApplication {
        application { bridgeHttpModule(testRegistry(FakeBridgeDriver())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody("""not json""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
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
    fun `POST bridge with waitForTag on a tag that never appears returns 504 after the timeout`() = testApplication {
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

        assertEquals(HttpStatusCode.GatewayTimeout, response.status)
        assertTrue(response.bodyAsText().contains("did not appear"))
    }

    @Test
    fun `POST bridge with waitForText Present returns the node once its text is settled`() = testApplication {
        val driver = FakeBridgeDriver().apply { tree = TAGGED_NODE }
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"target":{"platform":"desktop"},"operation":"waitForText",""" +
                        """"payload":{"tag":"my_tag","comparator":{"type":"present"},"timeoutMs":1000}}""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"text\":\"hello\""))
    }

    @Test
    fun `POST bridge with waitForText Present on a tag whose text stays null returns 504 after the timeout`() =
        testApplication {
            val driver = FakeBridgeDriver().apply { tree = SILENT_NODE }
            application { bridgeHttpModule(testRegistry(driver)) }
            val client = createClient { install(ContentNegotiation) { json() } }

            val response =
                client.post("/bridge") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"target":{"platform":"desktop"},"operation":"waitForText",""" +
                            """"payload":{"tag":"silent_tag","comparator":{"type":"present"},"timeoutMs":200}}""",
                    )
                }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            assertTrue(response.bodyAsText().contains("never satisfied"))
        }

    @Test
    fun `POST bridge with waitForText Present on a tag that never appears returns 504 after the timeout`() =
        testApplication {
            application { bridgeHttpModule(testRegistry(FakeBridgeDriver())) }
            val client = createClient { install(ContentNegotiation) { json() } }

            val response =
                client.post("/bridge") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"target":{"platform":"desktop"},"operation":"waitForText",""" +
                            """"payload":{"tag":"missing","comparator":{"type":"present"},"timeoutMs":200}}""",
                    )
                }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            assertTrue(response.bodyAsText().contains("never satisfied"))
        }

    @Test
    fun `POST bridge with waitForText Equals returns the node once its text matches`() = testApplication {
        val driver = FakeBridgeDriver().apply { tree = TAGGED_NODE }
        application { bridgeHttpModule(testRegistry(driver)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"target":{"platform":"desktop"},"operation":"waitForText",""" +
                        """"payload":{"tag":"my_tag","comparator":{"type":"equals","value":"hello"},""" +
                        """"timeoutMs":1000}}""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"text\":\"hello\""))
    }

    @Test
    fun `POST bridge with waitForText Equals on text that never matches returns 504 after the timeout`() =
        testApplication {
            val driver = FakeBridgeDriver().apply { tree = TAGGED_NODE }
            application { bridgeHttpModule(testRegistry(driver)) }
            val client = createClient { install(ContentNegotiation) { json() } }

            val response =
                client.post("/bridge") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"target":{"platform":"desktop"},"operation":"waitForText",""" +
                            """"payload":{"tag":"my_tag","comparator":{"type":"equals","value":"goodbye"},""" +
                            """"timeoutMs":200}}""",
                    )
                }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            assertTrue(response.bodyAsText().contains("never satisfied"))
        }

    @Test
    fun `POST bridge with waitForTagGone returns 200 once the tag is gone`() = testApplication {
        application { bridgeHttpModule(testRegistry(FakeBridgeDriver())) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/bridge") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"target":{"platform":"desktop"},"operation":"waitForTagGone",""" +
                        """"payload":{"tag":"missing","timeoutMs":1000}}""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST bridge with waitForTagGone on a tag that never disappears returns 504 after the timeout`() =
        testApplication {
            val driver = FakeBridgeDriver().apply { tree = TAGGED_NODE }
            application { bridgeHttpModule(testRegistry(driver)) }
            val client = createClient { install(ContentNegotiation) { json() } }

            val response =
                client.post("/bridge") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"target":{"platform":"desktop"},"operation":"waitForTagGone",""" +
                            """"payload":{"tag":"my_tag","timeoutMs":200}}""",
                    )
                }

            assertEquals(HttpStatusCode.GatewayTimeout, response.status)
            assertTrue(response.bodyAsText().contains("still present"))
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
