package com.cramsan.cmpbridge.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import org.jetbrains.skiko.wasm.onWasmReady

/**
 * wasmJs entry point. No cmp-bridge-specific code needed — Compose Multiplatform's accessibility
 * DOM is enabled by default, which is all [com.cramsan.cmpbridge.driver.WebBridgeDriver] needs.
 *
 * [onWasmReady] wrapping the no-arg [ComposeViewport] is load-bearing: without it, layout can run
 * before the wasm runtime and `<body>` sizing are ready, leaving later content stuck with zero
 * bounds. Paired with `index.html`'s viewport meta tag and `styles.css`'s explicit `html, body`
 * sizing.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    onWasmReady {
        ComposeViewport {
            App()
        }
    }
}
