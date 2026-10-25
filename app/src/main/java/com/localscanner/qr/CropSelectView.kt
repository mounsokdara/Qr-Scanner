package com.localscanner.qr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class CropSelectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onSelected: ((RectF) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var drawing = false
    private var hasRect = false

    fun reset() {
        drawing = false
        hasRect = false
        invalidate()
    }

    private fun selection() = RectF(
        min(startX, endX).coerceIn(0f, width.toFloat()),
        min(startY, endY).coerceIn(0f, height.toFloat()),
        max(startX, endX).coerceIn(0f, width.toFloat()),
        max(startY, endY).coerceIn(0f, height.toFloat())
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                drawing = true
                hasRect = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (drawing) {
                endX = event.x
                endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (drawing) {
                drawing = false
                endX = event.x
                endY = event.y
                val rect = selection()
                if (rect.width() >= 40f * density && rect.height() >= 40f * density) {
                    performClick()
                    onSelected?.invoke(rect)
                } else {
                    hasRect = false
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!hasRect) {
            canvas.drawRect(0f, 0f, w, h, dim)
            return
        }
        val r = selection()
        canvas.drawRect(0f, 0f, w, r.top, dim)
        canvas.drawRect(0f, r.bottom, w, h, dim)
        canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
        canvas.drawRect(r.right, r.top, w, r.bottom, dim)
        canvas.drawRect(r, border)
        val hr = 5f * density
        canvas.drawCircle(r.left, r.top, hr, handle)
        canvas.drawCircle(r.right, r.top, hr, handle)
        canvas.drawCircle(r.right, r.bottom, hr, handle)
        canvas.drawCircle(r.left, r.bottom, hr, handle)
    }
}
