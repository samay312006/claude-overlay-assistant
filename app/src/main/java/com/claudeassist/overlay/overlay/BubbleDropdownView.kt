package com.claudeassist.overlay.overlay

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The animated action dropdown shown when the bubble is tapped: a vertical stack
 * of action pills that "falls down" from the bubble with a staggered slide+fade.
 * Pure view; the controller positions it and supplies [MenuItem]s.
 */
class BubbleDropdownView(context: Context, items: List<MenuItem>) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val rows = ArrayList<TextView>()

    init {
        orientation = VERTICAL
        items.forEach { item ->
            val row = pill(item)
            rows.add(row)
            addView(row, LayoutParams(dp(190), LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }
    }

    /** Staggered slide-down + fade-in; call after the view is added to the window. */
    fun animateIn() {
        rows.forEachIndexed { i, row ->
            row.alpha = 0f
            row.translationY = -dp(12).toFloat()
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * 40L)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun pill(item: MenuItem): TextView = TextView(context).apply {
        text = "${item.icon}  ${item.label}" + if (item.enabled) "" else "   (soon)"
        setTextColor(if (item.enabled) Color.parseColor("#202124") else Color.parseColor("#9AA0A6"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(com.claudeassist.overlay.R.drawable.panel_bg)
        val h = dp(16); val v = dp(12); setPadding(h, v, h, v)
        elevation = dp(6).toFloat()
        isClickable = item.enabled
        if (item.enabled) setOnClickListener { item.onClick() }
    }

    private fun dp(v: Int) = (v * density).toInt()
}
