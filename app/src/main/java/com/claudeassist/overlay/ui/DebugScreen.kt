package com.claudeassist.overlay.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.claudeassist.overlay.App
import com.claudeassist.overlay.automation.ActionExecutor
import com.claudeassist.overlay.automation.AssistAccessibilityService
import com.claudeassist.overlay.automation.ScreenReader
import com.claudeassist.overlay.automation.UiQuery
import com.claudeassist.overlay.core.driver.ClaudeAppSelectors
import com.claudeassist.overlay.core.uitree.UiSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

@Composable
fun DebugScreen(app: App) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service by AssistAccessibilityService.instance.collectAsState()
    var log by remember { mutableStateOf("") }
    var frame by remember { mutableStateOf<Bitmap?>(null) }

    Text("Debug / Driver Doctor", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(4.dp))
    Text(if (service == null) "Accessibility service: OFF" else "Accessibility service: ON")
    Spacer(Modifier.height(12.dp))

    // M2 demo: open Claude and tap the new-chat affordance.
    Button(
        enabled = service != null,
        onClick = {
            val svc = service ?: return@Button
            scope.launch {
                val exec = ActionExecutor(svc)
                val query = UiQuery(svc)
                if (!exec.launchApp(ClaudeAppSelectors.PACKAGE)) {
                    log = "Claude app not installed / no launch intent."
                    return@launch
                }
                val newChat = query.await(ClaudeAppSelectors.newChatButton, timeoutMs = 6_000)
                log = if (newChat != null && exec.tap(newChat)) {
                    "Opened Claude and tapped new-chat."
                } else {
                    "Opened Claude but couldn't find/tap new-chat (update selectors)."
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Demo: open Claude + tap new chat") }

    Spacer(Modifier.height(8.dp))

    // M3: live screenshot preview.
    Button(
        enabled = service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        onClick = {
            val svc = service ?: return@Button
            scope.launch {
                frame = withContext(Dispatchers.Default) {
                    ScreenReader(svc, UiQuery(svc)).takeScreenshot()
                }
                log = if (frame != null) "Captured ${frame!!.width}×${frame!!.height}." else "Screenshot failed."
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Capture screenshot") }

    frame?.let {
        Spacer(Modifier.height(8.dp))
        Image(it.asImageBitmap(), contentDescription = "Captured frame",
            modifier = Modifier.fillMaxWidth().height(200.dp))
    }

    Spacer(Modifier.height(8.dp))

    // Driver Doctor: dump the foreground UI tree to JSON + share.
    Button(
        enabled = service != null,
        onClick = {
            val svc = service ?: return@Button
            scope.launch {
                val snapshot: UiSnapshot = withContext(Dispatchers.Default) { UiQuery(svc).snapshot() }
                val json = Json { prettyPrint = true }.encodeToString(UiSnapshot.serializer(), snapshot)
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, "uitree-${snapshot.foregroundPackage}-${System.currentTimeMillis()}.json")
                file.writeText(json)
                val uri = FileProvider.getUriForFile(context, "com.claudeassist.overlay.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "Share UI tree").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                log = "Dumped tree of ${snapshot.foregroundPackage} (${snapshot.allNodes().count()} nodes)."
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Driver Doctor: dump foreground UI tree") }

    Spacer(Modifier.height(8.dp))

    // Readiness checks for the Claude driver.
    Button(onClick = {
        val brain = app.brains.byId("claude-app")
        scope.launch {
            val status = brain?.connect()
            log = "Claude brain readiness: $status"
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Run Claude readiness checks") }

    if (log.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) { Text(log, Modifier.padding(12.dp)) }
    }

    // Telemetry rows.
    val runs by app.telemetryStore.observeRecent().collectAsState(initial = emptyList())
    if (runs.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Recent automation runs", style = MaterialTheme.typography.titleMedium)
        runs.forEach { run ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${if (run.success) "✓" else "✗"} ${run.brainId} · ${run.totalMs} ms")
                    run.failureReason?.let { Text("reason: $it", style = MaterialTheme.typography.labelSmall) }
                    Text("${app.telemetryStore.decodeSteps(run).size} steps",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
