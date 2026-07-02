package com.claudeassist.overlay.overlay

import android.graphics.Bitmap

/** One message in the overlay chat. [thumb] is the attached screenshot (user
 *  messages only); [truncated] marks a Claude answer shortened by the app's
 *  notification so the UI can offer "Open in Claude". */
data class ChatMessage(
    val role: Role,
    val text: String,
    val thumb: Bitmap? = null,
    val truncated: Boolean = false,
    val error: Boolean = false,
) {
    enum class Role { USER, CLAUDE }
}
