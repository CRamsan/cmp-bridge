package com.cramsan.cmpbridge.driver

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Serves a static page that fakes Compose Web's accessibility DOM shape (a shadow root containing
 * `#cmp_a11y_root` with three children) — just enough for [WebBridgeDriver.connect]'s own
 * readiness check to pass, so tests get a real, usable [WebBridgeDriver] without needing an actual
 * Compose-Web app running. `text_field` is `contenteditable` rather than an `<input>` so its typed
 * content is readable back via `el.innerText`, the same property the real accessibility-walk JS
 * already reads — no fixture-specific read path needed. `invisible_tag` is zero-sized (no
 * padding/border) to exercise the "tag exists but has zero bounds" path (issue #12).
 */
private val FAKE_A11Y_PAGE = """
    <!DOCTYPE html>
    <html><body><script>
        const shadow = document.body.attachShadow({mode: 'open'});
        const root = document.createElement('div');
        root.id = 'cmp_a11y_root';
        const button = document.createElement('button');
        button.id = 'known_tag';
        button.setAttribute('role', 'button');
        root.appendChild(button);
        const textField = document.createElement('div');
        textField.id = 'text_field';
        textField.setAttribute('contenteditable', 'true');
        textField.setAttribute('role', 'textbox');
        textField.style.width = '200px';
        textField.style.height = '20px';
        textField.style.border = '1px solid black';
        root.appendChild(textField);
        const invisibleButton = document.createElement('button');
        invisibleButton.id = 'invisible_tag';
        invisibleButton.setAttribute('role', 'button');
        invisibleButton.style.width = '0px';
        invisibleButton.style.height = '0px';
        invisibleButton.style.padding = '0';
        invisibleButton.style.border = 'none';
        root.appendChild(invisibleButton);
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
    fun `click on an unknown tag throws UnknownTagException`() = runBlocking {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        val error = runCatching { driver!!.click("missing_tag") }.exceptionOrNull()

        assertTrue(error is UnknownTagException)
    }

    @Test
    fun `scroll on an unknown tag throws UnknownTagException`() = runBlocking {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        val error = runCatching { driver!!.scroll("missing_tag", 40) }.exceptionOrNull()

        assertTrue(error is UnknownTagException)
    }

    @Test
    fun `click on a zero-bounds tag throws TagNotVisibleException`() = runBlocking {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        val error = runCatching { driver!!.click("invisible_tag") }.exceptionOrNull()

        assertTrue(error is TagNotVisibleException)
    }

    @Test
    fun `scroll on a zero-bounds anchor throws TagNotVisibleException`() = runBlocking {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        val error = runCatching { driver!!.scroll("invisible_tag", 40) }.exceptionOrNull()

        assertTrue(error is TagNotVisibleException)
    }

    @Test
    fun `setText replaces existing content rather than appending`() = runBlocking {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        driver!!.setText("text_field", "Ada")
        driver!!.setText("text_field", "Grace")

        assertEquals("Grace", driver!!.getBounds("text_field")?.text)
    }

    @Test
    fun `setText with an empty string clears existing content rather than a no-op`() = runBlocking {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        driver!!.setText("text_field", "Gonzalez")
        driver!!.setText("text_field", "")

        // A cleared contenteditable can report innerText as "\n" (a lone <br> left behind) rather
        // than "" — blank either way, so that's what matters here, not the exact empty string.
        assertTrue(driver!!.getBounds("text_field")?.text.isNullOrBlank())
    }

    @Test
    fun `connect throws BridgeConnectionException when nothing is serving the url`() = runBlocking {
        val unusedPort = ServerSocket(0).use { it.localPort }

        val error = runCatching { WebBridgeDriver.connect("http://127.0.0.1:$unusedPort/") }.exceptionOrNull()

        assertTrue(error is BridgeConnectionException)
    }

    @Test
    fun `connect defaults to BridgeDriver's own default timeout and poll interval`() = runBlocking {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        assertEquals(BridgeDriver.DEFAULT_TIMEOUT, driver!!.defaultTimeout)
        assertEquals(BridgeDriver.DEFAULT_POLL_INTERVAL, driver!!.pollInterval)
    }

    @Test
    fun `connect honors custom defaultTimeout and pollInterval`() = runBlocking {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(
            pageServer!!.url,
            defaultTimeout = 777.milliseconds,
            pollInterval = 33.milliseconds,
        )

        assertEquals(777.milliseconds, driver!!.defaultTimeout)
        assertEquals(33.milliseconds, driver!!.pollInterval)
    }
}
