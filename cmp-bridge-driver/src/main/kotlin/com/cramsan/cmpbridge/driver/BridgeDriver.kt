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
        error("Tag \"$tag\" did not appear within ${timeoutMs}ms")
    }

    /** Clicks the node tagged [tag] via a real synthetic input event. */
    fun click(tag: String)

    /** Clicks [tag], then types [text] into it. */
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
