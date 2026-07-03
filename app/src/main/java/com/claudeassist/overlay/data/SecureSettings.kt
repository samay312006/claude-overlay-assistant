package com.claudeassist.overlay.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Small typed settings store. Backed by EncryptedSharedPreferences (future
 * connectors will hold API keys here); falls back to plain prefs on the rare
 * devices where the keystore-backed prefs fail to initialize.
 */
class SecureSettings(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        context.getSharedPreferences("settings_fallback", Context.MODE_PRIVATE)
    }

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING_DONE, value) }

    var activeBrainId: String
        get() = prefs.getString(KEY_ACTIVE_BRAIN, DEFAULT_BRAIN_ID) ?: DEFAULT_BRAIN_ID
        set(value) = prefs.edit { putString(KEY_ACTIVE_BRAIN, value) }

    var attachScreenshot: Boolean
        get() = prefs.getBoolean(KEY_ATTACH_SCREENSHOT, true)
        set(value) = prefs.edit { putBoolean(KEY_ATTACH_SCREENSHOT, value) }

    var saveThumbnails: Boolean
        get() = prefs.getBoolean(KEY_SAVE_THUMBNAILS, false)
        set(value) = prefs.edit { putBoolean(KEY_SAVE_THUMBNAILS, value) }

    // --- PC relay (server-hub over Tailscale) ---

    /** Tailscale hostname or 100.x IP of the PC running server-hub. */
    var relayHost: String
        get() = prefs.getString(KEY_RELAY_HOST, "") ?: ""
        set(value) = prefs.edit { putString(KEY_RELAY_HOST, value.trim()) }

    var relayPort: Int
        get() = prefs.getInt(KEY_RELAY_PORT, 8088)
        set(value) = prefs.edit { putInt(KEY_RELAY_PORT, value) }

    /** server-hub HUB_PASSWORD (owner). */
    var relayPassword: String
        get() = prefs.getString(KEY_RELAY_PASSWORD, "") ?: ""
        set(value) = prefs.edit { putString(KEY_RELAY_PASSWORD, value) }

    var relayModel: String
        get() = prefs.getString(KEY_RELAY_MODEL, "sonnet") ?: "sonnet"
        set(value) = prefs.edit { putString(KEY_RELAY_MODEL, value) }

    companion object {
        const val DEFAULT_BRAIN_ID = "claude-app"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_ACTIVE_BRAIN = "active_brain_id"
        private const val KEY_ATTACH_SCREENSHOT = "attach_screenshot"
        private const val KEY_SAVE_THUMBNAILS = "save_thumbnails"
        private const val KEY_RELAY_HOST = "relay_host"
        private const val KEY_RELAY_PORT = "relay_port"
        private const val KEY_RELAY_PASSWORD = "relay_password"
        private const val KEY_RELAY_MODEL = "relay_model"
    }
}
