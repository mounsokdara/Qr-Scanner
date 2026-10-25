package com.localscanner.qr

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageEnhance {
    fun resizeBilinear(src: Luma, newW: Int, newH: Int): Luma {
        val out = ByteArray(newW * newH)
        val sx = src.width.toFloat() / newW
        val sy = src.height.toFloat() / newH
        for (y in 0 until newH) {
            val srcY = (y + 0.5f) * sy
            val row = y * newW
            for (x in 0 until newW) {
                out[row + x] = src.sample((x + 0.5f) * sx, srcY).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return Luma(out, newW, newH)
    }

    fun boxBlur(src: Luma, radius: Int): Luma {
        if (radius <= 0) return src
        val w = src.width
        val h = src.height
        val integral = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                rowSum += src.data[y * w + x].toInt() and 0xFF
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - radius)
            val y1 = min(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - radius)
                val x1 = min(w, x + radius + 1)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] -
                    integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                out[y * w + x] = (sum / ((x1 - x0) * (y1 - y0))).toByte()
            }
        }
        return Luma(out, w, h)
    }

    fun stretchContrast(src: Luma, lowFraction: Float = 0.02f, highFraction: Float = 0.98f): Luma {
        val hist = IntArray(256)
        for (b in src.data) hist[b.toInt() and 0xFF]++
        val total = src.data.size
        var acc = 0
        var low = 0
        var high = 255
        var lowSet = false
        for (v in 0..255) {
            acc += hist[v]
            if (!lowSet && acc >= total * lowFraction) {
                low = v
                lowSet = true
            }
            if (acc >= total * highFraction) {
                high = v
                break
            }
        }
        if (high - low < 8) return src
        val scale = 255f / (high - low)
        val out = ByteArray(total)
        for (i in 0 until total) {
            val v = ((src.data[i].toInt() and 0xFF) - low) * scale
            out[i] = v.roundToInt().coerceIn(0, 255).toByte()
        }
        return Luma(out, src.width, src.height)
    }

    fun unsharp(src: Luma, radius: Int, amount: Float): Luma {
        val blurred = boxBlur(boxBlur(src, radius), radius)
        val out = ByteArray(src.data.size)
        for (i in out.indices) {
            val v = src.data[i].toInt() and 0xFF
            val b = blurred.data[i].toInt() and 0xFF
            out[i] = (v + amount * (v - b)).roundToInt().coerceIn(0, 255).toByte()
        }
        return Luma(out, src.width, src.height)
    }

    fun adaptiveThreshold(src: Luma, radius: Int, offset: Int): Luma {
        val mean = boxBlur(src, radius)
        val out = ByteArray(src.data.size)
        for (i in out.indices) {
            val v = src.data[i].toInt() and 0xFF
            val m = mean.data[i].toInt() and 0xFF
            out[i] = if (v < m - offset) 0 else 255.toByte()
        }
        return Luma(out, src.width, src.height)
    }

    fun downscale(src: Luma, factor: Float): Luma {
        val w = max(8, (src.width * factor).roundToInt())
        val h = max(8, (src.height * factor).roundToInt())
        val radius = max(1, (1f / factor / 2f).roundToInt())
        return resizeBilinear(boxBlur(src, radius), w, h)
    }

    fun deconvolve(src: Luma, sigma: Float, iterations: Int): Luma {
        val w = src.width
        val h = src.height
        val n = w * h
        val radius = max(1, ceil(sigma * 3f).toInt())
        val kernel = FloatArray(2 * radius + 1) {
            exp(-((it - radius) * (it - radius)).toFloat() / (2f * sigma * sigma))
        }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        val observed = FloatArray(n) { (src.data[it].toInt() and 0xFF) + 1f }
        val estimate = observed.copyOf()
        val scratch = FloatArray(n)
        val conv = FloatArray(n)
        val ratio = FloatArray(n)
        repeat(iterations) {
            convolve(estimate, conv, scratch, w, h, kernel, radius)
            for (i in 0 until n) ratio[i] = observed[i] / max(conv[i], 1e-3f)
            convolve(ratio, conv, scratch, w, h, kernel, radius)
            for (i in 0 until n) estimate[i] = (estimate[i] * conv[i]).coerceIn(0.5f, 400f)
        }
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = (estimate[i] - 1f).roundToInt().coerceIn(0, 255).toByte()
        return Luma(out, w, h)
    }

    private fun convolve(
        input: FloatArray, output: FloatArray, scratch: FloatArray,
        w: Int, h: Int, kernel: FloatArray, radius: Int
    ) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    acc += kernel[k + radius] * input[row + min(max(x + k, 0), w - 1)]
                }
                scratch[row + x] = acc
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (k in -radius..radius) {
                    acc += kernel[k + radius] * scratch[min(max(y + k, 0), h - 1) * w + x]
                }
                output[y * w + x] = acc
            }
        }
    }
}
