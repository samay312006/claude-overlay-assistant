package com.claudeassist.overlay.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.claudeassist.overlay.automation.AssistAccessibilityService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One answer read from a Claude completion notification. */
data class ClaudeReply(val text: String, val postedAtMs: Long)

/**
 * Reads the Claude app's "Claude responded" notifications so answers can be
 * delivered to the overlay while Claude stays in the background. Requires the
 * user to grant Notification access (a NotificationListenerService binding).
 *
 * Note: the Claude app truncates long answers in the notification body, so the
 * full text of long answers is not recoverable here — [openLatest] jumps into
 * the Claude app (the exact conversation, via the notification's own intent)
 * for the complete response.
 */
class ClaudeNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != CLAUDE_PACKAGE) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val isReply = sbn.notification.channelId == COMPLETION_CHANNEL ||
            title.contains("responded", ignoreCase = true)
        if (!isReply) return

        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim()
            ?: return
        if (text.isEmpty()) return

        latestContentIntent = sbn.notification.contentIntent
        val reply = ClaudeReply(text = text, postedAtMs = sbn.postTime)
        latestReply = reply
        _replies.tryEmit(reply)
    }

    companion object {
        const val CLAUDE_PACKAGE = "com.anthropic.claude"
        private const val COMPLETION_CHANNEL = "completion"

        // replay=1 so a reply posted just before the brain subscribes isn't
        // missed; the brain filters by postedAtMs to skip stale replies.
        private val _replies = MutableSharedFlow<ClaudeReply>(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val replies: SharedFlow<ClaudeReply> = _replies.asSharedFlow()

        @Volatile
        private var latestContentIntent: PendingIntent? = null

        @Volatile
        private var latestReply: ClaudeReply? = null

        /** The most recent Claude reply posted at/after [sinceMs], if any. Used as
         *  a fallback when the user backgrounds Claude before the on-screen poll
         *  captures the answer. */
        fun latestSince(sinceMs: Long): ClaudeReply? =
            latestReply?.takeIf { it.postedAtMs >= sinceMs }

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        /**
         * Opens the Claude app for the full reply. Launches through the
         * accessibility service FIRST: a plain startActivity() (or a
         * PendingIntent.send()) from the background overlay service is blocked by
         * Android's background-activity-launch rules — and, critically, that
         * block is silent (no exception), so trying it first would look like it
         * "succeeded" while nothing happened. An AccessibilityService is exempt
         * from BAL, so it reliably brings Claude forward (onto the conversation
         * we just used). Falls back to the notification's own intent, then a
         * plain launch.
         */
        fun openLatest(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            AssistAccessibilityService.instance.value?.let { svc ->
                if (launch != null && runCatching { svc.startActivity(launch) }.isSuccess) return
            }
            latestContentIntent?.let { pi ->
                if (runCatching { pi.send() }.isSuccess) return
            }
            if (launch != null) runCatching { context.startActivity(launch) }
        }
    }
}
