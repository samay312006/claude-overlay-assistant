package com.claudeassist.overlay.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.claudeassist.overlay.core.uitree.NodeMatcher
import com.claudeassist.overlay.core.uitree.UiNode
import com.claudeassist.overlay.core.uitree.UiSnapshot
import com.claudeassist.overlay.core.uitree.findAll
import com.claudeassist.overlay.core.uitree.findFirst
import com.claudeassist.overlay.core.uitree.findLast
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A matched node plus a handle back to the live framework node for actions. */
class MatchedNode(val node: UiNode, val live: AccessibilityNodeInfo?)

/**
 * Snapshot-based querying over the current window. Every read excludes our own
 * package so overlay windows never pollute matches. [await] re-snapshots on
 * accessibility events until a matcher hits or the timeout elapses.
 */
class UiQuery(private val service: AssistAccessibilityService) {

    fun snapshotNow(): UiTreeReader = UiTreeReader.read(
        root = resolveTargetRoot(),
        foregroundPackage = AssistAccessibilityService.foregroundPackage.value,
        nowMs = System.currentTimeMillis(),
        excludePackage = service.packageName,
    )

    /**
     * The window to read when driving another app. [rootInActiveWindow] is the
     * *focused* window — while our ask panel (a focusable overlay) is open, that
     * is OUR window, not the app we're driving, so reading it (then excluding
     * our own package) yields an empty tree. Instead prefer the foreground app's
     * window from the full window list, only falling back to the active window.
     */
    private fun resolveTargetRoot(): AccessibilityNodeInfo? {
        val mine = service.packageName
        val fg = AssistAccessibilityService.foregroundPackage.value

        service.rootInActiveWindow?.let { active ->
            if (active.packageName?.toString() != mine) return active
        }

        val roots = service.windows.mapNotNull { it.root }
        roots.firstOrNull { fg.isNotEmpty() && it.packageName?.toString() == fg }?.let { return it }
        return roots.lastOrNull { it.packageName?.toString() != mine }
    }

    /** Serializable tree for Driver Doctor. */
    fun snapshot(): UiSnapshot = snapshotNow().let { reader ->
        try {
            reader.snapshot
        } finally {
            reader.recycleLive()
        }
    }

    fun findFirst(matcher: NodeMatcher): MatchedNode? = withReader { reader ->
        reader.snapshot.findFirst(matcher)?.let { MatchedNode(it, reader.liveNode(it.id)) }
    }

    fun findLast(matcher: NodeMatcher): MatchedNode? = withReader { reader ->
        reader.snapshot.findLast(matcher)?.let { MatchedNode(it, reader.liveNode(it.id)) }
    }

    fun findAll(matcher: NodeMatcher): List<UiNode> = withReader { it.snapshot.findAll(matcher) }

    /**
     * Polls for a matcher until [timeoutMs]. Wakes on accessibility events but
     * also polls every [pollMs] so a match that arrived without a distinct event
     * is still found.
     */
    suspend fun await(matcher: NodeMatcher, timeoutMs: Long, pollMs: Long = 200): MatchedNode? {
        findFirst(matcher)?.let { return it }
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                // Race an event against the poll interval.
                withTimeoutOrNull(pollMs) { AssistAccessibilityService.events.first() }
                findFirst(matcher)?.let { return@withTimeoutOrNull it }
                delay(0)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    private inline fun <T> withReader(block: (UiTreeReader) -> T): T {
        val reader = snapshotNow()
        try {
            return block(reader)
        } finally {
            reader.recycleLive()
        }
    }
}
