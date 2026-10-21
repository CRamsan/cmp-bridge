package com.cramsan.cmpbridge

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.net.ServerSocket
import java.util.Base64
import javax.swing.SwingUtilities

/**
 * Debug-only local bridge server for JVM desktop. Drives the app via its real semantics tree
 * (`ComposeWindow.semanticsOwners`) for reads and synthetic AWT input events posted onto the app's
 * own event queue — never `java.awt.Robot`. Never started unless enabled via [ENABLED_PROPERTY] or
 * [ENABLED_ENV_VAR].
 *
 * Speaks the [BridgeCommand]/[BridgeResponse] protocol: one JSON-encoded command per line in, one
 * JSON-encoded response per line out.
 */
// One cohesive protocol handler, deliberately kept as small private helpers rather than split.
@Suppress("TooManyFunctions")
object DesktopBridgeServer {
    const val ENABLED_PROPERTY = "cmpBridge.enabled"
    const val PORT_PROPERTY = "cmpBridge.port"

    // System properties (-D) aren't inherited by a forked child process unless the launcher
    // explicitly forwards them; environment variables are, by default (Gradle's JavaExec, a
    // plain shell fork). The env var works out of the box through `./gradlew :run`; the system
    // property remains for callers that construct the process or invoke `java` directly.
    const val ENABLED_ENV_VAR = "CMP_BRIDGE_ENABLED"
    const val PORT_ENV_VAR = "CMP_BRIDGE_PORT"

    private const val DEFAULT_PORT = 8901

    // Each synthetic AWT event in a gesture needs a strictly increasing timestamp.
    private const val RELEASE_OFFSET_MS = 3L

    // AWT's wheel model is click-based, not pixel-based; matches the platform's typical default.
    private const val WHEEL_SCROLL_AMOUNT = 3

    // See pasteText's own doc for why this settle is needed between Ctrl+A and the paste.
    private const val SELECT_ALL_SETTLE_MS = 50L

    /**
     * Starts the bridge server on [scope] if enabled via [ENABLED_PROPERTY] or [ENABLED_ENV_VAR]
     * (either set to `"true"`); otherwise a no-op. Must be called with the app's root [Window] so
     * click/type coordinates can be resolved to the actual Compose input target inside it.
     */
    fun startIfEnabled(window: Window, scope: CoroutineScope) {
        val enabled = System.getProperty(ENABLED_PROPERTY) == "true" || System.getenv(ENABLED_ENV_VAR) == "true"
        if (!enabled) return
        val port =
            System.getProperty(PORT_PROPERTY)?.toIntOrNull()
                ?: System.getenv(PORT_ENV_VAR)?.toIntOrNull()
                ?: DEFAULT_PORT
        scope.launch(Dispatchers.IO) {
            ServerSocket(port).use { serverSocket ->
                while (true) {
                    val socket = serverSocket.accept()
                    launch(Dispatchers.IO) {
                        serveBridgeConnection(socket) { command -> handleCommand(command, window) }
                    }
                }
            }
        }
    }

    /** Dispatches a single [BridgeCommand] to its handler, producing a [BridgeResponse]. */
    private fun handleCommand(command: BridgeCommand, window: Window): BridgeResponse = when (command) {
        is BridgeCommand.GetHierarchy -> {
            BridgeResponse.Hierarchy(onEdt { buildHierarchy(window) })
        }

        is BridgeCommand.Click -> {
            click(command.tag, window).toResponse(command.tag)
        }

        is BridgeCommand.SetText -> {
            click(command.tag, window).toResponse(command.tag) { _ -> pasteText(command.text, window) }
        }

        is BridgeCommand.Scroll -> {
            scroll(command.anchorTag, command.deltaY, window).toResponse(command.anchorTag)
        }

        is BridgeCommand.Screenshot -> {
            BridgeResponse.Image(captureScreenshot(window))
        }
    }

    /**
     * Resolves [tag] to its node, distinguishing "not in the tree at all" from "present but
     * zero/off-screen bounds" (e.g. a `LazyColumn` item not yet scrolled into view) — see
     * https://github.com/CRamsan/cmp-bridge/issues/12.
     */
    private fun lookupNode(tag: String, window: Window): NodeLookup = onEdt { buildHierarchy(window) }.lookup(tag)

    /**
     * Runs [block] on the AWT event dispatch thread and returns its result. Compose's live
     * semantics/layout tree is only safe to read from the same thread that mutates it — the one
     * write operations already marshal onto via [SwingUtilities.invokeAndWait]. Reading it
     * directly from this connection's own IO-dispatched coroutine can race a concurrent
     * structural change (`LazyColumn` recycling during [scroll], recomposition during
     * [pasteText]) and crash mid-read on a torn tree.
     */
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Builds a fresh [HierarchyNode] tree from the app's semantics tree — queried live, never cached. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun buildHierarchy(window: Window): HierarchyNode {
        val composeWindow =
            window as? ComposeWindow
                ?: error("Hierarchy retrieval requires a ComposeWindow, got ${window::class.simpleName}")
        return composeWindow.semanticsOwners.map { it.rootSemanticsNode.toHierarchyNode() }.asSingleRoot()
    }

    /**
     * The component that actually receives input inside a Compose Desktop [Window] — found by
     * walking the component tree for whichever descendant has a mouse listener registered, rather
     * than hardcoding its (internal, version-dependent) nesting. Falls back to the window itself
     * if nothing qualifies.
     */
    private fun inputTargetComponent(window: Window): Component {
        fun findInteractive(component: Component): Component? {
            if (component is Container) {
                for (child in component.components) {
                    findInteractive(child)?.let { return it }
                }
            }
            return component.takeIf { it.mouseListeners.isNotEmpty() }
        }
        return findInteractive(window) ?: window
    }

    /**
     * Center of [node] in the AWT coordinate space that [MouseEvent]s take. [HierarchyNode] bounds
     * come from `boundsInWindow` in physical pixels (the same space as [screenshot]), while AWT
     * events are in logical, density-independent points — on a 2x HiDPI display, posting the pixel
     * values as-is lands every click at twice the intended offset and silently misses its target.
     */
    private fun pointerPosition(node: HierarchyNode, window: Window): Pair<Int, Int> {
        val transform = window.graphicsConfiguration?.defaultTransform
        val scaleX = transform?.scaleX?.takeIf { it > 0.0 } ?: 1.0
        val scaleY = transform?.scaleY?.takeIf { it > 0.0 } ?: 1.0
        val x = ((node.x + node.width / 2) / scaleX).toInt()
        val y = ((node.y + node.height / 2) / scaleY).toInt()
        return x to y
    }

    /** Returns without dispatching anything if [tag] isn't found or isn't currently visible. */
    private fun click(tag: String, window: Window): NodeLookup {
        val lookup = lookupNode(tag, window)
        val node = (lookup as? NodeLookup.Found)?.node ?: return lookup
        val target = inputTargetComponent(window)
        val (x, y) = pointerPosition(node, window)
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        val now = System.currentTimeMillis()
        // A press without preceding pointer movement isn't processed cleanly by Compose's
        // pointer-input pipeline.
        queue.postEvent(
            MouseEvent(target, MouseEvent.MOUSE_ENTERED, now, 0, x, y, 0, false),
        )
        queue.postEvent(
            MouseEvent(target, MouseEvent.MOUSE_MOVED, now + 1, 0, x, y, 0, false),
        )
        // modifiersEx must reflect button state at the time of each event (down during PRESSED,
        // up by RELEASED/CLICKED) or Compose Desktop silently drops the gesture.
        queue.postEvent(
            MouseEvent(
                target,
                MouseEvent.MOUSE_PRESSED,
                now + 2,
                InputEvent.BUTTON1_DOWN_MASK,
                x,
                y,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )
        queue.postEvent(
            MouseEvent(
                target,
                MouseEvent.MOUSE_RELEASED,
                now + RELEASE_OFFSET_MS,
                0,
                x,
                y,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )
        queue.postEvent(
            MouseEvent(
                target,
                MouseEvent.MOUSE_CLICKED,
                now + RELEASE_OFFSET_MS,
                0,
                x,
                y,
                1,
                false,
                MouseEvent.BUTTON1,
            ),
        )
        // Blocks until every event posted above has been dispatched.
        SwingUtilities.invokeAndWait {}
        return lookup
    }

    /**
     * Synthesizes a wheel-scroll gesture centered on [anchorTag]'s bounds. Returns without
     * dispatching anything if [anchorTag] isn't found or isn't currently visible.
     */
    private fun scroll(anchorTag: String, deltaY: Int, window: Window): NodeLookup {
        val lookup = lookupNode(anchorTag, window)
        val node = (lookup as? NodeLookup.Found)?.node ?: return lookup
        val target = inputTargetComponent(window)
        val (x, y) = pointerPosition(node, window)
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        val now = System.currentTimeMillis()
        // Pointer must be over the target before the wheel event, same as click().
        queue.postEvent(
            MouseEvent(target, MouseEvent.MOUSE_ENTERED, now, 0, x, y, 0, false),
        )
        queue.postEvent(
            MouseEvent(target, MouseEvent.MOUSE_MOVED, now + 1, 0, x, y, 0, false),
        )
        queue.postEvent(
            MouseWheelEvent(
                target,
                MouseEvent.MOUSE_WHEEL,
                now + RELEASE_OFFSET_MS,
                0,
                x,
                y,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                WHEEL_SCROLL_AMOUNT,
                deltaY,
            ),
        )
        SwingUtilities.invokeAndWait {}
        return lookup
    }

    /**
     * The [SkiaLayer] embedded somewhere in [window]'s component tree — Compose Desktop renders
     * through it directly, bypassing the standard AWT/Swing paint chain entirely. Neither
     * `Component.paint()` into an off-screen `BufferedImage` (never sees Skia's own surface) nor
     * `Robot.createScreenCapture` (needs a real, unoccluded, mapped window — fragile in CI) can
     * reliably capture real content because of this.
     */
    private fun skiaLayer(window: Window): SkiaLayer {
        fun find(component: Component): SkiaLayer? = when {
            component is SkiaLayer -> component
            component is Container -> component.components.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        return find(window) ?: error("No SkiaLayer found in the window's component tree")
    }

    /**
     * Captures the window's current frame as a base64-encoded PNG via [SkiaLayer.screenshot] —
     * Skia's own in-process capture of what it actually rendered, not a re-derivation through AWT.
     */
    private fun captureScreenshot(window: Window): String {
        val bitmap = skiaLayer(window).screenshot() ?: error("SkiaLayer.screenshot() returned no bitmap")
        val data =
            Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)
                ?: error("Failed to encode screenshot to PNG")
        return Base64.getEncoder().encodeToString(data.bytes)
    }

    /**
     * Presses [modifierKey] then [key], releases [key] then [modifierKey] — a single key chord
     * (e.g. Ctrl+A) — and blocks until it's been dispatched.
     */
    private fun sendKeyChord(target: Component, modifierKey: Int, key: Int, modifierMask: Int) {
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        val now = System.currentTimeMillis()
        queue.postEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifierMask, modifierKey, KeyEvent.CHAR_UNDEFINED))
        queue.postEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, now + 1, modifierMask, key, KeyEvent.CHAR_UNDEFINED))
        queue.postEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, now + 2, modifierMask, key, KeyEvent.CHAR_UNDEFINED))
        queue.postEvent(
            KeyEvent(target, KeyEvent.KEY_RELEASED, now + RELEASE_OFFSET_MS, 0, modifierKey, KeyEvent.CHAR_UNDEFINED),
        )
        SwingUtilities.invokeAndWait {}
    }

    /**
     * Replaces the target field's entire content with [text] via the system clipboard rather than
     * simulating keystrokes — per-character key simulation doesn't reliably insert arbitrary text
     * (unicode, symbols) across keyboard layouts/locales. Assumes the target field is already
     * focused by a preceding [click]. Selects all existing content first (Ctrl/Cmd+A) so the paste
     * replaces it rather than inserting at the cursor on top of it — without this, an empty [text]
     * (a common way to clear a field) would be a no-op, since pasting nothing inserts nothing.
     */
    private fun pasteText(text: String, window: Window) {
        val target = inputTargetComponent(window)
        // Compose binds select-all/paste to the platform's menu-shortcut modifier: Cmd (Meta) on
        // macOS, Ctrl everywhere else. A hardcoded Ctrl chord is silently ignored on a Mac.
        val modifierMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        val modifierKey = if (modifierMask == InputEvent.META_DOWN_MASK) KeyEvent.VK_META else KeyEvent.VK_CONTROL
        sendKeyChord(target, modifierKey, KeyEvent.VK_A, modifierMask)
        // Gives Compose's recomposition pipeline a moment to apply the select-all before the
        // paste arrives — invokeAndWait only guarantees the KEY_RELEASED was dispatched, not
        // that the resulting selection change has been applied yet (same race as onEdt's doc).
        Thread.sleep(SELECT_ALL_SETTLE_MS)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        sendKeyChord(target, modifierKey, KeyEvent.VK_V, modifierMask)
    }
}
