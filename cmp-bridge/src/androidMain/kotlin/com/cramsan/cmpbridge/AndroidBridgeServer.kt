package com.cramsan.cmpbridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Debug-only local bridge server for Android — the Android counterpart of [DesktopBridgeServer].
 * Reads the app's real semantics tree through Compose's [ViewRootForTest] roots and drives it
 * with synthetic [MotionEvent]/[KeyEvent]s dispatched into the app's own views on the main
 * thread — never `Instrumentation` or `UiAutomation`, so it needs no test APK or shell privileges
 * and exercises the same input pipeline a real touch would. Speaks the same
 * [BridgeCommand]/[BridgeResponse] line protocol as desktop, so `DesktopBridgeDriver` drives it
 * unchanged once the device port is reachable from the host (`adb forward tcp:8901 tcp:8901`).
 *
 * Never started unless the launching [Activity]'s intent carries [ENABLED_EXTRA] = `true`:
 * `adb shell am start -n <app>/<activity> --ez cmpBridge.enabled true [--ei cmpBridge.port N]`.
 * Binds to loopback only — the host reaches it through adb's forwarding, never over the network.
 */
// One cohesive protocol handler, deliberately kept as small private helpers rather than split.
@Suppress("TooManyFunctions")
object AndroidBridgeServer {
    const val ENABLED_EXTRA = "cmpBridge.enabled"
    const val PORT_EXTRA = "cmpBridge.port"

    private const val TAG = "cmp-bridge"
    private const val DEFAULT_PORT = 8901
    private const val SOCKET_BACKLOG = 50

    // Each synthetic event in a gesture needs a strictly increasing timestamp.
    private const val RELEASE_OFFSET_MS = 3L

    // See pasteText's own doc for why this settle is needed between select-all and the paste.
    private const val SELECT_ALL_SETTLE_MS = 50L

    private const val PNG_QUALITY = 100

    private val mainHandler = Handler(Looper.getMainLooper())

    // Every Compose root created since start(), including popup/dialog windows, which live in their
    // own Window outside the activity's decor view and so can't be found by walking it.
    private val roots = CopyOnWriteArrayList<WeakReference<ViewRootForTest>>()

    /**
     * Starts the bridge server on [scope] if [activity]'s launch intent enables it (see the class
     * doc); otherwise a no-op. Call from `onCreate`, before `setContent`, so the hook that tracks
     * Compose roots is in place before the first one is created.
     */
    fun startIfEnabled(activity: Activity, scope: CoroutineScope) {
        val intent = activity.intent ?: return
        if (!intent.getBooleanExtra(ENABLED_EXTRA, false)) return
        start(activity, scope, intent.getIntExtra(PORT_EXTRA, DEFAULT_PORT))
    }

    /** Unconditionally starts the bridge server on [port]; prefer [startIfEnabled] in app code. */
    fun start(activity: Activity, scope: CoroutineScope, port: Int = DEFAULT_PORT) {
        ViewRootForTest.onViewCreatedCallback = { root -> roots.add(WeakReference(root)) }
        scope.launch(Dispatchers.IO) {
            // A debug aid must never take the app down with it: a bind failure (port in use, the
            // consuming app missing the INTERNET permission this library's manifest requests) is
            // logged and the server simply isn't there, rather than crashing the process.
            try {
                ServerSocket(port, SOCKET_BACKLOG, InetAddress.getLoopbackAddress()).use { serverSocket ->
                    Log.i(TAG, "AndroidBridgeServer listening on 127.0.0.1:$port")
                    while (true) {
                        val socket = serverSocket.accept()
                        launch(Dispatchers.IO) {
                            serveBridgeConnection(socket) { command -> handleCommand(command, activity) }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "AndroidBridgeServer could not listen on 127.0.0.1:$port", e)
            }
        }
    }

    private fun handleCommand(command: BridgeCommand, activity: Activity): BridgeResponse = when (command) {
        is BridgeCommand.GetHierarchy -> BridgeResponse.Hierarchy(onMain { buildHierarchy(activity) })

        is BridgeCommand.Click -> lookup(command.tag, activity).toResponse(command.tag) { click(it) }

        is BridgeCommand.SetText ->
            lookup(command.tag, activity).toResponse(command.tag) { target ->
                click(target)
                pasteText(command.text, activity, target.root)
            }

        is BridgeCommand.Scroll ->
            lookup(command.anchorTag, activity).toResponse(command.anchorTag) { scroll(it, command.deltaY) }

        is BridgeCommand.Screenshot -> BridgeResponse.Image(captureScreenshot(activity))
    }

    /** A resolved node together with the Compose root (and therefore the [View]) that owns it. */
    private class Target(val node: HierarchyNode, val root: ViewRootForTest)

    private fun liveRoots(activity: Activity): List<ViewRootForTest> {
        val tracked = roots.mapNotNull { it.get() }.filter { it.view.isAttachedToWindow }
        // Roots created before start() (an unusual call order) are only reachable by walking the
        // activity's own view tree; popups created that way are missed until recreated.
        val walked = findComposeRoots(activity.window.decorView)
        return (tracked + walked).distinct()
    }

    private fun findComposeRoots(view: View): List<ViewRootForTest> = when {
        view is ViewRootForTest -> listOf(view)
        view is ViewGroup -> (0 until view.childCount).flatMap { findComposeRoots(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** Builds a fresh [HierarchyNode] tree from the live semantics trees — queried live, never cached. */
    private fun buildHierarchy(activity: Activity): HierarchyNode =
        liveRoots(activity).map { it.semanticsOwner.rootSemanticsNode.toHierarchyNode() }.asSingleRoot()

    /**
     * Resolves [tag] across every live root. Unlike desktop, the owning root matters here: input
     * must be dispatched into that root's own [View] (a popup's content lives in its own window).
     */
    private fun lookup(tag: String, activity: Activity): TargetLookup = onMain {
        liveRoots(activity).firstNotNullOfOrNull { root ->
            when (val lookup = root.semanticsOwner.rootSemanticsNode.toHierarchyNode().lookup(tag)) {
                is NodeLookup.Found -> TargetLookup.Found(Target(lookup.node, root))
                NodeLookup.NotVisible -> TargetLookup.NotVisible
                NodeLookup.NotFound -> null
            }
        } ?: TargetLookup.NotFound
    }

    private sealed class TargetLookup {
        class Found(val target: Target) : TargetLookup()
        data object NotVisible : TargetLookup()
        data object NotFound : TargetLookup()
    }

    private inline fun TargetLookup.toResponse(tag: String, onFound: (Target) -> Unit): BridgeResponse = when (this) {
        is TargetLookup.Found -> {
            onFound(target)
            BridgeResponse.Ack
        }

        TargetLookup.NotVisible -> NodeLookup.NotVisible.toResponse(tag)

        TargetLookup.NotFound -> NodeLookup.NotFound.toResponse(tag)
    }

    private fun click(target: Target) {
        val node = target.node
        onMain {
            val view = target.root.view
            val (x, y) = node.centerInView(view)
            val now = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(touchEvent(now, now, MotionEvent.ACTION_DOWN, x, y))
            view.dispatchTouchEvent(touchEvent(now, now + RELEASE_OFFSET_MS, MotionEvent.ACTION_UP, x, y))
        }
    }

    /**
     * Synthesizes a mouse-wheel scroll centered on the anchor, which Compose's scrollable
     * containers honour on Android the same way they do a fling. Positive [deltaY] scrolls the
     * content down (towards later items), matching the desktop server's AWT wheel convention.
     */
    private fun scroll(target: Target, deltaY: Int) {
        val node = target.node
        onMain {
            val view = target.root.view
            val (x, y) = node.centerInView(view)
            val now = SystemClock.uptimeMillis()
            val props = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
            val coords = MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_VSCROLL, -deltaY.toFloat())
            }
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
            )
            view.dispatchGenericMotionEvent(event)
            event.recycle()
        }
    }

    /**
     * Replaces the focused field's content with [text] via the system clipboard plus a hardware
     * Ctrl+A / Ctrl+V chord dispatched into the Compose root — the same approach as desktop, for
     * the same reason (per-character key simulation doesn't handle arbitrary text). Assumes the
     * preceding click focused the field.
     */
    private fun pasteText(text: String, activity: Activity, root: ViewRootForTest) {
        val view = root.view
        onMain { sendKeyChord(view, KeyEvent.KEYCODE_A) }
        Thread.sleep(SELECT_ALL_SETTLE_MS)
        onMain {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("cmp-bridge", text))
            sendKeyChord(view, KeyEvent.KEYCODE_V)
        }
    }

    private fun sendKeyChord(view: View, keyCode: Int) {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val now = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        view.dispatchKeyEvent(KeyEvent(now, now + RELEASE_OFFSET_MS, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /**
     * Captures the activity window's current frame via [PixelCopy] — what the compositor actually
     * rendered, not a re-draw through `View.draw` (which Compose's hardware-accelerated rendering
     * bypasses).
     */
    private fun captureScreenshot(activity: Activity): String {
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(
            activity.window,
            bitmap,
            {
                result = it
                latch.countDown()
            },
            mainHandler,
        )
        latch.await()
        check(result == PixelCopy.SUCCESS) { "PixelCopy failed with code $result" }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Node center translated from window coordinates (what semantics bounds use) to [view]'s own. */
    private fun HierarchyNode.centerInView(view: View): Pair<Float, Float> {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return (x + width / 2 - location[0]) to (y + height / 2 - location[1])
    }

    private fun touchEvent(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }

    /**
     * Runs [block] on the main thread and returns its result. Compose's live semantics/layout tree
     * is only safe to read from the thread that mutates it, and input must be dispatched there.
     */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        mainHandler.post {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }
}
