package com.cramsan.cmpbridge

import kotlinx.serialization.Serializable

/**
 * A node in the app's real semantics/accessibility tree, as reported by the platform itself
 * (`ComposeWindow.semanticsOwners` on desktop, the built-in ARIA accessibility DOM on web) rather
 * than a hand-tracked registry — so this always reflects the current, live layout, never a stale
 * "last known state".
 *
 * Fidelity is **not** identical across platforms: desktop enumerates the real
 * `SemanticsConfiguration` action set; web can only reliably infer `"OnClick"` (from an
 * interactive [role]), since that's the only action Compose Web's accessibility DOM wires up to a
 * real listener, and has no reliable [enabled] signal at all (always reported `true` there).
 * Treat web's tree as a best-effort subset, not a platform-equivalent one.
 *
 * [role] is normalized to a small, platform-independent vocabulary (`"button"`, `"checkbox"`,
 * `"switch"`, `"radio"`, `"tab"`, `"img"`, `"menu"`, `"heading"`, `"textbox"`, `"list"`, `"grid"`)
 * matching the ARIA role names Compose Web's own accessibility DOM already uses, so scenario code
 * doesn't need platform-specific role handling.
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
