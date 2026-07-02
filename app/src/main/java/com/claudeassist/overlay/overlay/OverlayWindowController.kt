package com.claudeassist.overlay.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the WindowManager windows: the always-present bubble and the on-demand
 * ask panel + session banner.
 *
 * IME protocol: the bubble is FLAG_NOT_FOCUSABLE (never steals touches from the
 * app below). The panel must be focusable for its EditText to receive the
 * keyboard, so it is added focusable; a Back key press on the focused panel
 * closes it (there is no Activity to consume Back otherwise).
 */
class OverlayWindowController(
    private val context: Context,
    private val onBubbleTap: () -> Unit,
    private val onSend: (String) -> Unit,
    private val onPanelClosed: () -> Unit,
    private val onRetry: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val overlayType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private lateinit var bubble: BubbleView
    private val bubbleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 240
    }

    private var panel: AskPanelView? = null
    private var banner: SessionBannerView? = null

    fun addBubble() {
        bubble = BubbleView(
            context,
            onTap = onBubbleTap,
            onDragBy = { dx, dy ->
                bubbleParams.x += dx
                bubbleParams.y += dy
                safeUpdate(bubble, bubbleParams)
            },
            onDragEnd = ::snapToEdge,
        )
        wm.addView(bubble, bubbleParams)
    }

    private fun snapToEdge() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val mid = screenWidth / 2
        bubbleParams.x = if (bubbleParams.x + bubble.width / 2 < mid) 24 else screenWidth - bubble.width - 24
        bubbleParams.y = max(0, min(bubbleParams.y, context.resources.displayMetrics.heightPixels - bubble.height))
        safeUpdate(bubble, bubbleParams)
    }

    fun showPanel(): AskPanelView {
        panel?.let { return it }
        val view = AskPanelView(
            context,
            onSend = onSend,
            onClose = ::hidePanel,
            onRetry = onRetry,
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            // Focusable so the EditText gets the keyboard; ADJUST_RESIZE keeps
            // the panel above the IME.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hidePanel(); true
            } else false
        }
        wm.addView(view, params)
        view.focusInput()
        panel = view
        return view
    }

    fun hidePanel() {
        panel?.let { safeRemove(it) }
        panel = null
        onPanelClosed()
    }

    fun panel(): AskPanelView? = panel

    fun showBanner(text: String? = null) {
        if (banner != null) {
            text?.let { banner?.setText(it) }
            return
        }
        val view = SessionBannerView(context)
        text?.let { view.setText(it) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = 40
        }
        wm.addView(view, params)
        banner = view
    }

    fun hideBanner() {
        banner?.let { safeRemove(it) }
        banner = null
    }

    fun destroy() {
        hidePanel()
        hideBanner()
        if (::bubble.isInitialized) safeRemove(bubble)
    }

    private fun safeUpdate(view: View, params: WindowManager.LayoutParams) {
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun safeRemove(view: View) {
        runCatching { wm.removeView(view) }
    }
}
