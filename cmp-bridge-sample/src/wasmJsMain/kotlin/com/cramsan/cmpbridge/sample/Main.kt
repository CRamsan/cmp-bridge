package com.cramsan.cmpbridge.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/**
 * wasmJs entry point. No cmp-bridge-specific code at all — Compose Multiplatform's own hidden
 * accessibility DOM is enabled by default (`ComposeViewportConfiguration.isA11YEnabled = true`),
 * which is all [com.cramsan.cmpbridge.driver.WebBridgeDriver] needs to read and drive this same
 * [App] from the outside via a real headless browser.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        App()
    }
}
