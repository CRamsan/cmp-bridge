package com.cramsan.cmpbridge.sample

import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.DesktopAppProcess
import com.cramsan.cmpbridge.driver.DesktopBridgeDriver
import com.cramsan.cmpbridge.driver.ManagedBridgeDriver
import com.cramsan.cmpbridge.driver.WasmDevServerProcess
import com.cramsan.cmpbridge.driver.WebBridgeDriver
import com.cramsan.cmpbridge.find
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the [App] sample screen through [BridgeDriver] on both platforms — desktop over the
 * socket bridge, web over Compose Multiplatform's own accessibility DOM — as the concrete proof
 * that `BridgeDriver` is one contract with two backends. See `CLAUDE.md` for why no equivalent
 * test existed before this: the original monorepo's E2E suites depended on app fixtures
 * (`framework-samples`/`edifikana`) that don't exist standalone, so extraction dropped them. This
 * module is that fixture, rebuilt.
 *
 * Desktop exercises all five `BridgeDriver` operations end to end. Web exercises three of five —
 * see the web test's own doc comment for the two it deliberately doesn't, and why.
 */
class DemoScenarioTest {
    @Test
    fun `desktop app is fully drivable through the bridge`() {
        val process = DesktopAppProcess.launch("com.cramsan.cmpbridge.sample.MainKt")
        // connect() is evaluated as a constructor argument, so if it throws, nothing has wrapped
        // `process` for cleanup yet — close it explicitly on that path or a failed connect leaks
        // the launched app process (confirmed live for the web equivalent of this same shape).
        val driver = runCatching { DesktopBridgeDriver.connect(process.host, process.port) }
            .getOrElse { process.close(); throw it }
        ManagedBridgeDriver(process, driver).use { d ->
            assertEquals("Count: 0", d.waitForTag("counter_text").text)

            // A freshly-launched window's very first click can silently miss (confirmed live: the
            // bridge's socket accepts connections slightly before the window is actually
            // input-ready, the same class of settling gap CLAUDE.md documents for transient
            // degenerate bounds) — so retry the click itself, not just the read.
            for (expected in 1..3) {
                d.clickUntilText("increment_button", "counter_text", "Count: $expected")
            }

            assertEquals("Hello, stranger!", d.waitForTag("greeting_text").text)
            d.setText("name_field", "Ada")
            assertEquals("Hello, Ada!", d.waitForText("greeting_text", "Hello, Ada!"))

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

            assertValidPng(d.screenshot())
        }
    }

    @Test
    fun `web app is drivable through the bridge for what this Compose Multiplatform version supports`() {
        val process = WasmDevServerProcess.launch(":cmp-bridge-sample")
        val driver = runCatching { WebBridgeDriver.connect(process.url) }
            .getOrElse { process.close(); throw it }
        ManagedBridgeDriver(process, driver).use { d ->
            assertEquals("Count: 0", d.waitForTag("counter_text").text)

            for (expected in 1..3) {
                d.clickUntilText("increment_button", "counter_text", "Count: $expected")
            }

            // NOT exercised on web, both confirmed live against Compose Multiplatform 1.10.3's
            // wasmJs target, neither fixable from this app:
            //
            // 1. click()/setText() on "name_field": a real text-input element (tried both
            //    material3 TextField and the simpler BasicTextField — same result either way)
            //    permanently reports (0,0,0,0) bounds in the accessibility DOM, and so does
            //    whatever Column sibling immediately follows it. Stable across 12+ seconds of
            //    polling, so this is not the transient settling gap in CLAUDE.md gotcha #1 — a
            //    getBounds()-based click can never locate it. getHierarchy() still reports its
            //    correct *text* (confirmed live), just never a usable bounding box.
            // 2. scroll(): known-unverified in this sandbox's headless Chromium fallback build —
            //    CLAUDE.md gotcha #4 / WebBridgeDriver.scroll()'s own doc comment.
            //
            // What *is* verified below: getHierarchy() reflects the live DOM even for a
            // zero-bounds node (reading "greeting_text" directly, bypassing the bounds filter),
            // click() works, and screenshot() produces a real PNG of the current frame.
            val greeting = d.getHierarchy().find("greeting_text")
            assertEquals("Hello, stranger!", greeting?.text)

            assertValidPng(d.screenshot())
        }
    }

    private fun assertValidPng(png: ByteArray) {
        assertTrue(png.size > MIN_PNG_SIZE_BYTES, "Screenshot was suspiciously small: ${png.size} bytes")
        assertEquals(PNG_MAGIC, png.take(PNG_MAGIC.size))
    }

    /** Polls [tag]'s text until it equals [expected], or fails after [timeoutMs]. */
    private fun BridgeDriver.waitForText(tag: String, expected: String, timeoutMs: Long = 15_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            last = getBounds(tag)?.text
            if (last == expected) return last
            Thread.sleep(TEXT_POLL_INTERVAL_MS)
        }
        error("\"$tag\" never showed \"$expected\" (last saw \"$last\") within ${timeoutMs}ms")
    }

    /** Clicks [clickTag], re-clicking up to [maxAttempts] times until [readTag] shows [expected]. */
    private fun BridgeDriver.clickUntilText(
        clickTag: String,
        readTag: String,
        expected: String,
        maxAttempts: Int = MAX_CLICK_ATTEMPTS,
    ) {
        repeat(maxAttempts) { attempt ->
            click(clickTag)
            try {
                waitForText(readTag, expected, timeoutMs = CLICK_SETTLE_TIMEOUT_MS)
                return
            } catch (e: IllegalStateException) {
                if (attempt == maxAttempts - 1) throw e
            }
        }
    }

    private companion object {
        const val MAX_SCROLL_ATTEMPTS = 60
        const val SCROLL_DELTA = 5
        const val MAX_CLICK_ATTEMPTS = 5
        const val CLICK_SETTLE_TIMEOUT_MS = 3_000L
        const val TEXT_POLL_INTERVAL_MS = 100L
        const val MIN_PNG_SIZE_BYTES = 100
        val PNG_MAGIC = listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    }
}
