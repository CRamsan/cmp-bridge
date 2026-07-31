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
import java.awt.Component
import java.awt.Container
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Debug-only local bridge server for JVM desktop, driving the app the same way an external tool
 * would: it never touches Compose internals for input, only the app's real semantics tree
 * (`ComposeWindow.semanticsOwners`) for reads plus synthetic AWT input events posted directly onto
 * the app's own event queue, so interactions go through the app's actual input pipeline
 * (Compose's real pointer/key event processing) without depending on OS-level input synthesis
 * (`java.awt.Robot`/XTEST), which isn't reliably supported on every X server (e.g. hangs outright
 * in some sandboxed/virtual displays).
 *
 * Never started unless [ENABLED_PROPERTY] is set — this must never run in a release build.
 * Speaks the typed [BridgeCommand]/[BridgeResponse] protocol (see `BridgeProtocol.kt`): one
 * JSON-encoded command per line in, one JSON-encoded response per line out.
 */
// One cohesive protocol handler, deliberately kept as small private helpers rather than split.
@Suppress("TooManyFunctions")
object DesktopBridgeServer {
    const val ENABLED_PROPERTY = "cmpBridge.enabled"
    const val PORT_PROPERTY = "cmpBridge.port"
    private const val DEFAULT_PORT = 8901
    private val protocolJson = Json { ignoreUnknownKeys = true }

    // Each synthetic AWT event in a gesture needs a strictly increasing timestamp (real input
    // never produces two events at the exact same millisecond), so every step in click()/
    // pasteText() below claims the next offset from `now`.
    private const val RELEASE_OFFSET_MS = 3L

    // AWT's wheel model is click-based, not pixel-based; 3 units per "click" matches the
    // platform's own typical default (see MouseWheelEvent.getScrollAmount() docs).
    private const val WHEEL_SCROLL_AMOUNT = 3

    // Representative subset of SemanticsActions surfaced on HierarchyNode.actions — not
    // exhaustive, but covers the actions a scenario/agent is actually likely to act on.
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
     * Starts the bridge server on [scope] if [ENABLED_PROPERTY] is set to `"true"`; otherwise a
     * no-op. Must be called with the app's root [Window] so click/type coordinates can be
     * resolved to the actual Compose input target inside it.
     */
    fun startIfEnabled(window: Window, scope: CoroutineScope) {
        if (System.getProperty(ENABLED_PROPERTY) != "true") return
        val port = System.getProperty(PORT_PROPERTY)?.toIntOrNull() ?: DEFAULT_PORT
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
                // Both decoding and command execution (e.g. a screen-capture call failing) must
                // never crash this connection's loop silently — the driver is always owed a
                // response line, even if it's a Failure.
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
            BridgeResponse.Hierarchy(buildHierarchy(window))
        }

        is BridgeCommand.Click -> {
            if (click(command.tag, window)) BridgeResponse.Ack else unknownTag(command.tag)
        }

        is BridgeCommand.SetText -> {
            if (click(command.tag, window)) {
                pasteText(command.text, window)
                BridgeResponse.Ack
            } else {
                unknownTag(command.tag)
            }
        }

        is BridgeCommand.Scroll -> {
            if (scroll(command.anchorTag, command.deltaY, window)) {
                BridgeResponse.Ack
            } else {
                unknownTag(command.anchorTag)
            }
        }

        is BridgeCommand.Screenshot -> {
            BridgeResponse.Image(captureScreenshot(window))
        }
    }

    private fun unknownTag(tag: String): BridgeResponse.Failure = BridgeResponse.Failure("Unknown tag: $tag")

    /**
     * Builds a fresh [HierarchyNode] tree from the app's real semantics tree — queried live on
     * every call, never cached, so there's no "last known state" to go stale.
     */
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
        // A field marked `password()` (see EdifikanaPasswordTextField) never surfaces its real
        // content through this bridge, even in a debug build — `PasswordVisualTransformation`
        // only masks the drawn glyphs, not what Compose's semantics tree itself reports.
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
     * Mirrors Compose Web's own `ComposeWebSemanticsListener.getRoleId()` mapping exactly
     * (including its later-check-wins ordering, and the same acknowledged imprecision: an
     * element with both an explicit [SemanticsProperties.Role] and [SemanticsActions.OnClick] —
     * e.g. a checkbox, which normally has both — gets normalized to `"button"`) so scenario code
     * sees the same role vocabulary on both platforms instead of two independently-drifting ones.
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
     * The component that actually receives input inside a Compose Desktop [Window]. This is
     * several layers below the window itself (`JRootPane` > `JLayeredPane` > ... >
     * `ComposeWindowPanel` > ... > Skiko's `SkiaLayer`'s content component), and that exact
     * nesting is an internal implementation detail that could shift between Compose Multiplatform
     * versions. Rather than hardcode the path, walk the component tree depth-first for whichever
     * descendant actually has a mouse listener registered on it — that's the real input target
     * (confirmed by inspecting Compose Desktop's own `ComposeSceneMediator.subscribeToInputEvents`)
     * regardless of how many wrapper panels sit above it. Falls back to the window itself if
     * nothing qualifies, so this degrades rather than crashing.
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

    /** Returns `false` without dispatching anything if [tag] isn't known yet. */
    private fun click(tag: String, window: Window): Boolean {
        val node = buildHierarchy(window).find(tag) ?: return false
        val target = inputTargetComponent(window)
        val x = (node.x + node.width / 2).toInt()
        val y = (node.y + node.height / 2).toInt()
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        val now = System.currentTimeMillis()
        // A real click is always preceded by pointer movement; Compose's pointer-input pipeline
        // tracks hover/position state and doesn't process a press cleanly without it.
        queue.postEvent(
            MouseEvent(target, MouseEvent.MOUSE_ENTERED, now, 0, x, y, 0, false),
        )
        queue.postEvent(
            MouseEvent(target, MouseEvent.MOUSE_MOVED, now + 1, 0, x, y, 0, false),
        )
        // modifiersEx must reflect button state *at the time of the event*, not just "which
        // button is involved" (that's what the trailing `button` param is for): the button is
        // down during PRESSED, but no longer down by RELEASED/CLICKED. Compose Desktop's
        // internal OnlyValidPrimaryMouseButtonFilter tracks this across events and silently drops
        // the gesture if told the button is still down after it's released — confirmed by
        // decompiling ComposeSceneMediator/AwtEventFilter and reproducing the fix live.
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
        // The event queue processes events strictly in order, so an empty invokeAndWait here
        // only returns once every event posted above has actually been dispatched.
        SwingUtilities.invokeAndWait {}
        return true
    }

    /**
     * Synthesizes a wheel-scroll gesture centered on [anchorTag]'s bounds. Returns `false` without
     * dispatching anything if [anchorTag] isn't known yet.
     */
    private fun scroll(anchorTag: String, deltaY: Int, window: Window): Boolean {
        val node = buildHierarchy(window).find(anchorTag) ?: return false
        val target = inputTargetComponent(window)
        val x = (node.x + node.width / 2).toInt()
        val y = (node.y + node.height / 2).toInt()
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        val now = System.currentTimeMillis()
        // Same lesson as click(): the pointer needs to be over the target before the gesture.
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
        return true
    }

    /**
     * Captures the app window's current frame as a base64-encoded PNG by painting the window's
     * own component tree into an off-screen image — not [Robot.createScreenCapture], which this
     * sandbox's display denies outright (`"Screen Capture in the selected area was not
     * allowed"`), confirmed live. [Component.paint] re-invokes Compose Desktop's real Skia
     * rendering synchronously into the supplied [java.awt.Graphics], the same as any other AWT
     * repaint, so this reflects the actual current frame rather than a stale buffer.
     */
    private fun captureScreenshot(window: Window): String {
        val image = BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        window.paint(graphics)
        graphics.dispose()
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    /**
     * Pastes [text] via the system clipboard rather than simulating each keystroke, since
     * per-character key simulation is unreliable across keyboard layouts/locales for arbitrary
     * text (unicode, symbols) — confirmed live that raw `KEY_TYPED` events don't insert text at
     * all (Compose Desktop's normal typing path goes through the platform input-method framework,
     * not bare key events), whereas a real Ctrl+V shortcut does. Assumes the target field is
     * already focused by a preceding [click].
     */
    private fun pasteText(text: String, window: Window) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        val target = inputTargetComponent(window)
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        val now = System.currentTimeMillis()
        queue.postEvent(
            KeyEvent(
                target,
                KeyEvent.KEY_PRESSED,
                now,
                InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_CONTROL,
                KeyEvent.CHAR_UNDEFINED,
            ),
        )
        queue.postEvent(
            KeyEvent(
                target,
                KeyEvent.KEY_PRESSED,
                now + 1,
                InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_V,
                KeyEvent.CHAR_UNDEFINED,
            ),
        )
        queue.postEvent(
            KeyEvent(
                target,
                KeyEvent.KEY_RELEASED,
                now + 2,
                InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_V,
                KeyEvent.CHAR_UNDEFINED,
            ),
        )
        queue.postEvent(
            KeyEvent(
                target,
                KeyEvent.KEY_RELEASED,
                now + RELEASE_OFFSET_MS,
                0,
                KeyEvent.VK_CONTROL,
                KeyEvent.CHAR_UNDEFINED,
            ),
        )
        SwingUtilities.invokeAndWait {}
    }
}
