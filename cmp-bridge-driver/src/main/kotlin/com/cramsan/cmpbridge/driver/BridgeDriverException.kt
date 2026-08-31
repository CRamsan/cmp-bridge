package com.cramsan.cmpbridge.driver

/** A failure raised by [BridgeDriver]/[BridgeSessionRegistry], typed by what kind of failure it is. */
sealed class BridgeDriverException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The targeted tag doesn't exist right now (click/setText/scroll/hierarchy lookup). */
class UnknownTagException(message: String) : BridgeDriverException(message)

/**
 * The targeted tag exists in the tree but has zero/off-screen bounds right now — e.g. a
 * `LazyColumn` item not yet scrolled into view (click/setText/scroll). Distinguishable from
 * [UnknownTagException] so a caller can react differently (scroll into view, then retry) — see
 * https://github.com/CRamsan/cmp-bridge/issues/12.
 */
class TagNotVisibleException(message: String) : BridgeDriverException(message)

/** A wait ([BridgeDriver.waitForTagVisibility]/[BridgeDriver.waitForText]) exceeded its timeout. */
class BridgeTimeoutException(message: String) : BridgeDriverException(message)

/** The driver couldn't reach or stay connected to the app (socket/browser-level failure). */
class BridgeConnectionException(message: String, cause: Throwable? = null) : BridgeDriverException(message, cause)

/** The request's target itself is invalid (unknown platform, missing required field). */
class InvalidTargetException(message: String) : BridgeDriverException(message)
