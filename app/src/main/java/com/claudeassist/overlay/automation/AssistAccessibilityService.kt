package com.claudeassist.overlay.automation

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single class touching the AccessibilityService framework API. Everything
 * else works through [instance], the [events] stream, and helper classes
 * (UiQuery, ActionExecutor, ScreenReader) that take this service as a handle.
 */
class AssistAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            // Ignore our own overlay windows AND transient windows (soft
            // keyboard, system UI) so foreground tracking reflects the app the
            // user is actually looking at. Without this, the keyboard popping up
            // during automation reads as "the user switched apps".
            if (!pkg.isNullOrEmpty() && pkg != packageName && !isTransientWindowPackage(pkg)) {
                _foregroundPackage.value = pkg
            }
        }
        _events.tryEmit(event.eventType)
    }

    /** True for windows that aren't the app the user is looking at: the current
     *  input method (soft keyboard) and the system UI. */
    private fun isTransientWindowPackage(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true
        val ime = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        val imePkg = ime?.substringBefore('/')?.takeIf { it.isNotEmpty() }
        return imePkg != null && pkg == imePkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (_instance.value === this) _instance.value = null
        return super.onUnbind(intent)
    }

    companion object {
        private val _instance = MutableStateFlow<AssistAccessibilityService?>(null)
        val instance: StateFlow<AssistAccessibilityService?> = _instance.asStateFlow()

        private val _foregroundPackage = MutableStateFlow("")
        val foregroundPackage: StateFlow<String> = _foregroundPackage.asStateFlow()

        // replay=0, generous buffer so event bursts don't drop the signal that
        // an await() is waiting for.
        private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 64)
        val events: SharedFlow<Int> = _events.asSharedFlow()
    }
}
