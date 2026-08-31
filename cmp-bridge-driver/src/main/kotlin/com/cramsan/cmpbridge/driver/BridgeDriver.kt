package com.cramsan.cmpbridge.driver

import com.cramsan.cmpbridge.HierarchyNode
import com.cramsan.cmpbridge.find
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives a live, already-running app through its UI interaction bridge. Implementations connect
 * via a platform-specific `connect(...)` and are torn down via [close]. The five core operations
 * ([getHierarchy], [click], [setText], [scroll], [screenshot]) are plain blocking calls, same as
 * ever — only [waitForTagVisibility]/[waitForText] are `suspend`, and call [getBounds]/
 * [getHierarchy] directly (blocking) inside their poll loop rather than dispatching them
 * elsewhere, consistent with every other caller of those two methods.
 */
interface BridgeDriver : AutoCloseable {
    /**
     * Default [waitForTagVisibility]/[waitForText] timeout when a call doesn't pass its own
     * `timeout`. [BridgeDriver.DEFAULT_TIMEOUT] unless the implementation was connected with an
     * override — increase this for slow CI environments or network-backed navigation.
     */
    val defaultTimeout: Duration get() = DEFAULT_TIMEOUT

    /**
     * Interval between hierarchy re-fetches in [waitForTagVisibility]/[waitForText].
     * [BridgeDriver.DEFAULT_POLL_INTERVAL] unless the implementation was connected with an
     * override.
     */
    val pollInterval: Duration get() = DEFAULT_POLL_INTERVAL

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
     * Suspends until [tag]'s existence matches [visibility], up to [timeout] (defaults to
     * [defaultTimeout], `15s` unless overridden at connect time), by repeatedly re-fetching the
     * hierarchy — [TagVisibility.VISIBLE] for "a new tag appeared" (e.g. after a navigation),
     * [TagVisibility.GONE] for "same screen, state changed" cases like a success banner or dialog
     * closing (the same class of race [waitForText] closes for a text change). Returns the node
     * when [visibility] is `VISIBLE`, `null` when it's `GONE`. Polls every [pollInterval] (`200ms`
     * unless overridden).
     *
     * Uses [withTimeout] (not `withTimeoutOrNull`) and catches only [TimeoutCancellationException]
     * deliberately: `withTimeoutOrNull`'s null-on-timeout return would be indistinguishable from
     * `GONE`'s own legitimate null result. This narrow catch won't swallow an unrelated outer
     * cancellation — but if a future caller ever wraps this call in its *own* [withTimeout], that
     * outer timeout's exception would also be caught here and mis-reported as this function's own
     * [BridgeTimeoutException]. No caller does that today.
     */
    suspend fun waitForTagVisibility(
        tag: String,
        visibility: TagVisibility,
        timeout: Duration = defaultTimeout,
    ): HierarchyNode? {
        var result: HierarchyNode? = null
        try {
            withTimeout(timeout) {
                while (true) {
                    val node = getBounds(tag)
                    when (visibility) {
                        TagVisibility.VISIBLE -> if (node != null) {
                            result = node
                            return@withTimeout
                        }

                        TagVisibility.GONE -> if (node == null) return@withTimeout
                    }
                    delay(pollInterval)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw BridgeTimeoutException("Tag \"$tag\" never reached visibility $visibility within $timeout")
        }
        return result
    }

    /**
     * Suspends until [tag]'s settled (non-null) text satisfies [comparator], up to [timeout]
     * (defaults to [defaultTimeout], `15s` unless overridden at connect time). A node can report
     * `null`/stale text on the very first read after it appears or a screen transition —
     * desktop's semantics tree and web's (debounced 100–1000ms) accessibility-DOM sync both need
     * a moment to catch up — so callers reading freshly-appeared or freshly-changed text should
     * go through this rather than a raw [getHierarchy] lookup. Also covers "same screen, state
     * changed" assertions where no new tag appears — inline validation errors, toggled badges, a
     * counter's new value — that a click's own async (e.g. coroutine-dispatched) state update may
     * not have applied yet by the time it returns. Polls every [pollInterval] (`200ms` unless
     * overridden).
     *
     * See [waitForTagVisibility]'s own doc for why this catches only [TimeoutCancellationException].
     */
    suspend fun waitForText(
        tag: String,
        comparator: TextComparator,
        timeout: Duration = defaultTimeout,
    ): HierarchyNode {
        var result: HierarchyNode? = null
        try {
            withTimeout(timeout) {
                while (true) {
                    val node = getBounds(tag)
                    val text = node?.text
                    if (node != null && text != null && comparator.matches(text)) {
                        result = node
                        return@withTimeout
                    }
                    delay(pollInterval)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw BridgeTimeoutException("Tag \"$tag\" never satisfied $comparator within $timeout")
        }
        // Non-null: withTimeout only returns normally (no exception) via the return@withTimeout
        // above, which always sets `result` first.
        return checkNotNull(result)
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
        /** Default [defaultTimeout] for an implementation that doesn't override it. */
        val DEFAULT_TIMEOUT: Duration = 15.seconds

        /** Default [pollInterval] for an implementation that doesn't override it. */
        val DEFAULT_POLL_INTERVAL: Duration = 200.milliseconds
    }
}
