package com.cramsan.cmpbridge.sample

import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.BridgeTimeoutException
import com.cramsan.cmpbridge.driver.DesktopAppProcess
import com.cramsan.cmpbridge.driver.DesktopBridgeDriver
import com.cramsan.cmpbridge.driver.ManagedBridgeDriver
import com.cramsan.cmpbridge.driver.TagVisibility
import com.cramsan.cmpbridge.driver.TextComparator
import com.cramsan.cmpbridge.driver.WasmDevServerProcess
import com.cramsan.cmpbridge.driver.WebBridgeDriver
import com.cramsan.cmpbridge.find
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the [App] sample screen through [BridgeDriver] on both platforms — desktop over the
 * socket bridge, web over Compose Multiplatform's accessibility DOM.
 *
 * Desktop exercises all five `BridgeDriver` operations end to end. Web exercises four of five —
 * see the web test's own comment for why.
 */
class DemoScenarioTest {
    @Test
    fun `desktop app is fully drivable through the bridge`() = runBlocking {
        val process = DesktopAppProcess.launch("com.cramsan.cmpbridge.sample.MainKt")
        // connect() is evaluated as a constructor argument, so if it throws, nothing has wrapped
        // `process` for cleanup yet — close it explicitly or a failed connect leaks the process.
        val driver = runCatching { DesktopBridgeDriver.connect(process.host, process.port) }
            .getOrElse {
                process.close()
                throw it
            }
        ManagedBridgeDriver(process, driver).use { d ->
            assertEquals("Count: 0", d.waitForText("counter_text", TextComparator.Present).text)

            // A freshly-launched window's very first click can silently miss — the bridge socket
            // accepts connections slightly before the window is input-ready. Retry the click.
            for (expected in 1..3) {
                d.clickUntilText("increment_button", "counter_text", "Count: $expected")
            }

            assertEquals("Hello, stranger!", d.waitForText("greeting_text", TextComparator.Present).text)
            d.setText("name_field", "Ada")
            assertEquals(
                "Hello, Ada!",
                d.waitForText("greeting_text", TextComparator.Equals("Hello, Ada!")).text,
            )

            // setText must replace a field's existing content, not paste at the cursor on top of
            // it (issue #7) — this would settle on "Hello, AdaGrace!" if it regressed. Longer
            // pre-existing text (matching the issue's own repro shape, "Gonzalez") lands a center
            // click mid-text rather than past its end. setTextUntilText retries like
            // clickUntilText does — synthetic AWT input delivery here is occasionally flaky
            // independent of this fix (see clickUntilText's own doc).
            d.setTextUntilText("name_field", "Grace", "greeting_text", "Hello, Grace!")
            d.setTextUntilText("name_field", "Gonzalez", "greeting_text", "Hello, Gonzalez!")

            // Exact repro from issue #7: clearing via setText(tag, "") must reset to empty, not
            // be a no-op (pasting an empty clipboard inserts nothing at a bare cursor position).
            d.setTextUntilText("name_field", "", "greeting_text", "Hello, stranger!")

            // Scroll units aren't equivalent across platforms (BridgeDriver.scroll's own doc) —
            // poll for the target row to appear rather than trust a fixed deltaY to land it.
            val targetTag = "item_${ITEM_COUNT - 1}"
            var found = d.getBounds(targetTag)
            var attempts = 0
            while (found == null && attempts < MAX_SCROLL_ATTEMPTS) {
                d.scroll("item_list", SCROLL_DELTA)
                found = d.getBounds(targetTag)
                attempts++
            }
            assertTrue(found != null, "Scrolled $attempts times but \"$targetTag\" never appeared")

            // Dropdown recipe (issue #11) — see README's "Driving a dropdown/select".
            assertEquals("Selected: none", d.waitForText("favorite_fruit_text", TextComparator.Present).text)
            d.clickUntilTagVisible("favorite_fruit_field", "favorite_fruit_field_option_0")
            d.clickUntilText("favorite_fruit_field_option_1", "favorite_fruit_text", "Selected: Banana")

            assertValidPng(d.screenshot())
        }
    }

    @Test
    fun `web app is drivable through the bridge for what this Compose Multiplatform version supports`() = runBlocking {
        val process = launchWasmDevServer()
        val driver = runCatching { WebBridgeDriver.connect(process.url) }
            .getOrElse {
                process.close()
                throw it
            }
        ManagedBridgeDriver(process, driver).use { d ->
            assertEquals("Count: 0", d.waitForText("counter_text", TextComparator.Present).text)

            for (expected in 1..3) {
                d.clickUntilText("increment_button", "counter_text", "Count: $expected")
            }

            // Not exercised on web: click()/setText() on "name_field", which permanently reports
            // zero bounds in the accessibility DOM (its text still reads correctly via
            // getHierarchy()).
            //
            // What *is* verified: getHierarchy() still reflects a zero-bounds node's live text,
            // click() works, scroll() works, and screenshot() produces a real PNG.
            val greeting = d.getHierarchy().find("greeting_text")
            assertEquals("Hello, stranger!", greeting?.text)

            // deltaY isn't equivalent across platforms (BridgeDriver.scroll's own doc): web's
            // wheel delta is pixel-based, unlike desktop's AWT scroll units, so it needs a much
            // larger per-call value to cover the same list within MAX_SCROLL_ATTEMPTS.
            val targetTag = "item_${ITEM_COUNT - 1}"
            var found = d.getBounds(targetTag)
            var attempts = 0
            while (found == null && attempts < MAX_SCROLL_ATTEMPTS) {
                d.scroll("item_list", WEB_SCROLL_DELTA)
                found = d.getBounds(targetTag)
                attempts++
            }
            assertTrue(found != null, "Scrolled $attempts times but \"$targetTag\" never appeared")

            // Not exercised on web: selecting a dropdown option — see ARCHITECTURE.md's "Known
            // platform gaps".
            val favoriteFruit = d.getHierarchy().find("favorite_fruit_text")
            assertEquals("Selected: none", favoriteFruit?.text)

            assertValidPng(d.screenshot())
        }
    }

    /**
     * Reference recipe for issue #14: `WasmDevServerProcess.launch` has no built-in notion of
     * Gradle or a repo root — it just runs whatever command it's handed. `e2e.repoRoot` is this
     * module's own convention (wired in `build.gradle.kts`) for getting the repo root into the
     * test JVM, not something the library reads itself.
     */
    private suspend fun launchWasmDevServer(): WasmDevServerProcess {
        val repoRoot = System.getProperty("e2e.repoRoot")
            ?: error("e2e.repoRoot not set — run via the jvmTest Gradle task in this module")
        return WasmDevServerProcess.launch(
            command = listOf(
                File(repoRoot, "gradlew").absolutePath,
                ":cmp-bridge-sample:wasmJsBrowserDevelopmentRun",
                "--console=plain",
            ),
            workingDir = File(repoRoot),
        )
    }

    private fun assertValidPng(png: ByteArray) {
        assertTrue(png.size > MIN_PNG_SIZE_BYTES, "Screenshot was suspiciously small: ${png.size} bytes")
        assertEquals(PNG_MAGIC, png.take(PNG_MAGIC.size))
    }

    /** Clicks [clickTag], re-clicking up to [maxAttempts] times until [readTag] shows [expected]. */
    private suspend fun BridgeDriver.clickUntilText(
        clickTag: String,
        readTag: String,
        expected: String,
        maxAttempts: Int = MAX_CLICK_ATTEMPTS,
    ) {
        repeat(maxAttempts) { attempt ->
            click(clickTag)
            try {
                waitForText(readTag, TextComparator.Equals(expected), timeout = CLICK_SETTLE_TIMEOUT)
                return
            } catch (e: BridgeTimeoutException) {
                if (attempt == maxAttempts - 1) throw e
            }
        }
    }

    /**
     * Clicks [clickTag], re-clicking up to [maxAttempts] times until [readTag] becomes visible —
     * mirrors [clickUntilText]'s retry for the same reason, for the "opens a menu/dialog" case
     * where there's no existing tag whose *text* changes, only a new tag that appears.
     */
    private suspend fun BridgeDriver.clickUntilTagVisible(
        clickTag: String,
        readTag: String,
        maxAttempts: Int = MAX_CLICK_ATTEMPTS,
    ) {
        repeat(maxAttempts) { attempt ->
            click(clickTag)
            try {
                waitForTagVisibility(readTag, TagVisibility.VISIBLE, timeout = CLICK_SETTLE_TIMEOUT)
                return
            } catch (e: BridgeTimeoutException) {
                if (attempt == maxAttempts - 1) throw e
            }
        }
    }

    /**
     * Calls [BridgeDriver.setText] on [tag], re-sending up to [maxAttempts] times until [readTag]
     * shows [expected] — mirrors [clickUntilText]'s own retry for the same reason: synthetic AWT
     * input delivery here occasionally needs a retry independent of whether the operation itself
     * is correct.
     */
    private suspend fun BridgeDriver.setTextUntilText(
        tag: String,
        text: String,
        readTag: String,
        expected: String,
        maxAttempts: Int = MAX_CLICK_ATTEMPTS,
    ) {
        repeat(maxAttempts) { attempt ->
            setText(tag, text)
            try {
                waitForText(readTag, TextComparator.Equals(expected), timeout = CLICK_SETTLE_TIMEOUT)
                return
            } catch (e: BridgeTimeoutException) {
                if (attempt == maxAttempts - 1) throw e
            }
        }
    }

    private companion object {
        const val MAX_SCROLL_ATTEMPTS = 60
        const val SCROLL_DELTA = 5
        const val WEB_SCROLL_DELTA = 300
        const val MAX_CLICK_ATTEMPTS = 5
        val CLICK_SETTLE_TIMEOUT: Duration = 3.seconds
        const val MIN_PNG_SIZE_BYTES = 100
        val PNG_MAGIC = listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    }
}
