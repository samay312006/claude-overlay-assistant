package com.claudeassist.overlay.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Performs gestures and text entry through the accessibility service. Each
 * action prefers the direct node action and falls back to a coordinate gesture
 * / clipboard paste, which matters for Compose apps where node actions are
 * often unsupported.
 */
class ActionExecutor(private val service: AssistAccessibilityService) {

    suspend fun tap(matched: MatchedNode): Boolean {
        // Prefer a real ACTION_CLICK on the node or its nearest clickable
        // ancestor. Compose apps often label an icon node (e.g. content-desc
        // "Send") that is NOT itself clickable — the clickable target is a
        // parent. A real click is also immune to our overlay window
        // intercepting a coordinate gesture at that screen location.
        var node: AccessibilityNodeInfo? = matched.live
        var hops = 0
        while (node != null && hops < 6) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            node = node.parent
            hops++
        }
        return tapAt(matched.node.bounds.centerX, matched.node.bounds.centerY)
    }

    suspend fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(gesture)
    }

    suspend fun typeText(matched: MatchedNode, text: String): Boolean {
        val live = matched.live ?: return pasteViaClipboard(text)
        live.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        // Fallback: put text on the clipboard and paste into the focused field.
        return pasteViaClipboard(text) && run {
            delay(80)
            live.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    /** Copies [text] to the clipboard (used by the paste fallback and tests). */
    fun pasteViaClipboard(text: String): Boolean {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("claude-assist", text))
        return true
    }

    fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    fun home(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    fun launchApp(pkg: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { service.startActivity(intent); true }.getOrDefault(false)
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!ok && cont.isActive) cont.resume(false)
        }
}
