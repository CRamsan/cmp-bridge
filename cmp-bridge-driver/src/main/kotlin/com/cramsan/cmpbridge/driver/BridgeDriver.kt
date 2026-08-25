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

    /**
     * Blocks until [tag]'s existence matches [visibility], up to [timeoutMs], by repeatedly
     * re-fetching the hierarchy — [TagVisibility.VISIBLE] for "a new tag appeared" (e.g. after a
     * navigation), [TagVisibility.GONE] for "same screen, state changed" cases like a success
     * banner or dialog closing (the same class of race [waitForText] closes for a text change).
     * Returns the node when [visibility] is `VISIBLE`, `null` when it's `GONE`.
     */
    fun waitForTagVisibility(tag: String, visibility: TagVisibility, timeoutMs: Long = 15_000): HierarchyNode? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = getBounds(tag)
            when (visibility) {
                TagVisibility.VISIBLE -> if (node != null) return node
                TagVisibility.GONE -> if (node == null) return null
            }
            Thread.sleep(WAIT_FOR_TAG_POLL_INTERVAL_MS)
        }
        throw BridgeTimeoutException("Tag \"$tag\" never reached visibility $visibility within ${timeoutMs}ms")
    }

    /**
     * Blocks until [tag]'s settled (non-null) text satisfies [comparator], up to [timeoutMs]. A
     * node can report `null`/stale text on the very first read after it appears or a screen
     * transition — desktop's semantics tree and web's (debounced 100–1000ms) accessibility-DOM
     * sync both need a moment to catch up — so callers reading freshly-appeared or
     * freshly-changed text should go through this rather than a raw [getHierarchy] lookup. Also
     * covers "same screen, state changed" assertions where no new tag appears — inline
     * validation errors, toggled badges, a counter's new value — that a click's own async (e.g.
     * coroutine-dispatched) state update may not have applied yet by the time it returns.
     */
    fun waitForText(tag: String, comparator: TextComparator, timeoutMs: Long = 15_000): HierarchyNode {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = getBounds(tag)
            val text = node?.text
            if (node != null && text != null && comparator.matches(text)) return node
            Thread.sleep(WAIT_FOR_TAG_POLL_INTERVAL_MS)
        }
        throw BridgeTimeoutException("Tag \"$tag\" never satisfied $comparator within ${timeoutMs}ms")
    }

    /** Clicks the node tagged [tag] via a real synthetic input event. */
    fun click(tag: String)

    /** Clicks [tag], selects any existing content, and replaces it with [text] (`""` clears it). */
    fun setText(tag: String, text: String)

    /**
     * Scrolls near [anchorTag] by [deltaY], in the platform's native scroll units (not equivalent
     * across platforms — poll via [waitForTagVisibility]/[getBounds] rather than relying on a
     * fixed distance).
     */
    fun scroll(anchorTag: String, deltaY: Int)

    /** Captures a screenshot of the running app's current frame, PNG-encoded. */
    fun screenshot(): ByteArray

    private companion object {
        const val WAIT_FOR_TAG_POLL_INTERVAL_MS = 200L
    }
}
