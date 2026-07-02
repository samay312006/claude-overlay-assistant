package com.claudeassist.overlay

import android.app.Application
import com.claudeassist.overlay.automation.AssistAccessibilityService
import com.claudeassist.overlay.brain.Brain
import com.claudeassist.overlay.brain.BrainRegistry
import com.claudeassist.overlay.brain.ClaudeAppBrain
import com.claudeassist.overlay.brain.FakeBrain
import com.claudeassist.overlay.brain.PlaceholderBrain
import com.claudeassist.overlay.brain.drivers.ClaudeAppDriver
import com.claudeassist.overlay.data.AppDatabase
import com.claudeassist.overlay.data.HistoryStore
import com.claudeassist.overlay.data.SecureSettings
import com.claudeassist.overlay.data.TelemetryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal manual DI. Building the accessibility-backed helpers requires a live
 * service, so the automation layer is created lazily via [withService] once the
 * user has enabled it.
 */
class App : Application() {

    lateinit var settings: SecureSettings
        private set
    lateinit var historyStore: HistoryStore
        private set
    lateinit var telemetryStore: TelemetryStore
        private set
    lateinit var brains: BrainRegistry
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this

        settings = SecureSettings(this)
        val db = AppDatabase.build(this)
        historyStore = HistoryStore(db.historyDao())
        telemetryStore = TelemetryStore(db.telemetryDao())

        val driver = ClaudeAppDriver(
            appContext = this,
            serviceProvider = { AssistAccessibilityService.instance.value },
        )
        val claudeBrain = ClaudeAppBrain(
            driver = driver,
            onRun = { runLog -> telemetryStore.record(runLog) },
        )
        val allBrains: List<Brain> = listOf(
            claudeBrain,
            FakeBrain(),
            PlaceholderBrain.chatGpt(),
            PlaceholderBrain.anthropicApi(),
            PlaceholderBrain.pcRelay(),
        )
        brains = BrainRegistry(allBrains, settings)
    }

    fun <T> withService(block: (AssistAccessibilityService) -> T): T? =
        AssistAccessibilityService.instance.value?.let(block)

    companion object {
        lateinit var instance: App
            private set
    }
}
