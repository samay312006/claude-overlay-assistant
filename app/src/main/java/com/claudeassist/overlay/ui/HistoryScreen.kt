package com.claudeassist.overlay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudeassist.overlay.App
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(app: App) {
    val entries by app.historyStore.observeRecent().collectAsState(initial = emptyList())

    Text("History", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))

    if (entries.isEmpty()) {
        Text("No asks yet. Tap the bubble over any app to ask something.")
        return
    }

    entries.forEach { entry ->
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(entry.question, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(entry.answer)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${entry.brainId} · ${DateFormat.getDateTimeInstance().format(Date(entry.timestampMs))}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
