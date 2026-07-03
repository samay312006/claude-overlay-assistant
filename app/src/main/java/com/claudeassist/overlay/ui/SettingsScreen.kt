package com.claudeassist.overlay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

    Spacer(Modifier.height(20.dp))
    RelaySection(app)
}

@Composable
private fun RelaySection(app: App) {
    // Start in edit mode only if nothing is configured yet; otherwise locked.
    var editing by remember { mutableStateOf(app.settings.relayHost.isBlank()) }
    var host by remember { mutableStateOf(app.settings.relayHost) }
    var port by remember { mutableStateOf(app.settings.relayPort.toString()) }
    var password by remember { mutableStateOf(app.settings.relayPassword) }
    var model by remember { mutableStateOf(app.settings.relayModel) }

    Text("PC relay (background)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Answer on your PC via server-hub over Tailscale — fully in the background, no Claude " +
            "app, using your Claude Code subscription. After saving, select \"PC relay\" in the " +
            "Brains tab.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))

    Field("PC Tailscale host or IP (e.g. 100.x.y.z)", host, enabled = editing) { host = it }
    Field("Port", port, enabled = editing) { port = it }
    Field("server-hub password", password, enabled = editing, isPassword = true) { password = it }
    Field("Model (haiku / sonnet / opus)", model, enabled = editing) { model = it }

    Spacer(Modifier.height(4.dp))
    Row {
        if (editing) {
            Button(onClick = {
                app.settings.relayHost = host.trim()
                app.settings.relayPort = port.toIntOrNull() ?: 8088
                app.settings.relayPassword = password
                app.settings.relayModel = model.trim().ifBlank { "sonnet" }
                // reflect any normalization back into the fields
                port = app.settings.relayPort.toString()
                model = app.settings.relayModel
                editing = false
            }) { Text("Save") }
        } else {
            OutlinedButton(onClick = { editing = true }) { Text("Edit") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, modifier = Modifier.weight(1f).fillMaxWidth())
        Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    enabled: Boolean,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
