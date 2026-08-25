package com.cramsan.cmpbridge.driver

import kotlinx.serialization.Serializable

/** Which existence state [BridgeDriver.waitForTagVisibility] should wait for. */
@Serializable
enum class TagVisibility {
    /** The tag is found (has settled, non-zero bounds). */
    VISIBLE,

    /** The tag is not found. */
    GONE,
}
