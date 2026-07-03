package com.claudeassist.overlay.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A compact WhatsApp-style chat surface for the overlay: a header with new-chat
 * / more / close, a scrollable stream of message bubbles (user right, Claude
 * left), a typing indicator, and a rounded input row with a send button.
 *
 * Pure view — state lives in the service and is pushed via [render]; the view
 * only reports user intents through its callbacks.
 */
class AskPanelView(
    context: Context,
    private val onSend: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onNewChat: () -> Unit,
    private val onMore: () -> Unit,
    private val onOpenInClaude: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val messagesContainer: LinearLayout
    private val scroll: ScrollView
    private val input: EditText
    private val emptyHint: TextView

    /** Invoked when the user taps outside the panel (FLAG_WATCH_OUTSIDE_TOUCH). */
    var onOutsideTouch: (() -> Unit)? = null

    private val userColor = Color.parseColor("#E8815A")
    private val userTextColor = Color.WHITE
    private val claudeColor = Color.parseColor("#F1F3F4")
    private val claudeTextColor = Color.parseColor("#202124")
    private val errorColor = Color.parseColor("#FDECEA")
    private val errorTextColor = Color.parseColor("#B00020")

    init {
        orientation = VERTICAL
        background = card(Color.WHITE, 20f)
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        elevation = dp(8).toFloat()

        // ---- Header ----
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Claude"
            setTextColor(Color.parseColor("#202124"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f)
        })
        header.addView(iconButton("✎") { onNewChat() })   // new chat
        header.addView(iconButton("⋯") { onMore() })       // more actions
        header.addView(iconButton("✕") { onClose() })      // close
        addView(header, matchWrap())

        // ---- Messages ----
        scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        messagesContainer = LinearLayout(context).apply { orientation = VERTICAL }
        emptyHint = TextView(context).apply {
            text = "Ask about what's on your screen."
            setTextColor(Color.parseColor("#9AA0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
        }
        messagesContainer.addView(emptyHint)
        scroll.addView(messagesContainer)
        addView(scroll, LinearLayout.LayoutParams(matchParent(), dp(300)).apply { topMargin = dp(8) })

        // ---- Input row ----
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(context).apply {
            hint = "Message"
            background = card(Color.parseColor("#F1F3F4"), 22f)
            setHintTextColor(Color.parseColor("#9AA0A6"))
            setTextColor(Color.parseColor("#202124"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            val h = dp(14); val v = dp(10)
            setPadding(h, v, h, v)
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, wrap(), 1f))
        inputRow.addView(sendButton(), LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            leftMargin = dp(8)
        })
        addView(inputRow, matchWrap().apply { topMargin = dp(8) })
    }

    // ---- Public API ----

    /** Rebuild the message stream and typing indicator, then scroll to bottom. */
    fun render(messages: List<ChatMessage>, typing: Boolean) {
        messagesContainer.removeAllViews()
        if (messages.isEmpty() && !typing) {
            messagesContainer.addView(emptyHint)
        } else {
            messages.forEach { messagesContainer.addView(bubbleFor(it)) }
            if (typing) messagesContainer.addView(typingBubble())
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun inputText(): String = input.text.toString().trim()
    fun clearInput() { input.setText("") }
    fun focusInput() { input.requestFocus() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.onTouchEvent(event)
    }

    // ---- Bubble builders ----

    private fun bubbleFor(msg: ChatMessage): View {
        val isUser = msg.role == ChatMessage.Role.USER
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            val v = dp(3)
            setPadding(0, v, 0, v)
            // Full-width row so END/START gravity actually aligns the bubble.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val bubble = LinearLayout(context).apply {
            orientation = VERTICAL
            background = when {
                msg.error -> card(errorColor, 16f)
                isUser -> card(userColor, 16f)
                else -> card(claudeColor, 16f)
            }
            val h = dp(12); val vv = dp(8)
            setPadding(h, vv, h, vv)
        }

        msg.thumb?.let { thumb ->
            bubble.addView(ImageView(context).apply {
                setImageBitmap(thumb)
                adjustViewBounds = true
                maxHeight = dp(110)
                background = card(Color.parseColor("#33000000"), 10f)
            }, LinearLayout.LayoutParams(wrap(), wrap()).apply { bottomMargin = dp(6) })
        }

        bubble.addView(
            TextView(context).apply {
                text = msg.text
                setTextColor(
                    when {
                        msg.error -> errorTextColor
                        isUser -> userTextColor
                        else -> claudeTextColor
                    },
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextIsSelectable(true)
                maxWidth = dp(232) // wrap long lines instead of stretching edge-to-edge
            },
            // Explicit WRAP_CONTENT: a vertical LinearLayout defaults children to
            // MATCH_PARENT width, which would shrink the text to the narrow
            // screenshot's width and wrap one word per line.
            LinearLayout.LayoutParams(wrap(), wrap()),
        )

        if (msg.truncated) {
            bubble.addView(
                TextView(context).apply {
                    text = "Open in Claude ›"
                    setTextColor(Color.parseColor("#1A73E8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, dp(6), 0, 0)
                    setOnClickListener { onOpenInClaude() }
                },
                LinearLayout.LayoutParams(wrap(), wrap()),
            )
        }

        row.addView(bubble, LinearLayout.LayoutParams(wrap(), wrap()))
        return row
    }

    private fun typingBubble(): View = LinearLayout(context).apply {
        gravity = Gravity.START
        val v = dp(3); setPadding(0, v, 0, v)
        addView(TextView(context).apply {
            text = "Claude is typing…"
            setTextColor(Color.parseColor("#5F6368"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = card(claudeColor, 16f)
            val h = dp(12); val vv = dp(8); setPadding(h, vv, h, vv)
        })
    }

    // ---- Small view helpers ----

    private fun iconButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.parseColor("#5F6368"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        val p = dp(8); setPadding(p, p, p, p)
        setOnClickListener { onClick() }
    }

    private fun sendButton(): View = TextView(context).apply {
        text = "➤"
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(userColor)
        }
        setOnClickListener {
            val q = inputText()
            if (q.isNotEmpty()) onSend(q)
        }
    }

    private fun card(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun dp(v: Int) = (v * density).toInt()
    private fun wrap() = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun matchParent() = LinearLayout.LayoutParams.MATCH_PARENT
    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
}
