package com.claudeassist.overlay.core.driver

import kotlinx.serialization.Serializable

/**
 * Telemetry for one automation run. Recorded from the first driver run on;
 * this corpus is the training data for the future learning loop (Phase 6).
 */
@Serializable
enum class StepOutcome { OK, RETRIED, FAILED, SKIPPED }

@Serializable
data class DriverStep(
    val name: String,
    val detail: String = "",
    val outcome: StepOutcome,
    val durationMs: Long,
)

@Serializable
data class RunLog(
    val brainId: String,
    val startedAtMs: Long,
    val totalMs: Long,
    val success: Boolean,
    val failureReason: String? = null,
    val steps: List<DriverStep> = emptyList(),
)

class RunLogBuilder(private val brainId: String, private val startedAtMs: Long) {
    private val steps = mutableListOf<DriverStep>()

    fun step(name: String, outcome: StepOutcome, durationMs: Long, detail: String = "") {
        steps += DriverStep(name = name, detail = detail, outcome = outcome, durationMs = durationMs)
    }

    /** Runs [block], records its duration and OK/FAILED outcome, and rethrows failures. */
    inline fun <T> timed(name: String, nowMs: () -> Long, block: () -> T): T {
        val start = nowMs()
        try {
            val result = block()
            step(name, StepOutcome.OK, nowMs() - start)
            return result
        } catch (t: Throwable) {
            step(name, StepOutcome.FAILED, nowMs() - start, detail = t.message.orEmpty())
            throw t
        }
    }

    fun build(endedAtMs: Long, success: Boolean, failureReason: String? = null): RunLog =
        RunLog(
            brainId = brainId,
            startedAtMs = startedAtMs,
            totalMs = endedAtMs - startedAtMs,
            success = success,
            failureReason = failureReason,
            steps = steps.toList(),
        )
}
