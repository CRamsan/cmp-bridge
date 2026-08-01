package com.cramsan.cmpbridge

import kotlinx.serialization.Serializable

/**
 * Wire protocol between [DesktopBridgeServer] and `DesktopBridgeDriver`: one JSON-encoded
 * [BridgeCommand] per line in, one JSON-encoded [BridgeResponse] per line out.
 */
@Serializable
sealed interface BridgeCommand {
    /** Requests the app's current real semantics tree. */
    @Serializable
    data object GetHierarchy : BridgeCommand

    /** Requests a real synthetic click on [tag]. */
    @Serializable
    data class Click(val tag: String) : BridgeCommand

    /** Requests a real synthetic click on [tag] followed by typing [text] into it. */
    @Serializable
    data class SetText(val tag: String, val text: String) : BridgeCommand

    /** Requests a real synthetic scroll gesture centered on [anchorTag]'s current bounds. */
    @Serializable
    data class Scroll(val anchorTag: String, val deltaY: Int) : BridgeCommand

    /** Requests a screenshot of the app's current frame. */
    @Serializable
    data object Screenshot : BridgeCommand
}

/** Responses for every [BridgeCommand] variant. */
@Serializable
sealed interface BridgeResponse {
    /** Answers [BridgeCommand.GetHierarchy]. */
    @Serializable
    data class Hierarchy(val root: HierarchyNode) : BridgeResponse

    /** Answers a successful [BridgeCommand.Click]/[BridgeCommand.SetText]/[BridgeCommand.Scroll]. */
    @Serializable
    data object Ack : BridgeResponse

    /** Answers [BridgeCommand.Screenshot] with a base64-encoded PNG. */
    @Serializable
    data class Image(val pngBase64: String) : BridgeResponse

    /** Answers any command that couldn't be carried out, e.g. an unknown tag or malformed input. */
    @Serializable
    data class Failure(val message: String) : BridgeResponse
}
