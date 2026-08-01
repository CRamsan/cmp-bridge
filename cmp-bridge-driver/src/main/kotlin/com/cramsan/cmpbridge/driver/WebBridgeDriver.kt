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
 * Walks Compose Web's own built-in, hidden ARIA accessibility DOM (`#cmp_a11y_root`, enabled by
 * default — `ComposeViewportConfiguration.isA11YEnabled = true`) into the same [HierarchyNode]
 * shape [DesktopBridgeServer] builds from the real semantics tree. No custom JS export is needed
 * on the app side at all: Compose Web already maintains this tree for real screen readers.
 *
 * Reached via `document.body.shadowRoot`, not a plain `document.getElementById` — Compose Web
 * attaches its canvas and this accessibility root inside a real shadow root on `<body>`
 * (confirmed live: `document.body.attachShadow(...)`), which a light-DOM query can't see into.
 *
 * `enabled` can't be read reliably from this DOM (Compose Web doesn't mark disabled elements),
 * so it's always reported `true`; `actions` can only reliably infer `"OnClick"`, inferred from an
 * interactive ARIA role, since that's the only action Compose Web wires to a real DOM listener.
 * `text` is read as `el.innerText` directly (never coerced to `null` on empty) — `innerText` is
 * never itself `null`/absent the way a semantics property can be, so an element with no textual
 * content reports `""` here rather than `null` (confirmed live: naively falling back to `null` on
 * an empty string via `||` broke reading a freshly-focused, still-empty text field).
 *
 * **Known unfixable gap**: unlike `DesktopBridgeServer` (which suppresses `text`/
 * `contentDescription` for password fields), this walk has no way to suppress a password field's
 * real text. Compose Web's own accessibility-DOM sync doesn't check for
 * `SemanticsProperties.Password` before setting `innerText`, so a field marked
 * `Modifier.semantics { password() }` can still leak its real content through `text` here — there's
 * no client-side workaround, the fix would have to happen upstream in Compose Web itself.
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
 * Drives a real browser instance against a live wasmJs app — connection-only, never launches a
 * dev server itself, so [close] never touches one. Reads are done entirely through Compose Web's
 * own built-in accessibility DOM (see [WALK_ACCESSIBILITY_TREE_JS]) — clicks and typing go
 * through Playwright's own real input pipeline (`page.mouse()`/`page.keyboard()`), the same as a
 * real user's input, never a semantics shortcut.
 *
 * For a test that wants its own disposable dev server, pair [connect] with
 * [WasmDevServerProcess.launch] (optionally via [ManagedBridgeDriver] for single-call teardown).
 * A real Playwright browser/page is always created here regardless of how the URL came to be —
 * unavoidable, since that's what actually reads the DOM.
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
     * `page.mouse().wheel()` — the standard synthetic input for a wheel-scroll, matching how a
     * real desktop browser user actually scrolls. **Known issue in this sandbox's headless
     * Chromium fallback build** (confirmed live, not yet root-caused): the wheel event doesn't
     * corrupt anything visibly (no console error, no page error) but every currently-composed
     * element's reported bounds collapse to a degenerate `(0,0,0,0)` and never recover — see
     * [BridgeDriverWebTest]'s scroll test, disabled with this same note. A manual synthetic
     * mouse-drag gesture was also tried as a substitute and produced no scroll effect at all in
     * this same environment, so it isn't a viable workaround either. Left as `wheel()` since it's
     * the semantically correct approach and may well work in a real (non-fallback-build) browser.
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
            // Compose Web attaches everything (canvas + the accessibility DOM) inside a real
            // shadow root on <body> (`document.body.attachShadow(...)`), not the light DOM —
            // confirmed live: plain document.getElementById never finds it. The accessibility
            // root exists as soon as ComposeViewport starts (isA11YEnabled defaults to true),
            // but only gets children once the first semantics sync runs.
            page.waitForFunction(
                "() => document.body.shadowRoot?.getElementById('cmp_a11y_root')?.children.length > 0",
                null,
                Page.WaitForFunctionOptions().setTimeout(BRIDGE_TIMEOUT_MS.toDouble()),
            )
            return WebBridgeDriver(playwright, browser, page)
        }

        /**
         * Playwright normally resolves its own bundled browser, but that path insists on
         * recognizing the host OS first — which fails outright on OS releases newer than
         * whatever this Playwright version's allowlist knows about (seen live: "does not support
         * chromium on ubuntu26.04-x64"), even when a perfectly usable browser is already
         * installed. Prefer whatever's already cached under Playwright's standard cache dir
         * (shared across the Node and Java clients) and hand it to Playwright explicitly via
         * `executablePath`, sidestepping that OS check entirely. Falls back to Playwright's own
         * resolution (via `installPlaywrightBrowsers`, run once per machine) if nothing is cached.
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
