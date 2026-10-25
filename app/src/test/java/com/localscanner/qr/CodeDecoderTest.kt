package com.localscanner.qr

import com.google.zxing.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeDecoderTest {
    private fun blurryQr(scale: Float, sigma: Float): Triple<Luma, Int, Float> {
        val matrix = qrMatrix("https://blurry.example/${(scale * 10).toInt()}")
        val canvas = SyntheticCanvas(500, 400, 21)
        canvas.draw(matrix, 250f, 200f, scale, 3f)
        canvas.blur(sigma)
        canvas.noise(9, 2.5f)
        return Triple(canvas.toLuma(), (matrix.width * scale).toInt(), scale)
    }

    @Test fun blurryCodeNeedsTheEnhancedPath() {
        val (luma, size, _) = blurryQr(4f, 2.8f)
        val source = com.google.zxing.PlanarYUVLuminanceSource(luma.data, luma.width, luma.height, 0, 0, luma.width, luma.height, false)
        assertTrue(CodeDecoder.decodeStillSource(source).isEmpty())
        val pad = 12
        val found = CodeDecoder.decodeEnhanced(luma, 250 - size / 2 - pad, 200 - size / 2 - pad, size + 2 * pad, size + 2 * pad)
        assertEquals(1, found.size)
        val truth = rotatedRect(size.toFloat(), size.toFloat(), 250f, 200f, 3f)
        assertTrue(cornerError(truth, found[0].box()) <= 3f)
    }

    @Test fun enhancedPathRecoversMostBlurLevels() {
        var recovered = 0
        val cases = listOf(3f to 1.6f, 3f to 2.0f, 4f to 2.2f, 5f to 3.4f, 2.5f to 1.4f, 6f to 4.0f)
        for ((scale, sigma) in cases) {
            val (luma, size, _) = blurryQr(scale, sigma)
            val pad = 12
            val found = CodeDecoder.decodeEnhanced(luma, 250 - size / 2 - pad, 200 - size / 2 - pad, size + 2 * pad, size + 2 * pad)
            if (found.isNotEmpty()) recovered++
        }
        assertTrue("recovered $recovered of ${cases.size}", recovered >= 5)
    }

    @Test fun looselyDrawnCropStillWorks() {
        val (luma, size, _) = blurryQr(3f, 1.6f)
        val pad = (size * 0.3f).toInt()
        val found = CodeDecoder.decodeEnhanced(luma, 250 - size / 2 - pad, 200 - size / 2 - pad, size + 2 * pad, size + 2 * pad)
        assertTrue(found.isNotEmpty())
    }

    @Test fun cropOutsideTheImageIsClampedNotFatal() {
        val (luma, _, _) = blurryQr(4f, 1.0f)
        CodeDecoder.decodeEnhanced(luma, -50, -50, 120, 120)
        assertTrue(CodeDecoder.decodeEnhanced(luma, 490, 390, 4, 4).isEmpty())
    }

    @Test fun everyCodeInAPictureIsFoundIncludingBarcodes() {
        val canvas = SyntheticCanvas(900, 700, 5)
        val texts = listOf(
            "https://one.example/a" to (170f to 170f),
            "https://two.example/b" to (560f to 160f),
            "https://three.example/c" to (200f to 520f)
        )
        for ((text, position) in texts) canvas.draw(qrMatrix(text), position.first, position.second, 4f, 0f)
        canvas.draw(code128Bars("ABC-1234", 70), 600f, 520f, 2.5f, 0f)
        val live = CodeDecoder.decodeLive(canvas.source(), true, false)
        val still = CodeDecoder.decodeStillSource(canvas.source())
        val expected = texts.map { it.first }.toSet() + "ABC-1234"
        assertEquals(expected, live.map { it.text }.toSet())
        assertEquals(expected, still.map { it.text }.toSet())
        assertTrue(live.any { it.format == BarcodeFormat.CODE_128.name })
        assertTrue(live.all { it.quad != null })
    }

    @Test fun singleModeFindsTheOneCode() {
        val canvas = SyntheticCanvas(400, 400, 2)
        canvas.draw(qrMatrix("https://single.example"), 200f, 200f, 5f, 0f)
        val live = CodeDecoder.decodeLive(canvas.source(), false, false)
        assertEquals(1, live.size)
        assertNotNull(live[0].text)
    }

    @Test fun blankPictureHasNoCodes() {
        val canvas = SyntheticCanvas(300, 300, 4)
        assertTrue(CodeDecoder.decodeStillSource(canvas.source()).isEmpty())
        assertTrue(CodeDecoder.decodeLive(canvas.source(), true, true).isEmpty())
    }
}
