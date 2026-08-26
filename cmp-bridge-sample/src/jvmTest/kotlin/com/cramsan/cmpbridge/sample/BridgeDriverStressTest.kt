package com.cramsan.cmpbridge.sample

import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.DesktopAppProcess
import com.cramsan.cmpbridge.driver.DesktopBridgeDriver
import com.cramsan.cmpbridge.driver.ManagedBridgeDriver
import com.cramsan.cmpbridge.driver.TextComparator
import com.cramsan.cmpbridge.find
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Stress-tests each `BridgeDriver` core operation against a single already-launched desktop app
 * instance, with no retry wrapper, to measure how often it silently has no effect (issue #23).
 * This is the baseline every candidate fix gets compared against — a real before/after number
 * instead of eyeballing a handful of manual runs.
 *
 * Each test is a diagnostic, not (yet) a pass/fail regression test: there's no fix in place yet,
 * so each one only asserts a sanity bound (the operation isn't *completely* dead) and reports the
 * actual miss count/rate. Once #23 has a fix, tighten the assertions to something close to 0.
 */
class BridgeDriverStressTest {
    @Test
    fun `click() drop rate at the same location`() {
        withApp { d -> measureClickDropRate(d, label = "same location") { } }
    }

    @Test
    fun `click() drop rate when alternating with a click at a different location`() {
        withApp { d ->
            // increment_button and name_field sit at different coordinates — real usage clicks
            // different elements across a session, and click() never sends a MOUSE_EXITED at the
            // old position before the next click's MOUSE_ENTERED at the new one.
            measureClickDropRate(d, label = "alternating location") { d.click("name_field") }
        }
    }

    @Test
    fun `setText() drop rate over a tight loop`() {
        // #23's own repro was specifically setText/pasteText cycles (click + clipboard paste +
        // Ctrl+A key chords), not a plain click() — closer to the actual reported failure shape.
        withApp { d ->
            var misses = 0
            val missedAtIteration = mutableListOf<Int>()
            repeat(SETTEXT_ITERATIONS) { i ->
                val expected = "Name$i"
                d.setText("name_field", expected)
                if (!pollUntil(SETTEXT_SETTLE_MS) { d.getBounds("greeting_text")?.text == "Hello, $expected!" }) {
                    misses++
                    missedAtIteration += i
                }
            }
            report("setText()", misses, SETTEXT_ITERATIONS, missedAtIteration)
        }
    }

    @Test
    fun `scroll() drop rate over a tight loop`() {
        // Alternates direction each call so the tracked row oscillates within the middle of the
        // list rather than pinning at an edge, where a real "no more room to scroll" boundary
        // would be indistinguishable from a dropped scroll.
        withApp { d ->
            var misses = 0
            val missedAtIteration = mutableListOf<Int>()
            repeat(SCROLL_ITERATIONS) { i ->
                val before = d.getBounds(SCROLL_TRACK_TAG)?.y
                val deltaY = if (i % 2 == 0) SCROLL_DELTA else -SCROLL_DELTA
                d.scroll("item_list", deltaY)
                if (!pollUntil(SCROLL_SETTLE_MS) { d.getBounds(SCROLL_TRACK_TAG)?.y != before }) {
                    misses++
                    missedAtIteration += i
                }
            }
            report("scroll()", misses, SCROLL_ITERATIONS, missedAtIteration)
        }
    }

    @Test
    fun `getHierarchy() failure rate over a tight loop`() {
        withApp { d ->
            var failures = 0
            val failedAtIteration = mutableListOf<Int>()
            repeat(READ_ITERATIONS) { i ->
                val ok = runCatching { d.getHierarchy() }.map { it.find("counter_text") != null }.getOrDefault(false)
                if (!ok) {
                    failures++
                    failedAtIteration += i
                }
            }
            report("getHierarchy()", failures, READ_ITERATIONS, failedAtIteration)
        }
    }

    @Test
    fun `screenshot() failure rate over a tight loop`() {
        withApp { d ->
            var failures = 0
            val failedAtIteration = mutableListOf<Int>()
            repeat(SCREENSHOT_ITERATIONS) { i ->
                val ok = runCatching { d.screenshot() }
                    .map { it.size > MIN_PNG_SIZE_BYTES && it.take(PNG_MAGIC.size) == PNG_MAGIC }
                    .getOrDefault(false)
                if (!ok) {
                    failures++
                    failedAtIteration += i
                }
            }
            report("screenshot()", failures, SCREENSHOT_ITERATIONS, failedAtIteration)
        }
    }

    private fun withApp(block: (BridgeDriver) -> Unit) {
        val process = DesktopAppProcess.launch("com.cramsan.cmpbridge.sample.MainKt")
        val driver = runCatching { DesktopBridgeDriver.connect(process.host, process.port) }
            .getOrElse {
                process.close()
                throw it
            }
        ManagedBridgeDriver(process, driver).use { d ->
            // Settle past the documented "first click after launch" miss before measuring.
            d.waitForText("counter_text", TextComparator.Present)
            block(d)
        }
    }

    /**
     * Clicks `increment_button` [CLICK_ITERATIONS] times, running [beforeClick] immediately before
     * each one (e.g. a click elsewhere, to relocate the pointer first), and reports how often
     * `counter_text` failed to advance within [CLICK_SETTLE_MS]. A miss doesn't abort the loop —
     * a single dropped click must not invalidate the rest of the measurement.
     */
    private fun measureClickDropRate(d: BridgeDriver, label: String, beforeClick: () -> Unit) {
        var misses = 0
        val missedAtIteration = mutableListOf<Int>()
        repeat(CLICK_ITERATIONS) { i ->
            beforeClick()
            val before = d.getBounds("counter_text")?.text
            d.click("increment_button")
            if (!pollUntil(CLICK_SETTLE_MS) { d.getBounds("counter_text")?.text != before }) {
                misses++
                missedAtIteration += i
            }
        }
        report("click() ($label)", misses, CLICK_ITERATIONS, missedAtIteration)
    }

    /** Polls [condition] every [POLL_INTERVAL_MS] until it's true or [timeoutMs] elapses. */
    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun report(operation: String, misses: Int, total: Int, missedAtIteration: List<Int>) {
        val missRate = misses.toDouble() / total
        System.err.println(
            "=== $operation drop rate: $misses/$total (${"%.1f".format(missRate * 100)}%) " +
                "missed at iterations: $missedAtIteration ===",
        )
        assertTrue(missRate < 1.0, "Every single $operation call failed — the app likely isn't responding at all")
    }

    private companion object {
        const val CLICK_ITERATIONS = 100
        const val CLICK_SETTLE_MS = 500L
        const val SETTEXT_ITERATIONS = 100
        const val SETTEXT_SETTLE_MS = 1_500L
        const val SCROLL_ITERATIONS = 100
        const val SCROLL_SETTLE_MS = 500L
        const val SCROLL_DELTA = 5
        const val SCROLL_TRACK_TAG = "item_5"
        const val READ_ITERATIONS = 200
        const val SCREENSHOT_ITERATIONS = 50
        const val POLL_INTERVAL_MS = 20L
        const val MIN_PNG_SIZE_BYTES = 100
        val PNG_MAGIC = listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    }
}
