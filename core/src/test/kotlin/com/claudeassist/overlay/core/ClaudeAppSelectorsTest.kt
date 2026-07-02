package com.claudeassist.overlay.core

import com.claudeassist.overlay.core.driver.ClaudeAppSelectors
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
        val input = chat.findFirst(ClaudeAppSelectors.inputField)
        assertNotNull(input)
        assertTrue(input.editable)
    }

    @Test
    fun findsSendButton() {
        val send = chat.findFirst(ClaudeAppSelectors.sendButton)
        assertNotNull(send)
        assertEquals("Send message", send.contentDescription)
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
}
