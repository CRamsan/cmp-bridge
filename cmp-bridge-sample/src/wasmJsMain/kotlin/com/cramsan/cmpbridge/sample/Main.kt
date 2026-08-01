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
 * `ComposeViewport(document.body!!) { App() }` directly) is load-bearing, not stylistic. Without
 * it, on this Compose Multiplatform version, `ComposeViewport` could start rendering against a
 * `<body>` with no established size and before the wasm runtime had actually finished loading — the
 * very first layout pass measured against a degenerate viewport, and content placed after a
 * text-input composable (confirmed live: [App]'s name field, greeting text, and item list)
 * permanently stalled with zero bounds afterward, never getting a correct subsequent pass. Fixed
 * together with `index.html`'s `<meta name="viewport">` tag and `styles.css` forcing
 * `html, body { width: 100%; height: 100%; margin: 0; padding: 0; overflow: hidden; }`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    onWasmReady {
        ComposeViewport {
            App()
        }
    }
}
