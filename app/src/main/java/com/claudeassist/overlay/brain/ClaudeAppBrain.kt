package com.claudeassist.overlay.brain

import android.graphics.Bitmap
import com.claudeassist.overlay.brain.drivers.ClaudeAppDriver
import com.claudeassist.overlay.brain.drivers.DriverException
import com.claudeassist.overlay.core.brain.BrainEvent
import com.claudeassist.overlay.core.brain.BrainStatus
import com.claudeassist.overlay.core.driver.RunLog
import com.claudeassist.overlay.core.driver.RunLogBuilder
import com.claudeassist.overlay.core.driver.StepOutcome
import com.claudeassist.overlay.notifications.ClaudeNotificationListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Brain that answers by driving the installed Claude app via [ClaudeAppDriver].
 *
 * Flow: submit the ask (open Claude, attach screenshot, type, send), then wait
 * for the answer via EITHER path, whichever lands first:
 *  - **on-screen poll** while Claude stays foreground → the complete answer;
 *  - **completion notification** if the user switches away from Claude → the
 *    (possibly shortened) answer, so it never hangs on "typing".
 * Finally return the user to their previous app.
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
        var failure: String? = null
        val startMs = nowMs()

        try {
            driver.submitAsk(image, prompt)
            log.step("submit", StepOutcome.OK, 0, "sent")

            val deadline = startMs + ANSWER_TIMEOUT_MS
            var done = false
            while (nowMs() < deadline) {
                // 1) Claude still on screen → read the complete answer.
                driver.readAnswer(prompt)?.let { answer ->
                    log.step("answer", StepOutcome.OK, 0, "screen")
                    emit(BrainEvent.Done(answer, timedOut = false))
                    done = true
                }
                if (done) break

                // 2) User left Claude → its completion notification (shortened).
                ClaudeNotificationListener.latestSince(startMs)?.let { reply ->
                    log.step("answer", StepOutcome.OK, 0, "notification")
                    emit(BrainEvent.Done(reply.text, timedOut = looksTruncated(reply.text)))
                    done = true
                }
                if (done) break

                delay(POLL_MS)
            }
            if (!done) {
                failure = "Timed out waiting for Claude's answer."
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

    /** Notification bodies are shortened by the Claude app; treat an answer that
     *  doesn't end on sentence/code punctuation as likely cut off. */
    private fun looksTruncated(text: String): Boolean {
        val last = text.trimEnd().lastOrNull() ?: return false
        return last !in ".!?\"')]}`…"
    }

    companion object {
        private const val ANSWER_TIMEOUT_MS = 120_000L
        private const val POLL_MS = 600L
    }
}
