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

    /** The message input field on the chat screen. */
    val inputField: NodeMatcher = Matchers.editable and Matchers.visible

    /** The send button next to the input field. */
    val sendButton: NodeMatcher = Matchers.clickable and (
        Matchers.descContains("send") or Matchers.textEquals("send")
    )

    /** The new-chat affordance (used by the M2 debug demo). */
    val newChatButton: NodeMatcher = Matchers.clickable and (
        Matchers.descContains("new chat") or
            Matchers.textContains("new chat") or
            Matchers.descContains("start chat")
    )

    /** The scrollable conversation list. */
    val messageList: NodeMatcher = Matchers.scrollable

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
        Matchers.textContains("claude can make mistakes"),
        Matchers.textContains("message claude"),
        Matchers.textContains("reply to claude"),
        Matchers.textEquals("claude"),
        Matchers.textContains("upgrade"),
    )

    /**
     * Extracts the assistant's latest answer from a chat-screen snapshot.
     *
     * Heuristic: within the largest scrollable container (or the whole tree if
     * none), collect non-editable text nodes in depth-first order, drop chrome
     * text, locate the last node matching the just-sent prompt (the user
     * bubble), and join everything after it. Falls back to text after the last
     * known answer boundary when the prompt bubble isn't found.
     */
    fun extractLatestAnswer(snapshot: UiSnapshot, prompt: String): String? {
        val container: UiNode = snapshot.findAll(messageList)
            .maxByOrNull { it.bounds.area }
            ?: snapshot.root
            ?: return null

        val candidates = container.findAll(
            Matchers.hasText and !Matchers.editable and !ignoredText
        )
        if (candidates.isEmpty()) return null

        val promptTrimmed = prompt.trim()
        val promptIndex = candidates.indexOfLast { node ->
            val t = node.text?.trim() ?: return@indexOfLast false
            t == promptTrimmed || (promptTrimmed.length > 20 && t.startsWith(promptTrimmed.take(40)))
        }

        val answerNodes = if (promptIndex >= 0) {
            candidates.drop(promptIndex + 1)
        } else {
            // Prompt bubble not found (e.g. truncated rendering): take the
            // trailing run of text nodes as the best available guess.
            listOfNotNull(candidates.lastOrNull())
        }

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
