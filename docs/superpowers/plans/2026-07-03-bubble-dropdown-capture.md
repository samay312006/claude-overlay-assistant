# Bubble Dropdown + Unified Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the overlay bubble open an animated action dropdown (Ask / Capture / Open Claude / Voice) instead of the chat directly, and add a unified Capture surface with rectangle + magic-pencil (masked-to-white) freehand modes whose crop attaches as a clearable preview to the next ask.

**Architecture:** The pure crop **geometry** (scale view points → bitmap space, compute a padded/clamped bounding box, min-size validity) goes in the JVM-testable `:core` module. The Android drawing/masking (`Bitmap`/`Canvas`/`Path`/`clipPath`) and all overlay `View`s live in `:app` and are verified on-device. The existing `pendingAttachment` → `onSend` → `brain.ask(image, prompt)` path is reused unchanged; only how a crop is produced and previewed changes.

**Tech Stack:** Kotlin, Android Views + WindowManager (overlay), JUnit (core unit tests), Gradle (`./gradlew.bat`).

## Global Constraints

- minSdk 30, targetSdk 35; Kotlin; classic Views for the overlay (NOT Compose).
- `:core` is pure JVM — no `android.*` imports. `:app` depends on `:core`.
- Do NOT change the brain layer, drivers, `ClaudeAppSelectors`, or the answer-capture hybrid.
- Masked-out freehand pixels are erased to **white** (not transparent).
- Accent color is `#E8815A`; Claude-gray `#F1F3F4`; text `#202124`; muted `#9AA0A6` (match `AskPanelView`).
- Overlay windows use `TYPE_APPLICATION_OVERLAY`; outside-dismiss uses a transparent `ACTION_DOWN` layer (the existing `menuScrim` pattern), never a full-screen opaque scrim.
- Build check for every `:app` task: `./gradlew.bat :app:assembleDebug` exits 0. Device install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Commit after every task.

---

### Task 1: Pure freehand crop geometry (core, unit-tested)

**Files:**
- Create: `core/src/main/kotlin/com/claudeassist/overlay/core/capture/CropGeometry.kt`
- Test: `core/src/test/kotlin/com/claudeassist/overlay/core/CropGeometryTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class CropBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)` with `val width: Int` and `val height: Int`.
  - `object CropGeometry` with:
    - `fun scaleToBitmap(points: List<Pair<Float, Float>>, viewW: Int, viewH: Int, bmpW: Int, bmpH: Int): List<Pair<Float, Float>>` — maps view-space points to bitmap-pixel space (linear scale-to-fill, same convention as the existing rectangle path).
    - `fun boundsOf(bmpPoints: List<Pair<Float, Float>>, bmpW: Int, bmpH: Int, padPx: Int, minPx: Int): CropBounds?` — tight bounding box of the given bitmap-space points, expanded by `padPx`, clamped to `[0,bmpW]×[0,bmpH]`, guaranteeing `right > left` and `bottom > top`; returns `null` if fewer than 2 points or if the (pre-pad) box is smaller than `minPx` on either side.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.claudeassist.overlay.core

import com.claudeassist.overlay.core.capture.CropBounds
import com.claudeassist.overlay.core.capture.CropGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropGeometryTest {

    @Test fun scalesViewPointsToBitmapSpace() {
        // View 100x200 -> bitmap 1000x2000 is a 10x scale on both axes.
        val scaled = CropGeometry.scaleToBitmap(
            points = listOf(10f to 20f, 50f to 80f),
            viewW = 100, viewH = 200, bmpW = 1000, bmpH = 2000,
        )
        assertEquals(100f, scaled[0].first, 0.001f)
        assertEquals(200f, scaled[0].second, 0.001f)
        assertEquals(500f, scaled[1].first, 0.001f)
        assertEquals(800f, scaled[1].second, 0.001f)
    }

    @Test fun boundsAreTightPaddedAndClamped() {
        // Points span x[100..500], y[200..800]; pad 10 -> [90..510]x[190..810].
        val b = CropGeometry.boundsOf(
            bmpPoints = listOf(100f to 200f, 500f to 800f, 300f to 500f),
            bmpW = 1000, bmpH = 2000, padPx = 10, minPx = 24,
        )
        assertEquals(CropBounds(90, 190, 510, 810), b)
    }

    @Test fun boundsClampToBitmapEdges() {
        // Pad pushes past 0 and past the far edge -> clamp to [0,bmpW]/[0,bmpH].
        val b = CropGeometry.boundsOf(
            bmpPoints = listOf(5f to 5f, 995f to 1995f),
            bmpW = 1000, bmpH = 2000, padPx = 20, minPx = 24,
        )
        assertEquals(CropBounds(0, 0, 1000, 2000), b)
    }

    @Test fun tooSmallSelectionReturnsNull() {
        val b = CropGeometry.boundsOf(
            bmpPoints = listOf(100f to 100f, 110f to 108f), // 10x8, below minPx 24
            bmpW = 1000, bmpH = 2000, padPx = 0, minPx = 24,
        )
        assertNull(b)
    }

    @Test fun fewerThanTwoPointsReturnsNull() {
        assertNull(CropGeometry.boundsOf(listOf(1f to 1f), 100, 100, 0, 24))
        assertNull(CropGeometry.boundsOf(emptyList(), 100, 100, 0, 24))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :core:test --tests "com.claudeassist.overlay.core.CropGeometryTest"`
Expected: FAIL — `CropGeometry` / `CropBounds` unresolved (compile error).

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.claudeassist.overlay.core.capture

/** A crop rectangle in bitmap-pixel space, right/bottom exclusive. */
data class CropBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Pure geometry for the freehand ("magic pencil") capture: converting a drawn
 * path from view space to bitmap pixels and computing the crop box. No Android
 * dependencies so it can be unit-tested on the JVM; the actual masking/drawing
 * is done in the :app overlay layer.
 */
object CropGeometry {

    fun scaleToBitmap(
        points: List<Pair<Float, Float>>,
        viewW: Int,
        viewH: Int,
        bmpW: Int,
        bmpH: Int,
    ): List<Pair<Float, Float>> {
        if (viewW <= 0 || viewH <= 0) return points
        val sx = bmpW.toFloat() / viewW
        val sy = bmpH.toFloat() / viewH
        return points.map { (x, y) -> (x * sx) to (y * sy) }
    }

    fun boundsOf(
        bmpPoints: List<Pair<Float, Float>>,
        bmpW: Int,
        bmpH: Int,
        padPx: Int,
        minPx: Int,
    ): CropBounds? {
        if (bmpPoints.size < 2) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((x, y) in bmpPoints) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if ((maxX - minX) < minPx || (maxY - minY) < minPx) return null
        val left = (minX - padPx).toInt().coerceIn(0, bmpW - 1)
        val top = (minY - padPx).toInt().coerceIn(0, bmpH - 1)
        val right = (maxX + padPx).toInt().coerceIn(left + 1, bmpW)
        val bottom = (maxY + padPx).toInt().coerceIn(top + 1, bmpH)
        return CropBounds(left, top, right, bottom)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :core:test --tests "com.claudeassist.overlay.core.CropGeometryTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/claudeassist/overlay/core/capture/CropGeometry.kt core/src/test/kotlin/com/claudeassist/overlay/core/CropGeometryTest.kt
git commit -m "feat(core): pure freehand crop geometry with unit tests"
```

---

### Task 2: Capture surface with rectangle + freehand modes

Evolve `RegionSelectView` into a two-mode capture surface. Rectangle mode is the current behavior; freehand mode records a path, masks inside-to-white-outside using `CropGeometry` for the crop box, and returns the masked bitmap through the same `onDone(crop)` callback.

**Files:**
- Modify: `app/src/main/java/com/claudeassist/overlay/overlay/RegionSelectView.kt` (full rewrite below)

**Interfaces:**
- Consumes: `com.claudeassist.overlay.core.capture.CropGeometry`, `CropBounds` (Task 1).
- Produces: `RegionSelectView(context, screenshot: Bitmap, onDone: (Bitmap) -> Unit, onCancel: () -> Unit)` — unchanged constructor signature so `OverlayWindowController.showRegionSelect` keeps working; internally adds a mode toggle. Default mode `RECTANGLE`.

- [ ] **Step 1: Rewrite `RegionSelectView.kt`**

```kotlin
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
        // Chip taps switch mode (only on a clean DOWN+UP on a chip).
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (rectChip.contains(event.x, event.y)) { switchTo(Mode.RECTANGLE); return true }
            if (freeChip.contains(event.x, event.y)) { switchTo(Mode.FREEHAND); return true }
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
```

- [ ] **Step 2: Build**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/claudeassist/overlay/overlay/RegionSelectView.kt
git commit -m "feat(overlay): rectangle + magic-pencil freehand capture modes"
```

- [ ] **Step 4: On-device check (manual, after Task 5 wires it up)**
Deferred to the end-to-end check in Task 5 (capture is reachable only once the dropdown is wired).

---

### Task 3: Animated bubble dropdown view

Replace the tap-target menu with an animated dropdown that falls down from the bubble. Reuse the `MenuItem` data class already defined in `BubbleMenuView.kt`.

**Files:**
- Create: `app/src/main/java/com/claudeassist/overlay/overlay/BubbleDropdownView.kt`

**Interfaces:**
- Consumes: `MenuItem(icon, label, enabled, onClick)` from `BubbleMenuView.kt` (kept).
- Produces: `BubbleDropdownView(context, items: List<MenuItem>)` — a vertical `LinearLayout` that runs a staggered slide-down + fade-in on attach via `animateIn()` (called by the controller after `addView`).

- [ ] **Step 1: Create `BubbleDropdownView.kt`**

```kotlin
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
```

- [ ] **Step 2: Build**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/claudeassist/overlay/overlay/BubbleDropdownView.kt
git commit -m "feat(overlay): animated bubble dropdown view"
```

---

### Task 4: Controller — show/hide dropdown, drop the old menu

Add `showDropdown`/`hideDropdown` (mirroring the removed `toggleMenu`/`hideMenu` window + outside-dismiss-layer pattern) and remove the menu methods.

**Files:**
- Modify: `app/src/main/java/com/claudeassist/overlay/overlay/OverlayWindowController.kt`

**Interfaces:**
- Consumes: `BubbleDropdownView` (Task 3), `MenuItem`.
- Produces: `fun showDropdown(items: List<MenuItem>)`, `fun hideDropdown()`, `fun isDropdownOpen(): Boolean` on `OverlayWindowController`.

- [ ] **Step 1: Replace the menu fields and methods**

In `OverlayWindowController.kt`, change the menu fields:

```kotlin
    private var menu: BubbleMenuView? = null
    private var menuScrim: View? = null
    private var regionSelect: RegionSelectView? = null
```

to:

```kotlin
    private var dropdown: BubbleDropdownView? = null
    private var dropdownScrim: View? = null
    private var regionSelect: RegionSelectView? = null
```

- [ ] **Step 2: Replace `toggleMenu`/`hideMenu` with dropdown methods**

Delete the entire `fun toggleMenu(items: List<MenuItem>) { … }` and `fun hideMenu() { … }` blocks and insert:

```kotlin
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
```

- [ ] **Step 3: Update `destroy()` and any `hideMenu()` callers**

In `destroy()`, replace `hideMenu()` with `hideDropdown()`:

```kotlin
    fun destroy() {
        hideDropdown()
        hideRegionSelect()
        hidePanel()
        hideBanner()
        if (::bubble.isInitialized) safeRemove(bubble)
    }
```

- [ ] **Step 4: Verify no stray references remain**

Run: `git grep -n "toggleMenu\|hideMenu\|BubbleMenuView\|menuScrim" app/src`
Expected: only matches inside `BubbleMenuView.kt` itself (the `MenuItem` data class file, which we keep). If `OverlayService.kt` still references `hideMenu`, those are fixed in Task 5.

- [ ] **Step 5: Commit** (build happens in Task 5 once callers are updated)

```bash
git add app/src/main/java/com/claudeassist/overlay/overlay/OverlayWindowController.kt
git commit -m "feat(overlay): controller shows animated dropdown; drop old menu"
```

---

### Task 5: Wire OverlayService + clearable attachment preview; end-to-end

Bubble tap opens the dropdown (Ask / Capture / Open Claude / Voice). Ask opens the chat. Capture runs the capture surface. Remove `onMore` and the chat header `⋯`. Add a clearable attached-region preview above the chat input.

**Files:**
- Modify: `app/src/main/java/com/claudeassist/overlay/overlay/OverlayService.kt`
- Modify: `app/src/main/java/com/claudeassist/overlay/overlay/AskPanelView.kt`

**Interfaces:**
- Consumes: `OverlayWindowController.showDropdown/hideDropdown` (Task 4); `RegionSelectView` via existing `showRegionSelect` (Task 2).
- Produces: `AskPanelView` gains `fun showAttachment(bitmap: Bitmap?, onClear: () -> Unit)` — shows/hides a preview thumbnail row above the input with an ✕ that calls `onClear`. `AskPanelView` constructor loses the `onMore` parameter.

- [ ] **Step 1: AskPanelView — remove `onMore`, add attachment preview row**

In `AskPanelView.kt`, remove the `onMore` constructor param and the `⋯` header button.

Change the constructor:

```kotlin
class AskPanelView(
    context: Context,
    private val onSend: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onNewChat: () -> Unit,
    private val onOpenInClaude: () -> Unit,
) : LinearLayout(context) {
```

Remove this header line:

```kotlin
        header.addView(iconButton("⋯") { onMore() })       // more actions
```

Add an attachment-preview field near the other view fields (after `private val emptyHint: TextView`):

```kotlin
    private val attachmentRow: LinearLayout
    private val attachmentThumb: ImageView
```

Build the row in `init` — insert it BETWEEN the `scroll` `addView` and the input row `addView` (i.e. just before `// ---- Input row ----`):

```kotlin
        // ---- Attached-region preview (hidden unless a capture is pending) ----
        attachmentThumb = ImageView(context).apply {
            adjustViewBounds = true
            maxHeight = dp(64)
            background = card(Color.parseColor("#33000000"), 10f)
        }
        val clearBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#5F6368"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val p = dp(6); setPadding(p, p, p, p)
        }
        attachmentRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(TextView(context).apply {
                text = "Region attached"
                setTextColor(Color.parseColor("#5F6368"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(attachmentThumb, LinearLayout.LayoutParams(wrap(), dp(48)).apply { rightMargin = dp(6) })
            addView(clearBtn, LinearLayout.LayoutParams(wrap(), wrap()))
        }
        clearBtn.setOnClickListener { attachmentClear?.invoke() }
        addView(attachmentRow, matchWrap().apply { topMargin = dp(8) })
```

Add the callback field (near `onOutsideTouch`):

```kotlin
    private var attachmentClear: (() -> Unit)? = null
```

Add the public API (near `clearInput`/`focusInput`):

```kotlin
    /** Show (or hide, if null) the pending-capture preview above the input. */
    fun showAttachment(bitmap: Bitmap?, onClear: () -> Unit) {
        attachmentClear = onClear
        if (bitmap == null) {
            attachmentRow.visibility = View.GONE
            attachmentThumb.setImageBitmap(null)
        } else {
            attachmentThumb.setImageBitmap(bitmap)
            attachmentRow.visibility = View.VISIBLE
        }
    }
```

Add the import at the top:

```kotlin
import android.graphics.Bitmap
```

- [ ] **Step 2: OverlayService — dropdown on tap, capture action, preview wiring**

In `OverlayService.kt`:

Change the `controller` construction (remove `onMore`, keep the rest). Replace the `OverlayWindowController(...)` call's argument list so it no longer passes `onMore`, and change `onBubbleTap` to open the dropdown. Replace the `onCreate` controller block with:

```kotlin
        controller = OverlayWindowController(
            context = this,
            onBubbleTap = ::onBubbleTap,
            onSend = ::onSend,
            onPanelClosed = { /* nothing extra to tear down */ },
            onNewChat = ::onNewChat,
            onOpenInClaude = {
                controller.hidePanel()
                com.claudeassist.overlay.notifications.ClaudeNotificationListener.openLatest(this)
            },
        )
```

Replace `onBubbleTap` with a dropdown launcher:

```kotlin
    /** Bubble tap toggles the animated action dropdown. */
    private fun onBubbleTap() {
        if (controller.isDropdownOpen()) { controller.hideDropdown(); return }
        controller.showDropdown(
            listOf(
                MenuItem("💬", "Ask") { controller.hideDropdown(); openChat() },
                MenuItem("✏️", "Capture") { controller.hideDropdown(); onCapture() },
                MenuItem("🅰️", "Open Claude") {
                    controller.hideDropdown()
                    com.claudeassist.overlay.notifications.ClaudeNotificationListener.openLatest(this)
                },
                MenuItem("🎤", "Voice", enabled = false) {},
            ),
        )
    }

    /** Open the chat, resuming the session and grabbing a screenshot for a fresh one. */
    private fun openChat() {
        scope.launch {
            if (!sessionActive) {
                sessionImage = if (app.settings.attachScreenshot) captureScreenshot() else null
                sessionActive = true
            }
            val panel = controller.showPanel()
            panel.render(messages, typing = askJob?.isActive == true)
            panel.showAttachment(pendingAttachment) { clearPendingAttachment() }
            panel.focusInput()
        }
    }
```

Delete the entire `private fun onMore() { … }` method.

Rename/replace `onCircleSearch` with `onCapture` (same body; the surface now offers both modes) and wire the preview:

```kotlin
    /** Capture: freeze a screenshot, let the user mark a region (rectangle or
     *  freehand), attach the crop as a clearable preview on the next ask. */
    private fun onCapture() {
        controller.hidePanel()
        scope.launch {
            kotlinx.coroutines.delay(120)
            val shot = captureScreenshot() ?: return@launch
            controller.showRegionSelect(shot) { crop ->
                pendingAttachment = crop
                sessionActive = true
                val panel = controller.showPanel()
                panel.render(messages, typing = false)
                panel.showAttachment(crop) { clearPendingAttachment() }
                panel.focusInput()
            }
        }
    }

    private fun clearPendingAttachment() {
        pendingAttachment = null
        controller.panel()?.showAttachment(null) {}
    }
```

In `onSend`, after `pendingAttachment = null`, also clear the preview row. Change:

```kotlin
        val attach = pendingAttachment ?: if (firstUserMessage) sessionImage else null
        pendingAttachment = null
```

to:

```kotlin
        val attach = pendingAttachment ?: if (firstUserMessage) sessionImage else null
        pendingAttachment = null
        controller.panel()?.showAttachment(null) {}
```

- [ ] **Step 3: Build**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (If it fails on a lingering `onMore`/`hideMenu` reference, fix that reference — all callers are listed above.)

- [ ] **Step 4: Run core tests (regression)**

Run: `./gradlew.bat :core:test`
Expected: BUILD SUCCESSFUL (all core tests, incl. `CropGeometryTest`, pass).

- [ ] **Step 5: Install and device-verify the whole feature**

Run:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Manual checks on device:
1. Tap bubble → dropdown falls down with staggered animation (Ask / Capture / Open Claude / Voice-soon).
2. Tap bubble again (or outside) → dropdown closes; bubble stays on top.
3. Ask → chat opens, resumes prior messages.
4. Capture → dimmed frozen screenshot with ▭ Rectangle / ✏ Freehand chips.
   - Rectangle: drag a box → chat reopens, "Region attached" preview shows the rect crop → type → answer references it.
   - Freehand: switch to ✏, draw a loop around an object → chat reopens, preview shows the masked (white-outside) crop → type → answer references it.
   - ✕ on the preview clears it; sending without a pending crop behaves as before.
   - Back on the capture surface cancels; a tiny box/scribble cancels.
5. Open Claude → jumps to the Claude app.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/claudeassist/overlay/overlay/OverlayService.kt app/src/main/java/com/claudeassist/overlay/overlay/AskPanelView.kt
git commit -m "feat(overlay): bubble dropdown launcher + clearable capture preview"
```

---

### Task 6: Remove the now-unused BubbleMenuView (keep MenuItem)

`MenuItem` is still used by the dropdown; `BubbleMenuView` (the class) is dead. Move `MenuItem` to its own file and delete the old view to avoid confusion.

**Files:**
- Create: `app/src/main/java/com/claudeassist/overlay/overlay/MenuItem.kt`
- Delete: `app/src/main/java/com/claudeassist/overlay/overlay/BubbleMenuView.kt`

- [ ] **Step 1: Create `MenuItem.kt`**

```kotlin
package com.claudeassist.overlay.overlay

/** One entry in the bubble action dropdown. [enabled] false renders it dimmed/"soon". */
data class MenuItem(
    val icon: String,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)
```

- [ ] **Step 2: Delete the old view**

```bash
git rm app/src/main/java/com/claudeassist/overlay/overlay/BubbleMenuView.kt
```

- [ ] **Step 3: Build**

Run: `./gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no references to `BubbleMenuView` remain).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/claudeassist/overlay/overlay/MenuItem.kt
git commit -m "refactor(overlay): extract MenuItem, remove dead BubbleMenuView"
```

---

## Self-Review

**Spec coverage:**
- Animated dropdown on bubble tap (Ask/Capture/Open Claude/Voice) → Tasks 3, 4, 5. ✓
- Removal of chat header ⋯ menu and `onMore` → Tasks 4, 5, 6. ✓
- Unified capture surface, rectangle + freehand modes → Task 2. ✓
- Freehand masked-to-white, cropped to stroke bounds → Tasks 1 (geometry) + 2 (drawing). ✓
- Coordinate scaling identical to rectangle path → Task 1 `scaleToBitmap` + Task 2 reuse of `bitmapRect`. ✓
- Clearable attached-region preview above input → Task 5. ✓
- No brain/driver/selector changes → none touched. ✓
- Pure mask geometry isolated + unit-tested → Task 1. ✓

**Placeholder scan:** none — every code step shows full code; commands have expected output.

**Type consistency:** `MenuItem` (icon, label, enabled, onClick) consistent across Tasks 3/5/6. `showDropdown/hideDropdown/isDropdownOpen` consistent Tasks 4/5. `RegionSelectView(context, screenshot, onDone, onCancel)` unchanged so `showRegionSelect` (Task 2/5) matches. `showAttachment(Bitmap?, () -> Unit)` consistent Task 5. `CropGeometry.scaleToBitmap`/`boundsOf` + `CropBounds.width/height` consistent Tasks 1/2.

**Note:** `RegionSelectView` keeps its constructor and `showRegionSelect` name (rather than renaming to `CaptureView`) so the controller surface is untouched — smaller, lower-risk diff than the spec's optional rename. The user-facing behavior matches the spec.
