package com.claudeassist.overlay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudeassist.overlay.App
import com.claudeassist.overlay.brain.Brain
import com.claudeassist.overlay.core.brain.BrainStatus
import kotlinx.coroutines.launch

@Composable
fun BrainsScreen(app: App) {
    val active by app.brains.active.collectAsState()
    val scope = rememberCoroutineScope()

    Text("Brains", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(4.dp))
    Text("Connect the AI you already pay for. Claude is the most optimized connector.")
    Spacer(Modifier.height(12.dp))

    app.brains.all().forEach { brain ->
        BrainCard(
            brain = brain,
            isActive = brain.id == active.id,
            onConnect = { scope.launch { brain.connect() } },
            onMakeActive = { app.brains.setActive(brain.id) },
        )
    }
}

@Composable
private fun BrainCard(
    brain: Brain,
    isActive: Boolean,
    onConnect: () -> Unit,
    onMakeActive: () -> Unit,
) {
    val status by brain.status.collectAsState()
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Text(brain.displayName, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().weight(1f))
                if (isActive) Text("ACTIVE", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(statusText(status))
            Spacer(Modifier.height(10.dp))
            Row {
                Button(onClick = onConnect) { Text("Connect") }
                Spacer(Modifier.padding(4.dp))
                Button(onClick = onMakeActive, enabled = !isActive) { Text("Use this") }
            }
        }
    }
}

private fun statusText(status: BrainStatus): String = when (status) {
    is BrainStatus.NotConnected -> "Not connected — tap Connect to check."
    is BrainStatus.NeedsSetup -> "Needs setup: ${status.what}"
    is BrainStatus.Ready -> "Ready ✓"
    is BrainStatus.Busy -> "Working…"
    is BrainStatus.Broken -> "Broken: ${status.reason}"
}
