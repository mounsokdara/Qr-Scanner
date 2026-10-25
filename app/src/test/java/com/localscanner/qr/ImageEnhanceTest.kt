package com.localscanner.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEnhanceTest {
    private fun gradient(w: Int, h: Int, low: Int, high: Int): Luma {
        val data = ByteArray(w * h) { (low + (high - low) * (it % w) / (w - 1)).toByte() }
        return Luma(data, w, h)
    }

    @Test fun samplingAPixelCentreReturnsItsValue() {
        val luma = Luma(byteArrayOf(10, 20, 30, 40), 2, 2)
        assertEquals(10f, luma.sample(0.5f, 0.5f), 0.01f)
        assertEquals(40f, luma.sample(1.5f, 1.5f), 0.01f)
        assertEquals(25f, luma.sample(1f, 1f), 0.01f)
    }

    @Test fun resizeProducesTheRequestedSize() {
        val out = ImageEnhance.resizeBilinear(gradient(20, 10, 0, 255), 60, 30)
        assertEquals(60, out.width)
        assertEquals(30, out.height)
        assertEquals(60 * 30, out.data.size)
    }

    @Test fun stretchWidensANarrowRange() {
        val out = ImageEnhance.stretchContrast(gradient(100, 10, 100, 140))
        val values = out.data.map { it.toInt() and 0xFF }
        assertTrue(values.max() - values.min() > 200)
    }

    @Test fun adaptiveThresholdIsPureBlackAndWhite() {
        val out = ImageEnhance.adaptiveThreshold(gradient(60, 20, 30, 220), 8, 4)
        assertTrue(out.data.all { (it.toInt() and 0xFF) == 0 || (it.toInt() and 0xFF) == 255 })
    }

    @Test fun downscaleHalvesTheSize() {
        val out = ImageEnhance.downscale(gradient(80, 40, 0, 255), 0.5f)
        assertEquals(40, out.width)
        assertEquals(20, out.height)
    }

    @Test fun blurKeepsAFlatImageFlat() {
        val flat = Luma(ByteArray(30 * 30) { 120 }, 30, 30)
        val out = ImageEnhance.boxBlur(flat, 3)
        assertTrue(out.data.all { (it.toInt() and 0xFF) == 120 })
    }

    @Test fun deconvolutionKeepsSizeAndAverageBrightness() {
        val source = gradient(40, 40, 40, 200)
        val out = ImageEnhance.deconvolve(source, 1.5f, 10)
        assertEquals(40, out.width)
        assertEquals(40, out.height)
        val before = source.data.map { it.toInt() and 0xFF }.average()
        val after = out.data.map { it.toInt() and 0xFF }.average()
        assertTrue(kotlin.math.abs(before - after) < 15)
    }
}
