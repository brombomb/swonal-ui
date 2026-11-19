package com.swonal.kiosk.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.swonal.kiosk.model.ColorMath
import com.swonal.kiosk.theme.SwonalPalette
import com.swonal.kiosk.theme.SwonalThemeTokens
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hardware-accelerated atmospheric background matching `index.css` & `KioskView.tsx`:
 * - Bakes the static radial canvas + 36px crosshair/blueprint grid + 4px anodized noise grain +
 *   ambient blooms into a single cached Bitmap on size/palette change (zero per-frame line loops!)
 * - Renders only 1 `drawBitmap()` + optional pulsing rest vignette rect per frame
 */
class AtmosphericBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var palette: SwonalPalette = SwonalThemeTokens.resolve("dark", "#00F0FF")
    private val density = context.resources.displayMetrics.density
    private val gridStepPx = 27f * density

    private var cachedBackdrop: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val restEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var restActive = false
    private var restPulse = 0f
    private var restAnimator: ValueAnimator? = null

    fun applyPalette(newPalette: SwonalPalette) {
        palette = newPalette
        rebuildCache(width, height)
        invalidate()
    }

    fun setRestTimerActive(active: Boolean) {
        if (restActive == active) return
        restActive = active
        if (active) {
            restAnimator?.cancel()
            restAnimator = ValueAnimator.ofFloat(0.25f, 0.72f).apply {
                duration = 800L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    restPulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            restAnimator?.cancel()
            restAnimator = null
            restPulse = 0f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        restAnimator?.cancel()
        cachedBackdrop?.recycle()
        cachedBackdrop = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildCache(w, h)
    }

    private fun rebuildCache(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        restEdgePaint.shader = RadialGradient(
            w * 0.5f,
            h * 0.5f,
            max(w, h) * 0.62f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, palette.accentGlow),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )

        cachedBackdrop?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()

        // 1. Base radial gradient canvas
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                wf * 0.5f,
                hf * 0.25f,
                max(wf, hf) * 0.78f,
                palette.bgCanvasCenter,
                palette.bgCanvas,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, wf, hf, bgPaint)

        // 2. Tiled 36px crosshair (dark) or hairline blueprint grid (light) via BitmapShader
        val tileSize = gridStepPx.roundToInt().coerceAtLeast(16)
        val gridTile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val gc = Canvas(gridTile)
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.gridLineColor
            strokeWidth = max(1f, density * 0.8f)
            strokeCap = Paint.Cap.SQUARE
        }
        if (palette.isDark) {
            val mid = tileSize * 0.5f
            val arm = 3.2f * density
            gc.drawLine(mid - arm, mid, mid + arm, mid, gp)
            gc.drawLine(mid, mid - arm, mid, mid + arm, gp)
        } else {
            gc.drawLine(0f, 0f, tileSize.toFloat(), 0f, gp)
            gc.drawLine(0f, 0f, 0f, tileSize.toFloat(), gp)
        }
        val gridPaint = Paint().apply {
            shader = BitmapShader(gridTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        c.drawRect(0f, 0f, wf, hf, gridPaint)
        gridTile.recycle()

        // 3. Subtle 4px anodized dot-stipple grain (`.swonal-noise-grain`)
        val grainSize = (3f * density).roundToInt().coerceAtLeast(3)
        val grainTile = Bitmap.createBitmap(grainSize, grainSize, Bitmap.Config.ARGB_8888)
        val grC = Canvas(grainTile)
        val grainAlpha = if (palette.isDark) 0x09 else 0x06
        val grainColor = if (palette.isDark) (grainAlpha shl 24) or 0xFFFFFF else (grainAlpha shl 24) or 0x0F172A
        grC.drawPoint(1f, 1f, Paint().apply { color = grainColor })
        val grainPaint = Paint().apply {
            shader = BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        c.drawRect(0f, 0f, wf, hf, grainPaint)
        grainTile.recycle()

        // 4. Top-right & bottom-left ambient accent blooms
        val topR = wf * 0.48f
        val topBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                wf - topR * 0.15f,
                topR * 0.12f,
                topR,
                intArrayOf(ColorMath.withAlpha(palette.accentMuted, 0.80f), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, wf, hf, topBloomPaint)

        val botR = wf * 0.38f
        val bottomBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                botR * 0.10f,
                hf - botR * 0.10f,
                botR,
                intArrayOf(ColorMath.withAlpha(palette.accentMuted, 0.45f), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, wf, hf, bottomBloomPaint)

        cachedBackdrop = bmp
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = cachedBackdrop
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        } else {
            canvas.drawColor(palette.bgCanvas)
        }

        if (restActive && restPulse > 0f) {
            restEdgePaint.alpha = (restPulse * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), restEdgePaint)
        }
    }
}
