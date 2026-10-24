package com.localscanner.qr

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class ScanFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val arc = RectF()
    private var lock = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
    }

    fun setLocked(locked: Boolean) {
        val target = if (locked) 1f else 0f
        if (lock == target && animator == null) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(lock, target).apply {
            duration = 160L
            addUpdateListener {
                lock = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val side = min(w, h) * 0.70f * (1f - 0.06f * lock)
        val cx = w / 2f
        val cy = h * 0.44f
        val left = cx - side / 2f
        val top = cy - side / 2f
        val right = left + side
        val bottom = top + side
        val r = side * 0.14f
        paint.alpha = (205 + 50 * lock).toInt()

        arc.set(left, top, left + 2 * r, top + 2 * r)
        canvas.drawArc(arc, 180f, 90f, false, paint)
        arc.set(right - 2 * r, top, right, top + 2 * r)
        canvas.drawArc(arc, 270f, 90f, false, paint)
        arc.set(right - 2 * r, bottom - 2 * r, right, bottom)
        canvas.drawArc(arc, 0f, 90f, false, paint)
        arc.set(left, bottom - 2 * r, left + 2 * r, bottom)
        canvas.drawArc(arc, 90f, 90f, false, paint)
    }
}
