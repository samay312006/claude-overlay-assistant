package com.claudeassist.overlay.overlay

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** "Asking Claude… don't touch the screen" strip shown during a driver run. */
class SessionBannerView(context: Context) : LinearLayout(context) {
    private val label: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundResource(com.claudeassist.overlay.R.drawable.banner_bg)
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        setPadding(pad, (8 * density).toInt(), pad, (8 * density).toInt())
        label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "Asking Claude… don't touch the screen"
        }
        addView(label)
    }

    fun setText(text: String) {
        label.text = text
    }
}
