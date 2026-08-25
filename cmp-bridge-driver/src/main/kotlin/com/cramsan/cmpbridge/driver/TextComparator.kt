package com.cramsan.cmpbridge.driver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How [BridgeDriver.waitForText] decides a tag's settled text is a match. `@SerialName` keeps the
 * HTTP/MCP wire format short and stable (`"present"`/`"equals"`/...) instead of the fully-qualified
 * Kotlin class name kotlinx.serialization would otherwise use as the discriminator by default.
 */
@Serializable
sealed interface TextComparator {
    /** Matches once the tag has any non-null, settled text. */
    @Serializable
    @SerialName("present")
    data object Present : TextComparator

    /** Matches once the tag's settled text is exactly empty. */
    @Serializable
    @SerialName("empty")
    data object Empty : TextComparator

    /** Matches once the tag's settled text equals [value] exactly. */
    @Serializable
    @SerialName("equals")
    data class Equals(val value: String) : TextComparator

    /** Matches once the tag's settled text starts with [prefix]. */
    @Serializable
    @SerialName("startsWith")
    data class StartsWith(val prefix: String) : TextComparator
}

/** Whether [text] — a tag's already-settled (non-null) text — satisfies this comparator. */
fun TextComparator.matches(text: String): Boolean = when (this) {
    TextComparator.Present -> true
    TextComparator.Empty -> text.isEmpty()
    is TextComparator.Equals -> text == value
    is TextComparator.StartsWith -> text.startsWith(prefix)
}
