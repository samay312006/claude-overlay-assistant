package com.claudeassist.overlay.brain

import android.graphics.Bitmap
import com.claudeassist.overlay.core.brain.BrainEvent
import com.claudeassist.overlay.core.brain.BrainStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A pluggable connector — the "Connect X" cards on the Brains screen. Phase 1
 * ships ClaudeAppBrain (drives the installed Claude app) and FakeBrain (for UI
 * work). Future: ChatGptAppBrain, AnthropicApiBrain, PcRelayBrain.
 */
interface Brain {
    val id: String
    val displayName: String
    val status: StateFlow<BrainStatus>

    /** Verifies the connector can run (app installed, service on, …). */
    suspend fun connect(): BrainStatus

    /** One ask. Emits [BrainEvent]s and completes when done or failed. */
    fun ask(image: Bitmap?, prompt: String): Flow<BrainEvent>
}
