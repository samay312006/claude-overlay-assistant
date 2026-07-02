package com.claudeassist.overlay.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * The expandable ask panel: optional screenshot thumbnail, a question field,
 * send/close, and a scrollable answer area with progress + error/retry states.
 * Pure view; the controller wires callbacks and pushes state.
 */
class AskPanelView(
    context: Context,
    private val onSend: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onRetry: () -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val thumbnail: ImageView
    private val questionField: EditText
    private val sendButton: Button
    private val answerText: TextView
    private val progress: ProgressBar
    private val retryButton: Button
    private var lastPrompt: String = ""

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.claudeassist.overlay.R.drawable.panel_bg)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Ask Claude"
            setTextColor(Color.parseColor("#202124"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, wrap(), 1f)
        })
        header.addView(Button(context).apply {
            text = "✕"
            setOnClickListener { onClose() }
        })
        root.addView(header, matchWrap())

        thumbnail = ImageView(context).apply {
            visibility = View.GONE
            adjustViewBounds = true
            maxHeight = dp(120)
        }
        root.addView(thumbnail, matchWrap().apply { topMargin = dp(8) })

        questionField = EditText(context).apply {
            hint = "Ask about your screen…"
            setTextColor(Color.parseColor("#202124"))
            setHintTextColor(Color.parseColor("#80000000"))
            maxLines = 3
        }
        root.addView(questionField, matchWrap().apply { topMargin = dp(8) })

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        progress = ProgressBar(context).apply {
            visibility = View.GONE
            val s = dp(24)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = dp(12) }
        }
        retryButton = Button(context).apply {
            text = "Retry"
            visibility = View.GONE
            setOnClickListener { onRetry() }
        }
        sendButton = Button(context).apply {
            text = "Send"
            setOnClickListener {
                val q = questionField.text.toString().trim()
                if (q.isNotEmpty()) { lastPrompt = q; onSend(q) }
            }
        }
        actions.addView(progress)
        actions.addView(retryButton)
        actions.addView(sendButton)
        root.addView(actions, matchWrap().apply { topMargin = dp(8) })

        answerText = TextView(context).apply {
            setTextColor(Color.parseColor("#202124"))
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            maxHeight = dp(260)
        }
        root.addView(answerText, matchWrap().apply { topMargin = dp(10) })

        addView(root, LayoutParams(dp(320), LayoutParams.WRAP_CONTENT))
    }

    fun setThumbnail(bitmap: Bitmap?) {
        if (bitmap == null) {
            thumbnail.visibility = View.GONE
        } else {
            thumbnail.setImageBitmap(bitmap)
            thumbnail.visibility = View.VISIBLE
        }
    }

    fun showSending() {
        progress.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        sendButton.isEnabled = false
        answerText.text = ""
    }

    fun showAnswer(text: String) {
        answerText.setTextColor(Color.parseColor("#202124"))
        answerText.text = text
    }

    fun showDone(text: String, timedOut: Boolean) {
        progress.visibility = View.GONE
        sendButton.isEnabled = true
        answerText.text = if (timedOut) "$text\n\n(Answer may be incomplete.)" else text
    }

    fun showError(message: String, recoverable: Boolean) {
        progress.visibility = View.GONE
        sendButton.isEnabled = true
        retryButton.visibility = if (recoverable) View.VISIBLE else View.GONE
        answerText.setTextColor(Color.parseColor("#B00020"))
        answerText.text = message
    }

    fun currentPrompt(): String = lastPrompt

    fun focusInput() {
        questionField.requestFocus()
    }

    private fun dp(v: Int) = (v * density).toInt()
    private fun wrap() = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
}
