package com.claudeassist.overlay.brain.drivers

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.claudeassist.overlay.automation.ActionExecutor
import com.claudeassist.overlay.automation.AssistAccessibilityService
import com.claudeassist.overlay.automation.ScreenReader
import com.claudeassist.overlay.automation.UiQuery
import com.claudeassist.overlay.core.driver.CheckResult
import com.claudeassist.overlay.core.driver.ClaudeAppSelectors
import com.claudeassist.overlay.core.driver.DriverCheckReport
import com.claudeassist.overlay.util.Images
import com.claudeassist.overlay.util.Permissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Drives the installed Claude Android app: bring it to the foreground (with the
 * screenshot shared in, when present), type the prompt, send, and read the
 * answer back. All UI anchors live in [ClaudeAppSelectors] — the only file to
 * touch when the Claude app changes.
 *
 * [DriverException] carries a human-readable reason that surfaces as
 * BrainStatus.Broken and in telemetry.
 */
class DriverException(val reason: String, val recoverable: Boolean = true) : Exception(reason)

/** One raw answer sample polled from the Claude app while it streams. */
data class AnswerSample(val text: String)

/** Live automation helpers bound to a connected accessibility service. */
class AutomationHandle(
    val exec: ActionExecutor,
    val query: UiQuery,
    val screenReader: ScreenReader,
)

class ClaudeAppDriver(
    private val appContext: Context,
    private val serviceProvider: () -> AssistAccessibilityService?,
) {
    var lastForegroundPackage: String = ""
        private set

    private fun handle(): AutomationHandle {
        val service = serviceProvider()
            ?: throw DriverException("Enable Claude Assist in Settings > Accessibility.")
        val query = UiQuery(service)
        return AutomationHandle(
            exec = ActionExecutor(service),
            query = query,
            screenReader = ScreenReader(service, query),
        )
    }

    fun checkReadiness(): DriverCheckReport {
        val results = mutableListOf<CheckResult>()

        val installed = runCatching {
            appContext.packageManager.getLaunchIntentForPackage(ClaudeAppSelectors.PACKAGE) != null
        }.getOrDefault(false)
        results += CheckResult(
            "Claude app installed",
            installed,
            if (installed) "" else "Install the Claude app and sign in.",
        )

        val serviceOn = Permissions.isAccessibilityEnabled(appContext)
        results += CheckResult(
            "Accessibility service enabled",
            serviceOn,
            if (serviceOn) "" else "Enable Claude Assist in Settings > Accessibility.",
        )

        return DriverCheckReport(results)
    }

    /**
     * Opens the Claude app, submits the ask, then emits raw answer samples until
     * cancelled by the caller (ClaudeAppBrain applies AnswerTracker for
     * stability/timeout). Records the app the user was in for return-after.
     */
    fun run(image: Bitmap?, prompt: String): Flow<AnswerSample> = flow {
        lastForegroundPackage = AssistAccessibilityService.foregroundPackage.value

        checkReadiness().firstFailure?.let { throw DriverException(it.detail.ifEmpty { it.name }) }
        val h = handle()
        val exec = h.exec
        val query = h.query

        val sharedImage = image != null && openWithSharedImage(image)
        if (!sharedImage && !exec.launchApp(ClaudeAppSelectors.PACKAGE)) {
            throw DriverException("Couldn't launch the Claude app.")
        }

        val input = query.await(ClaudeAppSelectors.inputField, timeoutMs = 8_000)
            ?: throw DriverException(
                "Couldn't find the Claude message field. Run Driver Doctor and update selectors.",
            )

        if (!exec.typeText(input, prompt)) {
            throw DriverException("Couldn't type into the Claude message field.")
        }
        delay(250)

        val send = query.await(ClaudeAppSelectors.sendButton, timeoutMs = 3_000)
        // If there's no send button (some builds submit via IME action), keep
        // going; the answer poll will surface a real failure via timeout.
        if (send != null) exec.tap(send)

        while (true) {
            val snapshot = query.snapshot()
            ClaudeAppSelectors.findError(snapshot)?.let {
                throw DriverException("Claude app reported: $it")
            }
            ClaudeAppSelectors.extractLatestAnswer(snapshot, prompt)?.let {
                emit(AnswerSample(it))
            }
            delay(POLL_MS)
        }
    }

    /**
     * Opens Claude, attaches the screenshot (when present), types the prompt and
     * taps send — then returns. No foreground polling: the caller backgrounds
     * Claude and reads the answer from Claude's completion notification. Records
     * the app the user came from so it can be restored.
     */
    suspend fun submitAsk(image: Bitmap?, prompt: String) {
        lastForegroundPackage = AssistAccessibilityService.foregroundPackage.value

        checkReadiness().firstFailure?.let { throw DriverException(it.detail.ifEmpty { it.name }) }
        val h = handle()
        val exec = h.exec
        val query = h.query

        val sharedImage = image != null && openWithSharedImage(image)
        if (!sharedImage && !exec.launchApp(ClaudeAppSelectors.PACKAGE)) {
            throw DriverException("Couldn't launch the Claude app.")
        }

        val input = query.await(ClaudeAppSelectors.inputField, timeoutMs = 8_000)
        if (input == null) {
            val snap = query.snapshot()
            val nodes = snap.allNodes().toList()
            val editTexts = nodes.count { it.className.contains("EditText", ignoreCase = true) }
            val classes = nodes.map { it.className.substringAfterLast('.') }
                .filter { it.isNotBlank() }.distinct().take(20)
            android.util.Log.w(
                "DriverDbg",
                "inputField miss: nodes=${nodes.size} editTexts=$editTexts classes=$classes; " +
                    query.debugWindowInfo(),
            )
            throw DriverException(
                "Couldn't find the Claude message field. Run Driver Doctor and update selectors.",
            )
        }
        if (!exec.typeText(input, prompt)) {
            throw DriverException("Couldn't type into the Claude message field.")
        }
        delay(300)

        val send = query.await(ClaudeAppSelectors.sendButton, timeoutMs = 4_000)
            ?: throw DriverException("Couldn't find the send button in the Claude app.")
        if (!exec.tap(send)) {
            throw DriverException("Couldn't tap send in the Claude app.")
        }
        delay(400) // let the send register before the caller backgrounds Claude
    }

    /** @return true if the share intent was dispatched; false to fall back to launch. */
    private fun openWithSharedImage(image: Bitmap): Boolean {
        val uri: Uri = Images.writeForShare(appContext, image)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage(ClaudeAppSelectors.PACKAGE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (appContext.packageManager.resolveActivity(intent, 0) == null) return false
        return runCatching { appContext.startActivity(intent); true }.getOrDefault(false)
    }

    fun returnToPreviousApp() {
        val exec = serviceProvider()?.let { ActionExecutor(it) } ?: return
        val pkg = lastForegroundPackage
        if (pkg.isNotEmpty() && pkg != ClaudeAppSelectors.PACKAGE) {
            exec.launchApp(pkg)
        } else {
            exec.home()
        }
    }

    /**
     * One-shot read of Claude's on-screen answer (null until complete, or if
     * Claude isn't the foreground window — e.g. the user switched away, in which
     * case the caller falls back to the completion notification).
     */
    fun readAnswer(prompt: String): String? {
        val service = serviceProvider() ?: return null
        val snapshot = UiQuery(service).snapshot()
        if (snapshot.foregroundPackage != ClaudeAppSelectors.PACKAGE) return null
        return ClaudeAppSelectors.extractLatestAnswer(snapshot, prompt)
    }

    /** Foreground snapshot for Driver Doctor. Null when the service isn't live. */
    fun dumpForegroundTree(): com.claudeassist.overlay.core.uitree.UiSnapshot? =
        serviceProvider()?.let { UiQuery(it).snapshot() }

    companion object {
        const val POLL_MS = 700L
    }
}
