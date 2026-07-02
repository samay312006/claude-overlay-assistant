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

    companion object {
        const val DEFAULT_BRAIN_ID = "claude-app"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_ACTIVE_BRAIN = "active_brain_id"
        private const val KEY_ATTACH_SCREENSHOT = "attach_screenshot"
        private const val KEY_SAVE_THUMBNAILS = "save_thumbnails"
    }
}
