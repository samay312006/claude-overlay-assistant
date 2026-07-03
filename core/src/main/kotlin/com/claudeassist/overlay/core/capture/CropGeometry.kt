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
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for ((x, y) in bmpPoints) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if ((maxX - minX) < minPx || (maxY - minY) < minPx) return null
        val left = (minX - padPx).toInt().coerceIn(0, bmpW - 1)
        val top = (minY - padPx).toInt().coerceIn(0, bmpH - 1)
        val right = (maxX + padPx).toInt().coerceIn(left + 1, bmpW)
        val bottom = (maxY + padPx).toInt().coerceIn(top + 1, bmpH)
        return CropBounds(left, top, right, bottom)
    }
}
