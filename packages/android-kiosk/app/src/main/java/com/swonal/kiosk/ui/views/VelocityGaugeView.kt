package com.swonal.kiosk.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.swonal.kiosk.theme.SwonalPalette
import com.swonal.kiosk.theme.SwonalThemeTokens
import kotlin.math.abs
import kotlin.math.exp

/**
 * 60fps 5-segment Velocity & Fatigue (VBT) horizontal gauge:
 * - Pre-built clipPath & horizontal gradient in onSizeChanged()
 * - True 60fps exponential convergence via SystemClock.uptimeMillis() + postInvalidateOnAnimation()
 */
class VelocityGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var palette: SwonalPalette = SwonalThemeTokens.resolve("dark", "#00F0FF")
    private var targetFillPct: Float = 0f
    private var displayedFillPct: Float = 0f
    private var lastFrameMs: Long = 0L

    private val density = context.resources.displayMetrics.density
    private val trackRect = RectF()
    private val clipPath = Path()
    private var cachedRadius = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59000000
        strokeWidth = 1f * density
    }

    fun applyPalette(newPalette: SwonalPalette) {
        palette = newPalette
        rebuildGradient(width)
        invalidate()
    }

    fun updateFillPercent(targetPct: Float) {
        targetFillPct = targetPct.coerceIn(0f, 100f)
        if (lastFrameMs == 0L) lastFrameMs = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cachedRadius = h * 0.5f
        trackRect.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(trackRect, cachedRadius, cachedRadius, Path.Direction.CW)
        rebuildGradient(w)
    }

    private fun rebuildGradient(w: Int) {
        if (w <= 0) return
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            0f,
            intArrayOf(palette.accentPrimary, 0xFFF59E0B.toInt(), 0xFFEF4444.toInt()),
            floatArrayOf(0f, 0.60f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val nowMs = SystemClock.uptimeMillis()
        val dtMs = if (lastFrameMs > 0L) (nowMs - lastFrameMs).coerceIn(1L, 64L) else 16L
        lastFrameMs = nowMs

        var needsNextFrame = false
        val diff = targetFillPct - displayedFillPct
        if (abs(diff) > 0.25f) {
            val step = (1.0 - exp(-dtMs / 60.0)).toFloat()
            displayedFillPct += diff * step
            needsNextFrame = true
        } else {
            displayedFillPct = targetFillPct
        }

        bgPaint.color = palette.bgElevated
        canvas.drawRoundRect(trackRect, cachedRadius, cachedRadius, bgPaint)

        canvas.save()
        canvas.clipPath(clipPath)

        val fillW = w * (displayedFillPct / 100f)
        if (fillW > 0.5f) {
            canvas.drawRect(0f, 0f, fillW, h, fillPaint)
        }

        for (i in 1..4) {
            val x = w * (i * 0.20f)
            canvas.drawLine(x, 0f, x, h, dividerPaint)
        }

        canvas.restore()

        borderPaint.color = palette.borderSubtle
        canvas.drawRoundRect(trackRect, cachedRadius, cachedRadius, borderPaint)

        if (needsNextFrame) {
            postInvalidateOnAnimation()
        }
    }
}
