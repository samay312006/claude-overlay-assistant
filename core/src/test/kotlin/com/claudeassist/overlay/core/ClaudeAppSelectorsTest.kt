package com.claudeassist.overlay.core

import com.claudeassist.overlay.core.driver.ClaudeAppSelectors
import com.claudeassist.overlay.core.uitree.UiNode
import com.claudeassist.overlay.core.uitree.UiSnapshot
import com.claudeassist.overlay.core.uitree.findFirst
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeAppSelectorsTest {

    private fun loadFixture(name: String): UiSnapshot {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream(name))
            .bufferedReader().readText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(UiSnapshot.serializer(), text)
    }

    private val chat = loadFixture("claude_chat_fixture.json")

    @Test
    fun findsInputField() {
        // Claude's Compose field is an EditText that does NOT set isEditable, so
        // the selector must match on class name.
        val input = chat.findFirst(ClaudeAppSelectors.inputField)
        assertNotNull(input)
        assertTrue(input.className.contains("EditText"))
    }

    @Test
    fun findsSendButton() {
        val send = chat.findFirst(ClaudeAppSelectors.sendButton)
        assertNotNull(send)
        assertEquals("Send", send.contentDescription)
    }

    @Test
    fun extractsAnswerAfterPromptBubble() {
        val answer = ClaudeAppSelectors.extractLatestAnswer(chat, prompt = "what is 2+2")
        assertEquals("2 + 2 = 4.", answer)
    }

    @Test
    fun answerExcludesChromeText() {
        val answer = ClaudeAppSelectors.extractLatestAnswer(chat, prompt = "what is 2+2")
        assertNotNull(answer)
        assertTrue(!answer.contains("Claude can make mistakes"))
    }

    @Test
    fun noErrorInHealthyChat() {
        assertNull(ClaudeAppSelectors.findError(chat))
    }

    @Test
    fun noAnswerFromHomeScreenGreeting() {
        // Regression: Claude's home screen ("Back at it, <name>") has no Copy/Retry
        // action row, so it must NOT be returned as an answer.
        val home = UiSnapshot(
            foregroundPackage = "com.anthropic.claude",
            root = UiNode(
                id = 0,
                className = "android.widget.FrameLayout",
                children = listOf(
                    UiNode(id = 1, className = "android.widget.TextView", text = "Back at it, SAMMY BOI"),
                    UiNode(id = 2, className = "android.widget.EditText", text = ""),
                ),
            ),
        )
        assertNull(ClaudeAppSelectors.extractLatestAnswer(home, prompt = "what's on screen"))
    }
}
