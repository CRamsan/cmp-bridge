package com.cramsan.cmpbridge.driver

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Serves a static page that fakes Compose Web's accessibility DOM shape (a shadow root containing
 * `#cmp_a11y_root` with one child) — just enough for [WebBridgeDriver.connect]'s own readiness
 * check to pass, so tests get a real, usable [WebBridgeDriver] without needing an actual
 * Compose-Web app running.
 */
private val FAKE_A11Y_PAGE = """
    <!DOCTYPE html>
    <html><body><script>
        const shadow = document.body.attachShadow({mode: 'open'});
        const root = document.createElement('div');
        root.id = 'cmp_a11y_root';
        const child = document.createElement('button');
        child.id = 'known_tag';
        child.setAttribute('role', 'button');
        root.appendChild(child);
        shadow.appendChild(root);
    </script></body></html>
""".trimIndent()

private class FakeA11yPageServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = FAKE_A11Y_PAGE.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/"

    override fun close() = server.stop(0)
}

class WebBridgeDriverTest {
    private var pageServer: FakeA11yPageServer? = null
    private var driver: WebBridgeDriver? = null

    @AfterTest
    fun tearDown() {
        driver?.close()
        pageServer?.close()
    }

    @Test
    fun `click on an unknown tag throws UnknownTagException`() {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        val error = runCatching { driver!!.click("missing_tag") }.exceptionOrNull()

        assertTrue(error is UnknownTagException)
    }

    @Test
    fun `scroll on an unknown tag throws UnknownTagException`() {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        val error = runCatching { driver!!.scroll("missing_tag", 40) }.exceptionOrNull()

        assertTrue(error is UnknownTagException)
    }

    @Test
    fun `connect throws BridgeConnectionException when nothing is serving the url`() {
        val unusedPort = ServerSocket(0).use { it.localPort }

        val error = runCatching { WebBridgeDriver.connect("http://127.0.0.1:$unusedPort/") }.exceptionOrNull()

        assertTrue(error is BridgeConnectionException)
    }
}
