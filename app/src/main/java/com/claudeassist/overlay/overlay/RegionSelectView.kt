package com.claudeassist.overlay.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.claudeassist.overlay.core.capture.CropGeometry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen capture surface for "circle search": shows a frozen screenshot,
 * dims it, and lets the user mark a region in one of two modes:
 *  - RECTANGLE: drag a box -> tight rectangular crop.
 *  - FREEHAND ("magic pencil"): draw a loop -> pixels inside the closed path are
 *    kept, everything outside is erased to WHITE, cropped to the stroke bounds.
 * On release the crop is handed back via [onDone]; Back / too-small cancels.
 */
class RegionSelectView(
    context: Context,
    private val screenshot: Bitmap,
    private val onDone: (Bitmap) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private enum class Mode { RECTANGLE, FREEHAND }
    private var mode = Mode.RECTANGLE

    private val density = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0xB0000000.toInt() }
    private val border = Paint().apply {
        color = Color.parseColor("#E8815A")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8815A")
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
    }
    private val hintBg = Paint().apply { color = 0x99000000.toInt() }

    // Toggle chip geometry (bottom-center); two pills side by side.
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private var rectChip = RectF()
    private var freeChip = RectF()

    // Rectangle drag state.
    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var selecting = false

    // Freehand path state (view space).
    private val pathPts = ArrayList<Pair<Float, Float>>()
    private val drawPath = Path()

    // True while a chip tap owns the current gesture, so the leftover MOVE/UP of
    // that same touch isn't misread as a draw (or a finish) by the mode handler.
    private var chipConsumedGesture = false

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(screenshot, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bmpPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)

        if (mode == Mode.RECTANGLE && selecting) {
            val dst = viewRect()
            canvas.drawBitmap(screenshot, bitmapRect(dst), dst, bmpPaint)
            canvas.drawRect(dst, border)
        } else if (mode == Mode.FREEHAND && pathPts.isNotEmpty()) {
            canvas.drawPath(drawPath, inkPaint)
        }

        drawHint(canvas)
        drawChips(canvas)
    }

    private fun drawHint(canvas: Canvas) {
        val label = when (mode) {
            Mode.RECTANGLE -> if (selecting) "Release to ask about this" else "Drag a box · Back to cancel"
            Mode.FREEHAND -> if (pathPts.isNotEmpty()) "Release to ask about this" else "Draw around it · Back to cancel"
        }
        val ty = 60f * density
        val tw = hintPaint.measureText(label)
        val pad = 12f * density
        canvas.drawRoundRect(
            width / 2f - tw / 2 - pad, ty - 20f * density, width / 2f + tw / 2 + pad, ty + 10f * density,
            10f * density, 10f * density, hintBg,
        )
        canvas.drawText(label, width / 2f, ty, hintPaint)
    }

    private fun drawChips(canvas: Canvas) {
        val chipW = 130f * density
        val chipH = 40f * density
        val gap = 10f * density
        val cy = height - 48f * density
        val totalW = chipW * 2 + gap
        val leftX = width / 2f - totalW / 2
        rectChip = RectF(leftX, cy - chipH / 2, leftX + chipW, cy + chipH / 2)
        freeChip = RectF(rectChip.right + gap, cy - chipH / 2, rectChip.right + gap + chipW, cy + chipH / 2)
        drawChip(canvas, rectChip, "▭ Rectangle", mode == Mode.RECTANGLE)
        drawChip(canvas, freeChip, "✏ Freehand", mode == Mode.FREEHAND)
    }

    private fun drawChip(canvas: Canvas, r: RectF, label: String, active: Boolean) {
        chipPaint.color = if (active) Color.parseColor("#E8815A") else 0xCC202124.toInt()
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, chipPaint)
        canvas.drawText(label, r.centerX(), r.centerY() + 5f * density, chipTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                chipConsumedGesture = false
                if (rectChip.contains(event.x, event.y)) {
                    switchTo(Mode.RECTANGLE); chipConsumedGesture = true; return true
                }
                if (freeChip.contains(event.x, event.y)) {
                    switchTo(Mode.FREEHAND); chipConsumedGesture = true; return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (chipConsumedGesture) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (chipConsumedGesture) { chipConsumedGesture = false; return true }
        }
        return when (mode) {
            Mode.RECTANGLE -> onRectTouch(event)
            Mode.FREEHAND -> onFreehandTouch(event)
        }
    }

    private fun switchTo(m: Mode) {
        mode = m
        selecting = false
        pathPts.clear()
        drawPath.reset()
        invalidate()
    }

    private fun onRectTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; curX = event.x; curY = event.y
                selecting = true; invalidate()
            }
            MotionEvent.ACTION_MOVE -> { curX = event.x; curY = event.y; invalidate() }
            MotionEvent.ACTION_UP -> finishRect()
            MotionEvent.ACTION_CANCEL -> { selecting = false; onCancel() }
        }
        return true
    }

    private fun onFreehandTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pathPts.clear(); drawPath.reset()
                pathPts.add(event.x to event.y)
                drawPath.moveTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                pathPts.add(event.x to event.y)
                drawPath.lineTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> finishFreehand()
            MotionEvent.ACTION_CANCEL -> { pathPts.clear(); drawPath.reset(); onCancel() }
        }
        return true
    }

    private fun finishRect() {
        val dst = viewRect()
        val minPx = 24 * density
        if (dst.width() < minPx || dst.height() < minPx) { onCancel(); return }
        val src = bitmapRect(dst)
        val crop = runCatching {
            Bitmap.createBitmap(screenshot, src.left, src.top, src.width(), src.height())
        }.getOrNull()
        if (crop == null) onCancel() else onDone(crop)
    }

    private fun finishFreehand() {
        val bmpPts = CropGeometry.scaleToBitmap(pathPts, width, height, screenshot.width, screenshot.height)
        val bounds = CropGeometry.boundsOf(
            bmpPoints = bmpPts,
            bmpW = screenshot.width,
            bmpH = screenshot.height,
            padPx = (6 * density).roundToInt(),
            minPx = (24 * density).roundToInt(),
        )
        if (bounds == null) { onCancel(); return }
        val crop = runCatching {
            val out = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.WHITE) // erased-to-white outside the path
            // Path in bitmap space, translated to the crop origin.
            val bmpPath = Path()
            bmpPts.forEachIndexed { i, (x, y) ->
                val px = x - bounds.left
                val py = y - bounds.top
                if (i == 0) bmpPath.moveTo(px, py) else bmpPath.lineTo(px, py)
            }
            bmpPath.close()
            c.save()
            c.clipPath(bmpPath)
            // Draw the corresponding slice of the screenshot into the clip.
            val srcRect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
            val dstRect = Rect(0, 0, bounds.width, bounds.height)
            c.drawBitmap(screenshot, srcRect, dstRect, bmpPaint)
            c.restore()
            out
        }.getOrNull()
        if (crop == null) onCancel() else onDone(crop)
    }

    private fun viewRect(): RectF = RectF(
        min(startX, curX), min(startY, curY), max(startX, curX), max(startY, curY),
    )

    private fun bitmapRect(dst: RectF): Rect {
        val sx = screenshot.width.toFloat() / width
        val sy = screenshot.height.toFloat() / height
        val l = (dst.left * sx).roundToInt().coerceIn(0, screenshot.width - 1)
        val t = (dst.top * sy).roundToInt().coerceIn(0, screenshot.height - 1)
        val r = (dst.right * sx).roundToInt().coerceIn(l + 1, screenshot.width)
        val b = (dst.bottom * sy).roundToInt().coerceIn(t + 1, screenshot.height)
        return Rect(l, t, r, b)
    }
}
