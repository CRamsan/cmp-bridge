package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.HierarchyNode
import com.cramsan.cmpbridge.find

/**
 * Drives a live, already-running app through its UI interaction bridge. Implementations connect
 * via a platform-specific `connect(...)` and are torn down via [close].
 */
interface BridgeDriver : AutoCloseable {
    /** Returns the app's current semantics tree, root first. */
    fun getHierarchy(): HierarchyNode

    /** Returns the node tagged [tag], or `null` if absent or its layout hasn't settled yet. */
    fun getBounds(tag: String): HierarchyNode? = getHierarchy().find(tag)?.takeIf { it.width > 0f && it.height > 0f }

    /** Blocks until [tag] appears, up to [timeoutMs], by repeatedly re-fetching the hierarchy. */
    fun waitForTag(tag: String, timeoutMs: Long = 15_000): HierarchyNode {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            getBounds(tag)?.let { return it }
            Thread.sleep(WAIT_FOR_TAG_POLL_INTERVAL_MS)
        }
        throw BridgeTimeoutException("Tag \"$tag\" did not appear within ${timeoutMs}ms")
    }

    /**
     * Blocks until [tag]'s bounds have settled (per [getBounds]) and its `text` is non-null, up
     * to [timeoutMs]. A node can report `null`/stale text on the very first read after it
     * appears or a screen transition — desktop's semantics tree and web's (debounced
     * 100–1000ms) accessibility-DOM sync both need a moment to catch up — so callers reading
     * freshly-appeared text should go through this rather than a raw [getHierarchy] lookup.
     */
    fun waitForText(tag: String, timeoutMs: Long = 15_000): HierarchyNode {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            getBounds(tag)?.takeIf { it.text != null }?.let { return it }
            Thread.sleep(WAIT_FOR_TAG_POLL_INTERVAL_MS)
        }
        throw BridgeTimeoutException("Tag \"$tag\"'s text never settled within ${timeoutMs}ms")
    }

    /**
     * Blocks until [tag]'s text equals [expected], up to [timeoutMs]. Use this rather than
     * [waitForTag]/[waitForText] for "same screen, state changed" assertions where no new tag
     * appears — inline validation errors, toggled badges, a counter's new value — that a click's
     * own async (e.g. coroutine-dispatched) state update may not have applied yet by the time it
     * returns.
     */
    fun waitForTextEquals(tag: String, expected: String, timeoutMs: Long = 15_000): HierarchyNode {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            getBounds(tag)?.takeIf { it.text == expected }?.let { return it }
            Thread.sleep(WAIT_FOR_TAG_POLL_INTERVAL_MS)
        }
        throw BridgeTimeoutException("Tag \"$tag\" never showed \"$expected\" within ${timeoutMs}ms")
    }

    /**
     * Blocks until [tag] is no longer found, up to [timeoutMs] — a no-op if it's already gone.
     * Covers "same screen, state changed" cases like a success banner or dialog closing, the same
     * way [waitForTextEquals] covers a text change.
     */
    fun waitForTagGone(tag: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (getBounds(tag) == null) return
            Thread.sleep(WAIT_FOR_TAG_POLL_INTERVAL_MS)
        }
        throw BridgeTimeoutException("Tag \"$tag\" was still present after ${timeoutMs}ms")
    }

    /** Clicks the node tagged [tag] via a real synthetic input event. */
    fun click(tag: String)

    /** Clicks [tag], selects any existing content, and replaces it with [text] (`""` clears it). */
    fun setText(tag: String, text: String)

    /**
     * Scrolls near [anchorTag] by [deltaY], in the platform's native scroll units (not equivalent
     * across platforms — poll via [waitForTag]/[getBounds] rather than relying on a fixed distance).
     */
    fun scroll(anchorTag: String, deltaY: Int)

    /** Captures a screenshot of the running app's current frame, PNG-encoded. */
    fun screenshot(): ByteArray

    private companion object {
        const val WAIT_FOR_TAG_POLL_INTERVAL_MS = 200L
    }
}
