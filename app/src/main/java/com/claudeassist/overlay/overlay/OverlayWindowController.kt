package com.claudeassist.overlay.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the WindowManager windows: the always-on bubble, the chat panel, a
 * transparent scrim behind the panel (tap-outside-to-close), and the session
 * banner.
 *
 * Layout intent: the chat is anchored to the bubble and follows it when dragged;
 * the bubble is always raised above the chat; tapping outside the chat closes it
 * (state is kept, so reopening resumes).
 */
class OverlayWindowController(
    private val context: Context,
    private val onBubbleTap: () -> Unit,
    private val onSend: (String) -> Unit,
    private val onPanelClosed: () -> Unit,
    private val onNewChat: () -> Unit,
    private val onMore: () -> Unit,
    private val onOpenInClaude: () -> Unit,
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
    private var panelParams: WindowManager.LayoutParams? = null
    private var scrim: View? = null
    private var banner: SessionBannerView? = null
    private var dropdown: BubbleDropdownView? = null
    private var dropdownScrim: View? = null
    private var regionSelect: RegionSelectView? = null

    private val panelWidthDp = 320

    /** While an ask is running, ignore outside taps so the chat doesn't close
     *  when Claude briefly comes to the foreground. */
    var suppressOutsideClose = false

    fun addBubble() {
        bubble = BubbleView(
            context,
            onTap = onBubbleTap,
            onDragBy = { dx, dy ->
                bubbleParams.x += dx
                bubbleParams.y += dy
                safeUpdate(bubble, bubbleParams)
                movePanelToBubble()
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
        movePanelToBubble()
    }

    fun isPanelOpen(): Boolean = panel != null

    /** Show the chat panel anchored to the bubble. Tap-outside closes it via
     *  FLAG_WATCH_OUTSIDE_TOUCH — NOT a full-screen scrim, which would fully
     *  occlude the app we drive and drop it from the accessibility window list. */
    fun showPanel(): AskPanelView {
        panel?.let { return it }

        val view = AskPanelView(
            context,
            onSend = onSend,
            onClose = ::hidePanel,
            onNewChat = onNewChat,
            onMore = onMore,
            onOpenInClaude = onOpenInClaude,
        )
        view.onOutsideTouch = { if (!suppressOutsideClose) hidePanel() }
        val params = WindowManager.LayoutParams(
            dp(panelWidthDp),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            // Focusable for the keyboard; NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH
            // delivers an ACTION_OUTSIDE on taps outside the panel without an
            // occluding window.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hidePanel(); true
            } else false
        }
        panelParams = params
        wm.addView(view, params)
        panel = view
        movePanelToBubble()
        raiseBubble()
        return view
    }

    /** Keep the chat pinned near the bubble as it moves / snaps. */
    private fun movePanelToBubble() {
        val p = panelParams ?: return
        val view = panel ?: return
        val screenW = context.resources.displayMetrics.widthPixels
        val panelW = dp(panelWidthDp)
        // Prefer left-aligned to the bubble; clamp within the screen.
        p.x = max(8, min(bubbleParams.x, screenW - panelW - 8))
        p.y = bubbleParams.y + dp(64)
        safeUpdate(view, p)
    }

    /** Re-add the bubble so it draws above the chat panel. */
    private fun raiseBubble() {
        if (!::bubble.isInitialized) return
        safeRemove(bubble)
        wm.addView(bubble, bubbleParams)
    }

    fun hidePanel() {
        panel?.let { safeRemove(it) }
        panel = null
        panelParams = null
        scrim?.let { safeRemove(it) }
        scrim = null
        onPanelClosed()
    }

    fun panel(): AskPanelView? = panel

    fun isDropdownOpen(): Boolean = dropdown != null

    /** Show the animated action dropdown anchored under the bubble. An outside
     *  tap closes ONLY the dropdown (its own transparent dismiss layer). */
    fun showDropdown(items: List<MenuItem>) {
        if (dropdown != null) { hideDropdown(); return }

        val scrimView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) { hideDropdown(); true } else false
            }
        }
        wm.addView(
            scrimView,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ),
        )
        dropdownScrim = scrimView

        val view = BubbleDropdownView(context, items)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = max(8, min(bubbleParams.x, context.resources.displayMetrics.widthPixels - dp(200)))
            y = bubbleParams.y + dp(64)
        }
        wm.addView(view, params)
        dropdown = view
        raiseBubble()
        view.animateIn()
    }

    fun hideDropdown() {
        dropdown?.let { safeRemove(it) }
        dropdown = null
        dropdownScrim?.let { safeRemove(it) }
        dropdownScrim = null
    }

    /** Full-screen region selector for circle search (focusable for Back-to-cancel). */
    fun showRegionSelect(screenshot: android.graphics.Bitmap, onDone: (android.graphics.Bitmap) -> Unit) {
        hideRegionSelect()
        val view = RegionSelectView(
            context,
            screenshot,
            onDone = { crop -> hideRegionSelect(); onDone(crop) },
            onCancel = { hideRegionSelect() },
        )
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hideRegionSelect(); true
            } else false
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            0, // focusable + touchable (modal): capture the drag and the Back key
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(view, params)
        view.requestFocus()
        regionSelect = view
    }

    fun hideRegionSelect() {
        regionSelect?.let { safeRemove(it) }
        regionSelect = null
    }

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
        hideDropdown()
        hideRegionSelect()
        hidePanel()
        hideBanner()
        if (::bubble.isInitialized) safeRemove(bubble)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun safeUpdate(view: View, params: WindowManager.LayoutParams) {
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun safeRemove(view: View) {
        runCatching { wm.removeView(view) }
    }
}
