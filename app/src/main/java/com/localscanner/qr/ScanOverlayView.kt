package com.localscanner.qr

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val laser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.STROKE
    }
    private val frame = RectF()
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val boxWidth = width * 0.84f
        val boxHeight = height * 0.30f
        val left = (width - boxWidth) / 2f
        val top = height * 0.24f
        frame.set(left, top, left + boxWidth, top + boxHeight)

        val thin = Paint(bracket).apply { strokeWidth = dp(1.2f) }
        canvas.drawRect(frame, thin)

        bracket.strokeWidth = dp(4f)
        val length = dp(28f)
        corner(canvas, frame.left, frame.top + length, frame.left, frame.top, frame.left + length, frame.top)
        corner(canvas, frame.right - length, frame.top, frame.right, frame.top, frame.right, frame.top + length)
        corner(canvas, frame.left, frame.bottom - length, frame.left, frame.bottom, frame.left + length, frame.bottom)
        corner(canvas, frame.right, frame.bottom - length, frame.right, frame.bottom, frame.right - length, frame.bottom)

        laser.strokeWidth = dp(2f)
        val y = frame.top + frame.height() * (0.08f + 0.84f * phase)
        canvas.drawLine(frame.left + dp(10f), y, frame.right - dp(10f), y, laser)
    }

    private fun corner(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        canvas.drawLine(x1, y1, x2, y2, bracket)
        canvas.drawLine(x2, y2, x3, y3, bracket)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
