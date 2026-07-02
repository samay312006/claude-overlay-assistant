package com.claudeassist.overlay.overlay

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** One entry in the bubble action menu. [enabled] false renders it dimmed/"soon". */
data class MenuItem(
    val icon: String,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The action menu shown when the bubble is tapped — the "burger" that opens the
 * assistant's functions (ask about screen, circle search, voice, open Claude…).
 * Pure view; the controller positions it and supplies the items.
 */
class BubbleMenuView(context: Context, items: List<MenuItem>) : LinearLayout(context) {

    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        setBackgroundResource(com.claudeassist.overlay.R.drawable.panel_bg)
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)

        items.forEach { item ->
            addView(row(item))
        }
    }

    private fun row(item: MenuItem): TextView = TextView(context).apply {
        text = "${item.icon}  ${item.label}" + if (item.enabled) "" else "   (soon)"
        setTextColor(if (item.enabled) Color.parseColor("#202124") else Color.parseColor("#9AA0A6"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER_VERTICAL
        val h = dp(12)
        val v = dp(12)
        setPadding(h, v, h, v)
        isClickable = item.enabled
        if (item.enabled) setOnClickListener { item.onClick() }
        layoutParams = LayoutParams(dp(240), LayoutParams.WRAP_CONTENT)
    }

    private fun dp(v: Int) = (v * density).toInt()
}
