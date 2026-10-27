package com.localscanner.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object Decoder {
    private const val FRAME_CYCLE = 3
    private const val EXTRAS_TICK = 1
    private var invertTick = 0

    @Volatile private var latestFrame: LuminanceSource? = null

    class LiveFrame(val codes: List<DetectedCode>, val width: Int, val height: Int)

    fun decodeLive(image: ImageProxy, multi: Boolean, viewW: Int, viewH: Int, mirror: Boolean): LiveFrame? {
        val source = try {
            yuvToLuminance(image)
        } catch (_: Exception) {
            null
        } ?: return null
        latestFrame = source
        invertTick = (invertTick + 1) % FRAME_CYCLE
        val allowInverted = invertTick == 0
        val extras = invertTick == EXTRAS_TICK
        val codes = try {
            if (multi) {
                CodeDecoder.decodeLive(source, true, allowInverted, extras)
            } else {
                decodeInSquare(source, viewW, viewH, mirror, allowInverted)
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (codes.isEmpty()) return null
        return LiveFrame(codes, source.width, source.height)
    }

    private fun decodeInSquare(
        source: LuminanceSource,
        viewW: Int,
        viewH: Int,
        mirror: Boolean,
        allowInverted: Boolean
    ): List<DetectedCode> {
        if (viewW <= 0 || viewH <= 0) return CodeDecoder.decodeLive(source, false, allowInverted)
        val sw = source.width.toFloat()
        val sh = source.height.toFloat()
        val square = CodeGeometry.scanSquare(viewW.toFloat(), viewH.toFloat())
        val a = CodeGeometry.unmapPoint(Pt(square[0], square[1]), sw, sh, viewW.toFloat(), viewH.toFloat(), false, mirror)
        val b = CodeGeometry.unmapPoint(Pt(square[2], square[3]), sw, sh, viewW.toFloat(), viewH.toFloat(), false, mirror)
        val left = floor(min(a.x, b.x)).toInt().coerceIn(0, source.width - 1)
        val top = floor(min(a.y, b.y)).toInt().coerceIn(0, source.height - 1)
        val right = ceil(max(a.x, b.x)).toInt().coerceIn(left + 1, source.width)
        val bottom = ceil(max(a.y, b.y)).toInt().coerceIn(top + 1, source.height)
        val width = right - left
        val height = bottom - top
        if (width < 32 || height < 32) return CodeDecoder.decodeLive(source, false, allowInverted)
        val cropped = source.crop(left, top, width, height)
        return CodeDecoder.decodeLive(cropped, false, allowInverted)
            .map { code -> code.transformed({ p -> Pt(p.x + left, p.y + top) }) }
    }

    fun decodeLatestFrame(): LiveFrame? {
        val source = latestFrame ?: return null
        val codes = CodeDecoder.decodeStillSource(source)
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
        return CodeDecoder.decodeStillSource(source)
    }

    fun rescan(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int): List<DetectedCode> {
        val source = bitmapToSource(bitmap) ?: return emptyList()
        val luma = Luma(source.matrix, bitmap.width, bitmap.height)
        return CodeDecoder.decodeEnhanced(luma, left, top, width, height)
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
