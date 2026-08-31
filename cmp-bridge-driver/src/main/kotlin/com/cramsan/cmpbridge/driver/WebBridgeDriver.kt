package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.HierarchyNode
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

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
    /** Runs [block], rethrowing any [PlaywrightException] (a dead page/browser) as [BridgeConnectionException]. */
    private inline fun <T> playwrightCall(block: () -> T): T = try {
        block()
    } catch (e: PlaywrightException) {
        throw BridgeConnectionException(e.message ?: "Playwright call failed", e)
    }

    override fun getHierarchy(): HierarchyNode = playwrightCall {
        val response = page.evaluate(WALK_ACCESSIBILITY_TREE_JS) as String
        json.decodeFromString(response)
    }

    override fun click(tag: String) = playwrightCall {
        val node = requireInteractableNode(tag, "click")
        page.mouse().click(node.x + node.width / 2.0, node.y + node.height / 2.0)
    }

    override fun setText(tag: String, text: String) {
        click(tag)
        // Select all existing content first so writing replaces it rather than inserting at the
        // cursor on top of it (issue #7). Typing a non-empty string over a selection replaces it
        // as a matter of course, but typing an empty string sends zero keystrokes — a common way
        // to clear a field would otherwise be a no-op — so clearing needs an explicit Delete.
        playwrightCall {
            page.keyboard().press("Control+A")
            if (text.isEmpty()) {
                page.keyboard().press("Delete")
            } else {
                page.keyboard().type(text)
            }
        }
    }

    override fun scroll(anchorTag: String, deltaY: Int) = playwrightCall {
        val node = requireInteractableNode(anchorTag, "scroll at")
        val x = node.x + node.width / 2.0
        val y = node.y + node.height / 2.0
        page.mouse().move(x, y)
        page.mouse().wheel(0.0, deltaY.toDouble())
    }

    override fun screenshot(): ByteArray = playwrightCall { page.screenshot(Page.ScreenshotOptions()) }

    override fun close() {
        page.close()
        browser.close()
        playwright.close()
    }

    companion object {
        private const val BRIDGE_TIMEOUT_MS = 30_000L
        private const val CHROMIUM_INSTALL_TIMEOUT_MS = 600_000L
        private const val OUTPUT_DRAIN_TIMEOUT_MS = 2_000L

        // Tracks a single install across every target/thread in the process — see
        // ensureChromiumInstalled(). installFuture is only ever written inside installLock.
        private val installLock = Any()

        @Volatile
        private var installFuture: Future<*>? = null
        private val installExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "cmp-bridge-playwright-install").apply { isDaemon = true }
            }

        /** Attaches to a wasmJs app that's already running at [url]. */
        fun connect(url: String): WebBridgeDriver = try {
            ensureChromiumInstalled()
            // Playwright.create() would otherwise install its whole default browser set
            // (Chromium, Firefox, WebKit) on first use — this driver only ever launches Chromium.
            val createOptions =
                Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"))
            val playwright = Playwright.create(createOptions)
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
            WebBridgeDriver(playwright, browser, page)
        } catch (e: PlaywrightException) {
            throw BridgeConnectionException(e.message ?: "Could not connect to a web app at $url", e)
        }

        /**
         * Installs Chromium alone (via Playwright's own CLI, in a separate JVM) if it isn't
         * already cached. Left to its own default, Playwright installs its entire browser family
         * (Chromium, Firefox, WebKit — hundreds of MiB) on first use even though this driver only
         * ever launches Chromium; scoping the install to just it here avoids that, and pairs with
         * `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` in [connect] so [Playwright.create] doesn't redo it.
         *
         * The install itself runs on a single background thread shared by every caller in this
         * process — concurrent [connect] calls (even for different targets, where
         * [BridgeSessionRegistry]'s own per-target locking doesn't help) share the one install
         * instead of racing into separate downloads. A caller never blocks on the download itself:
         * while it's running, every caller fails fast with a clear "try again" message instead of
         * hanging for however long the download takes — connecting a web target the first time on
         * a machine is expected to fail once or twice before it succeeds.
         */
        private fun ensureChromiumInstalled() {
            if (resolveCachedChromiumExecutable() != null) return

            val future = installFuture ?: synchronized(installLock) { installFuture ?: startChromiumInstall() }

            if (!future.isDone) {
                throw BridgeConnectionException(
                    "Chromium isn't installed yet — a first-time install (~500 MiB) just started " +
                        "in the background; see server logs for progress. Try this request again " +
                        "in a few minutes.",
                )
            }
            try {
                future.get()
            } catch (e: ExecutionException) {
                synchronized(installLock) { installFuture = null } // let the next caller retry
                throw (e.cause ?: e)
            }
        }

        /** Starts the background install (assumes [installLock] is held) and records its [Future]. */
        @Suppress("TooGenericExceptionCaught")
        private fun startChromiumInstall(): Future<*> {
            System.err.println(
                "[cmp-bridge-driver] Chromium not found — installing in the background (first web " +
                    "request only, ~500 MiB, can take several minutes on a slow connection)...",
            )
            return installExecutor.submit {
                try {
                    runChromiumInstallProcess()
                    System.err.println("[cmp-bridge-driver] Chromium install finished — ready for use.")
                } catch (e: Exception) {
                    System.err.println("[cmp-bridge-driver] Chromium install failed: ${e.message}")
                    throw e
                }
            }.also { installFuture = it }
        }

        private fun runChromiumInstallProcess() {
            val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
            val classpath = System.getProperty("java.class.path")
            val process =
                ProcessBuilder(javaBin, "-cp", classpath, "com.microsoft.playwright.CLI", "install", "chromium")
                    .redirectErrorStream(true)
                    .start()
            // The CLI's own progress bar prints as plain lines (not carriage-return redraws) once
            // it detects its output isn't a TTY, which is exactly this case — stream them straight
            // to stderr as they arrive so download progress is visible while it's happening, not
            // just a start/finish line. Runs on its own thread so it can't itself block the
            // waitFor/timeout logic below if the process hangs without producing more output.
            val outputThread =
                Thread({
                    process.inputStream.bufferedReader().forEachLine { line ->
                        System.err.println("[cmp-bridge-driver] $line")
                    }
                }, "cmp-bridge-playwright-install-output").apply {
                    isDaemon = true
                    start()
                }

            val finished = process.waitFor(CHROMIUM_INSTALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                // The CLI shells out to a bundled Node.js process to do the actual download;
                // destroying just this JVM leaves that child running unsupervised in the
                // background indefinitely. Kill the whole descendant tree, not just this process.
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            outputThread.join(OUTPUT_DRAIN_TIMEOUT_MS)

            if (finished && process.exitValue() == 0) return
            throw BridgeConnectionException(
                "Failed to install Chromium for Playwright within ${CHROMIUM_INSTALL_TIMEOUT_MS}ms",
            )
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
