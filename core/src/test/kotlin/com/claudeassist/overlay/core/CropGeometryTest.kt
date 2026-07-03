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
