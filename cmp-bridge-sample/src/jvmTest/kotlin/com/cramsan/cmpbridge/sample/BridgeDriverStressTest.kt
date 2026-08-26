package com.cramsan.cmpbridge.sample

import com.cramsan.cmpbridge.driver.BridgeDriver
import com.cramsan.cmpbridge.driver.DesktopAppProcess
import com.cramsan.cmpbridge.driver.DesktopBridgeDriver
import com.cramsan.cmpbridge.driver.ManagedBridgeDriver
import com.cramsan.cmpbridge.driver.TextComparator
import com.cramsan.cmpbridge.driver.WasmDevServerProcess
import com.cramsan.cmpbridge.driver.WebBridgeDriver
import com.cramsan.cmpbridge.find
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Every target this suite can drive — add a case here (plus [BridgeDriverStressTest.launch]) to
 * cover a new platform.
 */
enum class Target { DESKTOP, WEB }

/**
 * Stress-tests each `BridgeDriver` core operation against a single already-launched app instance,
 * per [Target], with no retry wrapper, to measure how often it silently has no effect (issue #23).
 * This is the baseline every candidate fix gets compared against — a real before/after number
 * instead of eyeballing a handful of manual runs. Written once against the `BridgeDriver`
 * interface so a future target only needs a [launch] case, not a parallel test class.
 *
 * Where a scenario genuinely can't run on a target — `setText` on web, since `name_field`
 * permanently reports zero bounds there (see ARCHITECTURE.md's "Known platform gaps") — the test
 * is skipped for that target with a reason, not silently omitted or forced to fail.
 *
 * Each test is a diagnostic, not (yet) a pass/fail regression test for every target: there's no
 * fix in place yet for web's own characteristics, so each one only asserts a sanity bound (the
 * operation isn't *completely* dead) and reports the actual miss count/rate.
 *
 * Every miss burns a full settle-timeout wait, so a genuinely broken operation (or a test-design
 * bug like an unexpected overlay swallowing clicks) can silently balloon to minutes rather than
 * fail fast. The class-level [Timeout] turns that into a clear, bounded failure instead of an
 * open-ended wait — 3 minutes is generous for legitimate web dev-server startup plus a real but
 * partial miss rate, while still catching an actual hang.
 */
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class BridgeDriverStressTest {
    @ParameterizedTest(name = "{0}")
    @EnumSource(Target::class)
    fun `click() drop rate at the same location`(target: Target) {
        withApp(target) { d -> measureClickDropRate(target, d, label = "same location") { } }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Target::class)
    fun `click() drop rate when alternating with a click at a different location`(target: Target) {
        withApp(target) { d ->
            // increment_button and item_0 sit at different coordinates and both have real bounds
            // on every target — real usage clicks different elements across a session, and
            // desktop's click() never sends a MOUSE_EXITED at the old position before the next
            // click's MOUSE_ENTERED at the new one. item_0 has no click action of its own (a
            // list row, not a button), so clicking it can't have a side effect that interferes
            // with the next click — unlike favorite_fruit_field, which opens a dropdown overlay
            // that then swallows the following click on increment_button entirely.
            measureClickDropRate(target, d, label = "alternating location") { d.click("item_0") }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Target::class)
    fun `setText() drop rate over a tight loop`(target: Target) {
        assumeTrue(
            target != Target.WEB,
            "name_field permanently reports zero bounds on web " +
                "(ARCHITECTURE.md's Known platform gaps) — not drivable there yet",
        )
        // #23's own repro was specifically setText/pasteText cycles (click + clipboard paste +
        // Ctrl+A key chords), not a plain click() — closer to the actual reported failure shape.
        withApp(target) { d ->
            var misses = 0
            val missedAtIteration = mutableListOf<Int>()
            repeat(SETTEXT_ITERATIONS) { i ->
                val expected = "Name$i"
                d.setText("name_field", expected)
                if (!pollUntil(SETTEXT_SETTLE_MS) { d.getBounds("greeting_text")?.text == "Hello, $expected!" }) {
                    misses++
                    missedAtIteration += i
                    System.err.println("=== miss at $i: greeting=${d.getBounds("greeting_text")?.text} ===")
                }
            }
            report("setText()", target, misses, SETTEXT_ITERATIONS, missedAtIteration)
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Target::class)
    fun `scroll() drop rate over a tight loop`(target: Target) {
        // Alternates direction each call so the tracked row oscillates within the middle of the
        // list rather than pinning at an edge, where a real "no more room to scroll" boundary
        // would be indistinguishable from a dropped scroll.
        withApp(target) { d ->
            var misses = 0
            val missedAtIteration = mutableListOf<Int>()
            val delta = scrollDelta(target)
            repeat(SCROLL_ITERATIONS) { i ->
                val before = d.getBounds(SCROLL_TRACK_TAG)?.y
                d.scroll("item_list", if (i % 2 == 0) delta else -delta)
                if (!pollUntil(SCROLL_SETTLE_MS) { d.getBounds(SCROLL_TRACK_TAG)?.y != before }) {
                    misses++
                    missedAtIteration += i
                }
            }
            report("scroll()", target, misses, SCROLL_ITERATIONS, missedAtIteration)
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Target::class)
    fun `getHierarchy() failure rate over a tight loop`(target: Target) {
        withApp(target) { d ->
            var failures = 0
            val failedAtIteration = mutableListOf<Int>()
            repeat(READ_ITERATIONS) { i ->
                val ok = runCatching { d.getHierarchy() }.map { it.find("counter_text") != null }.getOrDefault(false)
                if (!ok) {
                    failures++
                    failedAtIteration += i
                }
            }
            report("getHierarchy()", target, failures, READ_ITERATIONS, failedAtIteration)
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Target::class)
    fun `screenshot() failure rate over a tight loop`(target: Target) {
        withApp(target) { d ->
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
            report("screenshot()", target, failures, SCREENSHOT_ITERATIONS, failedAtIteration)
        }
    }

    /** Launches [target], connects a driver, waits past the "first interaction" settle, then runs [block]. */
    private fun withApp(target: Target, block: (BridgeDriver) -> Unit) {
        launch(target).use { d ->
            d.waitForText("counter_text", TextComparator.Present)
            block(d)
        }
    }

    /** Launches and connects [target] — the one place a new [Target] case needs to be wired up. */
    private fun launch(target: Target): ManagedBridgeDriver = when (target) {
        Target.DESKTOP -> {
            val process = DesktopAppProcess.launch("com.cramsan.cmpbridge.sample.MainKt")
            val driver = runCatching { DesktopBridgeDriver.connect(process.host, process.port) }
                .getOrElse {
                    process.close()
                    throw it
                }
            ManagedBridgeDriver(process, driver)
        }

        Target.WEB -> {
            val process = WasmDevServerProcess.launch(":cmp-bridge-sample")
            val driver = runCatching { WebBridgeDriver.connect(process.url) }
                .getOrElse {
                    process.close()
                    throw it
                }
            ManagedBridgeDriver(process, driver)
        }
    }

    /** Scroll units aren't equivalent across platforms (BridgeDriver.scroll's own doc). */
    private fun scrollDelta(target: Target): Int = when (target) {
        Target.DESKTOP -> SCROLL_DELTA
        Target.WEB -> WEB_SCROLL_DELTA
    }

    /**
     * Clicks `increment_button` [CLICK_ITERATIONS] times, running [beforeClick] immediately before
     * each one (e.g. a click elsewhere, to relocate the pointer first), and reports how often
     * `counter_text` failed to advance within [CLICK_SETTLE_MS]. A miss doesn't abort the loop —
     * a single dropped click must not invalidate the rest of the measurement.
     */
    private fun measureClickDropRate(target: Target, d: BridgeDriver, label: String, beforeClick: () -> Unit) {
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
        report("click() ($label)", target, misses, CLICK_ITERATIONS, missedAtIteration)
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

    private fun report(operation: String, target: Target, misses: Int, total: Int, missedAtIteration: List<Int>) {
        val missRate = misses.toDouble() / total
        System.err.println(
            "=== $operation [$target] drop rate: $misses/$total (${"%.1f".format(missRate * 100)}%) " +
                "missed at iterations: $missedAtIteration ===",
        )
        assertTrue(missRate < 1.0, "Every single $operation call failed on $target — the app likely isn't responding")
    }

    private companion object {
        const val CLICK_ITERATIONS = 100
        const val CLICK_SETTLE_MS = 1_500L
        const val SETTEXT_ITERATIONS = 100
        const val SETTEXT_SETTLE_MS = 1_500L
        const val SCROLL_ITERATIONS = 100
        const val SCROLL_SETTLE_MS = 1_500L
        const val SCROLL_DELTA = 5
        const val WEB_SCROLL_DELTA = 300
        const val SCROLL_TRACK_TAG = "item_5"
        const val READ_ITERATIONS = 200
        const val SCREENSHOT_ITERATIONS = 50
        const val POLL_INTERVAL_MS = 20L
        const val MIN_PNG_SIZE_BYTES = 100
        val PNG_MAGIC = listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    }
}
