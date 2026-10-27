package com.localscanner.qr

import android.view.View

object UiUtil {
    /**
     * Keeps a screen's content at most [maxDp] wide and centred on wide displays (tablets,
     * landscape). The extra space becomes side padding. The padding is applied after the current
     * layout pass: changing it inside the pass leaves the children laid out at their old
     * position, which clipped the left edge and pushed controls off the right edge.
     */
    fun constrainWidth(view: View, maxDp: Int) {
        val maxPx = (maxDp * view.resources.displayMetrics.density).toInt()
        val baseLeft = view.paddingLeft
        val baseRight = view.paddingRight
        val update = Runnable {
            val extra = ((view.width - baseLeft - baseRight - maxPx) / 2).coerceAtLeast(0)
            if (view.paddingLeft != baseLeft + extra || view.paddingRight != baseRight + extra) {
                view.setPadding(baseLeft + extra, view.paddingTop, baseRight + extra, view.paddingBottom)
            }
        }
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.removeCallbacks(update)
            v.post(update)
        }
    }

    fun columns(view: View, minColumnDp: Int, max: Int): Int {
        val widthDp = view.resources.configuration.screenWidthDp
        return (widthDp / minColumnDp).coerceIn(1, max)
    }
}
