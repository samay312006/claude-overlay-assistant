package com.claudeassist.overlay.core.driver

/**
 * Pure state machine deciding when a polled, still-streaming answer is
 * complete: the text must stay unchanged for [stableSamplesToFinish]
 * consecutive samples, or the hard [maxDurationMs] timeout fires (returning
 * whatever was captured, flagged as timed out).
 *
 * The driver feeds it one sample per poll tick; this class holds no timers and
 * no Android types, so completion logic is unit-testable.
 */
class AnswerTracker(
    private val stableSamplesToFinish: Int = 3,
    private val maxDurationMs: Long = 60_000L,
) {
    sealed class State {
        /** Still collecting. [changed] is true when this sample grew the text. */
        data class Streaming(val textSoFar: String, val changed: Boolean) : State()
        data class Finished(val fullText: String, val timedOut: Boolean) : State()
    }

    private var text = ""
    private var stableSamples = 0
    private var startedAtMs = -1L
    private var sawText = false

    fun onSample(sample: String?, nowMs: Long): State {
        if (startedAtMs < 0) startedAtMs = nowMs
        val incoming = sample?.trim().orEmpty()

        if (nowMs - startedAtMs >= maxDurationMs) {
            val best = if (incoming.length > text.length) incoming else text
            return State.Finished(best, timedOut = true)
        }

        if (incoming.isNotEmpty() && incoming != text) {
            // A transient shrink can happen when the node re-renders mid-poll;
            // only accept shorter text if it's not a prefix-truncation of what
            // we already have.
            if (incoming.length >= text.length || !text.startsWith(incoming)) {
                text = incoming
            }
            sawText = true
            stableSamples = 0
            return State.Streaming(text, changed = true)
        }

        if (sawText) {
            stableSamples++
            if (stableSamples >= stableSamplesToFinish) {
                return State.Finished(text, timedOut = false)
            }
        }
        return State.Streaming(text, changed = false)
    }
}
