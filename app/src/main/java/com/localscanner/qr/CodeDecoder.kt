package com.localscanner.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.detector.FinderPattern
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CodeDecoder {
    private val DECONVOLVE_TARGETS = floatArrayOf(500f, 750f)
    private val DECONVOLVE_SIGMAS = floatArrayOf(0.8f, 1.0f, 1.25f, 1.6f, 2.0f, 2.5f, 3.1f)

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

    fun decodeLive(source: LuminanceSource, multi: Boolean, allowInverted: Boolean): List<DetectedCode> {
        var codes = liveOnce(source, multi)
        if (codes.isEmpty() && allowInverted) {
            codes = try {
                liveOnce(source.invert(), multi)
            } catch (_: Exception) {
                emptyList()
            }
        }
        return codes
    }

    private fun liveOnce(source: LuminanceSource, multi: Boolean): List<DetectedCode> {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val found: List<DetectedCode> = if (multi) {
            val hints = baseHints(false)
            multiQr(bitmap, hints) + multiGeneric(bitmap, hints)
        } else {
            listOfNotNull(decodeSingle(bitmap)?.let { toCode(it) })
        }
        if (found.isEmpty()) return emptyList()
        val unique = CodeGeometry.dedupe(found)
        return if (multi) BoxRefiner.refineAll(Luma.of(source), unique) else unique
    }

    fun decodeStillSource(source: LuminanceSource): List<DetectedCode> {
        val variants = ArrayList<LuminanceSource>(2)
        variants.add(source)
        try {
            variants.add(source.invert())
        } catch (_: Exception) {
            variants.size
        }
        for (variant in variants) {
            val attempts = listOf<() -> BinaryBitmap>(
                { BinaryBitmap(HybridBinarizer(variant)) },
                { BinaryBitmap(GlobalHistogramBinarizer(variant)) }
            )
            for (make in attempts) {
                val found = decodeAllThorough(make())
                if (found.isNotEmpty()) {
                    return BoxRefiner.refineAll(Luma.of(variant), CodeGeometry.dedupe(found))
                }
            }
        }
        return emptyList()
    }

    private class Variant(
        val luma: Luma,
        val gray: Luma,
        val binary: Boolean,
        val scale: Float,
        val normalOnly: Boolean = false
    )

    private fun variants(crop: Luma, target: Float): Sequence<Variant> = sequence {
        val longest = max(crop.width, crop.height)
        val up = (target / longest).coerceIn(1f, 8f)
        val base = if (up > 1.01f) {
            ImageEnhance.resizeBilinear(crop, (crop.width * up).roundToInt(), (crop.height * up).roundToInt())
        } else {
            crop
        }
        yield(Variant(base, base, false, up))
        val stretched = ImageEnhance.stretchContrast(base)
        yield(Variant(stretched, stretched, false, up))
        val sharp = ImageEnhance.unsharp(stretched, max(2, (up * 1.2f).roundToInt()), 1.5f)
        yield(Variant(sharp, sharp, false, up))
        val localRadius = max(8, max(base.width, base.height) / 12)
        yield(Variant(ImageEnhance.adaptiveThreshold(stretched, localRadius, 6), stretched, true, up))
        yield(Variant(ImageEnhance.adaptiveThreshold(sharp, localRadius, 6), sharp, true, up))
        if (max(base.width, base.height) >= 500) {
            val small = ImageEnhance.stretchContrast(ImageEnhance.downscale(base, 0.5f))
            yield(Variant(small, small, false, up * 0.5f))
        }
        val workScale = min(1f, 360f / longest)
        val work = if (workScale < 0.99f) ImageEnhance.downscale(crop, workScale) else crop
        val workStretched = ImageEnhance.stretchContrast(work)
        for (sigma in DECONVOLVE_SIGMAS) {
            val deconvolved = ImageEnhance.deconvolve(workStretched, sigma, 15)
            for (target in DECONVOLVE_TARGETS) {
                val upFactor = (target / max(work.width, work.height)).coerceIn(1f, 8f)
                val big = ImageEnhance.resizeBilinear(
                    deconvolved,
                    (deconvolved.width * upFactor).roundToInt(),
                    (deconvolved.height * upFactor).roundToInt()
                )
                yield(Variant(big, big, false, workScale * upFactor, true))
            }
        }
    }

    fun decodeEnhanced(full: Luma, rx: Int, ry: Int, rw: Int, rh: Int, target: Float = 700f): List<DetectedCode> {
        val x0 = rx.coerceIn(0, full.width - 1)
        val y0 = ry.coerceIn(0, full.height - 1)
        val w = min(rw, full.width - x0)
        val h = min(rh, full.height - y0)
        if (w < 8 || h < 8) return emptyList()
        val crop = full.crop(x0, y0, w, h)
        for (variant in variants(crop, target)) {
            val source = PlanarYUVLuminanceSource(
                variant.luma.data, variant.luma.width, variant.luma.height,
                0, 0, variant.luma.width, variant.luma.height, false
            )
            val polarities = if (variant.normalOnly) listOf(source) else listOf(source, source.invert())
            for (polarity in polarities) {
                val binarizers = if (variant.binary) {
                    listOf<() -> BinaryBitmap>({ BinaryBitmap(HybridBinarizer(polarity)) })
                } else {
                    listOf<() -> BinaryBitmap>(
                        { BinaryBitmap(HybridBinarizer(polarity)) },
                        { BinaryBitmap(GlobalHistogramBinarizer(polarity)) }
                    )
                }
                for (make in binarizers) {
                    val found = decodeAllThorough(make())
                    if (found.isEmpty()) continue
                    val refined = BoxRefiner.refineAll(variant.gray, CodeGeometry.dedupe(found))
                    val inverse = 1f / variant.scale
                    return refined.map { code ->
                        code.transformed({ p -> Pt(p.x * inverse + x0, p.y * inverse + y0) }, inverse)
                    }
                }
            }
        }
        return emptyList()
    }

    private fun decodeAllThorough(bitmap: BinaryBitmap): List<DetectedCode> {
        val hints = baseHints(true)
        val out = ArrayList<DetectedCode>()
        out.addAll(multiGeneric(bitmap, hints))
        out.addAll(multiQr(bitmap, hints))
        return out
    }

    private fun multiGeneric(bitmap: BinaryBitmap, hints: Map<DecodeHintType, Any>): List<DetectedCode> {
        return try {
            val reader = MultiFormatReader()
            reader.setHints(hints)
            GenericMultipleBarcodeReader(reader).decodeMultiple(bitmap, hints).mapNotNull { toCode(it) }
        } catch (_: Exception) {
            emptyList()
        }
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
        if (!Parsers.isReadable(text)) return null
        val format = result.barcodeFormat?.name ?: return null
        val raw = result.resultPoints?.filterNotNull().orEmpty()
        val points = raw.map { Pt(it.x, it.y) }
        if (points.isEmpty()) return null
        val module = raw.filterIsInstance<FinderPattern>().firstOrNull()?.estimatedModuleSize ?: 0f
        return DetectedCode(text, format, points, module)
    }

    private fun baseHints(thorough: Boolean): HashMap<DecodeHintType, Any> {
        val hints = HashMap<DecodeHintType, Any>()
        hints[DecodeHintType.POSSIBLE_FORMATS] = FORMATS
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        if (thorough) hints[DecodeHintType.TRY_HARDER] = true
        return hints
    }
}
