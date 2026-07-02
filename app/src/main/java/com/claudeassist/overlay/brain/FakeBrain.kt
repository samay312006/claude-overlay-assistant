package com.claudeassist.overlay.brain

import android.graphics.Bitmap
import com.claudeassist.overlay.core.brain.BrainEvent
import com.claudeassist.overlay.core.brain.BrainStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Canned, streamed answers so the overlay/UI can be exercised end-to-end
 * without the Claude app or accessibility service. Used until the real driver
 * is wired, and to demo the overlay UX.
 */
class FakeBrain : Brain {
    override val id: String = "fake"
    override val displayName: String = "Demo (offline)"

    private val _status = MutableStateFlow<BrainStatus>(BrainStatus.Ready)
    override val status: StateFlow<BrainStatus> = _status

    override suspend fun connect(): BrainStatus = BrainStatus.Ready

    override fun ask(image: Bitmap?, prompt: String): Flow<BrainEvent> = flow {
        val hasImage = if (image != null) "a screenshot and " else ""
        val full = "You asked ${hasImage}\"$prompt\". This is a demo answer streamed " +
            "word by word to exercise the overlay. Wire a real brain to get real answers."
        val sb = StringBuilder()
        for (word in full.split(' ')) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(word)
            emit(BrainEvent.Delta(sb.toString()))
            delay(45)
        }
        emit(BrainEvent.Done(sb.toString()))
    }
}
