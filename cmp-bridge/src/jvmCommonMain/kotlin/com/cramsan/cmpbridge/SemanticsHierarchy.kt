package com.cramsan.cmpbridge

import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey

/** Subset of [SemanticsActions] surfaced on [HierarchyNode.actions]. */
private val ACTION_KEYS: List<Pair<String, SemanticsPropertyKey<*>>> =
    listOf(
        "OnClick" to SemanticsActions.OnClick,
        "OnLongClick" to SemanticsActions.OnLongClick,
        "ScrollBy" to SemanticsActions.ScrollBy,
        "SetText" to SemanticsActions.SetText,
        "Expand" to SemanticsActions.Expand,
        "Collapse" to SemanticsActions.Collapse,
        "RequestFocus" to SemanticsActions.RequestFocus,
    )

/**
 * Maps a live Compose [SemanticsNode] (and its subtree) to the wire-level [HierarchyNode]. Bounds
 * are [SemanticsNode.boundsInWindow], in physical pixels. Shared verbatim by the desktop and
 * Android servers so both platforms report the same tree shape for the same UI.
 */
internal fun SemanticsNode.toHierarchyNode(): HierarchyNode {
    val bounds = boundsInWindow
    val cfg = config
    // password() fields never surface real text/contentDescription through this bridge.
    val isPassword = cfg.contains(SemanticsProperties.Password)
    return HierarchyNode(
        testTag = cfg.valueOrNull(SemanticsProperties.TestTag),
        role = cfg.roleString(),
        text =
        if (isPassword) {
            null
        } else {
            cfg.valueOrNull(SemanticsProperties.EditableText)?.text
                ?: cfg.valueOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }
        },
        contentDescription =
        if (isPassword) null else cfg.valueOrNull(SemanticsProperties.ContentDescription)?.joinToString(", "),
        x = bounds.left,
        y = bounds.top,
        width = bounds.width,
        height = bounds.height,
        enabled = !cfg.contains(SemanticsProperties.Disabled),
        actions = ACTION_KEYS.mapNotNullTo(mutableSetOf()) { (name, key) -> name.takeIf { cfg.contains(key) } },
        children = children.map { it.toHierarchyNode() },
    )
}

/**
 * Wraps several root nodes (e.g. a popup/dialog on top of the main content) under one synthetic,
 * tag-less root rather than picking one arbitrarily; a single root is returned as-is.
 */
internal fun List<HierarchyNode>.asSingleRoot(): HierarchyNode = when (size) {
    0 -> error("No semantics owner available yet")

    1 -> first()

    else -> HierarchyNode(
        testTag = null,
        role = null,
        text = null,
        contentDescription = null,
        x = 0f,
        y = 0f,
        width = 0f,
        height = 0f,
        enabled = true,
        actions = emptySet(),
        children = this,
    )
}

private fun <T> SemanticsConfiguration.valueOrNull(key: SemanticsPropertyKey<T>): T? =
    if (contains(key)) get(key) else null

/**
 * Normalizes to the same role vocabulary as the web accessibility DOM, including its ambiguity:
 * an element with both an explicit [SemanticsProperties.Role] and [SemanticsActions.OnClick]
 * normalizes to `"button"`.
 */
private fun SemanticsConfiguration.roleString(): String? {
    var role = valueOrNull(SemanticsProperties.Role)?.let { explicitRoleString(it) }
    if (contains(SemanticsActions.OnClick)) role = "button"
    if (contains(SemanticsProperties.Heading)) role = "heading"
    if (contains(SemanticsProperties.EditableText)) role = "textbox"
    if (contains(SemanticsProperties.CollectionInfo)) {
        role = collectionRoleString(get(SemanticsProperties.CollectionInfo))
    }
    return role
}

private fun explicitRoleString(role: Role): String? = when (role) {
    Role.Button -> "button"
    Role.Checkbox -> "checkbox"
    Role.Switch -> "switch"
    Role.RadioButton -> "radio"
    Role.Tab -> "tab"
    Role.Image -> "img"
    Role.DropdownList -> "menu"
    else -> null
}

private fun collectionRoleString(info: CollectionInfo): String =
    if (info.rowCount > 1 && info.columnCount > 1) "grid" else "list"
