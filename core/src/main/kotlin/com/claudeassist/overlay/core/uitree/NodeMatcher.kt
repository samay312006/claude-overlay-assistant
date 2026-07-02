package com.claudeassist.overlay.core.uitree

fun interface NodeMatcher {
    fun matches(node: UiNode): Boolean
}

infix fun NodeMatcher.and(other: NodeMatcher): NodeMatcher =
    NodeMatcher { matches(it) && other.matches(it) }

infix fun NodeMatcher.or(other: NodeMatcher): NodeMatcher =
    NodeMatcher { matches(it) || other.matches(it) }

operator fun NodeMatcher.not(): NodeMatcher = NodeMatcher { !matches(it) }

object Matchers {
    val any: NodeMatcher = NodeMatcher { true }
    val editable: NodeMatcher = NodeMatcher { it.editable }
    val clickable: NodeMatcher = NodeMatcher { it.clickable }
    val scrollable: NodeMatcher = NodeMatcher { it.scrollable }
    val visible: NodeMatcher = NodeMatcher { it.visible }
    val hasText: NodeMatcher = NodeMatcher { !it.text.isNullOrBlank() }

    fun textEquals(value: String, ignoreCase: Boolean = true): NodeMatcher =
        NodeMatcher { it.text?.trim()?.equals(value, ignoreCase) == true }

    fun textContains(value: String, ignoreCase: Boolean = true): NodeMatcher =
        NodeMatcher { it.text?.contains(value, ignoreCase) == true }

    fun descEquals(value: String, ignoreCase: Boolean = true): NodeMatcher =
        NodeMatcher { it.contentDescription?.trim()?.equals(value, ignoreCase) == true }

    fun descContains(value: String, ignoreCase: Boolean = true): NodeMatcher =
        NodeMatcher { it.contentDescription?.contains(value, ignoreCase) == true }

    fun viewId(id: String): NodeMatcher =
        NodeMatcher { it.viewId == id || it.viewId?.endsWith(":id/$id") == true }

    fun classNameContains(value: String): NodeMatcher =
        NodeMatcher { it.className.contains(value, ignoreCase = true) }

    fun packageName(value: String): NodeMatcher = NodeMatcher { it.packageName == value }

    fun anyOf(vararg matchers: NodeMatcher): NodeMatcher =
        NodeMatcher { node -> matchers.any { it.matches(node) } }
}

/** First match in depth-first order. */
fun UiSnapshot.findFirst(matcher: NodeMatcher): UiNode? = allNodes().firstOrNull(matcher::matches)

/** Last match in depth-first order — approximates the bottom-most/newest element. */
fun UiSnapshot.findLast(matcher: NodeMatcher): UiNode? = allNodes().lastOrNull(matcher::matches)

fun UiSnapshot.findAll(matcher: NodeMatcher): List<UiNode> = allNodes().filter(matcher::matches).toList()

fun UiNode.findFirst(matcher: NodeMatcher): UiNode? = selfAndDescendants().firstOrNull(matcher::matches)

fun UiNode.findLast(matcher: NodeMatcher): UiNode? = selfAndDescendants().lastOrNull(matcher::matches)

fun UiNode.findAll(matcher: NodeMatcher): List<UiNode> = selfAndDescendants().filter(matcher::matches).toList()
