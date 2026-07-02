package com.claudeassist.overlay.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.claudeassist.overlay.core.uitree.UiSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Screen capture and UI-tree reads through the accessibility service. Uses
 * AccessibilityService.takeScreenshot() (API 30+), so there is no
 * MediaProjection consent dialog. The API is rate-limited to roughly one shot
 * per second; [takeScreenshot] retries once after a short delay on the interval
 * error.
 */
class ScreenReader(
    private val service: AssistAccessibilityService,
    private val query: UiQuery,
) {
    private val executor = Executors.newSingleThreadExecutor()

    fun uiTreeSnapshot(): UiSnapshot = query.snapshot()

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun takeScreenshot(): Bitmap? {
        captureOnce()?.let { return it }
        // Likely the ~1s interval limit; wait it out and try once more.
        delay(1100)
        return captureOnce()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureOnce(): Bitmap? = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bitmap = try {
                        result.hardwareBuffer.use { hb ->
                            Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                                // Copy off the hardware buffer so the bitmap is
                                // readable (software) and survives buffer release.
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    } catch (t: Throwable) {
                        null
                    }
                    if (cont.isActive) cont.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }
}
