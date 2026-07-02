package com.claudeassist.overlay.brain

import android.graphics.Bitmap
import com.claudeassist.overlay.automation.AssistAccessibilityService
import com.claudeassist.overlay.brain.drivers.ClaudeAppDriver
import com.claudeassist.overlay.brain.drivers.DriverException
import com.claudeassist.overlay.core.brain.BrainEvent
import com.claudeassist.overlay.core.brain.BrainStatus
import com.claudeassist.overlay.core.driver.AnswerTracker
import com.claudeassist.overlay.core.driver.RunLog
import com.claudeassist.overlay.core.driver.RunLogBuilder
import com.claudeassist.overlay.core.driver.StepOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Brain that answers by driving the installed Claude app via [ClaudeAppDriver].
 *
 * Responsibilities layered on top of the driver:
 *  - one ask at a time (Busy);
 *  - AnswerTracker for stability/timeout over the driver's raw samples;
 *  - a foreground watchdog that aborts if the user leaves the Claude app
 *    mid-run (they touched the screen);
 *  - return to the user's previous app;
 *  - a RunLog handed to [onRun] for telemetry.
 */
class ClaudeAppBrain(
    private val driver: ClaudeAppDriver,
    private val onRun: suspend (RunLog) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Brain {

    override val id: String = "claude-app"
    override val displayName: String = "Claude (app)"

    private val _status = MutableStateFlow<BrainStatus>(BrainStatus.NotConnected)
    override val status: StateFlow<BrainStatus> = _status

    override suspend fun connect(): BrainStatus {
        val report = driver.checkReadiness()
        val status = if (report.ok) {
            BrainStatus.Ready
        } else {
            BrainStatus.NeedsSetup(report.firstFailure?.detail ?: "Setup required")
        }
        _status.value = status
        return status
    }

    override fun ask(image: Bitmap?, prompt: String): Flow<BrainEvent> = flow {
        if (_status.value is BrainStatus.Busy) {
            emit(BrainEvent.Failure("Another ask is already running.", recoverable = true))
            return@flow
        }
        _status.value = BrainStatus.Busy

        val log = RunLogBuilder(brainId = id, startedAtMs = nowMs())
        val tracker = AnswerTracker()
        var finalText = ""
        var failure: String? = null
        val startPackage = AssistAccessibilityService.foregroundPackage.value

        try {
            var done = false
            driver.run(image, prompt).collect { sample ->
                if (done) return@collect

                // Watchdog: if the user navigates away from the Claude app to
                // something other than where they started, abort cleanly.
                val fg = AssistAccessibilityService.foregroundPackage.value
                if (fg.isNotEmpty() &&
                    fg != com.claudeassist.overlay.core.driver.ClaudeAppSelectors.PACKAGE &&
                    fg != startPackage
                ) {
                    throw DriverException("You switched apps mid-ask.", recoverable = true)
                }

                when (val state = tracker.onSample(sample.text, nowMs())) {
                    is AnswerTracker.State.Streaming -> {
                        if (state.changed) emit(BrainEvent.Delta(state.textSoFar))
                    }
                    is AnswerTracker.State.Finished -> {
                        finalText = state.fullText
                        done = true
                        log.step("answer", StepOutcome.OK, 0, if (state.timedOut) "timed-out" else "stable")
                        emit(BrainEvent.Done(state.fullText, timedOut = state.timedOut))
                    }
                }
            }
            if (!done) {
                // Driver flow ended without a finish (shouldn't normally happen).
                finalText = ""
                failure = "Claude app closed before answering."
                emit(BrainEvent.Failure(failure, recoverable = true))
            }
        } catch (e: DriverException) {
            failure = e.reason
            log.step("driver", StepOutcome.FAILED, 0, e.reason)
            emit(BrainEvent.Failure(e.reason, e.recoverable))
        } catch (t: Throwable) {
            failure = t.message ?: "Unexpected automation error."
            log.step("driver", StepOutcome.FAILED, 0, failure!!)
            emit(BrainEvent.Failure(failure!!, recoverable = true))
        } finally {
            driver.returnToPreviousApp()
            _status.value = if (failure == null) BrainStatus.Ready else BrainStatus.Broken(failure!!)
            onRun(log.build(nowMs(), success = failure == null, failureReason = failure))
        }
    }
}
