package com.cramsan.cmpbridge

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
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

    // JVM system properties (-D) aren't inherited by a forked child process on any launcher —
    // Gradle's JavaExec, an IDE run configuration, a shell script — unless that launcher explicitly
    // forwards them, which none do by default. Environment variables are, by default, everywhere
    // (Gradle's JavaExec.environment defaults to the launching process's own env; so does a plain
    // shell fork). The env var is the mechanism that works out of the box through `./gradlew :run`
    // for any consuming app, without that app needing any Gradle changes of its own; the system
    // property stays supported for callers that construct the process directly (e.g.
    // DesktopAppProcess) or invoke `java` themselves.
    const val ENABLED_ENV_VAR = "CMP_BRIDGE_ENABLED"
    const val PORT_ENV_VAR = "CMP_BRIDGE_PORT"

    private const val DEFAULT_PORT = 8901
    private val protocolJson = Json { ignoreUnknownKeys = true }

    // The exact prefixes DesktopBridgeDriver.sendTyped pattern-matches on to distinguish these two
    // failure modes client-side (see issue #12) — this object is the single source of both.
    private const val UNKNOWN_TAG_PREFIX = "Unknown tag: "
    private const val NOT_VISIBLE_TAG_PREFIX = "Tag not visible: "

    // Each synthetic AWT event in a gesture needs a strictly increasing timestamp.
    private const val RELEASE_OFFSET_MS = 3L

    // AWT's wheel model is click-based, not pixel-based; matches the platform's typical default.
    private const val WHEEL_SCROLL_AMOUNT = 3

    // See pasteText's own doc for why this settle is needed between Ctrl+A and the paste.
    private const val SELECT_ALL_SETTLE_MS = 50L

    // Subset of SemanticsActions surfaced on HierarchyNode.actions.
    private val ACTION_KEYS: List<Pair<String, SemanticsPropertyKey<*>>> =
        listOf(
            "OnClick" to SemanticsActions.OnClick,
            "OnLongClick" to SemanticsActions.OnLongClick,
            "ScrollBy" to SemanticsActions.ScrollBy,
            "SetText" to SemanticsActions.SetText,
            "Expand" to SemanticsActions.Expand,
            "Collapse" to SemanticsActions.Collapse,
            "RequestFocus" to SemanticsActions.RequestFocus,
        )

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
                    launch(Dispatchers.IO) { handleConnection(socket, window) }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // see the comment on the catch site below: deliberate, not an oversight
    private suspend fun handleConnection(socket: Socket, window: Window) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val writer = PrintWriter(it.getOutputStream(), true)
            while (true) {
                val line = reader.readLine() ?: break
                // A command that fails to decode or execute still owes the driver a response line.
                val response =
                    try {
                        handleCommand(protocolJson.decodeFromString<BridgeCommand>(line), window)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        BridgeResponse.Failure(e.message ?: e::class.simpleName ?: "command failed")
                    }
                writer.println(protocolJson.encodeToString(response))
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
            click(command.tag, window).toResponse(command.tag) { pasteText(command.text, window) }
        }

        is BridgeCommand.Scroll -> {
            scroll(command.anchorTag, command.deltaY, window).toResponse(command.anchorTag)
        }

        is BridgeCommand.Screenshot -> {
            BridgeResponse.Image(captureScreenshot(window))
        }
    }

    /** Turns a tag lookup outcome into the [BridgeResponse] for it, running [onFound] first if it resolved. */
    private fun NodeLookup.toResponse(tag: String, onFound: () -> Unit = {}): BridgeResponse = when (this) {
        is NodeLookup.Found -> {
            onFound()
            BridgeResponse.Ack
        }

        NodeLookup.NotFound -> unknownTag(tag)

        NodeLookup.NotVisible -> notVisibleTag(tag)
    }

    private fun unknownTag(tag: String): BridgeResponse.Failure = BridgeResponse.Failure("$UNKNOWN_TAG_PREFIX$tag")

    private fun notVisibleTag(tag: String): BridgeResponse.Failure =
        BridgeResponse.Failure("$NOT_VISIBLE_TAG_PREFIX$tag")

    /** The outcome of resolving a tag to a node before dispatching input at it. */
    private sealed class NodeLookup {
        data class Found(val node: HierarchyNode) : NodeLookup()
        data object NotFound : NodeLookup()
        data object NotVisible : NodeLookup()
    }

    /**
     * Resolves [tag] to its node, distinguishing "not in the tree at all" from "present but
     * zero/off-screen bounds" (e.g. a `LazyColumn` item not yet scrolled into view) — see
     * https://github.com/CRamsan/cmp-bridge/issues/12.
     */
    private fun lookupNode(tag: String, window: Window): NodeLookup {
        val node = onEdt { buildHierarchy(window).find(tag) } ?: return NodeLookup.NotFound
        return if (node.width <= 0f || node.height <= 0f) NodeLookup.NotVisible else NodeLookup.Found(node)
    }

    /**
     * Runs [block] on the AWT event dispatch thread and returns its result. Compose's live
     * semantics/layout tree is only safe to read from the same thread that mutates it — the one
     * write operations already marshal onto via [SwingUtilities.invokeAndWait]. Reading it
     * directly from this connection's own IO-dispatched coroutine can race a concurrent
     * structural change (`LazyColumn` recycling during [scroll], recomposition during
     * [pasteText]) and crash mid-read on a torn tree — reproduced as a
     * `LayoutNode.getZIndex()` NPE during stress testing (issue #23).
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
        val owners = composeWindow.semanticsOwners
        return when (owners.size) {
            0 -> {
                error("No semantics owner available yet")
            }

            1 -> {
                owners.first().rootSemanticsNode.toHierarchyNode()
            }

            // Multiple roots (e.g. a popup/dialog on top of the main content): wrap them under a
            // synthetic, tag-less root rather than picking one arbitrarily.
            else -> {
                HierarchyNode(
                    testTag = null,
                    role = null,
                    text = null,
                    contentDescription = null,
                    x = 0f,
                    y = 0f,
                    width = 0f,
                    height = 0f,
                    enabled = true,
                    actions = emptySet(),
                    children = owners.map { it.rootSemanticsNode.toHierarchyNode() },
                )
            }
        }
    }

    private fun SemanticsNode.toHierarchyNode(): HierarchyNode {
        val bounds = boundsInWindow
        val cfg = config
        // password() fields never surface real text/contentDescription through this bridge.
        val isPassword = cfg.contains(SemanticsProperties.Password)
        return HierarchyNode(
            testTag = cfg.valueOrNull(SemanticsProperties.TestTag),
            role = cfg.roleString(),
            text =
            if (isPassword) {
                null
            } else {
                cfg.valueOrNull(SemanticsProperties.EditableText)?.text
                    ?: cfg.valueOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }
            },
            contentDescription =
            if (isPassword) null else cfg.valueOrNull(SemanticsProperties.ContentDescription)?.joinToString(", "),
            x = bounds.left,
            y = bounds.top,
            width = bounds.width,
            height = bounds.height,
            enabled = !cfg.contains(SemanticsProperties.Disabled),
            actions = ACTION_KEYS.mapNotNullTo(mutableSetOf()) { (name, key) -> name.takeIf { cfg.contains(key) } },
            children = children.map { it.toHierarchyNode() },
        )
    }

    private fun <T> SemanticsConfiguration.valueOrNull(key: SemanticsPropertyKey<T>): T? =
        if (contains(key)) get(key) else null

    /**
     * Normalizes to the same role vocabulary as the web accessibility DOM, including its
     * ambiguity: an element with both an explicit [SemanticsProperties.Role] and
     * [SemanticsActions.OnClick] normalizes to `"button"`.
     */
    private fun SemanticsConfiguration.roleString(): String? {
        var role = valueOrNull(SemanticsProperties.Role)?.let { explicitRoleString(it) }
        if (contains(SemanticsActions.OnClick)) role = "button"
        if (contains(SemanticsProperties.Heading)) role = "heading"
        if (contains(SemanticsProperties.EditableText)) role = "textbox"
        if (contains(SemanticsProperties.CollectionInfo)) {
            role = collectionRoleString(get(SemanticsProperties.CollectionInfo))
        }
        return role
    }

    private fun explicitRoleString(role: Role): String? = when (role) {
        Role.Button -> "button"
        Role.Checkbox -> "checkbox"
        Role.Switch -> "switch"
        Role.RadioButton -> "radio"
        Role.Tab -> "tab"
        Role.Image -> "img"
        Role.DropdownList -> "menu"
        else -> null
    }

    private fun collectionRoleString(info: CollectionInfo): String =
        if (info.rowCount > 1 && info.columnCount > 1) "grid" else "list"

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

    /** Returns without dispatching anything if [tag] isn't found or isn't currently visible. */
    private fun click(tag: String, window: Window): NodeLookup {
        val lookup = lookupNode(tag, window)
        val node = (lookup as? NodeLookup.Found)?.node ?: return lookup
        val target = inputTargetComponent(window)
        val x = (node.x + node.width / 2).toInt()
        val y = (node.y + node.height / 2).toInt()
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
        val x = (node.x + node.width / 2).toInt()
        val y = (node.y + node.height / 2).toInt()
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
     * through it directly (Skia manages its own GPU/software surface), bypassing the standard
     * AWT/Swing paint chain entirely. That's why neither `Component.paint()` into an off-screen
     * `BufferedImage` nor `Robot.createScreenCapture` reliably captures real content: the former
     * only ever sees whatever a plain Swing repaint would draw (Skia's content never reaches that
     * `Graphics2D`), and the latter reads real screen pixels, which requires an actual mapped,
     * unoccluded window and X11 permission to capture it — fragile exactly in the kind of
     * sandboxed/CI environment this bridge needs to work in.
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
     * focused by a preceding [click]. Selects all existing content first (Ctrl+A) so the paste
     * replaces it rather than inserting at the cursor on top of it — without this, an empty [text]
     * (a common way to clear a field) would be a no-op, since pasting nothing inserts nothing.
     */
    private fun pasteText(text: String, window: Window) {
        val target = inputTargetComponent(window)
        sendKeyChord(target, KeyEvent.VK_CONTROL, KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK)
        // Gives Compose's own state/recomposition pipeline a moment to actually apply the
        // select-all before the paste arrives — invokeAndWait only guarantees the KEY_RELEASED
        // was *dispatched*, not that Compose's resulting selection state change has been applied
        // yet (same class of race as onEdt's own doc). Without this, setText's own select-all
        // intermittently loses the race and the paste appends instead of replacing (issue #23):
        // measured at a 10-42% failure rate before this, ~0% after, across repeated 100-iteration
        // stress runs (see BridgeDriverStressTest).
        Thread.sleep(SELECT_ALL_SETTLE_MS)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        sendKeyChord(target, KeyEvent.VK_CONTROL, KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)
    }
}
