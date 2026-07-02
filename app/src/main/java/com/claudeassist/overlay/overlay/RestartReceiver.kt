package com.claudeassist.overlay.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the overlay service after the task is swiped from recents. Starting a
 * foreground service from an alarm's broadcast is on the platform's allow-list
 * for background FGS starts, so this reliably brings the bubble back with only a
 * brief gap even on OEMs that kill the process on task removal.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching { OverlayService.start(context.applicationContext) }
    }

    companion object {
        const val ACTION_RESTART = "com.claudeassist.overlay.action.RESTART_OVERLAY"
    }
}
