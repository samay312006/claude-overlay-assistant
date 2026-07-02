package com.claudeassist.overlay.core.uitree

import kotlinx.serialization.Serializable

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()
}

/**
 * Framework-free mirror of an AccessibilityNodeInfo. Everything above the
 * accessibility service matches against these, which keeps selector logic
 * JVM-testable and lets Driver Doctor serialize live trees to JSON.
 *
 * [id] is assigned in depth-first order when the snapshot is built; the app
 * layer uses it to map a matched node back to the live framework node.
 */
@Serializable
data class UiNode(
    val id: Int,
    val packageName: String = "",
    val className: String = "",
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val visible: Boolean = true,
    val children: List<UiNode> = emptyList(),
) {
    /** This node and all descendants, depth-first (roughly top-to-bottom visual order). */
    fun selfAndDescendants(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (child in children) yieldAll(child.selfAndDescendants())
    }
}

@Serializable
data class UiSnapshot(
    val capturedAtMs: Long = 0L,
    val foregroundPackage: String = "",
    val root: UiNode? = null,
) {
    fun allNodes(): Sequence<UiNode> = root?.selfAndDescendants() ?: emptySequence()
}
