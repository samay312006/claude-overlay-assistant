package com.claudeassist.overlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import com.claudeassist.overlay.App
import com.claudeassist.overlay.R
import com.claudeassist.overlay.automation.AssistAccessibilityService
import com.claudeassist.overlay.automation.ScreenReader
import com.claudeassist.overlay.automation.UiQuery
import com.claudeassist.overlay.brain.Brain
import com.claudeassist.overlay.core.brain.BrainEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the overlay windows and runs asks against the
 * active brain. Uses the accessibility screenshot (when the user opted in and
 * the service is live) as the image passed to the brain.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: OverlayWindowController
    private lateinit var app: App
    private var askJob: Job? = null

    // Persistent chat session (survives closing/reopening the panel).
    private val messages = mutableListOf<ChatMessage>()

    // A one-shot image attached to the NEXT ask: a Capture crop, or the current
    // screen grabbed on demand via the chat's 📷 button. Null = a normal, text-
    // only question (the default — the screen is never attached automatically).
    private var pendingAttachment: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        app = application as App
        startAsForeground()
        controller = OverlayWindowController(
            context = this,
            onBubbleTap = ::onBubbleTap,
            onSend = ::onSend,
            onPanelClosed = { /* nothing extra to tear down */ },
            onNewChat = ::onNewChat,
            onAttachScreen = ::onAttachScreen,
            onOpenInClaude = {
                // Retract the chat so Claude is actually visible.
                controller.hidePanel()
                com.claudeassist.overlay.notifications.ClaudeNotificationListener.openLatest(this)
            },
        )
        controller.addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The task was swiped from recents. On aggressive OEMs the process gets
     * killed anyway; schedule a near-immediate restart via an exact alarm so the
     * bubble comes back with a minimal gap rather than waiting on START_STICKY.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = Intent(applicationContext, RestartReceiver::class.java)
            .apply { action = RestartReceiver.ACTION_RESTART }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(this, 42, restart, flags)
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        runCatching {
            am.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                pi,
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    /** Bubble tap toggles the animated action dropdown. */
    private fun onBubbleTap() {
        if (controller.isDropdownOpen()) { controller.hideDropdown(); return }
        controller.showDropdown(
            listOf(
                MenuItem("💬", "Ask") { controller.hideDropdown(); openChat() },
                MenuItem("✏️", "Capture") { controller.hideDropdown(); onCapture() },
                MenuItem("🅰️", "Open Claude") {
                    controller.hideDropdown()
                    com.claudeassist.overlay.notifications.ClaudeNotificationListener.openLatest(this)
                },
                MenuItem("🎤", "Voice", enabled = false) {},
            ),
        )
    }

    /** Open the chat as a normal chatbox — no screenshot is attached by default.
     *  The conversation (and any pending attachment) is resumed as-is. */
    private fun openChat() {
        val panel = controller.showPanel()
        panel.render(messages, typing = askJob?.isActive == true)
        panel.showAttachment(pendingAttachment) { clearPendingAttachment() }
        panel.focusInput()
    }

    /** Chat 📷 button: grab the current screen on demand and attach it (with a
     *  clearable preview) to the next message. */
    private fun onAttachScreen() {
        controller.hidePanel()
        scope.launch {
            // Let our overlay windows clear so they're not in the screenshot.
            kotlinx.coroutines.delay(120)
            val shot = captureScreenshot()
            val panel = controller.showPanel()
            panel.render(messages, typing = askJob?.isActive == true)
            if (shot != null) {
                pendingAttachment = shot
                panel.showAttachment(shot) { clearPendingAttachment() }
            }
            panel.focusInput()
        }
    }

    /** Capture: freeze a screenshot, let the user mark a region (rectangle or
     *  freehand), attach the crop as a clearable preview on the next ask. */
    private fun onCapture() {
        controller.hidePanel()
        scope.launch {
            // Let our overlay windows clear so they're not in the screenshot.
            kotlinx.coroutines.delay(120)
            val shot = captureScreenshot() ?: return@launch
            controller.showRegionSelect(shot) { crop ->
                pendingAttachment = crop
                val panel = controller.showPanel()
                panel.render(messages, typing = false)
                panel.showAttachment(crop) { clearPendingAttachment() }
                panel.focusInput()
            }
        }
    }

    private fun clearPendingAttachment() {
        pendingAttachment = null
        controller.panel()?.showAttachment(null) {}
    }

    /** Header "new chat": clear the conversation and any pending attachment. */
    private fun onNewChat() {
        askJob?.cancel()
        messages.clear()
        pendingAttachment = null
        controller.panel()?.render(messages, typing = false)
        controller.panel()?.showAttachment(null) {}
    }

    private fun onSend(prompt: String) {
        val brain: Brain = app.brains.active.value
        // Attach only what the user explicitly staged (a Capture crop or a 📷
        // screen grab); otherwise it's a normal, text-only question.
        val attach = pendingAttachment
        pendingAttachment = null
        controller.panel()?.showAttachment(null) {}
        messages.add(ChatMessage(ChatMessage.Role.USER, prompt, thumb = attach))
        controller.panel()?.clearInput()
        controller.panel()?.render(messages, typing = true)

        val imageForAsk = attach
        askJob?.cancel()
        controller.suppressOutsideClose = true // keep chat open while Claude is driven
        askJob = scope.launch {
            try {
                brain.ask(imageForAsk, prompt).collect { event ->
                    when (event) {
                        is BrainEvent.Delta -> Unit // keep the typing indicator; no status spam
                        is BrainEvent.Done -> {
                            messages.add(
                                ChatMessage(
                                    ChatMessage.Role.CLAUDE,
                                    event.fullText,
                                    truncated = event.timedOut,
                                ),
                            )
                            controller.panel()?.render(messages, typing = false)
                            saveHistory(prompt, event.fullText, brain.id)
                        }
                        is BrainEvent.Failure -> {
                            messages.add(ChatMessage(ChatMessage.Role.CLAUDE, event.reason, error = true))
                            controller.panel()?.render(messages, typing = false)
                        }
                    }
                }
            } finally {
                controller.suppressOutsideClose = false
            }
        }
    }

    private suspend fun captureScreenshot(): Bitmap? {
        val service = AssistAccessibilityService.instance.value ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withContext(Dispatchers.Default) {
            val reader = ScreenReader(service, UiQuery(service))
            reader.takeScreenshot()
        }
    }

    private fun saveHistory(question: String, answer: String, brainId: String) {
        app.appScope.launch {
            runCatching { app.historyStore.add(question, answer, brainId) }
        }
    }

    private fun startAsForeground() {
        val channelId = "overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.overlay_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_bubble)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        askJob?.cancel()
        controller.destroy()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
