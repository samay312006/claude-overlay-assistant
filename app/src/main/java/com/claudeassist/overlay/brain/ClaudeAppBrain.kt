package com.claudeassist.overlay.brain

import android.graphics.Bitmap
import com.claudeassist.overlay.brain.drivers.ClaudeAppDriver
import com.claudeassist.overlay.brain.drivers.DriverException
import com.claudeassist.overlay.core.brain.BrainEvent
import com.claudeassist.overlay.core.brain.BrainStatus
import com.claudeassist.overlay.core.driver.RunLogBuilder
import com.claudeassist.overlay.core.driver.StepOutcome
import com.claudeassist.overlay.notifications.ClaudeNotificationListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Brain that answers by driving the installed Claude app via [ClaudeAppDriver].
 *
 * Flow: submit the ask (screenshot + prompt) in the Claude app, immediately
 * return the user to their previous app, then read the answer from Claude's
 * "responded" notification — so the user can switch apps freely and the answer
 * still lands in the overlay. Long answers arrive truncated in the notification
 * (a platform limit); [BrainEvent.Done.timedOut] flags that so the UI can offer
 * "Open in Claude" for the full text.
 */
class ClaudeAppBrain(
    private val driver: ClaudeAppDriver,
    private val onRun: suspend (com.claudeassist.overlay.core.driver.RunLog) -> Unit = {},
    private val notificationAccessEnabled: () -> Boolean = { true },
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
        if (!notificationAccessEnabled()) {
            emit(
                BrainEvent.Failure(
                    "Grant Notification access so Claude's reply can come back to the overlay " +
                        "(Setup ▸ step 4).",
                    recoverable = false,
                ),
            )
            return@flow
        }
        _status.value = BrainStatus.Busy

        val log = RunLogBuilder(brainId = id, startedAtMs = nowMs())
        var failure: String? = null
        val startMs = nowMs()

        try {
            driver.submitAsk(image, prompt)
            log.step("submit", StepOutcome.OK, 0, "sent")

            // Background Claude so it posts the completion notification, and put
            // the user back where they were.
            driver.returnToPreviousApp()
            emit(BrainEvent.Delta("Sent. You can switch apps — waiting for Claude's reply…"))

            val reply = withTimeoutOrNull(ANSWER_TIMEOUT_MS) {
                ClaudeNotificationListener.replies.first { it.postedAtMs >= startMs }
            }
            if (reply == null) {
                failure = "No reply from Claude within ${ANSWER_TIMEOUT_MS / 1000}s. " +
                    "Open Claude to check."
                emit(BrainEvent.Failure(failure, recoverable = true))
            } else {
                val truncated = looksTruncated(reply.text)
                log.step("answer", StepOutcome.OK, 0, if (truncated) "truncated" else "full")
                emit(BrainEvent.Done(reply.text, timedOut = truncated))
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
            _status.value = if (failure == null) BrainStatus.Ready else BrainStatus.Broken(failure!!)
            onRun(log.build(nowMs(), success = failure == null, failureReason = failure))
        }
    }

    /** The Claude app truncates long notification bodies; treat an answer that
     *  doesn't end on sentence/code punctuation as likely cut off. */
    private fun looksTruncated(text: String): Boolean {
        val last = text.trimEnd().lastOrNull() ?: return false
        return last !in ".!?\"')]}`…"
    }

    companion object {
        private const val ANSWER_TIMEOUT_MS = 120_000L
    }
}
