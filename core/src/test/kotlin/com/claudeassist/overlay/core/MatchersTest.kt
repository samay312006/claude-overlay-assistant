package com.claudeassist.overlay.core

import com.claudeassist.overlay.core.uitree.Matchers
import com.claudeassist.overlay.core.uitree.UiNode
import com.claudeassist.overlay.core.uitree.and
import com.claudeassist.overlay.core.uitree.findAll
import com.claudeassist.overlay.core.uitree.findFirst
import com.claudeassist.overlay.core.uitree.findLast
import com.claudeassist.overlay.core.uitree.not
import com.claudeassist.overlay.core.uitree.or
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchersTest {

    private val tree = UiNode(
        id = 0, className = "Root",
        children = listOf(
            UiNode(id = 1, text = "Hello", clickable = true),
            UiNode(id = 2, contentDescription = "Send", clickable = true),
            UiNode(id = 3, editable = true),
            UiNode(id = 4, text = "Send", editable = false, clickable = false),
        ),
    )

    @Test
    fun textAndDescMatchers() {
        assertEquals(1, tree.findAll(Matchers.textEquals("hello")).single().id)
        assertEquals(2, tree.findFirst(Matchers.descContains("sen"))!!.id)
    }

    @Test
    fun editableMatcher() {
        assertEquals(3, tree.findFirst(Matchers.editable)!!.id)
    }

    @Test
    fun combinators() {
        val clickableSend = Matchers.clickable and Matchers.descEquals("Send")
        assertEquals(2, tree.findFirst(clickableSend)!!.id)

        val notClickable = !Matchers.clickable
        assertTrue(tree.findAll(notClickable).all { !it.clickable })

        val textOrDesc = Matchers.textEquals("Hello") or Matchers.descEquals("Send")
        assertEquals(listOf(1, 2), tree.findAll(textOrDesc).map { it.id })
    }

    @Test
    fun findLastReturnsBottommost() {
        // Two nodes with text "Send": ids 2(desc only) and 4(text). Text match:
        assertEquals(4, tree.findLast(Matchers.textEquals("Send"))!!.id)
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(tree.findFirst(Matchers.textEquals("absent")))
    }
}
