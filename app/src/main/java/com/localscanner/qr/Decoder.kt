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
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader

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

    @Volatile private var latestFrame: LuminanceSource? = null

    class LiveFrame(val codes: List<DetectedCode>, val width: Int, val height: Int)

    fun decodeLive(image: ImageProxy, multi: Boolean): LiveFrame? {
        val source = try {
            yuvToLuminance(image)
        } catch (_: Exception) {
            null
        } ?: return null
        latestFrame = source
        var codes = decodeLiveSource(source, multi)
        if (codes.isEmpty()) {
            invertTick = (invertTick + 1) % 3
            if (invertTick == 0) {
                codes = try {
                    decodeLiveSource(source.invert(), multi)
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
        if (codes.isEmpty()) return null
        return LiveFrame(codes, source.width, source.height)
    }

    private fun decodeLiveSource(source: LuminanceSource, multi: Boolean): List<DetectedCode> {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        if (multi) {
            val found = multiQr(bitmap, baseHints(false))
            if (found.isNotEmpty()) return CodeGeometry.dedupe(found)
        }
        val single = decodeSingle(bitmap) ?: return emptyList()
        return listOfNotNull(toCode(single))
    }

    fun decodeLatestFrame(): LiveFrame? {
        val source = latestFrame ?: return null
        val codes = decodeStillSource(source)
        return if (codes.isEmpty()) null else LiveFrame(codes, source.width, source.height)
    }

    fun clearLatestFrame() {
        latestFrame = null
    }

    fun decodeStill(bitmap: Bitmap): List<DetectedCode> {
        val source = try {
            bitmapToSource(bitmap)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return decodeStillSource(source)
    }

    private fun decodeStillSource(source: LuminanceSource): List<DetectedCode> {
        val variants = ArrayList<LuminanceSource>(2)
        variants.add(source)
        try {
            variants.add(source.invert())
        } catch (_: Exception) {
        }
        for (variant in variants) {
            val attempts = listOf<() -> BinaryBitmap>(
                { BinaryBitmap(HybridBinarizer(variant)) },
                { BinaryBitmap(GlobalHistogramBinarizer(variant)) }
            )
            for (make in attempts) {
                val found = decodeAllThorough(make())
                if (found.isNotEmpty()) return CodeGeometry.dedupe(found)
            }
        }
        return emptyList()
    }

    private fun decodeAllThorough(bitmap: BinaryBitmap): List<DetectedCode> {
        val hints = baseHints(true)
        val out = ArrayList<DetectedCode>()
        try {
            val reader = MultiFormatReader()
            reader.setHints(hints)
            for (result in GenericMultipleBarcodeReader(reader).decodeMultiple(bitmap, hints)) {
                toCode(result)?.let { out.add(it) }
            }
        } catch (_: Exception) {
        }
        out.addAll(multiQr(bitmap, hints))
        return out
    }

    private fun multiQr(bitmap: BinaryBitmap, hints: Map<DecodeHintType, Any>): List<DetectedCode> {
        return try {
            QRCodeMultiReader().decodeMultiple(bitmap, hints).mapNotNull { toCode(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeSingle(bitmap: BinaryBitmap): Result? {
        val reader = MultiFormatReader()
        reader.setHints(baseHints(false))
        return try {
            reader.decodeWithState(bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private fun toCode(result: Result): DetectedCode? {
        val text = result.text?.trim().orEmpty()
        if (text.isEmpty()) return null
        val format = result.barcodeFormat?.name ?: return null
        val points = result.resultPoints?.filterNotNull()?.map { Pt(it.x, it.y) }.orEmpty()
        if (points.isEmpty()) return null
        return DetectedCode(text, format, points)
    }

    private fun baseHints(thorough: Boolean): HashMap<DecodeHintType, Any> {
        val hints = HashMap<DecodeHintType, Any>()
        hints[DecodeHintType.POSSIBLE_FORMATS] = FORMATS
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        if (thorough) hints[DecodeHintType.TRY_HARDER] = true
        return hints
    }

    fun loadOrientedBitmap(context: Context, uri: Uri): Bitmap? {
        val decoded = decodeSampled(context, uri) ?: return null
        val oriented = applyExif(context, uri, decoded)
        if (oriented !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        return oriented
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
        val packed = ByteArray(width * height)
        if (pixelStride == 1) {
            for (row in 0 until height) {
                val start = row * rowStride
                if (start + width > bytes.size) break
                System.arraycopy(bytes, start, packed, row * width, width)
            }
        } else {
            var out = 0
            for (row in 0 until height) {
                var input = row * rowStride
                for (col in 0 until width) {
                    packed[out++] = if (input in bytes.indices) bytes[input] else 0
                    input += pixelStride
                }
            }
        }
        val upright = LumaRotation.rotateClockwise(packed, width, height, image.imageInfo.rotationDegrees)
        return PlanarYUVLuminanceSource(
            upright.data, upright.width, upright.height, 0, 0, upright.width, upright.height, false
        )
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
