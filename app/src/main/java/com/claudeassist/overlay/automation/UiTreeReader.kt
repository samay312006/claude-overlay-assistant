package com.claudeassist.overlay.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.claudeassist.overlay.core.uitree.Bounds
import com.claudeassist.overlay.core.uitree.UiNode
import com.claudeassist.overlay.core.uitree.UiSnapshot

/**
 * Converts the live AccessibilityNodeInfo tree into a framework-free
 * [UiSnapshot], assigning each node a depth-first id. Live nodes are kept in a
 * parallel id->node map so a matched [UiNode] can be acted on ([ActionExecutor]
 * uses it); callers must [recycleLive] the reader when done.
 */
class UiTreeReader private constructor(
    val snapshot: UiSnapshot,
    private val liveById: Map<Int, AccessibilityNodeInfo>,
) {
    fun liveNode(id: Int): AccessibilityNodeInfo? = liveById[id]

    fun recycleLive() {
        // AccessibilityNodeInfo.recycle() is deprecated/no-op on modern APIs;
        // dropping references is sufficient. Kept as an explicit lifecycle hook.
        liveById.values.forEach {
            @Suppress("DEPRECATION")
            runCatching { it.recycle() }
        }
    }

    companion object {
        fun read(
            root: AccessibilityNodeInfo?,
            foregroundPackage: String,
            nowMs: Long,
            excludePackage: String?,
        ): UiTreeReader {
            val live = HashMap<Int, AccessibilityNodeInfo>()
            val counter = intArrayOf(0)
            val rootNode = root?.let { build(it, live, counter, excludePackage) }
            return UiTreeReader(
                UiSnapshot(capturedAtMs = nowMs, foregroundPackage = foregroundPackage, root = rootNode),
                live,
            )
        }

        private fun build(
            info: AccessibilityNodeInfo,
            live: MutableMap<Int, AccessibilityNodeInfo>,
            counter: IntArray,
            excludePackage: String?,
        ): UiNode? {
            val pkg = info.packageName?.toString().orEmpty()
            if (excludePackage != null && pkg == excludePackage) return null

            val id = counter[0]++
            live[id] = info

            val rect = Rect().also { info.getBoundsInScreen(it) }
            val children = ArrayList<UiNode>(info.childCount)
            for (i in 0 until info.childCount) {
                val child = info.getChild(i) ?: continue
                build(child, live, counter, excludePackage)?.let { children.add(it) }
            }

            return UiNode(
                id = id,
                packageName = pkg,
                className = info.className?.toString().orEmpty(),
                viewId = info.viewIdResourceName,
                text = info.text?.toString(),
                contentDescription = info.contentDescription?.toString(),
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                clickable = info.isClickable,
                editable = info.isEditable,
                scrollable = info.isScrollable,
                visible = info.isVisibleToUser,
                children = children,
            )
        }
    }
}
