package com.localscanner.qr

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class BoxRefinerTest {
    private fun qrError(scale: Float, angle: Float): Float {
        val matrix = qrMatrix("https://example.com/q?id=${(scale * 100).toInt()}")
        val canvas = SyntheticCanvas(520, 420, 7)
        canvas.draw(matrix, 260f, 210f, scale, angle)
        val size = matrix.width * scale
        val code = CodeDecoder.decodeStillSource(canvas.source()).firstOrNull()
        assertNotNull("QR at scale $scale angle $angle was not decoded", code)
        return cornerError(rotatedRect(size, size, 260f, 210f, angle), code!!.box())
    }

    @Test fun qrBoxesWrapTheModulesWithinAPixel() {
        for ((scale, angle) in listOf(3f to 0f, 4f to 0f, 6f to 0f, 6f to 20f, 5f to 40f, 8f to -30f, 4f to 90f)) {
            val error = qrError(scale, angle)
            assertTrue("scale $scale angle $angle error $error", error <= 1.5f)
        }
    }

    @Test fun qrBoxIgnoresNoiseAndNeighbouringBlocks() {
        val matrix = qrMatrix("HELLO-NEIGHBOUR-TEST")
        val canvas = SyntheticCanvas(600, 500, 3)
        canvas.draw(matrix, 240f, 230f, 6f, 7f)
        canvas.rect(40, 440, 560, 470, 30f)
        canvas.rect(40, 20, 560, 40, 30f)
        canvas.noise(5, 6f)
        val code = CodeDecoder.decodeStillSource(canvas.source()).firstOrNull()
        assertNotNull(code)
        val size = matrix.width * 6f
        assertTrue(cornerError(rotatedRect(size, size, 240f, 230f, 7f), code!!.box()) <= 3f)
    }

    private fun barcodeError(scale: Float, heightModules: Int, angle: Float): Float {
        val bars = code128Bars("ABC-1234", heightModules)
        val canvas = SyntheticCanvas(900, 700, 5)
        canvas.draw(bars, 600f, 520f, scale, angle)
        val (first, last) = barBounds(bars)
        val width = (last - first + 1) * scale
        val offset = ((first + last + 1) / 2f - bars.width / 2f) * scale
        val a = Math.toRadians(angle.toDouble())
        val truth = rotatedRect(
            width, heightModules * scale,
            600f + (offset * cos(a)).toFloat(), 520f + (offset * sin(a)).toFloat(), angle
        )
        val code = CodeDecoder.decodeStillSource(canvas.source()).firstOrNull()
        assertNotNull("barcode scale $scale angle $angle was not decoded", code)
        return cornerError(truth, code!!.box())
    }

    @Test fun barcodeBoxesWrapTheBarsNotAGuess() {
        for ((scale, height, angle) in listOf(
            Triple(3f, 70, 0f), Triple(2.5f, 70, 0f), Triple(2.5f, 40, 0f), Triple(2f, 70, 0f),
            Triple(3f, 70, 5f), Triple(2.5f, 40, -9f), Triple(4f, 30, 5f), Triple(2.7f, 90, -9f)
        )) {
            val error = barcodeError(scale, height, angle)
            assertTrue("scale $scale height $height angle $angle error $error", error <= 3.5f)
        }
    }
}
