package com.localscanner.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeGeometryTest {
    private val eps = 0.01f

    private fun assertPt(expectedX: Float, expectedY: Float, p: Pt) {
        assertEquals(expectedX, p.x, eps)
        assertEquals(expectedY, p.y, eps)
    }

    @Test fun qrBoxExtendsBeyondFinderCentres() {
        val box = CodeGeometry.corners("QR_CODE", listOf(Pt(0f, 100f), Pt(0f, 0f), Pt(100f, 0f)))
        assertEquals(4, box.size)
        assertPt(-18f, -18f, box[0])
        assertPt(118f, -18f, box[1])
        assertPt(118f, 118f, box[2])
        assertPt(-18f, 118f, box[3])
    }

    @Test fun rotatedQrBoxStaysAParallelogram() {
        val box = CodeGeometry.corners("QR_CODE", listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f)))
        assertEquals(4, box.size)
        val cx = box.map { it.x }.average().toFloat()
        val cy = box.map { it.y }.average().toFloat()
        assertEquals(50f, cx, eps)
        assertEquals(50f, cy, eps)
    }

    @Test fun barcodeScanLineBecomesABox() {
        val box = CodeGeometry.corners("EAN_13", listOf(Pt(0f, 0f), Pt(100f, 0f)))
        assertEquals(4, box.size)
        assertEquals(-6f, box.minOf { it.x }, eps)
        assertEquals(106f, box.maxOf { it.x }, eps)
        assertEquals(-25f, box.minOf { it.y }, eps)
        assertEquals(25f, box.maxOf { it.y }, eps)
    }

    @Test fun otherFormatsUsePaddedBounds() {
        val box = CodeGeometry.corners(
            "DATA_MATRIX",
            listOf(Pt(10f, 10f), Pt(10f, 110f), Pt(110f, 110f), Pt(110f, 10f))
        )
        assertEquals(4, box.size)
        assertTrue(box.minOf { it.x } < 10f)
        assertTrue(box.maxOf { it.y } > 110f)
    }

    @Test fun emptyPointsGiveNoBox() {
        assertTrue(CodeGeometry.corners("QR_CODE", emptyList()).isEmpty())
    }

    @Test fun fillMappingCropsAndCentres() {
        val centre = CodeGeometry.mapPoint(Pt(50f, 100f), 100f, 200f, 300f, 300f, fit = false, mirror = false)
        assertPt(150f, 150f, centre)
        val top = CodeGeometry.mapPoint(Pt(0f, 0f), 100f, 200f, 300f, 300f, fit = false, mirror = false)
        assertPt(0f, -150f, top)
    }

    @Test fun fitMappingLetterboxes() {
        val centre = CodeGeometry.mapPoint(Pt(50f, 100f), 100f, 200f, 300f, 300f, fit = true, mirror = false)
        assertPt(150f, 150f, centre)
        val topLeft = CodeGeometry.mapPoint(Pt(0f, 0f), 100f, 200f, 300f, 300f, fit = true, mirror = false)
        assertPt(75f, 0f, topLeft)
    }

    @Test fun mirroringFlipsHorizontally() {
        val mirrored = CodeGeometry.mapPoint(Pt(0f, 0f), 100f, 200f, 300f, 300f, fit = true, mirror = true)
        assertPt(225f, 0f, mirrored)
    }

    @Test fun pointInPolygon() {
        val square = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f))
        assertTrue(CodeGeometry.contains(square, 5f, 5f))
        assertFalse(CodeGeometry.contains(square, 15f, 5f))
        assertFalse(CodeGeometry.contains(square, -1f, 5f))
        assertFalse(CodeGeometry.contains(emptyList(), 1f, 1f))
    }

    private fun qr(text: String, x: Float, y: Float, size: Float = 100f) = DetectedCode(
        text, "QR_CODE", listOf(Pt(x, y + size), Pt(x, y), Pt(x + size, y))
    )

    @Test fun dedupeMergesRepeatsButKeepsSeparateCopies() {
        val codes = listOf(
            qr("A", 0f, 0f),
            qr("A", 4f, 3f),
            qr("A", 600f, 600f),
            qr("B", 0f, 0f)
        )
        val result = CodeGeometry.dedupe(codes)
        assertEquals(3, result.size)
        assertEquals(listOf("A", "A", "B"), result.map { it.text })
    }

    @Test fun unmapUndoesMap() {
        for (fit in listOf(true, false)) {
            for (mirror in listOf(true, false)) {
                val p = Pt(37f, 91f)
                val mapped = CodeGeometry.mapPoint(p, 100f, 200f, 300f, 340f, fit, mirror)
                val back = CodeGeometry.unmapPoint(mapped, 100f, 200f, 300f, 340f, fit, mirror)
                assertPt(37f, 91f, back)
            }
        }
    }

    @Test fun viewToImageIsIdentityWithoutZoom() {
        val direct = CodeGeometry.unmapPoint(Pt(120f, 80f), 400f, 300f, 200f, 300f, true, false)
        val viaZoom = CodeGeometry.viewToImage(Pt(120f, 80f), 400f, 300f, 200f, 300f, 1f, 0f, 0f)
        assertPt(direct.x, direct.y, viaZoom)
    }

    @Test fun viewToImageFollowsZoomAndPan() {
        val centre = CodeGeometry.viewToImage(Pt(100f, 150f), 400f, 300f, 200f, 300f, 2f, 0f, 0f)
        val straight = CodeGeometry.unmapPoint(Pt(100f, 150f), 400f, 300f, 200f, 300f, true, false)
        assertPt(straight.x, straight.y, centre)
        val panned = CodeGeometry.viewToImage(Pt(150f, 150f), 400f, 300f, 200f, 300f, 2f, 50f, 0f)
        assertPt(straight.x, straight.y, panned)
    }

    @Test fun scanSquareIsCentredAndSeventyPercentOfTheShortSide() {
        val square = CodeGeometry.scanSquare(1000f, 2000f)
        assertEquals(700f, square[2] - square[0], eps)
        assertEquals(700f, square[3] - square[1], eps)
        assertEquals(500f, (square[0] + square[2]) / 2f, eps)
        assertEquals(880f, (square[1] + square[3]) / 2f, eps)
    }

    @Test fun inflateGrowsEveryEdgeByTheSameAmount() {
        val grown = CodeGeometry.inflate(listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f), Pt(0f, 10f)), 1f)
        assertPt(-1f, -1f, grown[0])
        assertPt(11f, -1f, grown[1])
        assertPt(11f, 11f, grown[2])
        assertPt(-1f, 11f, grown[3])
    }

    @Test fun refinedQuadWinsOverTheExtrapolatedBox() {
        val points = listOf(Pt(0f, 100f), Pt(0f, 0f), Pt(100f, 0f))
        val quad = listOf(Pt(1f, 1f), Pt(5f, 1f), Pt(5f, 5f), Pt(1f, 5f))
        assertEquals(quad, DetectedCode("A", "QR_CODE", points, 2f, quad).box())
        assertEquals(4, DetectedCode("A", "QR_CODE", points).box().size)
    }

    @Test fun transformMovesPointsAndQuadAndScalesTheModule() {
        val code = DetectedCode("A", "QR_CODE", listOf(Pt(1f, 1f)), 2f, listOf(Pt(2f, 2f)))
        val moved = code.transformed({ Pt(it.x * 2f + 10f, it.y * 2f) }, 2f)
        assertPt(12f, 2f, moved.points[0])
        assertPt(14f, 4f, moved.quad!![0])
        assertEquals(4f, moved.moduleSize, eps)
    }
}
