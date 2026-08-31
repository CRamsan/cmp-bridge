package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.HierarchyNode
import com.cramsan.cmpbridge.find

/**
 * Drives a live, already-running app through its UI interaction bridge. Implementations connect
 * via a platform-specific `connect(...)` and are torn down via [close].
 */
interface BridgeDriver : AutoCloseable {
    /**
     * Default [waitForTagVisibility]/[waitForText] timeout (ms) when a call doesn't pass its own
     * `timeoutMs`. [BridgeDriver.DEFAULT_TIMEOUT_MS] unless the implementation was connected with
     * an override — increase this for slow CI environments or network-backed navigation.
     */
    val defaultTimeoutMs: Long get() = DEFAULT_TIMEOUT_MS

    /**
     * Interval (ms) between hierarchy re-fetches in [waitForTagVisibility]/[waitForText].
     * [BridgeDriver.DEFAULT_POLL_INTERVAL_MS] unless the implementation was connected with an
     * override.
     */
    val pollIntervalMs: Long get() = DEFAULT_POLL_INTERVAL_MS

    /** Returns the app's current semantics tree, root first. */
    fun getHierarchy(): HierarchyNode

    /** Returns the node tagged [tag], or `null` if absent or its layout hasn't settled yet. */
    fun getBounds(tag: String): HierarchyNode? = getHierarchy().find(tag)?.takeIf { it.width > 0f && it.height > 0f }

    /**
     * Resolves [tag] for an immediate interaction (click/setText/scroll), throwing
     * [UnknownTagException] if it's absent entirely, or [TagNotVisibleException] if it's present
     * but has zero/off-screen bounds right now (e.g. not yet scrolled into view). [action] names
     * the interaction being attempted, for the exception message (e.g. `"click"`, `"scroll"`).
     * Unlike [getBounds] — which the polling wait helpers above use and which intentionally
     * treats both cases as "not ready yet" — a one-shot interaction needs the distinction so a
     * caller can react differently (see https://github.com/CRamsan/cmp-bridge/issues/12).
     */
    fun requireInteractableNode(tag: String, action: String): HierarchyNode {
        val node = getHierarchy().find(tag) ?: throw UnknownTagException("Cannot $action unknown tag \"$tag\"")
        if (node.width <= 0f || node.height <= 0f) {
            throw TagNotVisibleException(
                "Cannot $action tag \"$tag\": it exists but has zero/off-screen bounds — scroll it into view first",
            )
        }
        return node
    }

    /**
     * Blocks until [tag]'s existence matches [visibility], up to [timeoutMs] (defaults to
     * [defaultTimeoutMs], `15_000`ms unless overridden at connect time), by repeatedly
     * re-fetching the hierarchy — [TagVisibility.VISIBLE] for "a new tag appeared" (e.g. after a
     * navigation), [TagVisibility.GONE] for "same screen, state changed" cases like a success
     * banner or dialog closing (the same class of race [waitForText] closes for a text change).
     * Returns the node when [visibility] is `VISIBLE`, `null` when it's `GONE`. Polls every
     * [pollIntervalMs] (`200`ms unless overridden).
     */
    fun waitForTagVisibility(
        tag: String,
        visibility: TagVisibility,
        timeoutMs: Long = defaultTimeoutMs,
    ): HierarchyNode? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = getBounds(tag)
            when (visibility) {
                TagVisibility.VISIBLE -> if (node != null) return node
                TagVisibility.GONE -> if (node == null) return null
            }
            Thread.sleep(pollIntervalMs)
        }
        throw BridgeTimeoutException("Tag \"$tag\" never reached visibility $visibility within ${timeoutMs}ms")
    }

    /**
     * Blocks until [tag]'s settled (non-null) text satisfies [comparator], up to [timeoutMs]
     * (defaults to [defaultTimeoutMs], `15_000`ms unless overridden at connect time). A node can
     * report `null`/stale text on the very first read after it appears or a screen transition —
     * desktop's semantics tree and web's (debounced 100–1000ms) accessibility-DOM sync both need
     * a moment to catch up — so callers reading freshly-appeared or freshly-changed text should
     * go through this rather than a raw [getHierarchy] lookup. Also covers "same screen, state
     * changed" assertions where no new tag appears — inline validation errors, toggled badges, a
     * counter's new value — that a click's own async (e.g. coroutine-dispatched) state update may
     * not have applied yet by the time it returns. Polls every [pollIntervalMs] (`200`ms unless
     * overridden).
     */
    fun waitForText(tag: String, comparator: TextComparator, timeoutMs: Long = defaultTimeoutMs): HierarchyNode {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = getBounds(tag)
            val text = node?.text
            if (node != null && text != null && comparator.matches(text)) return node
            Thread.sleep(pollIntervalMs)
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

    companion object {
        /** Default [defaultTimeoutMs] for an implementation that doesn't override it. */
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** Default [pollIntervalMs] for an implementation that doesn't override it. */
        const val DEFAULT_POLL_INTERVAL_MS = 200L
    }
}
