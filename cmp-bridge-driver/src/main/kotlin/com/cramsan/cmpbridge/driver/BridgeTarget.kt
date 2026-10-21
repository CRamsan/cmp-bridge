package com.cramsan.cmpbridge.driver

import kotlinx.serialization.Serializable

/**
 * Identifies which app instance to attach to: `"desktop"` (via [host]/[port]), `"android"` (via
 * the device-side [port], plus [serial] when several devices are attached — [host] is ignored,
 * the device is always reached through `adb forward`) or `"web"` (via [url]). Plain data —
 * [BridgeSessionRegistry] is what turns this into a live [BridgeDriver].
 */
@Serializable
data class BridgeTarget(
    val platform: String,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val url: String? = null,
    val serial: String? = null,
) {
    private companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8901
    }
}
