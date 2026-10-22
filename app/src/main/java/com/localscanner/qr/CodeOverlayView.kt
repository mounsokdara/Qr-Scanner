package com.localscanner.qr

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a glowing gradient border around every detected code, with a short tappable label chip
 * (Google Lens style). Touches that miss a code fall through to the views underneath so
 * tap-to-focus and pinch-to-zoom keep working.
 */
class CodeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class Item(val code: DetectedCode, val label: String, val risky: Boolean)

    private class Drawn(
        val item: Item,
        val quad: List<Pt>,
        val path: Path,
        val cx: Float,
        val cy: Float,
        val shader: SweepGradient,
        val chipText: String,
        val chip: RectF
    )

    var onItemTap: ((DetectedCode) -> Unit)? = null

    private var items: List<Item> = emptyList()
    private var srcW = 0
    private var srcH = 0
    private var fit = false
    private var mirror = false
    private var drawn: List<Drawn> = emptyList()

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val matrix = Matrix()
    private var phase = 0f
    private var pulse = 1f
    private var animator: ValueAnimator? = null
    private var downTarget: Drawn? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        pathEffect = CornerPathEffect(14f * density)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(14f * density)
    }
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(224, 20, 20, 24)
    }
    private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }

    private val chipTopMargin = 64f * density
    private val chipBottomMargin = 160f * density

    fun setCodes(newItems: List<Item>, sourceW: Int, sourceH: Int, fitCenter: Boolean, mirrored: Boolean) {
        items = newItems
        srcW = sourceW
        srcH = sourceH
        fit = fitCenter
        mirror = mirrored
        rebuild()
        updateAnimator()
        invalidate()
    }

    fun clear() {
        if (items.isEmpty() && drawn.isEmpty()) return
        items = emptyList()
        drawn = emptyList()
        updateAnimator()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimator()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun updateAnimator() {
        val shouldRun = drawn.isNotEmpty() && isAttachedToWindow
        if (shouldRun && animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    pulse = 0.8f + 0.2f * sin(phase * 4f * PI.toFloat())
                    invalidate()
                }
                start()
            }
        } else if (!shouldRun) {
            animator?.cancel()
            animator = null
        }
    }

    private fun rebuild() {
        if (width == 0 || height == 0 || srcW == 0 || srcH == 0 || items.isEmpty()) {
            drawn = emptyList()
            return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        val maxChipW = min(vw - 24f * density, 280f * density)
        val chipH = 30f * density
        val padH = 12f * density
        val list = ArrayList<Drawn>()
        for (item in items) {
            val corners = CodeGeometry.corners(item.code.format, item.code.points)
            if (corners.size < 4) continue
            val quad = corners.map {
                CodeGeometry.mapPoint(it, srcW.toFloat(), srcH.toFloat(), vw, vh, fit, mirror)
            }
            val path = Path().apply {
                moveTo(quad[0].x, quad[0].y)
                for (i in 1 until quad.size) lineTo(quad[i].x, quad[i].y)
                close()
            }
            val cx = quad.map { it.x }.average().toFloat()
            val cy = quad.map { it.y }.average().toFloat()
            val colors = if (item.risky) RISKY else NORMAL
            val shader = SweepGradient(cx, cy, colors, null)
            val text = TextUtils.ellipsize(
                item.label, textPaint, maxChipW - 2 * padH, TextUtils.TruncateAt.END
            ).toString()
            val chipW = textPaint.measureText(text) + 2 * padH
            val minY = quad.minOf { it.y }
            val maxY = quad.maxOf { it.y }
            var top = minY - chipH - 8f * density
            if (top < chipTopMargin) top = maxY + 8f * density
            if (top + chipH > vh - chipBottomMargin) top = cy - chipH / 2f
            top = top.coerceIn(chipTopMargin, max(chipTopMargin, vh - chipBottomMargin - chipH))
            val left = (cx - chipW / 2f).coerceIn(8f * density, max(8f * density, vw - chipW - 8f * density))
            list.add(Drawn(item, quad, path, cx, cy, shader, text, RectF(left, top, left + chipW, top + chipH)))
        }
        // Nudge overlapping chips apart so neighbouring labels stay readable.
        val placed = ArrayList<RectF>()
        for (d in list.sortedBy { it.chip.top }) {
            var guard = 0
            while (placed.any { RectF.intersects(it, d.chip) } && guard++ < 8) {
                val bottom = placed.filter { RectF.intersects(it, d.chip) }.maxOf { it.bottom }
                d.chip.offsetTo(d.chip.left, bottom + 6f * density)
            }
            placed.add(d.chip)
        }
        drawn = list
    }

    override fun onDraw(canvas: Canvas) {
        val list = drawn
        if (list.isEmpty()) return
        for (d in list) {
            matrix.setRotate(phase * 360f, d.cx, d.cy)
            d.shader.setLocalMatrix(matrix)
            strokePaint.shader = d.shader
            for (i in GLOW_WIDTHS.indices) {
                strokePaint.strokeWidth = GLOW_WIDTHS[i] * density
                strokePaint.alpha = (GLOW_ALPHA[i] * pulse * 255f).toInt().coerceIn(0, 255)
                canvas.drawPath(d.path, strokePaint)
            }
            strokePaint.strokeWidth = 3f * density
            strokePaint.alpha = 255
            canvas.drawPath(d.path, strokePaint)
            fillPaint.shader = d.shader
            fillPaint.alpha = 30
            canvas.drawPath(d.path, fillPaint)

            val r = d.chip.height() / 2f
            canvas.drawRoundRect(d.chip, r, r, chipFill)
            chipStroke.shader = d.shader
            canvas.drawRoundRect(d.chip, r, r, chipStroke)
            val baseline = d.chip.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(d.chipText, d.chip.left + 12f * density, baseline, textPaint)
        }
    }

    private fun hit(x: Float, y: Float): Drawn? {
        for (d in drawn.asReversed()) {
            if (d.chip.contains(x, y) ||
                CodeGeometry.contains(d.quad, x, y) ||
                hypot(x - d.cx, y - d.cy) < 28f * density
            ) {
                return d
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTarget = hit(event.x, event.y)
                return downTarget != null
            }
            MotionEvent.ACTION_UP -> {
                val target = downTarget
                downTarget = null
                if (target != null && hit(event.x, event.y) === target) {
                    performClick()
                    onItemTap?.invoke(target.item.code)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> downTarget = null
        }
        return downTarget != null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private val GLOW_WIDTHS = floatArrayOf(20f, 13f, 8f)
        private val GLOW_ALPHA = floatArrayOf(0.10f, 0.17f, 0.28f)
        private val NORMAL = intArrayOf(
            Color.parseColor("#00E5FF"),
            Color.parseColor("#2979FF"),
            Color.parseColor("#B388FF"),
            Color.parseColor("#FF4081"),
            Color.parseColor("#00E5FF")
        )
        private val RISKY = intArrayOf(
            Color.parseColor("#FFC107"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#FF5722"),
            Color.parseColor("#FF1744"),
            Color.parseColor("#FFC107")
        )
    }
}
