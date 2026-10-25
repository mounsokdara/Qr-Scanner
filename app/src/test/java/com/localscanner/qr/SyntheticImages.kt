package com.localscanner.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
)

fun code128Bars(text: String, heightModules: Int): BitMatrix {
    val line = Code128Writer().encode(text, BarcodeFormat.CODE_128, 0, 1)
    val bars = BitMatrix(line.width, heightModules)
    for (y in 0 until heightModules) {
        for (x in 0 until line.width) {
            if (line.get(x, 0)) bars.set(x, y)
        }
    }
    return bars
}

class SyntheticCanvas(val w: Int, val h: Int, seed: Long = 1) {
    val px = FloatArray(w * h)

    init {
        val random = Random(seed)
        for (i in px.indices) px[i] = 232f + (random.nextFloat() - 0.5f) * 10f
    }

    fun toLuma(): Luma {
        val out = ByteArray(w * h) { px[it].roundToInt().coerceIn(0, 255).toByte() }
        return Luma(out, w, h)
    }

    fun source(): LuminanceSource {
        val luma = toLuma()
        return PlanarYUVLuminanceSource(luma.data, w, h, 0, 0, w, h, false)
    }

    fun blur(sigma: Float) {
        val radius = kotlin.math.ceil(sigma * 3).toInt()
        val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        val tmp = FloatArray(px.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (i in kernel.indices) acc += kernel[i] * px[y * w + (x + i - radius).coerceIn(0, w - 1)]
                tmp[y * w + x] = acc
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0f
                for (i in kernel.indices) acc += kernel[i] * tmp[(y + i - radius).coerceIn(0, h - 1) * w + x]
                px[y * w + x] = acc
            }
        }
    }

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, value: Float) {
        for (y in max(0, y0) until min(h, y1)) {
            for (x in max(0, x0) until min(w, x1)) px[y * w + x] = value
        }
    }

    fun noise(seed: Long, sigma: Float) {
        val random = Random(seed)
        for (i in px.indices) px[i] += (random.nextGaussian() * sigma).toFloat()
    }

    fun draw(matrix: BitMatrix, cx: Float, cy: Float, scale: Float, angleDeg: Float) {
        val angle = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val reach = hypot(matrix.width * scale / 2f, matrix.height * scale / 2f).toInt() + 2
        for (y in (cy.toInt() - reach)..(cy.toInt() + reach)) {
            for (x in (cx.toInt() - reach)..(cx.toInt() + reach)) {
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                var acc = 0f
                for (sy in 0 until 3) {
                    for (sx in 0 until 3) {
                        val fx = x + (sx + 0.5f) / 3f - cx
                        val fy = y + (sy + 0.5f) / 3f - cy
                        val u = (fx * cosA + fy * sinA) / scale + matrix.width / 2f
                        val v = (-fx * sinA + fy * cosA) / scale + matrix.height / 2f
                        val mx = floor(u).toInt()
                        val my = floor(v).toInt()
                        acc += if (mx in 0 until matrix.width && my in 0 until matrix.height) {
                            if (matrix.get(mx, my)) 20f else 232f
                        } else {
                            px[y * w + x]
                        }
                    }
                }
                px[y * w + x] = acc / 9f
            }
        }
    }
}

fun rotatedRect(width: Float, height: Float, cx: Float, cy: Float, angleDeg: Float): List<Pt> {
    val angle = Math.toRadians(angleDeg.toDouble())
    val cosA = cos(angle).toFloat()
    val sinA = sin(angle).toFloat()
    return listOf(
        Pt(-width / 2, -height / 2), Pt(width / 2, -height / 2),
        Pt(width / 2, height / 2), Pt(-width / 2, height / 2)
    ).map { Pt(cx + it.x * cosA - it.y * sinA, cy + it.x * sinA + it.y * cosA) }
}

fun cornerError(truth: List<Pt>, got: List<Pt>): Float =
    truth.maxOf { t -> got.minOf { g -> hypot(t.x - g.x, t.y - g.y) } }

fun barBounds(matrix: BitMatrix): Pair<Int, Int> {
    var first = -1
    var last = -1
    for (x in 0 until matrix.width) {
        if (matrix.get(x, 0)) {
            if (first < 0) first = x
            last = x
        }
    }
    return first to last
}
