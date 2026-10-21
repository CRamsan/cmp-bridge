package com.cramsan.cmpbridge.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.cramsan.cmpbridge.AndroidBridgeServer
import com.cramsan.cmpbridge.sample.App

/**
 * Android entry point. Hosts the same shared [App] as the desktop and wasmJs entry points and arms
 * [AndroidBridgeServer] against it — a no-op unless launched with the `cmpBridge.enabled` intent
 * extra set to `true`:
 *
 * ```
 * adb shell am start -n com.cramsan.cmpbridge.sample/.android.MainActivity --ez cmpBridge.enabled true
 * adb forward tcp:8901 tcp:8901
 * ```
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before setContent, so the server's Compose-root hook is in place for the first root.
        AndroidBridgeServer.startIfEnabled(this, lifecycleScope)
        setContent {
            App()
        }
    }
}
