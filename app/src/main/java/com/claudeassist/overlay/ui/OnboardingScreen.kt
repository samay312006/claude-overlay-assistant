package com.claudeassist.overlay.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.claudeassist.overlay.App
import com.claudeassist.overlay.overlay.OverlayService
import com.claudeassist.overlay.util.Permissions

@Composable
fun OnboardingScreen(app: App) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Re-check permissions whenever we return from a system settings screen.
    var refresh by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    @Suppress("UNUSED_EXPRESSION") refresh

    val canOverlay = Permissions.canDrawOverlays(context)
    val accessibilityOn = Permissions.isAccessibilityEnabled(context)
    val notificationsOn = Permissions.hasNotificationPermission(context)
    val notifAccessOn =
        com.claudeassist.overlay.notifications.ClaudeNotificationListener.isEnabled(context)
    val batteryUnrestricted = Permissions.isIgnoringBatteryOptimizations(context)

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    Text("Setup", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))

    StepCard(
        title = "1. Display over other apps",
        done = canOverlay,
        body = "Lets the assistant bubble float on top of every app.",
        buttonText = "Grant overlay permission",
        onClick = { context.startActivity(Permissions.overlaySettingsIntent(context)) },
    )

    StepCard(
        title = "2. Accessibility service",
        done = accessibilityOn,
        body = "This is what reads your screen and drives the Claude app. Android will " +
            "show a scary warning — that's normal for accessibility apps.\n\n" +
            "If you sideloaded this app from a browser, Android 13+ blocks the toggle " +
            "under \"Restricted settings\": open App info ▸ ⋮ menu ▸ \"Allow restricted " +
            "settings\" first. Installs via adb/Android Studio are exempt.",
        buttonText = "Open Accessibility settings",
        onClick = { context.startActivity(Permissions.accessibilitySettingsIntent()) },
    )

    StepCard(
        title = "3. Notifications",
        done = notificationsOn,
        body = "The overlay runs as a foreground service, which needs an ongoing notification.",
        buttonText = "Allow notifications",
        onClick = {
            if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )

    StepCard(
        title = "4. Notification access",
        done = notifAccessOn,
        body = "Lets the assistant read Claude's reply notification, so answers come back to " +
            "the overlay while you keep working in other apps. Find \"Claude Assist\" in the " +
            "list and turn it on.",
        buttonText = "Open Notification access",
        onClick = {
            context.startActivity(
                com.claudeassist.overlay.notifications.ClaudeNotificationListener.settingsIntent()
            )
        },
    )

    StepCard(
        title = "5. Keep running in background",
        done = batteryUnrestricted,
        body = "So the bubble survives when you clear recent apps. Tap below and choose " +
            "\"Allow\" / \"Don't optimize\".\n\n" +
            "On OPPO/OnePlus/Xiaomi phones this isn't enough on its own — also open App info " +
            "(button below) and enable \"Auto-launch\" / \"Allow background activity\", and in " +
            "Recent apps lock this app (drag it down or tap the lock icon).",
        buttonText = "Allow background (battery)",
        onClick = { context.startActivity(Permissions.ignoreBatteryOptimizationsIntent(context)) },
        secondButtonText = "Open App info (auto-launch)",
        onSecondClick = { context.startActivity(Permissions.appDetailsSettingsIntent(context)) },
    )

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            app.settings.onboardingDone = true
            OverlayService.start(context)
        },
        enabled = canOverlay && notificationsOn,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (accessibilityOn) "Start the overlay" else "Start overlay (demo brain only)")
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = { OverlayService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
        Text("Stop the overlay")
    }
}

@Composable
private fun StepCard(
    title: String,
    done: Boolean,
    body: String,
    buttonText: String,
    onClick: () -> Unit,
    secondButtonText: String? = null,
    onSecondClick: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text((if (done) "✓ " else "• ") + title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body)
            if (!done) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onClick) { Text(buttonText) }
            }
            if (secondButtonText != null && onSecondClick != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSecondClick) { Text(secondButtonText) }
            }
        }
    }
}
