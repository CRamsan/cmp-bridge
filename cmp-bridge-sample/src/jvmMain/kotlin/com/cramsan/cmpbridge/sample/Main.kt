package com.cramsan.cmpbridge.sample

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.cramsan.cmpbridge.DesktopBridgeServer

/**
 * Desktop entry point. Arms [DesktopBridgeServer] against the app's real `ComposeWindow` — a
 * no-op unless launched with `CMP_BRIDGE_ENABLED=true` (or `-DcmpBridge.enabled=true`).
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "cmp-bridge sample") {
        val scope = rememberCoroutineScope()
        DesktopBridgeServer.startIfEnabled(window, scope)
        App()
    }
}
