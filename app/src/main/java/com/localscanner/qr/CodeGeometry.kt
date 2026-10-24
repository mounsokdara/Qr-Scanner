package com.localscanner.qr

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Float, val y: Float)

data class DetectedCode(val text: String, val format: String, val points: List<Pt>)

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

    fun dedupe(codes: List<DetectedCode>): List<DetectedCode> {
        val kept = ArrayList<DetectedCode>()
        for (code in codes) {
            val box = corners(code.format, code.points)
            if (box.isEmpty()) continue
            val cx = box.map { it.x }.average().toFloat()
            val cy = box.map { it.y }.average().toFloat()
            val duplicate = kept.any { other ->
                if (other.text != code.text || other.format != code.format) return@any false
                val ob = corners(other.format, other.points)
                val ox = ob.map { it.x }.average().toFloat()
                val oy = ob.map { it.y }.average().toFloat()
                val diag = hypot(ob[2].x - ob[0].x, ob[2].y - ob[0].y)
                hypot(cx - ox, cy - oy) < max(40f, 0.6f * diag)
            }
            if (!duplicate) kept.add(code)
        }
        return kept
    }
}
