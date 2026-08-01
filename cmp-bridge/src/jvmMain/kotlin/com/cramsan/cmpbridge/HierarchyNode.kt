package com.cramsan.cmpbridge

import kotlinx.serialization.Serializable

/**
 * A node in the app's semantics/accessibility tree, as reported live by the platform itself.
 *
 * Web's tree is a best-effort subset of desktop's: [actions] can only reliably infer `"OnClick"`,
 * and [enabled] is always `true`.
 *
 * [role] is normalized to a platform-independent vocabulary (`"button"`, `"checkbox"`, `"switch"`,
 * `"radio"`, `"tab"`, `"img"`, `"menu"`, `"heading"`, `"textbox"`, `"list"`, `"grid"`).
 */
@Serializable
data class HierarchyNode(
    val testTag: String?,
    val role: String?,
    val text: String?,
    val contentDescription: String?,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val enabled: Boolean,
    val actions: Set<String>,
    val children: List<HierarchyNode>,
)

/** Pre-order flattening of this node and all its descendants (this node included, first). */
fun HierarchyNode.flatten(): List<HierarchyNode> = listOf(this) + children.flatMap { it.flatten() }

/** Finds the first descendant (or this node itself) whose [HierarchyNode.testTag] equals [tag]. */
fun HierarchyNode.find(tag: String): HierarchyNode? = flatten().firstOrNull { it.testTag == tag }
