package com.claudeassist.overlay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import com.claudeassist.overlay.R
import kotlin.math.abs

/**
 * The draggable floating bubble. Distinguishes a tap (→ open the ask panel)
 * from a drag (→ reposition), and reports drag deltas so the controller can
 * move the window and edge-snap on release.
 */
@SuppressLint("ClickableViewAccessibility")
class BubbleView(
    context: Context,
    private val onTap: () -> Unit,
    private val onDragBy: (dx: Int, dy: Int) -> Unit,
    private val onDragEnd: () -> Unit,
) : FrameLayout(context) {

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private val touchSlop = 16f * resources.displayMetrics.density

    init {
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble)
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bubble_bg)
        }
        val size = (52 * resources.displayMetrics.density).toInt()
        addView(icon, LayoutParams(size, size))

        setOnTouchListener { _, event -> handleTouch(event) }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                lastRawX = event.rawX; lastRawY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                if (!dragging &&
                    (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop)
                ) {
                    dragging = true
                }
                if (dragging) {
                    onDragBy(dx.toInt(), dy.toInt())
                    lastRawX = event.rawX; lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) onDragEnd() else onTap()
                return true
            }
        }
        return false
    }
}
