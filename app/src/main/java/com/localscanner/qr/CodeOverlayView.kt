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
        val effect: CornerPathEffect,
        val chipText: String,
        val chip: RectF
    )

    var onItemTap: ((DetectedCode) -> Unit)? = null

    var gestureForwarder: ((MotionEvent) -> Unit)? = null

    var showBoxes = true
        set(value) {
            field = value
            invalidate()
        }
    var showLabels = true
        set(value) {
            field = value
            invalidate()
        }

    private var zoomScale = 1f
    private var zoomTx = 0f
    private var zoomTy = 0f
    private var downX = 0f
    private var downY = 0f
    private var tapCancelled = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

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
    private val chipFillRisky = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(235, 62, 38, 0)
    }
    private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * scaledDensity
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

    fun setTransform(scale: Float, tx: Float, ty: Float) {
        zoomScale = scale
        zoomTx = tx
        zoomTy = ty
        rebuild()
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
        val maxChipW = min(vw - 24f * density, 220f * density)
        val chipH = 24f * density
        val padH = 10f * density
        val list = ArrayList<Drawn>()
        for (item in items) {
            val corners = item.code.box()
            if (corners.size < 4) continue
            val mapped = corners.map {
                val p = CodeGeometry.mapPoint(it, srcW.toFloat(), srcH.toFloat(), vw, vh, fit, mirror)
                Pt((p.x - vw / 2f) * zoomScale + vw / 2f + zoomTx, (p.y - vh / 2f) * zoomScale + vh / 2f + zoomTy)
            }
            val quad = CodeGeometry.inflate(mapped, 2.5f * density)
            if (quad.maxOf { it.x } < 0f || quad.minOf { it.x } > vw ||
                quad.maxOf { it.y } < 0f || quad.minOf { it.y } > vh
            ) {
                continue
            }
            val path = Path().apply {
                moveTo(quad[0].x, quad[0].y)
                for (i in 1 until quad.size) lineTo(quad[i].x, quad[i].y)
                close()
            }
            val cx = quad.map { it.x }.average().toFloat()
            val cy = quad.map { it.y }.average().toFloat()
            val shader = SweepGradient(cx, cy, RAINBOW, null)
            val shortSide = min(quad.maxOf { it.x } - quad.minOf { it.x }, quad.maxOf { it.y } - quad.minOf { it.y })
            val effect = CornerPathEffect((shortSide * 0.05f).coerceIn(2f * density, 8f * density))
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
            list.add(Drawn(item, quad, path, cx, cy, shader, effect, text, RectF(left, top, left + chipW, top + chipH)))
        }
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
            if (showBoxes) {
                strokePaint.pathEffect = d.effect
                fillPaint.pathEffect = d.effect
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
            }
            if (showLabels) {
                val r = d.chip.height() / 2f
                canvas.drawRoundRect(d.chip, r, r, if (d.item.risky) chipFillRisky else chipFill)
                if (d.item.risky) {
                    chipStroke.shader = null
                    chipStroke.color = Color.parseColor("#FFC107")
                } else {
                    chipStroke.shader = d.shader
                }
                canvas.drawRoundRect(d.chip, r, r, chipStroke)
                val baseline = d.chip.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(d.chipText, d.chip.left + 10f * density, baseline, textPaint)
            }
        }
    }

    private fun hit(x: Float, y: Float): Drawn? {
        if (!showBoxes && !showLabels) return null
        for (d in drawn.asReversed()) {
            if (showLabels && d.chip.contains(x, y)) return d
            if (showBoxes &&
                (CodeGeometry.contains(d.quad, x, y) || hypot(x - d.cx, y - d.cy) < 28f * density)
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
                if (downTarget == null) return false
                downX = event.x
                downY = event.y
                tapCancelled = false
                gestureForwarder?.invoke(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapCancelled = true
                gestureForwarder?.invoke(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > touchSlop) tapCancelled = true
                gestureForwarder?.invoke(event)
                return downTarget != null
            }
            MotionEvent.ACTION_UP -> {
                val target = downTarget
                downTarget = null
                gestureForwarder?.invoke(event)
                if (target != null && !tapCancelled && hit(event.x, event.y) === target) {
                    performClick()
                    onItemTap?.invoke(target.item.code)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                downTarget = null
                gestureForwarder?.invoke(event)
            }
            else -> gestureForwarder?.invoke(event)
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
        private val RAINBOW = intArrayOf(
            Color.parseColor("#FF1744"),
            Color.parseColor("#FF9100"),
            Color.parseColor("#FFEA00"),
            Color.parseColor("#00E676"),
            Color.parseColor("#00B0FF"),
            Color.parseColor("#2979FF"),
            Color.parseColor("#D500F9"),
            Color.parseColor("#FF1744")
        )
    }
}
