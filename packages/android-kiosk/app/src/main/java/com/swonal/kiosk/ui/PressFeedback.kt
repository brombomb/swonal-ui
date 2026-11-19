package com.swonal.kiosk.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.swonal.kiosk.SwonalApp

/**
 * Adds crisp physical press-state feedback (scale-down + opacity dip + spring-back + haptic/click)
 * to any interactive button or pill without interfering with its OnClickListener.
 */
object PressFeedback {

    @SuppressLint("ClickableViewAccessibility")
    fun attach(vararg views: View) {
        views.forEach { attachSingle(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachSingle(view: View) {
        view.isSoundEffectsEnabled = false
        view.setOnTouchListener { v, event ->
            if (!v.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.94f)
                        .scaleY(0.94f)
                        .alpha(0.80f)
                        .setDuration(75L)
                        .start()
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_UP -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(110L)
                        .start()
                    (v.context.applicationContext as? SwonalApp)?.soundEngine?.playTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(110L)
                        .start()
                }
            }
            false
        }
    }
}
