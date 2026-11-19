package com.swonal.kiosk.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.swonal.kiosk.model.ColorMath
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.theme.SwonalFonts
import com.swonal.kiosk.theme.SwonalPalette
import com.swonal.kiosk.theme.SwonalThemeTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 60fps zero-allocation custom Canvas Load Dial matching `KioskView.tsx` 620px dial:
 * - Precomputed major/minor tick FloatArrays (2 drawLines calls total)
 * - Continuous 60fps exponential LERP via SystemClock.uptimeMillis() + postInvalidateOnAnimation()
 * - Subtle pulsing `WEIGHT ON` pill while engaged
 * - Safety-first touch handling: when `weightEnabled == true`, ANY tap on the center disk disengages
 *   weight immediately (never accidentally opens the keypad during live load!).
 */
class LoadDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onToggleWeight: (() -> Unit)? = null
    var onEditWeightClick: (() -> Unit)? = null

    private var palette: SwonalPalette = SwonalThemeTokens.resolve("dark", "#00F0FF")
    private var weightEnabled: Boolean = false
    private var targetWeightLb: Double = 25.0
    private var appliedResistanceLb: Double = 0.0
    private var activeUnit: String = "LB"

    private var targetProgress: Float = 0.125f
    private var animatedProgress: Float = 0.125f
    private var lastFrameMs: Long = 0L

    private var engagedFactor: Float = 0f
    private var engageAnimator: ValueAnimator? = null
    private var pressed: Boolean = false
    private var downOnWeightNumber: Boolean = false

    // Cached text strings & metrics (zero allocations in onDraw)
    private var cachedWeightStr: String = "25"
    private var cachedUnitLabel: String = "TARGET LB"
    private var cachedPillLabel: String = "WEIGHT OFF"
    private var cachedNumHalfW: Float = 60f
    private val pillFm = Paint.FontMetrics()

    private val arcRect = RectF()
    private val pillRect = RectF()
    private val weightTextHitRect = RectF()

    private var cachedCx = 0f
    private var cachedCy = 0f
    private var cachedDialRadius = 0f
    private var cachedDiskRadius = 0f
    private var cachedScale = 1f

    // Precomputed major (12 * 4) and minor (48 * 4) tick segments
    private val majorTickPts = FloatArray(12 * 4)
    private val minorTickPts = FloatArray(48 * 4)

    // Pre-allocated Paints
    private val spotlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val diskFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val diskRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val diskBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val diskGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val weightTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = SwonalFonts.monoExtraBold(context)
        fontFeatureSettings = "tnum"
    }
    private val unitLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = SwonalFonts.interExtraBold(context)
        letterSpacing = 0.22f
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = SwonalFonts.interBlack(context)
        letterSpacing = 0.14f
    }

    private var lastShaderEngaged: Boolean? = null

    fun applyPalette(newPalette: SwonalPalette) {
        palette = newPalette
        lastShaderEngaged = null
        rebuildShaders()
        invalidate()
    }

    fun updateTelemetry(
        enabled: Boolean,
        targetLb: Double,
        appliedLb: Double,
        unit: String
    ) {
        val stateChanged = (enabled != weightEnabled)
        weightEnabled = enabled
        targetWeightLb = targetLb
        appliedResistanceLb = appliedLb
        activeUnit = unit

        val shown = if (enabled) appliedLb else targetLb
        targetProgress = (shown / Units.MAX_WEIGHT_LB).coerceIn(0.025, 1.0).toFloat()

        val newWeightStr = Units.formatDialWeightString(shown, unit, exact = enabled)
        if (newWeightStr != cachedWeightStr) {
            cachedWeightStr = newWeightStr
            recomputeWeightTextMetrics()
        }
        cachedUnitLabel = "${if (enabled) "APPLIED" else "TARGET"} ${unit.uppercase()}"
        cachedPillLabel = if (enabled) "WEIGHT ON" else "WEIGHT OFF"

        if (stateChanged) {
            engageAnimator?.cancel()
            engageAnimator = ValueAnimator.ofFloat(engagedFactor, if (enabled) 1f else 0f).apply {
                duration = 280L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    engagedFactor = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            lastShaderEngaged = null
            rebuildShaders()
        }

        if (lastFrameMs == 0L) lastFrameMs = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        engageAnimator?.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxDialPx = resources.getDimensionPixelSize(com.swonal.kiosk.R.dimen.kiosk_dial_size)
        val availW = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maxDialPx
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val availH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maxDialPx
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        val side = min(maxDialPx, min(availW, availH)).coerceAtLeast((180f * resources.displayMetrics.density).toInt())
        setMeasuredDimension(side, side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedCx = w * 0.5f
        cachedCy = h * 0.5f
        val minDim = min(w, h).toFloat()
        cachedDialRadius = minDim * 0.445f
        cachedDiskRadius = cachedDialRadius * 0.768f
        cachedScale = cachedDialRadius / 276f
        arcRect.set(
            cachedCx - cachedDialRadius,
            cachedCy - cachedDialRadius,
            cachedCx + cachedDialRadius,
            cachedCy + cachedDialRadius
        )
        rebuildTickGeometry()
        recomputeWeightTextMetrics()
        lastShaderEngaged = null
        rebuildShaders()
    }

    private fun rebuildTickGeometry() {
        val rOuter = 250f * cachedScale
        val rMajorInner = 238f * cachedScale
        val rMinorInner = 243f * cachedScale
        var majIdx = 0
        var minIdx = 0
        for (i in 0 until 60) {
            val rad = (i * 6.0 - 90.0) * PI / 180.0
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            if (i % 5 == 0) {
                majorTickPts[majIdx++] = cachedCx + rMajorInner * c
                majorTickPts[majIdx++] = cachedCy + rMajorInner * s
                majorTickPts[majIdx++] = cachedCx + rOuter * c
                majorTickPts[majIdx++] = cachedCy + rOuter * s
            } else {
                minorTickPts[minIdx++] = cachedCx + rMinorInner * c
                minorTickPts[minIdx++] = cachedCy + rMinorInner * s
                minorTickPts[minIdx++] = cachedCx + rOuter * c
                minorTickPts[minIdx++] = cachedCy + rOuter * s
            }
        }
    }

    private fun recomputeWeightTextMetrics() {
        if (cachedScale <= 0f) return
        weightTextPaint.textSize = (if (cachedWeightStr.length >= 4) 114f else 140f) * cachedScale
        cachedNumHalfW = (weightTextPaint.measureText(cachedWeightStr) * 0.52f).coerceAtLeast(52f * cachedScale)
        pillTextPaint.textSize = 18f * cachedScale
        pillTextPaint.getFontMetrics(pillFm)
    }

    private fun rebuildShaders() {
        if (cachedDialRadius <= 0f) return
        spotlightPaint.shader = RadialGradient(
            cachedCx,
            cachedCy,
            cachedDialRadius * 1.24f,
            intArrayOf(palette.accentGlow, Color.TRANSPARENT),
            floatArrayOf(0f, 0.70f),
            Shader.TileMode.CLAMP
        )

        diskGlowPaint.shader = RadialGradient(
            cachedCx,
            cachedCy,
            cachedDiskRadius * 1.18f,
            intArrayOf(palette.accentGlow, Color.TRANSPARENT),
            floatArrayOf(0.58f, 1f),
            Shader.TileMode.CLAMP
        )

        // Subtle inner top-rim highlight & bottom inner shadow for the idle disk
        diskRimPaint.shader = LinearGradient(
            cachedCx,
            cachedCy - cachedDiskRadius,
            cachedCx,
            cachedCy + cachedDiskRadius,
            if (palette.isDark) 0x1AFFFFFF else 0xCCFFFFFF.toInt(),
            if (palette.isDark) 0x59000000 else 0x1F0F172A,
            Shader.TileMode.CLAMP
        )

        rebuildDiskShader(weightEnabled)
    }

    private fun rebuildDiskShader(engaged: Boolean) {
        if (cachedDiskRadius <= 0f || lastShaderEngaged == engaged) return
        lastShaderEngaged = engaged
        diskFillPaint.shader = if (engaged) {
            RadialGradient(
                cachedCx,
                cachedCy - cachedDiskRadius * 0.28f,
                cachedDiskRadius * 1.25f,
                intArrayOf(palette.accentMuted, palette.bgSurfaceSolid),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                cachedCx,
                cachedCy - cachedDiskRadius * 0.34f,
                cachedDiskRadius * 1.28f,
                intArrayOf(palette.dialIdleCenter, palette.dialIdleMid, palette.dialIdleEdge),
                floatArrayOf(0f, 0.68f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cachedDialRadius <= 0f) return
        val nowMs = SystemClock.uptimeMillis()
        val dtMs = if (lastFrameMs > 0L) (nowMs - lastFrameMs).coerceIn(1L, 64L) else 16L
        lastFrameMs = nowMs

        // Smooth 60fps exponential convergence (tau = 65ms)
        var needsNextFrame = false
        val diff = targetProgress - animatedProgress
        if (abs(diff) > 0.0015f) {
            val alphaStep = (1.0 - exp(-dtMs / 65.0)).toFloat()
            animatedProgress += diff * alphaStep
            needsNextFrame = true
        } else {
            animatedProgress = targetProgress
        }

        val cx = cachedCx
        val cy = cachedCy
        val r = cachedDialRadius
        val scale = cachedScale

        // 1. Continuous ambient radial spotlight (25% idle -> 95% engaged)
        val spotAlpha = (0.25f + engagedFactor * 0.70f).coerceIn(0f, 1f)
        val spotScale = 1f + engagedFactor * 0.05f
        spotlightPaint.alpha = (spotAlpha * 255f).toInt()
        canvas.save()
        canvas.scale(spotScale, spotScale, cx, cy)
        canvas.drawCircle(cx, cy, r * 1.24f, spotlightPaint)
        canvas.restore()

        // 2. Background track ring
        val ringStroke = 36f * scale
        trackPaint.strokeWidth = ringStroke
        trackPaint.color = palette.bgElevated
        canvas.drawCircle(cx, cy, r, trackPaint)

        // 3. 60-Tick concentric compass ring (2 batched drawLines calls)
        val baseTickColor = if (weightEnabled) palette.accentPrimary else palette.textMuted
        majorTickPaint.strokeWidth = 2.2f * scale
        majorTickPaint.color = ColorMath.withAlpha(baseTickColor, if (weightEnabled) 0.58f else 0.34f)
        canvas.drawLines(majorTickPts, majorTickPaint)

        minorTickPaint.strokeWidth = 1.1f * scale
        minorTickPaint.color = ColorMath.withAlpha(baseTickColor, 0.17f)
        canvas.drawLines(minorTickPts, minorTickPaint)

        // 4. Progress arc
        progressPaint.strokeWidth = ringStroke
        progressPaint.color = if (weightEnabled) {
            palette.accentPrimary
        } else {
            ColorMath.withAlpha(palette.textMuted, 0.38f)
        }
        canvas.drawArc(arcRect, -90f, animatedProgress * 360f, false, progressPaint)

        // 5. Center interactive disk
        val diskScale = (1f + engagedFactor * 0.02f) * (if (pressed) 0.985f else 1f)
        canvas.save()
        canvas.scale(diskScale, diskScale, cx, cy)

        if (engagedFactor > 0.02f) {
            diskGlowPaint.alpha = (engagedFactor * 220f).toInt()
            canvas.drawCircle(cx, cy, cachedDiskRadius * 1.18f, diskGlowPaint)
        }

        rebuildDiskShader(weightEnabled)
        canvas.drawCircle(cx, cy, cachedDiskRadius, diskFillPaint)

        // Inner bevel rim on idle disk
        if (!weightEnabled) {
            diskRimPaint.strokeWidth = 3f * scale
            canvas.drawCircle(cx, cy, cachedDiskRadius - 2.5f * scale, diskRimPaint)
        }

        diskBorderPaint.strokeWidth = 4f * scale
        diskBorderPaint.color = if (weightEnabled) palette.accentPrimary else palette.borderSubtle
        canvas.drawCircle(cx, cy, cachedDiskRadius, diskBorderPaint)

        // 6. Center weight number
        weightTextPaint.color = palette.textPrimary
        val weightBaseline = cy + 12f * scale
        canvas.drawText(cachedWeightStr, cx, weightBaseline, weightTextPaint)

        // Tight hitbox scaled to screen coordinates (only active when weight is OFF)
        weightTextHitRect.set(
            cx - cachedNumHalfW * diskScale,
            cy + (weightBaseline - weightTextPaint.textSize * 0.80f - cy) * diskScale,
            cx + cachedNumHalfW * diskScale,
            cy + (weightBaseline + 8f * scale - cy) * diskScale
        )

        // 7. Unit label
        unitLabelPaint.textSize = 20f * scale
        unitLabelPaint.color = ColorMath.withAlpha(palette.textPrimary, 0.80f)
        val unitBaseline = weightBaseline + 38f * scale
        canvas.drawText(cachedUnitLabel, cx, unitBaseline, unitLabelPaint)

        // 8. Status pill ("WEIGHT ON" pulses gently while engaged)
        val pillW = 214f * scale
        val pillH = 54f * scale
        val pillTop = unitBaseline + 24f * scale
        pillRect.set(cx - pillW * 0.5f, pillTop, cx + pillW * 0.5f, pillTop + pillH)

        val pillPulseAlpha = if (weightEnabled) {
            needsNextFrame = true
            val phase = (nowMs % 1600L) / 1600.0 * 2.0 * PI
            (0.82 + 0.18 * sin(phase)).toFloat()
        } else {
            1f
        }

        pillBgPaint.color = if (weightEnabled) {
            ColorMath.withAlpha(palette.accentPrimary, pillPulseAlpha)
        } else {
            palette.bgElevated
        }
        canvas.drawRoundRect(pillRect, pillH * 0.5f, pillH * 0.5f, pillBgPaint)

        pillBorderPaint.strokeWidth = 2f * scale
        pillBorderPaint.color = if (weightEnabled) palette.accentPrimary else palette.borderTopHighlight
        canvas.drawRoundRect(pillRect, pillH * 0.5f, pillH * 0.5f, pillBorderPaint)

        pillTextPaint.color = if (weightEnabled) palette.accentTextColor else palette.textPrimary
        val pillTextY = pillRect.centerY() - (pillFm.ascent + pillFm.descent) * 0.5f
        canvas.drawText(cachedPillLabel, cx, pillTextY, pillTextPaint)

        canvas.restore()

        if (needsNextFrame) {
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dist = hypot(event.x - cachedCx, event.y - cachedCy)
        val insideDisk = dist <= cachedDiskRadius * 1.05f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!insideDisk) return false
                pressed = true
                // Safety-first: only allow keypad tap when weight is OFF!
                downOnWeightNumber = !weightEnabled && weightTextHitRect.contains(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val startedOnNum = downOnWeightNumber
                pressed = false
                downOnWeightNumber = false
                invalidate()
                if (insideDisk) {
                    if (!weightEnabled && startedOnNum && weightTextHitRect.contains(event.x, event.y)) {
                        onEditWeightClick?.invoke()
                    } else {
                        onToggleWeight?.invoke()
                    }
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                downOnWeightNumber = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
