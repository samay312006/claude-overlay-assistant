package com.claudeassist.overlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
    private var pendingImage: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        app = application as App
        startAsForeground()
        controller = OverlayWindowController(
            context = this,
            onBubbleTap = ::onBubbleTap,
            onSend = ::onSend,
            onPanelClosed = { pendingImage = null },
            onRetry = ::onRetry,
        )
        controller.addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onBubbleTap() {
        val panel = controller.showPanel()
        // Grab a screenshot for this ask if enabled and the service is live.
        if (app.settings.attachScreenshot) {
            scope.launch {
                val bmp = captureScreenshot()
                pendingImage = bmp
                panel.setThumbnail(bmp)
            }
        }
    }

    private fun onSend(prompt: String) {
        val brain: Brain = app.brains.active.value
        val panel = controller.panel() ?: return
        panel.showSending()
        controller.showBanner("Asking ${brain.displayName}… don't touch the screen")

        askJob?.cancel()
        askJob = scope.launch {
            var full = ""
            try {
                brain.ask(pendingImage, prompt).collect { event ->
                    when (event) {
                        is BrainEvent.Delta -> {
                            full = event.textSoFar
                            panel.showAnswer(event.textSoFar)
                        }
                        is BrainEvent.Done -> {
                            full = event.fullText
                            panel.showDone(event.fullText, event.timedOut)
                            saveHistory(prompt, event.fullText, brain.id)
                        }
                        is BrainEvent.Failure -> {
                            panel.showError(event.reason, event.recoverable)
                        }
                    }
                }
            } finally {
                controller.hideBanner()
            }
        }
    }

    private fun onRetry() {
        val panel = controller.panel() ?: return
        val prompt = panel.currentPrompt()
        if (prompt.isNotEmpty()) onSend(prompt)
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
