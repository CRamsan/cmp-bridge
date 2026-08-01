package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.HierarchyNode
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path

private val json = Json { ignoreUnknownKeys = true }

/**
 * Walks Compose Web's built-in accessibility DOM (`#cmp_a11y_root`) into the same [HierarchyNode]
 * shape the desktop bridge produces. Reached via `document.body.shadowRoot`, since Compose Web
 * mounts it inside a shadow root a plain `document.getElementById` can't see into.
 *
 * `enabled` is always `true` (Compose Web doesn't mark disabled elements); `actions` only infers
 * `"OnClick"` from an interactive ARIA role; `text` reports `""` rather than `null` for elements
 * with no textual content.
 *
 * Known gap: password fields aren't masked here — see
 * https://github.com/CRamsan/cmp-bridge/issues/2.
 */
private const val WALK_ACCESSIBILITY_TREE_JS = """
() => {
    const INTERACTIVE_ROLES = ['button', 'checkbox', 'switch', 'radio', 'tab'];
    function walk(el) {
        const rect = el.getBoundingClientRect();
        const role = el.getAttribute('role');
        return {
            testTag: el.id || null,
            role: role,
            text: el.innerText,
            contentDescription: el.getAttribute('aria-label'),
            x: rect.left,
            y: rect.top,
            width: rect.width,
            height: rect.height,
            enabled: true,
            actions: INTERACTIVE_ROLES.includes(role) ? ['OnClick'] : [],
            children: Array.from(el.children).map(walk),
        };
    }
    const root = document.body.shadowRoot?.getElementById('cmp_a11y_root');
    const children = root ? Array.from(root.children).map(walk) : [];
    return JSON.stringify({
        testTag: null, role: null, text: null, contentDescription: null,
        x: 0, y: 0, width: 0, height: 0, enabled: true, actions: [], children: children,
    });
}
"""

/**
 * Drives a real browser instance (Playwright) against a live wasmJs app. Connection-only — never
 * launches a dev server, so [close] never touches one. Pair [connect] with
 * [WasmDevServerProcess.launch] (optionally via [ManagedBridgeDriver]) for a disposable instance.
 */
class WebBridgeDriver private constructor(
    private val playwright: Playwright,
    private val browser: Browser,
    private val page: Page,
) : BridgeDriver {
    override fun getHierarchy(): HierarchyNode {
        val response = page.evaluate(WALK_ACCESSIBILITY_TREE_JS) as String
        return json.decodeFromString(response)
    }

    override fun click(tag: String) {
        val node = getBounds(tag) ?: error("Cannot click unknown tag \"$tag\"")
        page.mouse().click(node.x + node.width / 2.0, node.y + node.height / 2.0)
    }

    override fun setText(tag: String, text: String) {
        click(tag)
        page.keyboard().type(text)
    }

    /**
     * Known issue: unreliable in some sandboxed headless Chromium builds — see
     * https://github.com/CRamsan/cmp-bridge/issues/1.
     */
    override fun scroll(anchorTag: String, deltaY: Int) {
        val node = getBounds(anchorTag) ?: error("Cannot scroll at unknown tag \"$anchorTag\"")
        val x = node.x + node.width / 2.0
        val y = node.y + node.height / 2.0
        page.mouse().move(x, y)
        page.mouse().wheel(0.0, deltaY.toDouble())
    }

    override fun screenshot(): ByteArray = page.screenshot(Page.ScreenshotOptions())

    override fun close() {
        page.close()
        browser.close()
        playwright.close()
    }

    companion object {
        private const val BRIDGE_TIMEOUT_MS = 30_000L

        /** Attaches to a wasmJs app that's already running at [url]. */
        fun connect(url: String): WebBridgeDriver {
            val playwright = Playwright.create()
            val launchOptions = BrowserType.LaunchOptions().setHeadless(true)
            resolveCachedChromiumExecutable()?.let { launchOptions.setExecutablePath(it) }
            val browser = playwright.chromium().launch(launchOptions)
            val page = browser.newPage()
            page.navigate(url)
            // The accessibility root exists once ComposeViewport starts, but only gets children
            // after the first semantics sync — wait for that before treating the app as ready.
            page.waitForFunction(
                "() => document.body.shadowRoot?.getElementById('cmp_a11y_root')?.children.length > 0",
                null,
                Page.WaitForFunctionOptions().setTimeout(BRIDGE_TIMEOUT_MS.toDouble()),
            )
            return WebBridgeDriver(playwright, browser, page)
        }

        /**
         * Resolves an already-cached Chromium executable directly, bypassing Playwright's own
         * host-OS check (which can reject newer OS releases it doesn't recognize yet). Falls back
         * to Playwright's normal resolution if nothing is cached.
         */
        private fun resolveCachedChromiumExecutable(): Path? {
            val cacheDir = File(System.getProperty("user.home"), ".cache/ms-playwright")
            return cacheDir
                .listFiles { file -> file.isDirectory && file.name.startsWith("chromium-") }
                ?.sortedByDescending { it.name }
                ?.asSequence()
                ?.map { File(it, "chrome-linux64/chrome") }
                ?.firstOrNull { it.exists() }
                ?.toPath()
        }
    }
}
