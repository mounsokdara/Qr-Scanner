package com.localscanner.qr

import android.view.View

object UiUtil {
    fun constrainWidth(view: View, maxDp: Int) {
        val maxPx = (maxDp * view.resources.displayMetrics.density).toInt()
        val baseLeft = view.paddingLeft
        val baseRight = view.paddingRight
        view.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val extra = ((right - left - baseLeft - baseRight - maxPx) / 2).coerceAtLeast(0)
            if (v.paddingLeft != baseLeft + extra || v.paddingRight != baseRight + extra) {
                v.setPadding(baseLeft + extra, v.paddingTop, baseRight + extra, v.paddingBottom)
            }
        }
    }

    fun columns(view: View, minColumnDp: Int, max: Int): Int {
        val widthDp = view.resources.configuration.screenWidthDp
        return (widthDp / minColumnDp).coerceIn(1, max)
    }
}
