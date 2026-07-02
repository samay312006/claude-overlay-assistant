package com.claudeassist.overlay.core.brain

/**
 * Events emitted while a brain answers one ask. [Delta] carries the full text
 * accumulated so far (not an increment) — natural for connectors that poll a
 * growing answer, and trivial for the UI to render.
 */
sealed class BrainEvent {
    data class Delta(val textSoFar: String) : BrainEvent()
    data class Done(val fullText: String, val timedOut: Boolean = false) : BrainEvent()
    data class Failure(val reason: String, val recoverable: Boolean = true) : BrainEvent()
}

sealed class BrainStatus {
    data object NotConnected : BrainStatus()
    data class NeedsSetup(val what: String) : BrainStatus()
    data object Ready : BrainStatus()
    data object Busy : BrainStatus()
    data class Broken(val reason: String) : BrainStatus()
}
