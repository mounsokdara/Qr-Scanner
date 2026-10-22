package com.localscanner.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object Encoder {
    fun formatOf(name: String): BarcodeFormat? {
        return try {
            BarcodeFormat.valueOf(name)
        } catch (_: Exception) {
            null
        }
    }

    fun canEncode(contents: String, format: BarcodeFormat): Boolean {
        if (contents.isEmpty()) return false
        return try {
            MultiFormatWriter().encode(contents, format, 1, 1, hints(format))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun encode(
        contents: String,
        format: BarcodeFormat,
        foreground: Int = Color.BLACK,
        ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M
    ): Bitmap? {
        if (contents.isEmpty()) return null
        return try {
            val (width, height) = dimensions(format)
            val matrix = MultiFormatWriter().encode(contents, format, width, height, hints(format, ecLevel))
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) foreground else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }

    private fun hints(
        format: BarcodeFormat,
        ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M
    ): Map<EncodeHintType, Any> {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        if (format == BarcodeFormat.QR_CODE) {
            hints[EncodeHintType.ERROR_CORRECTION] = ecLevel
        }
        return hints
    }

    private fun dimensions(format: BarcodeFormat): Pair<Int, Int> {
        return when (format) {
            BarcodeFormat.QR_CODE,
            BarcodeFormat.AZTEC,
            BarcodeFormat.DATA_MATRIX -> 800 to 800
            BarcodeFormat.PDF_417 -> 1000 to 420
            else -> 1000 to 340
        }
    }
}
