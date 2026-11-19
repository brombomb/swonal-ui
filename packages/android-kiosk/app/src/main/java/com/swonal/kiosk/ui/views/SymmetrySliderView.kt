package com.swonal.kiosk.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.swonal.kiosk.model.SymmetryMetrics
import com.swonal.kiosk.model.SymmetryStatus
import com.swonal.kiosk.theme.SwonalPalette
import com.swonal.kiosk.theme.SwonalThemeTokens
import kotlin.math.abs
import kotlin.math.exp

/**
 * 60fps Recessed Form Symmetry & Balance slider with 25%-opacity center notch tick behind a moving pill puck.
 */
class SymmetrySliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var palette: SwonalPalette = SwonalThemeTokens.resolve("dark", "#00F0FF")
    private var targetPuckPercent: Float = 50f
    private var puckPercent: Float = 50f
    private var lastFrameMs: Long = 0L
    private var status: SymmetryStatus = SymmetryStatus.BALANCED

    private val density = context.resources.displayMetrics.density
    private val trackRect = RectF()
    private val puckRect = RectF()

    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val puckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun applyPalette(newPalette: SwonalPalette) {
        palette = newPalette
        invalidate()
    }

    fun updateSymmetry(metrics: SymmetryMetrics) {
        targetPuckPercent = metrics.puckPercent
        status = metrics.status
        if (lastFrameMs == 0L) lastFrameMs = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val nowMs = SystemClock.uptimeMillis()
        val dtMs = if (lastFrameMs > 0L) (nowMs - lastFrameMs).coerceIn(1L, 64L) else 16L
        lastFrameMs = nowMs

        var needsNextFrame = false
        val diff = targetPuckPercent - puckPercent
        if (abs(diff) > 0.15f) {
            val step = (1.0 - exp(-dtMs / 55.0)).toFloat()
            puckPercent += diff * step
            needsNextFrame = true
        } else {
            puckPercent = targetPuckPercent
        }

        val trackH = (11f * density).coerceAtMost(h * 0.72f)
        val trackTop = (h - trackH) * 0.5f
        trackRect.set(0f, trackTop, w, trackTop + trackH)

        // Track capsule
        trackPaint.color = palette.bgElevated
        canvas.drawRoundRect(trackRect, trackH * 0.5f, trackH * 0.5f, trackPaint)
        borderPaint.color = palette.borderSubtle
        canvas.drawRoundRect(trackRect, trackH * 0.5f, trackH * 0.5f, borderPaint)

        // Center notch tick behind puck (25% opacity)
        notchPaint.color = palette.notchTickColor
        val notchW = 1.8f * density
        canvas.drawRect(w * 0.5f - notchW * 0.5f, 0f, w * 0.5f + notchW * 0.5f, h, notchPaint)

        // Moving puck
        val puckW = 20f * density
        val puckInset = 1.5f * density
        val puckH = (trackH - puckInset * 2f).coerceAtLeast(4f)
        val centerX = (w * (puckPercent / 100f)).coerceIn(puckW * 0.5f + 2f, w - puckW * 0.5f - 2f)
        puckRect.set(
            centerX - puckW * 0.5f,
            trackTop + puckInset,
            centerX + puckW * 0.5f,
            trackTop + puckInset + puckH
        )

        puckPaint.color = when (status) {
            SymmetryStatus.CRITICAL -> 0xFFEF4444.toInt()
            SymmetryStatus.DRIFT -> 0xFFF59E0B.toInt()
            SymmetryStatus.BALANCED -> palette.accentPrimary
        }
        canvas.drawRoundRect(puckRect, puckH * 0.5f, puckH * 0.5f, puckPaint)

        if (needsNextFrame) {
            postInvalidateOnAnimation()
        }
    }
}
