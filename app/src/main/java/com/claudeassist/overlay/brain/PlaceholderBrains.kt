package com.claudeassist.overlay.brain

import android.graphics.Bitmap
import com.claudeassist.overlay.core.brain.BrainEvent
import com.claudeassist.overlay.core.brain.BrainStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** "Soon" connector cards on the Brains screen; not yet functional. */
class PlaceholderBrain(
    override val id: String,
    override val displayName: String,
    private val note: String,
) : Brain {
    private val _status = MutableStateFlow<BrainStatus>(BrainStatus.NeedsSetup(note))
    override val status: StateFlow<BrainStatus> = _status

    override suspend fun connect(): BrainStatus = _status.value

    override fun ask(image: Bitmap?, prompt: String): Flow<BrainEvent> =
        flowOf(BrainEvent.Failure("$displayName is not available yet.", recoverable = false))

    companion object {
        fun chatGpt() = PlaceholderBrain("chatgpt-app", "ChatGPT (app)", "Coming soon")
        fun anthropicApi() = PlaceholderBrain("anthropic-api", "Anthropic API key", "Coming soon")
        fun pcRelay() = PlaceholderBrain("pc-relay", "PC relay", "Coming soon")
    }
}
