package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.HierarchyNode
import com.cramsan.cmpbridge.find

/**
 * Platform-agnostic contract for driving a live app instance through its UI interaction bridge.
 * Every implementation is connection-only — obtained via a platform-specific `connect(...)`
 * against an app that's already running, torn down via [close]. Neither implementation ever
 * launches an app itself; a test that wants a disposable instance composes its own
 * [DesktopAppProcess]/[WasmDevServerProcess] alongside the driver, optionally through
 * [ManagedBridgeDriver] for single-call teardown of both.
 *
 * Scenario tests are written once against this interface and run against every implementation —
 * [DesktopBridgeDriver] and [WebBridgeDriver] today, a future Android driver without any change to
 * existing scenario code.
 *
 * Every read goes through the app's own real semantics/accessibility tree ([getHierarchy]) —
 * never a hand-tracked registry — so results always reflect the current live layout. [getBounds]
 * and [waitForTag] are plain derivations of [getHierarchy] (default methods below), not separate
 * per-platform primitives.
 */
interface BridgeDriver : AutoCloseable {
    /** Returns the app's current real semantics tree, root first. */
    fun getHierarchy(): HierarchyNode

    /**
     * Returns the node whose [HierarchyNode.testTag] is [tag], or `null` if it isn't present yet.
     * A node whose very first layout pass hasn't settled can transiently report degenerate
     * `(0, 0, 0, 0)` bounds (confirmed live for both the desktop semantics tree and web's
     * accessibility DOM) — treated the same as "not present yet" here, since a caller asking
     * "where is this element" should never get a fake zero-sized rect back as if it were real.
     */
    fun getBounds(tag: String): HierarchyNode? = getHierarchy().find(tag)?.takeIf { it.width > 0f && it.height > 0f }

    /** Blocks until [tag] appears, up to [timeoutMs], by repeatedly re-fetching the hierarchy. */
    fun waitForTag(tag: String, timeoutMs: Long = 15_000): HierarchyNode {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            getBounds(tag)?.let { return it }
            Thread.sleep(WAIT_FOR_TAG_POLL_INTERVAL_MS)
        }
        error("Tag \"$tag\" did not appear within ${timeoutMs}ms")
    }

    /** Real synthetic click through the platform's own input pipeline — never a semantics shortcut. */
    fun click(tag: String)

    /** Clicks [tag], then types [text] into it via the platform's own real input pipeline. */
    fun setText(tag: String, text: String)

    /**
     * Synthesizes a real scroll gesture centered on [anchorTag]'s current bounds — never a
     * semantics shortcut. A positive [deltaY] scrolls toward the end of the content (typically
     * downward, moving on-screen elements up), matching wheel-down on both platforms; magnitude
     * is in the platform's own native scroll units (AWT wheel rotation ticks on desktop, CSS
     * pixels via Playwright on web) and is *not* numerically equivalent across platforms —
     * scenario code should scroll in a loop and re-check via [waitForTag]/[getBounds] rather than
     * relying on a fixed [deltaY] producing the same visible distance everywhere.
     */
    fun scroll(anchorTag: String, deltaY: Int)

    /** Captures a real screenshot of the running app's current frame, PNG-encoded. */
    fun screenshot(): ByteArray

    private companion object {
        const val WAIT_FOR_TAG_POLL_INTERVAL_MS = 200L
    }
}
