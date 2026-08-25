package com.cramsan.cmpbridge.driver

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serves a static page that fakes Compose Web's accessibility DOM shape (a shadow root containing
 * `#cmp_a11y_root` with two children) — just enough for [WebBridgeDriver.connect]'s own readiness
 * check to pass, so tests get a real, usable [WebBridgeDriver] without needing an actual
 * Compose-Web app running. `text_field` is `contenteditable` rather than an `<input>` so its typed
 * content is readable back via `el.innerText`, the same property the real accessibility-walk JS
 * already reads — no fixture-specific read path needed.
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
    fun `setText replaces existing content rather than appending`() {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        driver!!.setText("text_field", "Ada")
        driver!!.setText("text_field", "Grace")

        assertEquals("Grace", driver!!.getBounds("text_field")?.text)
    }

    @Test
    fun `setText with an empty string clears existing content rather than a no-op`() {
        pageServer = FakeA11yPageServer()
        driver = WebBridgeDriver.connect(pageServer!!.url)

        driver!!.setText("text_field", "Gonzalez")
        driver!!.setText("text_field", "")

        // A cleared contenteditable can report innerText as "\n" (a lone <br> left behind) rather
        // than "" — blank either way, so that's what matters here, not the exact empty string.
        assertTrue(driver!!.getBounds("text_field")?.text.isNullOrBlank())
    }

    @Test
    fun `connect throws BridgeConnectionException when nothing is serving the url`() {
        val unusedPort = ServerSocket(0).use { it.localPort }

        val error = runCatching { WebBridgeDriver.connect("http://127.0.0.1:$unusedPort/") }.exceptionOrNull()

        assertTrue(error is BridgeConnectionException)
    }
}
