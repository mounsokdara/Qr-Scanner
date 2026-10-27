package com.localscanner.qr

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Float, val y: Float)

data class DetectedCode(
    val text: String,
    val format: String,
    val points: List<Pt>,
    val moduleSize: Float = 0f,
    val quad: List<Pt>? = null
) {
    fun box(): List<Pt> = quad ?: CodeGeometry.corners(format, points)

    fun transformed(map: (Pt) -> Pt, scale: Float = 1f): DetectedCode = DetectedCode(
        text,
        format,
        points.map(map),
        moduleSize * scale,
        quad?.map(map)
    )
}

object CodeGeometry {
    fun corners(format: String, points: List<Pt>): List<Pt> {
        if (points.isEmpty()) return emptyList()
        return when {
            format == "QR_CODE" && points.size >= 3 -> qrCorners(points[0], points[1], points[2])
            points.size == 2 -> lineBox(points[0], points[1])
            else -> boundsBox(points)
        }
    }

    private fun qrCorners(bottomLeft: Pt, topLeft: Pt, topRight: Pt): List<Pt> {
        val e = 0.18f
        val ux = topRight.x - topLeft.x
        val uy = topRight.y - topLeft.y
        val vx = bottomLeft.x - topLeft.x
        val vy = bottomLeft.y - topLeft.y
        fun p(a: Float, b: Float) = Pt(topLeft.x + a * ux + b * vx, topLeft.y + a * uy + b * vy)
        return listOf(p(-e, -e), p(1 + e, -e), p(1 + e, 1 + e), p(-e, 1 + e))
    }

    private fun lineBox(a: Pt, b: Pt): List<Pt> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = max(hypot(dx, dy), 1f)
        val ux = dx / len
        val uy = dy / len
        val nx = -uy
        val ny = ux
        val ext = 0.06f * len
        val half = max(0.25f * len, 12f)
        val a2 = Pt(a.x - ux * ext, a.y - uy * ext)
        val b2 = Pt(b.x + ux * ext, b.y + uy * ext)
        return listOf(
            Pt(a2.x + nx * half, a2.y + ny * half),
            Pt(b2.x + nx * half, b2.y + ny * half),
            Pt(b2.x - nx * half, b2.y - ny * half),
            Pt(a2.x - nx * half, a2.y - ny * half)
        )
    }

    private fun boundsBox(points: List<Pt>): List<Pt> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
        val pad = max(10f, 0.08f * max(maxX - minX, maxY - minY))
        return listOf(
            Pt(minX - pad, minY - pad),
            Pt(maxX + pad, minY - pad),
            Pt(maxX + pad, maxY + pad),
            Pt(minX - pad, maxY + pad)
        )
    }

    fun mapPoint(
        p: Pt,
        srcW: Float,
        srcH: Float,
        viewW: Float,
        viewH: Float,
        fit: Boolean,
        mirror: Boolean
    ): Pt {
        val scale = if (fit) min(viewW / srcW, viewH / srcH) else max(viewW / srcW, viewH / srcH)
        val dx = (viewW - srcW * scale) / 2f
        val dy = (viewH - srcH * scale) / 2f
        val x = if (mirror) srcW - p.x else p.x
        return Pt(x * scale + dx, p.y * scale + dy)
    }

    fun unmapPoint(
        p: Pt,
        srcW: Float,
        srcH: Float,
        viewW: Float,
        viewH: Float,
        fit: Boolean,
        mirror: Boolean
    ): Pt {
        val scale = if (fit) min(viewW / srcW, viewH / srcH) else max(viewW / srcW, viewH / srcH)
        val dx = (viewW - srcW * scale) / 2f
        val dy = (viewH - srcH * scale) / 2f
        val x = (p.x - dx) / scale
        return Pt(if (mirror) srcW - x else x, (p.y - dy) / scale)
    }

    fun viewToImage(
        view: Pt,
        imageW: Float,
        imageH: Float,
        viewW: Float,
        viewH: Float,
        zoom: Float,
        tx: Float,
        ty: Float
    ): Pt {
        val cx = viewW / 2f
        val cy = viewH / 2f
        val fitted = Pt(cx + (view.x - cx - tx) / zoom, cy + (view.y - cy - ty) / zoom)
        return unmapPoint(fitted, imageW, imageH, viewW, viewH, fit = true, mirror = false)
    }

    fun scanSquare(viewW: Float, viewH: Float): FloatArray {
        val side = min(viewW, viewH) * 0.70f
        val cx = viewW / 2f
        val cy = viewH * 0.44f
        return floatArrayOf(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
    }

    fun inflate(quad: List<Pt>, delta: Float): List<Pt> {
        if (quad.size < 3) return quad
        val cx = quad.map { it.x }.average().toFloat()
        val cy = quad.map { it.y }.average().toFloat()
        fun normal(a: Pt, b: Pt): Pt {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = max(hypot(dx, dy), 1e-6f)
            var nx = dy / len
            var ny = -dx / len
            val mx = (a.x + b.x) / 2f - cx
            val my = (a.y + b.y) / 2f - cy
            if (nx * mx + ny * my < 0f) {
                nx = -nx
                ny = -ny
            }
            return Pt(nx, ny)
        }
        return quad.indices.map { i ->
            val prev = quad[(i + quad.size - 1) % quad.size]
            val cur = quad[i]
            val next = quad[(i + 1) % quad.size]
            val n1 = normal(prev, cur)
            val n2 = normal(cur, next)
            val denom = 1f + n1.x * n2.x + n1.y * n2.y
            if (denom < 0.2f) {
                val sx = n1.x + n2.x
                val sy = n1.y + n2.y
                val len = max(hypot(sx, sy), 1e-6f)
                Pt(cur.x + sx / len * delta * 1.5f, cur.y + sy / len * delta * 1.5f)
            } else {
                Pt(cur.x + (n1.x + n2.x) * delta / denom, cur.y + (n1.y + n2.y) * delta / denom)
            }
        }
    }

    fun contains(polygon: List<Pt>, x: Float, y: Float): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > y) != (pj.y > y) &&
                x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private const val DUPLICATE_TOLERANCE = 0.35f
    private const val MIN_DUPLICATE_DISTANCE = 12f

    /** True when both codes read the same text and sit at (nearly) the same place. */
    fun isSame(a: DetectedCode, b: DetectedCode, tolerance: Float = DUPLICATE_TOLERANCE): Boolean {
        if (a.text != b.text || a.format != b.format) return false
        val boxA = a.box()
        val boxB = b.box()
        if (boxA.size < 4 || boxB.size < 4) return false
        val ax = boxA.map { it.x }.average().toFloat()
        val ay = boxA.map { it.y }.average().toFloat()
        val bx = boxB.map { it.x }.average().toFloat()
        val by = boxB.map { it.y }.average().toFloat()
        val diag = hypot(boxB[2].x - boxB[0].x, boxB[2].y - boxB[0].y)
        return hypot(ax - bx, ay - by) < max(MIN_DUPLICATE_DISTANCE, tolerance * diag)
    }

    fun dedupe(codes: List<DetectedCode>, tolerance: Float = DUPLICATE_TOLERANCE): List<DetectedCode> {
        val kept = ArrayList<DetectedCode>()
        for (code in codes) {
            if (code.box().isEmpty()) continue
            if (kept.none { isSame(it, code, tolerance) }) kept.add(code)
        }
        return kept
    }
}
