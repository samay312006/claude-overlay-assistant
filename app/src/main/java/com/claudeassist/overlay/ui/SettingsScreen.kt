package com.claudeassist.overlay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudeassist.overlay.App

@Composable
fun SettingsScreen(app: App) {
    Text("Settings", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))

    ToggleRow(
        label = "Attach a screenshot to each ask",
        initial = app.settings.attachScreenshot,
    ) { app.settings.attachScreenshot = it }

    ToggleRow(
        label = "Save screenshot thumbnails in history",
        initial = app.settings.saveThumbnails,
    ) { app.settings.saveThumbnails = it }
}

@Composable
private fun ToggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f).fillMaxWidth())
        Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
    }
}
