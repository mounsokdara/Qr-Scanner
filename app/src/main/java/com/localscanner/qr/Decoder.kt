package com.localscanner.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

object Decoder {
    val FORMATS: List<BarcodeFormat> = listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.EAN_8,
        BarcodeFormat.EAN_13,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.CODE_128,
        BarcodeFormat.ITF,
        BarcodeFormat.CODABAR,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.PDF_417,
        BarcodeFormat.AZTEC
    )

    private var invertTick = 0

    fun decodeImageProxy(image: ImageProxy): Result? {
        val source = try {
            yuvToLuminance(image)
        } catch (_: Exception) {
            null
        } ?: return null
        val direct = decodeFast(source)
        if (direct != null) return direct
        // Inverted (light-on-dark) codes are rare; trying them on every frame doubles the CPU cost.
        invertTick = (invertTick + 1) % 3
        if (invertTick != 0) return null
        return try {
            decodeFast(source.invert())
        } catch (_: Exception) {
            null
        }
    }

    fun loadOrientedBitmap(context: Context, uri: Uri): Bitmap? {
        val decoded = decodeSampled(context, uri) ?: return null
        val oriented = applyExif(context, uri, decoded)
        if (oriented !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        return oriented
    }

    fun decodeBitmap(bitmap: Bitmap): Result? {
        val rotations = intArrayOf(0, 90, 180, 270)
        for (degrees in rotations) {
            val candidate = if (degrees == 0) bitmap else rotateBitmap(bitmap, degrees)
            try {
                val source = bitmapToSource(candidate) ?: continue
                val result = decodeThorough(source)
                if (result != null) return result
            } catch (_: Exception) {
                // try the next rotation
            } finally {
                if (candidate !== bitmap && !candidate.isRecycled) {
                    candidate.recycle()
                }
            }
        }
        return null
    }

    private fun yuvToLuminance(image: ImageProxy): LuminanceSource? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val buffer = plane.buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rotation = image.imageInfo.rotationDegrees
        if (pixelStride == 1 && rowStride >= width && bytes.size >= (height - 1) * rowStride + width) {
            try {
                val source = PlanarYUVLuminanceSource(
                    bytes,
                    rowStride,
                    height,
                    0,
                    0,
                    width,
                    height,
                    false
                )
                return rotateClockwise(source, rotation)
            } catch (_: Exception) {
                // Fall through and pack the plane tightly.
            }
        }
        val packed = ByteArray(width * height)
        var out = 0
        for (row in 0 until height) {
            var input = row * rowStride
            for (col in 0 until width) {
                if (input < 0 || input >= bytes.size) {
                    packed[out++] = 0
                } else {
                    packed[out++] = bytes[input]
                }
                input += pixelStride
            }
        }
        val source = PlanarYUVLuminanceSource(packed, width, height, 0, 0, width, height, false)
        return rotateClockwise(source, rotation)
    }

    private fun rotateClockwise(source: LuminanceSource, degrees: Int): LuminanceSource {
        if (!source.isRotateSupported) return source
        return when (((degrees % 360) + 360) % 360) {
            90 -> source.rotateCounterClockwise()
                .rotateCounterClockwise()
                .rotateCounterClockwise()
            180 -> source.rotateCounterClockwise().rotateCounterClockwise()
            270 -> source.rotateCounterClockwise()
            else -> source
        }
    }

    private fun decodeFast(source: LuminanceSource): Result? {
        val reader = MultiFormatReader()
        val hints = HashMap<DecodeHintType, Any>()
        hints[DecodeHintType.POSSIBLE_FORMATS] = FORMATS
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        reader.setHints(hints)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeThorough(source: LuminanceSource): Result? {
        val reader = MultiFormatReader()
        val hints = HashMap<DecodeHintType, Any>()
        hints[DecodeHintType.POSSIBLE_FORMATS] = FORMATS
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        hints[DecodeHintType.TRY_HARDER] = true
        try {
            hints[DecodeHintType.ALSO_INVERTED] = true
        } catch (_: Exception) {
            // Hint stays optional if a device build rejects it.
        }
        reader.setHints(hints)
        val sources = ArrayList<LuminanceSource>()
        sources.add(source)
        try {
            sources.add(source.invert())
        } catch (_: Exception) {
            // Ignore invert failures.
        }
        for (candidate in sources) {
            val bitmaps = listOf(
                BinaryBitmap(HybridBinarizer(candidate)),
                BinaryBitmap(GlobalHistogramBinarizer(candidate))
            )
            for (bitmap in bitmaps) {
                try {
                    return reader.decodeWithState(bitmap)
                } catch (_: NotFoundException) {
                    reader.reset()
                } catch (_: Exception) {
                    reader.reset()
                }
            }
        }
        return null
    }

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    private fun applyExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            } ?: ExifInterface.ORIENTATION_UNDEFINED
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_UNDEFINED
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
        if (degrees == 0) return bitmap
        return rotateBitmap(bitmap, degrees)
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun bitmapToSource(bitmap: Bitmap): RGBLuminanceSource? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    }
}
