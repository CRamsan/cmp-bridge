package com.cramsan.cmpbridge.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cramsan.cmpbridge.sample.App

/**
 * Android entry point. Hosts the same shared [App] as the desktop and wasmJs entry points. No
 * cmp-bridge code is wired in yet — Android support for the bridge is still being built out, and
 * this activity is the in-repo app it will be developed and tested against.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
