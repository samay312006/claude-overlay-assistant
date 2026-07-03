package com.claudeassist.overlay.brain

import android.graphics.Bitmap
import android.util.Base64
import com.claudeassist.overlay.core.brain.BrainEvent
import com.claudeassist.overlay.core.brain.BrainStatus
import com.claudeassist.overlay.data.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * PC-relay connector: talks to the user's server-hub instance over Tailscale.
 * server-hub drives `claude.exe` on the user's Claude Code subscription, so this
 * runs fully in the background with no Claude app and no API key.
 *
 * Contract (server-hub webui/server.js):
 *  - POST /api/login {password}          → Set-Cookie hub_auth=<token>
 *  - POST /api/upload {name,dataBase64}  → {path}  (Claude Code can read the file)
 *  - POST /api/chat  {message,model,convId} → NDJSON: {type:text,text} … {type:done}
 */
class RelayBrain(private val settings: SecureSettings) : Brain {

    override val id: String = "pc-relay"
    override val displayName: String = "PC relay (Claude Code)"

    private val _status = MutableStateFlow<BrainStatus>(BrainStatus.NotConnected)
    override val status: StateFlow<BrainStatus> = _status

    private val json = Json { ignoreUnknownKeys = true }
    private val convId = UUID.randomUUID().toString()
    @Volatile private var token: String? = null

    override suspend fun connect(): BrainStatus {
        val missing = configError()
        if (missing != null) {
            _status.value = BrainStatus.NeedsSetup(missing)
            return _status.value
        }
        _status.value = BrainStatus.Busy
        val status = try {
            // Network must not run on the caller's (main) thread.
            withContext(Dispatchers.IO) { login() }
            BrainStatus.Ready
        } catch (t: Throwable) {
            BrainStatus.Broken(reachError(t))
        }
        _status.value = status
        return status
    }

    override fun ask(image: Bitmap?, prompt: String): Flow<BrainEvent> = flow {
        configError()?.let {
            emit(BrainEvent.Failure(it, recoverable = false))
            return@flow
        }
        _status.value = BrainStatus.Busy
        var failure: String? = null
        try {
            if (token == null) login()

            val message = if (image != null) {
                val path = uploadImage(image)
                "The user's current screen is in the image at $path. " +
                    "Look at it and answer: $prompt"
            } else {
                prompt
            }

            streamChat(message) { delta -> emit(BrainEvent.Delta(delta)) }
                .let { full -> emit(BrainEvent.Done(full, timedOut = false)) }
        } catch (t: Throwable) {
            failure = reachError(t)
            emit(BrainEvent.Failure(failure, recoverable = true))
        } finally {
            _status.value = if (failure == null) BrainStatus.Ready else BrainStatus.Broken(failure!!)
        }
    }.flowOn(Dispatchers.IO)

    // --- HTTP ---

    private fun base(): String = "http://${settings.relayHost}:${settings.relayPort}"

    /** Turn low-level network exceptions into an actionable message. */
    private fun reachError(t: Throwable): String {
        val where = "${settings.relayHost}:${settings.relayPort}"
        return when (t) {
            is java.net.UnknownHostException ->
                "Can't resolve \"$where\". Use the PC's Tailscale IP (100.x.y.z) and make sure Tailscale is connected on this phone."
            is java.net.ConnectException, is java.net.SocketTimeoutException ->
                "Couldn't reach $where. Is Tailscale connected on this phone and is server-hub running on the PC?"
            else -> t.message ?: "PC relay error (${t.javaClass.simpleName})."
        }
    }

    private fun configError(): String? = when {
        settings.relayHost.isBlank() -> "Set the PC's Tailscale host in Settings."
        settings.relayPassword.isBlank() -> "Set the server-hub password in Settings."
        else -> null
    }

    private fun login() {
        val conn = open("/api/login")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        writeBody(conn.outputStream, buildJsonObject { put("password", settings.relayPassword) }.toString())
        if (conn.responseCode != 200) {
            throw RuntimeException(if (conn.responseCode == 401) "Wrong relay password." else "Login failed (${conn.responseCode}).")
        }
        val setCookie = conn.headerFields["Set-Cookie"]?.firstOrNull { it.contains("hub_auth=") }
            ?: throw RuntimeException("Relay didn't return an auth token.")
        token = Regex("hub_auth=([^;]+)").find(setCookie)?.groupValues?.get(1)
            ?: throw RuntimeException("Couldn't parse the relay auth token.")
        conn.disconnect()
    }

    private fun uploadImage(image: Bitmap): String {
        val jpeg = ByteArrayOutputStream().use { out ->
            image.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray()
        }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val conn = authed("/api/upload")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        writeBody(
            conn.outputStream,
            buildJsonObject {
                put("name", "overlay-screen-${System.currentTimeMillis()}.jpg")
                put("dataBase64", b64)
            }.toString(),
        )
        if (conn.responseCode != 200) throw RuntimeException("Screenshot upload failed (${conn.responseCode}).")
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return json.parseToJsonElement(body).jsonObject["path"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Relay didn't return the uploaded path.")
    }

    /** Streams the chat turn, invoking [onDelta] with the accumulated text; returns the full answer. */
    private suspend inline fun streamChat(message: String, onDelta: (String) -> Unit): String {
        val conn = authed("/api/chat")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.readTimeout = 180_000
        conn.setRequestProperty("Content-Type", "application/json")
        writeBody(
            conn.outputStream,
            buildJsonObject {
                put("message", message)
                put("model", settings.relayModel)
                put("convId", convId)
            }.toString(),
        )
        if (conn.responseCode != 200) {
            throw RuntimeException("Relay chat failed (${conn.responseCode}).")
        }
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (l.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(l).jsonObject }.getOrNull() ?: continue
                when (obj["type"]?.jsonPrimitive?.content) {
                    "text" -> obj["text"]?.jsonPrimitive?.content?.let { sb.append(it); onDelta(sb.toString()) }
                    "error" -> throw RuntimeException(obj["error"]?.jsonPrimitive?.content ?: "Relay error.")
                    "done" -> return sb.toString()
                }
            }
        }
        conn.disconnect()
        return sb.toString()
    }

    private fun open(path: String): HttpURLConnection =
        (URL(base() + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 30_000
        }

    private fun authed(path: String): HttpURLConnection =
        open(path).apply { setRequestProperty("Cookie", "hub_auth=${token ?: ""}") }

    private fun writeBody(out: OutputStream, body: String) {
        out.use { it.write(body.toByteArray()); it.flush() }
    }
}
