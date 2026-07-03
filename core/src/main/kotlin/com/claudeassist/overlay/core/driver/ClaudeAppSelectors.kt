package com.claudeassist.overlay.core.driver

import com.claudeassist.overlay.core.uitree.Matchers
import com.claudeassist.overlay.core.uitree.NodeMatcher
import com.claudeassist.overlay.core.uitree.UiNode
import com.claudeassist.overlay.core.uitree.UiSnapshot
import com.claudeassist.overlay.core.uitree.and
import com.claudeassist.overlay.core.uitree.findAll
import com.claudeassist.overlay.core.uitree.findFirst
import com.claudeassist.overlay.core.uitree.not
import com.claudeassist.overlay.core.uitree.or

/**
 * Every anchor used to drive the Claude Android app. This is the ONLY file to
 * touch when the Claude app updates and the driver breaks.
 *
 * The Claude app is Compose-based, so view IDs are mostly absent; anchors lean
 * on content descriptions, text, and structure. These are best-guess defaults —
 * the expected workflow is: run Driver Doctor on the device, dump the real UI
 * tree, and correct the anchors here (pinning fixtures in core tests).
 */
object ClaudeAppSelectors {
    const val PACKAGE = "com.anthropic.claude"

    /**
     * The message input field on the chat screen.
     *
     * The Claude app is Compose-based: the field reports as an `EditText`
     * class but does NOT set the accessibility `isEditable` flag, so matching on
     * `editable` alone misses it. Match on the class name, keeping `editable` as
     * a fallback for other builds/apps.
     */
    val inputField: NodeMatcher =
        (Matchers.classNameContains("EditText") or Matchers.editable) and Matchers.visible

    /**
     * The send button next to the input field. Only appears once the field is
     * non-empty. In the current Claude app it is a node with content-desc
     * "Send" whose clickable ancestor carries no description — so we match the
     * described node and rely on ActionExecutor's coordinate-tap fallback
     * (the direct-click path requires `isClickable`, the coordinate path does
     * not). Do NOT require `clickable` here.
     */
    val sendButton: NodeMatcher =
        Matchers.descEquals("send") or Matchers.descContains("send") or Matchers.textEquals("send")

    /** The new-chat affordance (used by the M2 debug demo). */
    val newChatButton: NodeMatcher = Matchers.clickable and (
        Matchers.descContains("new chat") or
            Matchers.textContains("new chat") or
            Matchers.descContains("start chat")
    )

    /** The scrollable conversation list. */
    val messageList: NodeMatcher = Matchers.scrollable

    /**
     * Marks a COMPLETED assistant turn: the action row (Copy / Retry) only
     * appears once Claude has finished responding. Used both to know the answer
     * is ready and to bound where the answer text ends.
     */
    val answerComplete: NodeMatcher = Matchers.anyOf(
        Matchers.descContains("copy message"),
        Matchers.descContains("retry message"),
    )

    /** Claude-app error states worth surfacing (network down, message failed). */
    val errorIndicator: NodeMatcher = Matchers.anyOf(
        Matchers.textContains("something went wrong"),
        Matchers.textContains("unable to send"),
        Matchers.textContains("failed to send"),
        Matchers.textContains("no internet"),
        Matchers.textContains("network error"),
        Matchers.textContains("check your connection"),
    )

    /** Chrome/boilerplate text that must never be mistaken for an answer. */
    val ignoredText: NodeMatcher = Matchers.anyOf(
        Matchers.textContains("can make mistakes"),
        Matchers.textContains("double-check responses"),
        Matchers.textContains("message claude"),
        Matchers.textContains("reply to claude"),
        Matchers.textContains("chat with claude"),
        Matchers.textContains("back at it"), // home-screen greeting
        Matchers.textEquals("claude"),
        Matchers.textEquals("sonnet 5"),
        Matchers.textEquals("thinking"),
        Matchers.textEquals("•"),
        Matchers.textContains("upgrade"),
    )

    /** True once Claude has finished the current response (action row present). */
    fun isAnswerComplete(snapshot: UiSnapshot): Boolean =
        snapshot.findFirst(answerComplete) != null

    /**
     * Extracts the assistant's latest answer from a chat-screen snapshot.
     *
     * Returns null until the answer is COMPLETE (the Copy/Retry action row is
     * present) — this is what prevents grabbing the home-screen greeting or a
     * half-rendered reply. Once complete, collect non-editable, non-chrome text
     * nodes in depth-first order, drop everything up to and including the sent
     * prompt (the user bubble), and join the rest — the assistant's message.
     */
    fun extractLatestAnswer(snapshot: UiSnapshot, prompt: String): String? {
        if (!isAnswerComplete(snapshot)) return null

        val candidates = snapshot.findAll(
            Matchers.hasText and !Matchers.editable and !ignoredText,
        )
        if (candidates.isEmpty()) return null

        val promptTrimmed = prompt.trim()
        val promptIndex = candidates.indexOfLast { node ->
            val t = node.text?.trim() ?: return@indexOfLast false
            t == promptTrimmed ||
                (promptTrimmed.length > 12 && t.startsWith(promptTrimmed.take(24)))
        }

        val answerNodes = if (promptIndex >= 0) candidates.drop(promptIndex + 1) else candidates

        val answer = answerNodes
            .mapNotNull { it.text?.trim() }
            .filter { it.isNotEmpty() && it != promptTrimmed }
            .joinToString("\n")
            .trim()
        return answer.ifEmpty { null }
    }

    /** True if the snapshot shows a Claude-app error state. */
    fun findError(snapshot: UiSnapshot): String? =
        snapshot.findFirst(errorIndicator)?.text?.trim()
}
