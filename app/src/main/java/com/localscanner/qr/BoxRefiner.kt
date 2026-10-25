package com.localscanner.qr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object BoxRefiner {
    fun refine(luma: Luma, code: DetectedCode): DetectedCode {
        val box = try {
            compute(luma, code)
        } catch (_: Exception) {
            null
        }
        return if (box != null && box.size == 4) code.copy(quad = box) else code
    }

    fun refineAll(luma: Luma, codes: List<DetectedCode>): List<DetectedCode> {
        return codes.map { refine(luma, it) }
    }

    private fun compute(luma: Luma, code: DetectedCode): List<Pt>? {
        val p = code.points
        return when {
            code.format == "QR_CODE" && p.size >= 3 -> refineQr(luma, p[0], p[1], p[2], code.moduleSize)
            p.size == 2 -> refineLine(luma, p[0], p[1])
            p.isNotEmpty() -> refineBounds(luma, p)
            else -> null
        }
    }

    private fun quadOf(
        o: Pt, ux: Float, uy: Float, vx: Float, vy: Float,
        a0: Float, a1: Float, b0: Float, b1: Float
    ): List<Pt> {
        fun p(a: Float, b: Float) = Pt(o.x + a * ux + b * vx, o.y + a * uy + b * vy)
        return listOf(p(a0, b0), p(a1, b0), p(a1, b1), p(a0, b1))
    }

    private fun area(q: List<Pt>): Float {
        var sum = 0f
        for (i in q.indices) {
            val a = q[i]
            val b = q[(i + 1) % q.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    private fun refineQr(luma: Luma, bl: Pt, tl: Pt, tr: Pt, moduleSize: Float): List<Pt>? {
        val ux = tr.x - tl.x
        val uy = tr.y - tl.y
        val vx = bl.x - tl.x
        val vy = bl.y - tl.y
        val dist = hypot(ux, uy)
        if (dist < 4f) return null
        val e = if (moduleSize > 0f) (3.5f * moduleSize / dist).coerceIn(0.05f, 0.3f) else 0.18f
        val margin = if (moduleSize > 0f) (1.5f * moduleSize / dist).coerceIn(0.03f, 0.12f) else 0.06f
        val base = quadOf(tl, ux, uy, vx, vy, -e, 1 + e, -e, 1 + e)
        val bounds = tighten(
            luma, tl, ux, uy, vx, vy,
            -e - margin, 1 + e + margin, -e - margin, 1 + e + margin,
            -e, 1 + e, -e, 1 + e
        ) ?: return base
        val refined = quadOf(tl, ux, uy, vx, vy, bounds[0], bounds[1], bounds[2], bounds[3])
        val ratio = area(refined) / max(area(base), 1f)
        return if (ratio in 0.7f..1.35f) refined else base
    }

    private fun refineBounds(luma: Luma, points: List<Pt>): List<Pt> {
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val pad = max(4f, 0.08f * max(maxX - minX, maxY - minY))
        val w = maxX - minX + 2 * pad
        val h = maxY - minY + 2 * pad
        val origin = Pt(minX - pad, minY - pad)
        val fallback = quadOf(origin, w, 0f, 0f, h, 0f, 1f, 0f, 1f)
        val bounds = tighten(
            luma, origin, w, 0f, 0f, h,
            0f, 1f, 0f, 1f,
            pad / w, 1f - pad / w, pad / h, 1f - pad / h
        ) ?: return fallback
        return quadOf(origin, w, 0f, 0f, h, bounds[0], bounds[1], bounds[2], bounds[3])
    }

    private fun tighten(
        luma: Luma, o: Pt, ux: Float, uy: Float, vx: Float, vy: Float,
        aMin: Float, aMax: Float, bMin: Float, bMax: Float,
        coreA0: Float, coreA1: Float, coreB0: Float, coreB1: Float
    ): FloatArray? {
        val lenU = hypot(ux, uy)
        val lenV = hypot(vx, vy)
        if (lenU < 4f || lenV < 4f) return null
        val na = ceil((aMax - aMin) * lenU).toInt().coerceIn(8, 600)
        val nb = ceil((bMax - bMin) * lenV).toInt().coerceIn(8, 600)
        val values = FloatArray(na * nb)
        for (j in 0 until nb) {
            val b = bMin + (j + 0.5f) / nb * (bMax - bMin)
            for (i in 0 until na) {
                val a = aMin + (i + 0.5f) / na * (aMax - aMin)
                values[j * na + i] = luma.sample(o.x + a * ux + b * vx, o.y + a * uy + b * vy)
            }
        }
        val hist = IntArray(256)
        var lo = 255
        var hi = 0
        for (j in 0 until nb) {
            val b = bMin + (j + 0.5f) / nb * (bMax - bMin)
            if (b < coreB0 || b > coreB1) continue
            for (i in 0 until na) {
                val a = aMin + (i + 0.5f) / na * (aMax - aMin)
                if (a < coreA0 || a > coreA1) continue
                val v = values[j * na + i].toInt().coerceIn(0, 255)
                hist[v]++
                lo = min(lo, v)
                hi = max(hi, v)
            }
        }
        if (hi - lo < 24) return null
        val threshold = otsu(hist)
        val cols = IntArray(na)
        val rows = IntArray(nb)
        for (j in 0 until nb) {
            for (i in 0 until na) {
                if (values[j * na + i] < threshold) {
                    cols[i]++
                    rows[j]++
                }
            }
        }
        val needCol = max(2, ceil(0.03f * nb).toInt())
        val needRow = max(2, ceil(0.03f * na).toInt())
        val minCol = cols.indexOfFirst { it >= needCol }
        val maxCol = cols.indexOfLast { it >= needCol }
        val minRow = rows.indexOfFirst { it >= needRow }
        val maxRow = rows.indexOfLast { it >= needRow }
        if (minCol < 0 || minRow < 0 || maxCol <= minCol || maxRow <= minRow) return null
        val da = (aMax - aMin) / na
        val db = (bMax - bMin) / nb
        return floatArrayOf(
            aMin + minCol * da,
            aMin + (maxCol + 1) * da,
            bMin + minRow * db,
            bMin + (maxRow + 1) * db
        )
    }

    private fun otsu(hist: IntArray): Float {
        var total = 0
        var sum = 0f
        for (i in 0..255) {
            total += hist[i]
            sum += i * hist[i]
        }
        var weightB = 0
        var sumB = 0f
        var best = 0f
        var threshold = 128f
        for (i in 0..255) {
            weightB += hist[i]
            if (weightB == 0) continue
            val weightF = total - weightB
            if (weightF == 0) break
            sumB += i * hist[i]
            val mB = sumB / weightB
            val mF = (sum - sumB) / weightF
            val between = weightB.toFloat() * weightF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = i + 0.5f
            }
        }
        return threshold
    }

    private fun transitions(luma: Luma, center: Pt, ux: Float, uy: Float, len: Float): Int {
        val count = len.toInt().coerceAtLeast(2)
        val startX = center.x - ux * count / 2f
        val startY = center.y - uy * count / 2f
        val values = FloatArray(count) { luma.sample(startX + it * ux, startY + it * uy) }
        val low = values.min()
        val high = values.max()
        if (high - low < 40f) return 0
        val mid = (low + high) / 2f
        val margin = 0.15f * (high - low)
        var dark = values[0] < mid
        var flips = 0
        for (v in values) {
            if (dark && v > mid + margin) {
                dark = false
                flips++
            } else if (!dark && v < mid - margin) {
                dark = true
                flips++
            }
        }
        return flips
    }

    private class Scan(val threshold: Float, val refs: IntArray, val sMin: Float, val sMax: Float)

    private fun scanAlong(luma: Luma, o: Pt, dx: Float, dy: Float, len: Float): Scan? {
        val lo = (-0.85f * len).toInt()
        val hi = (0.85f * len).toInt()
        val count = hi - lo + 1
        val line = FloatArray(count) { luma.sample(o.x + (lo + it) * dx, o.y + (lo + it) * dy) }
        val segLo = (-len / 2f).toInt()
        val segHi = (len / 2f).toInt()
        val hist = IntArray(256)
        var vmin = 255
        var vmax = 0
        for (k in 0 until count) {
            val pos = lo + k
            if (pos < segLo || pos > segHi) continue
            val v = line[k].toInt().coerceIn(0, 255)
            hist[v]++
            vmin = min(vmin, v)
            vmax = max(vmax, v)
        }
        if (vmax - vmin < 24) return null
        val threshold = otsu(hist)
        val gap = max(4, (0.06f * len).toInt())
        var bestStart = -1
        var bestEnd = -1
        var bestOverlap = 0
        var start = -1
        var last = -1
        fun evaluate(s: Int, e: Int) {
            val overlap = min(e, segHi - lo) - max(s, segLo - lo)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestStart = s
                bestEnd = e
            }
        }
        for (k in 0 until count) {
            if (line[k] < threshold) {
                if (start < 0) {
                    start = k
                } else if (k - last > gap) {
                    evaluate(start, last)
                    start = k
                }
                last = k
            }
        }
        if (start >= 0) evaluate(start, last)
        if (bestStart < 0 || bestEnd - bestStart < 6) return null
        val refs = ArrayList<Int>()
        val stride = max(1, (bestEnd - bestStart) / 160)
        var k = bestStart
        while (k <= bestEnd) {
            if (line[k] < threshold) refs.add(lo + k)
            k += stride
        }
        if (refs.size < 4) return null
        return Scan(threshold, refs.toIntArray(), (lo + bestStart).toFloat(), (lo + bestEnd + 1).toFloat())
    }

    private fun estimateTilt(
        luma: Luma, o: Pt, ux: Float, uy: Float, nx: Float, ny: Float, len: Float, scan: Scan
    ): Float {
        val t = min(max(0.12f * len, 10f), 40f)
        fun fraction(k: Float, sign: Float): Float {
            val wl = hypot(1f, k)
            val wx = (nx + k * ux) / wl
            val wy = (ny + k * uy) / wl
            var dark = 0
            for (s in scan.refs) {
                val v = luma.sample(o.x + s * ux + sign * t * wx, o.y + s * uy + sign * t * wy)
                if (v < scan.threshold) dark++
            }
            return dark.toFloat() / scan.refs.size
        }
        val candidates = ArrayList<Float>()
        candidates.add(0f)
        for (step in 1..36) {
            candidates.add(step * 0.01f)
            candidates.add(-step * 0.01f)
        }
        val signs = floatArrayOf(-1f, 1f)
        val table = Array(candidates.size) { i -> FloatArray(2) { j -> fraction(candidates[i], signs[j]) } }
        val active = (0..1).filter { j -> table.maxOf { it[j] } >= 0.6f }
        if (active.isEmpty()) return 0f
        var best = 0f
        var bestScore = -1f
        for (i in candidates.indices) {
            val score = active.map { table[i][it] }.average().toFloat()
            if (score > bestScore + 1e-4f) {
                bestScore = score
                best = candidates[i]
            }
        }
        return best
    }

    private fun refineLine(luma: Luma, a: Pt, b: Pt): List<Pt>? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 12f) return null
        val ux = dx / len
        val uy = dy / len
        val nx = -uy
        val ny = ux
        val mid = Pt((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        var offset = 0f
        var bestScore = -1
        for (candidate in floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 1.5f, -1.5f, 2f, -2f)) {
            val score = transitions(luma, Pt(mid.x + nx * candidate, mid.y + ny * candidate), ux, uy, len)
            if (score > bestScore) {
                bestScore = score
                offset = candidate
            }
        }
        val origin = Pt(mid.x + nx * offset, mid.y + ny * offset)
        val first = scanAlong(luma, origin, ux, uy, len) ?: return null
        val tilt = estimateTilt(luma, origin, ux, uy, nx, ny, len, first)
        val wl = hypot(1f, tilt)
        val e1x = (nx + tilt * ux) / wl
        val e1y = (ny + tilt * uy) / wl
        var e2x = e1y
        var e2y = -e1x
        if (e2x * ux + e2y * uy < 0f) {
            e2x = -e2x
            e2y = -e2y
        }
        var scan = if (tilt == 0f) first else scanAlong(luma, origin, e2x, e2y, len) ?: return null
        var center = origin
        var up = 0f
        var down = 0f
        for (pass in 0..1) {
            val current = scan
            val here = center
            fun darkFraction(t: Float): Float {
                var dark = 0
                for (s in current.refs) {
                    val v = luma.sample(here.x + s * e2x + t * e1x, here.y + s * e2y + t * e1y)
                    if (v < current.threshold) dark++
                }
                return dark.toFloat() / current.refs.size
            }
            val tMax = min(max(0.8f * len, 60f), 700f)
            fun extent(sign: Float): Float {
                var lastGood = 0f
                var misses = 0
                var t = 1f
                while (t <= tMax) {
                    if (darkFraction(sign * t) >= 0.6f) {
                        lastGood = t
                        misses = 0
                    } else {
                        misses++
                        if (misses >= 3) break
                    }
                    t += 1f
                }
                return lastGood + 0.5f
            }
            up = extent(-1f)
            down = extent(1f)
            if (pass == 0) {
                val shift = (down - up) / 2f
                if (kotlin.math.abs(shift) < 2f) break
                val moved = Pt(center.x + e1x * shift, center.y + e1y * shift)
                val rescan = scanAlong(luma, moved, e2x, e2y, len) ?: break
                center = moved
                scan = rescan
            }
        }
        val finalScan = scan
        val base = center
        fun p(s: Float, t: Float) = Pt(base.x + s * e2x + t * e1x, base.y + s * e2y + t * e1y)
        return listOf(
            p(finalScan.sMin, -up), p(finalScan.sMax, -up), p(finalScan.sMax, down), p(finalScan.sMin, down)
        )
    }
}
