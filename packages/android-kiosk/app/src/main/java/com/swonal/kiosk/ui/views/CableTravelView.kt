package com.swonal.kiosk.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.swonal.kiosk.model.CableData
import com.swonal.kiosk.model.ColorMath
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalPalette
import com.swonal.kiosk.theme.SwonalThemeTokens
import kotlin.math.roundToInt

/**
 * 60fps zero-allocation Custom Canvas Cable Travel Bar matching `KioskCableBar.tsx`:
 * - Pre-built `clipPath` and `(0, 0)`-centered `RadialGradient` translated in `onDraw()` (zero native shader churn!)
 * - Exact circular flush-bottom traveling puck (`puckH = innerRect.width()`) with zero crescent gap
 * - Pre-formatted `tabular-nums` strings updated only in `updateCable()`
 */
class CableTravelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var label: String = "LEFT"
        set(value) {
            field = value
            invalidate()
        }

    private var palette: SwonalPalette = SwonalThemeTokens.resolve("dark", "#00F0FF")
    private var travelFraction: Float = 0f
    private var cachedLoadStr: String = "0.0 LB"
    private var cachedVelStr: String = "0.0 in/s"
    private var cachedRomStr: String = "ROM 0"
    private var lastLoadTenth: Int = Int.MIN_VALUE
    private var lastVelTenth: Int = Int.MIN_VALUE
    private var lastRomInt: Int = Int.MIN_VALUE
    private var lastUnit: String = ""

    private val density = context.resources.displayMetrics.density
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val trailRect = RectF()
    private val puckRect = RectF()
    private val clipPath = Path()

    private var cachedCx = 0f
    private var cachedTopLabelY = 0f
    private var cachedOuterRadius = 0f
    private var cachedInnerRadius = 0f
    private var cachedPuckH = 0f
    private var cachedPuckGlowR = 0f
    private var cachedY1 = 0f
    private var cachedY2 = 0f
    private var cachedY3 = 0f

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = SwonalFonts.monoExtraBold(context)
        letterSpacing = 0.14f
        textSize = 11.5f * density
    }
    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val puckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val puckGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val loadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = SwonalFonts.monoExtraBold(context)
        fontFeatureSettings = "tnum"
        textSize = 14.5f * density
    }
    private val velocityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = SwonalFonts.monoBold(context)
        fontFeatureSettings = "tnum"
        textSize = 12f * density
    }
    private val romPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = SwonalFonts.monoMedium(context)
        fontFeatureSettings = "tnum"
        textSize = 11f * density
    }

    fun applyPalette(newPalette: SwonalPalette) {
        palette = newPalette
        rebuildPuckGlowShader()
        invalidate()
    }

    fun updateCable(newCable: CableData, unit: String) {
        travelFraction = Units.cableTravelPercent(newCable.position) / 100f

        val loadTenth = (newCable.loadLb * 10.0).roundToInt()
        val velTenth = (newCable.velocity * 10.0).roundToInt()
        val romInt = newCable.position.roundToInt()
        val unitChanged = unit != lastUnit

        if (unitChanged || loadTenth != lastLoadTenth) {
            lastLoadTenth = loadTenth
            cachedLoadStr = "${Units.formatExactWeight(newCable.loadLb, unit)} ${unit.uppercase()}"
        }
        if (unitChanged || velTenth != lastVelTenth) {
            lastVelTenth = velTenth
            cachedVelStr = Units.formatVelocity(newCable.velocity, unit)
        }
        if (romInt != lastRomInt) {
            lastRomInt = romInt
            cachedRomStr = "ROM $romInt"
        }
        lastUnit = unit

        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxH = resources.getDimensionPixelSize(com.swonal.kiosk.R.dimen.kiosk_cable_height)
        val desiredW = resources.getDimensionPixelSize(com.swonal.kiosk.R.dimen.kiosk_cable_width)
        val w = resolveSize(desiredW, widthMeasureSpec)
        val availH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maxH
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        val h = kotlin.math.min(maxH, availH).coerceAtLeast((180f * density).toInt())
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val wf = w.toFloat()
        val hf = h.toFloat()
        cachedCx = wf * 0.5f
        cachedTopLabelY = 14f * density

        val bottomBlockH = 56f * density
        val trackTop = cachedTopLabelY + 10f * density
        val trackBottom = (hf - bottomBlockH).coerceAtLeast(trackTop + 80f * density)
        val trackW = (48f * density).coerceAtMost(wf * 0.62f)
        cachedOuterRadius = trackW * 0.5f

        outerRect.set(cachedCx - trackW * 0.5f, trackTop, cachedCx + trackW * 0.5f, trackBottom)

        val inset = 2f * density
        innerRect.set(
            outerRect.left + inset,
            outerRect.top + inset,
            outerRect.right - inset,
            outerRect.bottom - inset
        )
        cachedInnerRadius = (innerRect.width() * 0.5f).coerceAtLeast(4f)
        // Exact circular puck matching inner capsule corner radius (zero corner curvature gap at 0%/100%)
        cachedPuckH = innerRect.width().coerceAtMost(innerRect.height() * 0.35f)
        cachedPuckGlowR = cachedPuckH * 0.95f

        clipPath.reset()
        clipPath.addRoundRect(innerRect, cachedInnerRadius, cachedInnerRadius, Path.Direction.CW)

        cachedY1 = trackBottom + 19f * density
        cachedY2 = cachedY1 + 16f * density
        cachedY3 = cachedY2 + 14.5f * density

        rebuildPuckGlowShader()
    }

    private fun rebuildPuckGlowShader() {
        if (cachedPuckGlowR <= 0f) return
        // Single (0, 0)-centered shader translated in onDraw — zero allocations per frame!
        puckGlowPaint.shader = RadialGradient(
            0f,
            0f,
            cachedPuckGlowR,
            intArrayOf(palette.accentGlow, Color.TRANSPARENT),
            floatArrayOf(0.25f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (cachedOuterRadius <= 0f) return
        val cx = cachedCx

        // Top label
        labelPaint.color = ColorMath.withAlpha(palette.textPrimary, 0.75f)
        canvas.drawText(label, cx, cachedTopLabelY, labelPaint)

        // Outer track capsule
        trackBgPaint.color = palette.bgElevated
        canvas.drawRoundRect(outerRect, cachedOuterRadius, cachedOuterRadius, trackBgPaint)
        trackBorderPaint.color = palette.borderSubtle
        canvas.drawRoundRect(outerRect, cachedOuterRadius, cachedOuterRadius, trackBorderPaint)

        // Traveling puck geometry
        val availableTravel = (innerRect.height() - cachedPuckH).coerceAtLeast(0f)
        val puckBottom = innerRect.bottom - travelFraction * availableTravel
        val puckTop = puckBottom - cachedPuckH
        val puckCenterY = (puckTop + puckBottom) * 0.5f

        canvas.save()
        canvas.clipPath(clipPath)

        // Trail fill anchored at bottom, filling up to the center point of the circle puck
        if (travelFraction > 0.002f) {
            trailPaint.color = ColorMath.withAlpha(palette.accentPrimary, 0.30f)
            trailRect.set(innerRect.left, puckCenterY, innerRect.right, innerRect.bottom)
            canvas.drawRect(trailRect, trailPaint)
        }

        // Puck radial glow (translated cached shader)
        canvas.save()
        canvas.translate(cx, puckCenterY)
        canvas.drawCircle(0f, 0f, cachedPuckGlowR, puckGlowPaint)
        canvas.restore()

        // Flush-bottom matching-radius puck
        puckRect.set(innerRect.left, puckTop, innerRect.right, puckBottom)
        puckPaint.color = palette.accentPrimary
        canvas.drawRoundRect(puckRect, cachedInnerRadius, cachedInnerRadius, puckPaint)

        canvas.restore()

        // Bottom fixed-width tabular-nums readouts (pre-formatted strings)
        loadPaint.color = palette.textPrimary
        canvas.drawText(cachedLoadStr, cx, cachedY1, loadPaint)

        velocityPaint.color = ColorMath.withAlpha(palette.textPrimary, 0.88f)
        canvas.drawText(cachedVelStr, cx, cachedY2, velocityPaint)

        romPaint.color = ColorMath.withAlpha(palette.textMuted, 0.90f)
        canvas.drawText(cachedRomStr, cx, cachedY3, romPaint)
    }
}
