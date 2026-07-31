package com.cramsan.cmpbridge.sample

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.cramsan.cmpbridge.DesktopBridgeServer

/**
 * Desktop entry point. `window` (from [androidx.compose.ui.window.WindowScope]) is the real
 * `ComposeWindow` [DesktopBridgeServer] reads its semantics tree from and posts synthetic AWT
 * input events onto — arming the bridge here is the only cmp-bridge-specific code this app needs
 * (see `README.md`'s "Using it in your app"). It's a no-op unless launched with
 * `-DcmpBridge.enabled=true`, which is exactly what [com.cramsan.cmpbridge.driver.DesktopAppProcess]
 * does for a test.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "cmp-bridge sample") {
        val scope = rememberCoroutineScope()
        DesktopBridgeServer.startIfEnabled(window, scope)
        App()
    }
}
