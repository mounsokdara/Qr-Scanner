package com.localscanner.qr

/** Pure helper that rotates a tightly packed 8-bit luminance image. */
object LumaRotation {
    class Rotated(val data: ByteArray, val width: Int, val height: Int)

    fun rotateClockwise(data: ByteArray, width: Int, height: Int, degrees: Int): Rotated {
        return when (((degrees % 360) + 360) % 360) {
            90 -> {
                val out = ByteArray(width * height)
                for (y in 0 until height) {
                    val row = y * width
                    for (x in 0 until width) {
                        out[x * height + (height - 1 - y)] = data[row + x]
                    }
                }
                Rotated(out, height, width)
            }
            180 -> {
                val out = ByteArray(width * height)
                val last = width * height - 1
                for (i in 0..last) out[last - i] = data[i]
                Rotated(out, width, height)
            }
            270 -> {
                val out = ByteArray(width * height)
                for (y in 0 until height) {
                    val row = y * width
                    for (x in 0 until width) {
                        out[(width - 1 - x) * height + y] = data[row + x]
                    }
                }
                Rotated(out, height, width)
            }
            else -> Rotated(data, width, height)
        }
    }
}
