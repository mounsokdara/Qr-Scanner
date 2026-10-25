package com.localscanner.qr

import com.google.zxing.LuminanceSource
import kotlin.math.floor

class Luma(val data: ByteArray, val width: Int, val height: Int) {
    fun at(x: Int, y: Int): Int {
        val cx = if (x < 0) 0 else if (x >= width) width - 1 else x
        val cy = if (y < 0) 0 else if (y >= height) height - 1 else y
        return data[cy * width + cx].toInt() and 0xFF
    }

    fun sample(x: Float, y: Float): Float {
        val fx = x - 0.5f
        val fy = y - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val a = at(x0, y0)
        val b = at(x0 + 1, y0)
        val c = at(x0, y0 + 1)
        val d = at(x0 + 1, y0 + 1)
        return (a * (1f - tx) + b * tx) * (1f - ty) + (c * (1f - tx) + d * tx) * ty
    }

    fun crop(left: Int, top: Int, w: Int, h: Int): Luma {
        val out = ByteArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(data, (top + row) * width + left, out, row * w, w)
        }
        return Luma(out, w, h)
    }

    companion object {
        fun of(source: LuminanceSource): Luma = Luma(source.matrix, source.width, source.height)
    }
}
