package com.cramsan.cmpbridge.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import org.jetbrains.skiko.wasm.onWasmReady

/**
 * wasmJs entry point. No cmp-bridge-specific code at all — Compose Multiplatform's own hidden
 * accessibility DOM is enabled by default (`ComposeViewportConfiguration.isA11YEnabled = true`),
 * which is all [com.cramsan.cmpbridge.driver.WebBridgeDriver] needs to read and drive this same
 * [App] from the outside via a real headless browser.
 *
 * [onWasmReady] wrapping the no-arg [ComposeViewport] (rather than calling
 * `ComposeViewport(document.body!!) { App() }` directly) is load-bearing, not stylistic — see
 * gotcha #10 in CLAUDE.md: without it, the layout pass for content placed after a text-input
 * composable permanently stalled with zero bounds on this Compose Multiplatform version.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    onWasmReady {
        ComposeViewport {
            App()
        }
    }
}
