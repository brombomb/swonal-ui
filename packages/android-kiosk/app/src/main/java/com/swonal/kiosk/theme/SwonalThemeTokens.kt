package com.swonal.kiosk.theme

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import com.swonal.kiosk.model.ColorMath

data class AccentSwatch(val name: String, val hex: String)

/**
 * Custom beveled surface drawable rendering the exact asymmetric top-lit border from `index.css`:
 * top edge (`borderTopHighlight`), left/right (`borderSubtle`), bottom edge (`borderBottomFade`),
 * plus vertical surface gradient or solid fill, with proper Outline support for Material shadows.
 */
class BeveledSurfaceDrawable(
    private val topFill: Int,
    private val bottomFill: Int,
    private val radiusPx: Float,
    private val sideBorderColor: Int,
    private val topHighlightColor: Int,
    private val bottomFadeColor: Int,
    private val strokeWidthPx: Float = 2f
) : Drawable() {

    private val rectF = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val half = strokeWidthPx * 0.5f
        rectF.set(
            bounds.left + half,
            bounds.top + half,
            bounds.right - half,
            bounds.bottom - half
        )
        if (topFill != bottomFill && bounds.height() > 0) {
            fillPaint.shader = LinearGradient(
                0f,
                bounds.top.toFloat(),
                0f,
                bounds.bottom.toFloat(),
                topFill,
                bottomFill,
                Shader.TileMode.CLAMP
            )
        } else {
            fillPaint.shader = null
            fillPaint.color = topFill
        }
        if (bounds.height() > 0) {
            borderPaint.shader = LinearGradient(
                0f,
                bounds.top.toFloat(),
                0f,
                bounds.bottom.toFloat(),
                intArrayOf(topHighlightColor, sideBorderColor, sideBorderColor, bottomFadeColor),
                floatArrayOf(0f, 0.14f, 0.86f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(rectF, radiusPx, radiusPx, fillPaint)
        canvas.drawRoundRect(rectF, radiusPx, radiusPx, borderPaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radiusPx)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        borderPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

data class SwonalPalette(
    val isDark: Boolean,
    val accentPrimary: Int,
    val accentGlow: Int,
    val accentMuted: Int,
    val accentInk: Int,
    val accentTextColor: Int,
    val bgCanvas: Int,
    val bgCanvasCenter: Int,
    val bgSurfaceTop: Int,
    val bgSurfaceBottom: Int,
    val bgSurfaceSolid: Int,
    val bgElevated: Int,
    val bgSecondaryBar: Int,
    val bgDialog: Int,
    val textPrimary: Int,
    val textMuted: Int,
    val borderSubtle: Int,
    val borderTopHighlight: Int,
    val borderBottomFade: Int,
    val gridLineColor: Int,
    val notchTickColor: Int,
    val dialIdleCenter: Int,
    val dialIdleMid: Int,
    val dialIdleEdge: Int,
    val warningBg: Int,
    val warningBorder: Int,
    val warningText: Int,
    val criticalBg: Int,
    val criticalBorder: Int,
    val criticalText: Int,
    val successBg: Int,
    val successText: Int
) {
    private fun wrapRipple(content: Drawable, radiusPx: Float, interactive: Boolean): Drawable {
        if (!interactive) return content
        val rippleColor = if (isDark) 0x48FFFFFF else 0x330F172A
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radiusPx
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    fun surfaceCardDrawable(
        dp: Float,
        radiusDp: Float = 18f,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 1f
    ): Drawable {
        val radiusPx = radiusDp * dp
        val strokePx = (strokeWidthDp * dp).coerceAtLeast(1f)
        if (strokeColor != null) {
            return BeveledSurfaceDrawable(
                topFill = bgSurfaceTop,
                bottomFill = bgSurfaceBottom,
                radiusPx = radiusPx,
                sideBorderColor = strokeColor,
                topHighlightColor = strokeColor,
                bottomFadeColor = strokeColor,
                strokeWidthPx = strokePx
            )
        }
        return BeveledSurfaceDrawable(
            topFill = bgSurfaceTop,
            bottomFill = bgSurfaceBottom,
            radiusPx = radiusPx,
            sideBorderColor = borderSubtle,
            topHighlightColor = borderTopHighlight,
            bottomFadeColor = borderBottomFade,
            strokeWidthPx = strokePx
        )
    }

    fun solidSurfaceDrawable(dp: Float, radiusDp: Float = 16f, strokeColor: Int = borderTopHighlight): Drawable {
        return BeveledSurfaceDrawable(
            topFill = bgSurfaceSolid,
            bottomFill = bgSurfaceSolid,
            radiusPx = radiusDp * dp,
            sideBorderColor = borderSubtle,
            topHighlightColor = strokeColor,
            bottomFadeColor = borderBottomFade,
            strokeWidthPx = (1f * dp).coerceAtLeast(1f)
        )
    }

    fun secondaryBarDrawable(dp: Float, radiusDp: Float = 18f): Drawable {
        return BeveledSurfaceDrawable(
            topFill = bgSecondaryBar,
            bottomFill = bgSecondaryBar,
            radiusPx = radiusDp * dp,
            sideBorderColor = borderSubtle,
            topHighlightColor = borderTopHighlight,
            bottomFadeColor = borderBottomFade,
            strokeWidthPx = (1f * dp).coerceAtLeast(1f)
        )
    }

    fun elevatedPillDrawable(
        dp: Float,
        radiusDp: Float = 12f,
        strokeColor: Int = borderSubtle,
        strokeWidthDp: Float = 1f,
        interactive: Boolean = true
    ): Drawable {
        val base = BeveledSurfaceDrawable(
            topFill = bgElevated,
            bottomFill = bgElevated,
            radiusPx = radiusDp * dp,
            sideBorderColor = strokeColor,
            topHighlightColor = if (strokeColor == borderSubtle) borderTopHighlight else strokeColor,
            bottomFadeColor = if (strokeColor == borderSubtle) borderBottomFade else strokeColor,
            strokeWidthPx = (strokeWidthDp * dp).coerceAtLeast(1f)
        )
        return wrapRipple(base, radiusDp * dp, interactive)
    }

    fun accentPillDrawable(dp: Float, radiusDp: Float = 12f, interactive: Boolean = true): Drawable {
        val base = GradientDrawable().apply {
            setColor(accentPrimary)
            cornerRadius = radiusDp * dp
        }
        return wrapRipple(base, radiusDp * dp, interactive)
    }

    fun accentMutedDrawable(
        dp: Float,
        radiusDp: Float = 12f,
        strokeWidthDp: Float = 1.5f,
        interactive: Boolean = true
    ): Drawable {
        val base = GradientDrawable().apply {
            setColor(accentMuted)
            cornerRadius = radiusDp * dp
            setStroke((strokeWidthDp * dp).toInt().coerceAtLeast(1), accentInk)
        }
        return wrapRipple(base, radiusDp * dp, interactive)
    }

    fun successPillDrawable(dp: Float, radiusDp: Float = 8f, interactive: Boolean = true): Drawable {
        val base = GradientDrawable().apply {
            setColor(successBg)
            cornerRadius = radiusDp * dp
            setStroke((1f * dp).toInt().coerceAtLeast(1), successText)
        }
        return wrapRipple(base, radiusDp * dp, interactive)
    }

    fun recessedStripDrawable(dp: Float, radiusDp: Float = 18f): Drawable {
        return GradientDrawable().apply {
            setColor(if (isDark) 0x07FFFFFF else 0x0A0F172A)
            cornerRadius = radiusDp * dp
            setStroke((1f * dp).toInt().coerceAtLeast(1), borderSubtle)
        }
    }

    fun dialogCardDrawable(dp: Float, radiusDp: Float = 20f): Drawable {
        return BeveledSurfaceDrawable(
            topFill = bgDialog,
            bottomFill = bgDialog,
            radiusPx = radiusDp * dp,
            sideBorderColor = borderSubtle,
            topHighlightColor = borderTopHighlight,
            bottomFadeColor = borderBottomFade,
            strokeWidthPx = (1f * dp).coerceAtLeast(1f)
        )
    }

    fun warningPillDrawable(dp: Float, radiusDp: Float = 12f, interactive: Boolean = false): Drawable {
        val base = GradientDrawable().apply {
            setColor(warningBg)
            cornerRadius = radiusDp * dp
            setStroke((1f * dp).toInt().coerceAtLeast(1), warningBorder)
        }
        return wrapRipple(base, radiusDp * dp, interactive)
    }

    fun criticalBannerDrawable(dp: Float, radiusDp: Float = 14f, interactive: Boolean = false): Drawable {
        val base = GradientDrawable().apply {
            setColor(criticalBg)
            cornerRadius = radiusDp * dp
            setStroke((2f * dp).toInt().coerceAtLeast(2), criticalBorder)
        }
        return wrapRipple(base, radiusDp * dp, interactive)
    }

    fun circleSwatchDrawable(color: Int, dp: Float, selected: Boolean): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) {
                setStroke((2.5f * dp).toInt().coerceAtLeast(2), if (isDark) Color.WHITE else 0xFF0F172A.toInt())
            }
        }
    }
}

object SwonalThemeTokens {
    val ACCENT_SWATCHES = listOf(
        AccentSwatch("Electric Cyan", "#00F0FF"),
        AccentSwatch("Hyper Pink", "#FF007A"),
        AccentSwatch("Volt Lime", "#CCFF00"),
        AccentSwatch("Blaze Orange", "#FF5500"),
        AccentSwatch("Ultra Violet", "#8B5CF6"),
        AccentSwatch("Emerald Green", "#10B981"),
        AccentSwatch("Crimson Red", "#EF4444"),
        AccentSwatch("Dark Blue", "#1D4ED8")
    )

    fun isSystemNight(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode != Configuration.UI_MODE_NIGHT_NO
    }

    fun resolve(themeMode: String, accentHex: String, systemNight: Boolean = true): SwonalPalette {
        val isDark = when (themeMode.lowercase()) {
            "light" -> false
            "system" -> systemNight
            else -> true
        }
        val accent = ColorMath.parseHexColor(accentHex, 0xFF00F0FF.toInt())
        val accentGlow = ColorMath.withAlpha(accent, 0.35f)
        val accentMuted = ColorMath.withAlpha(accent, 0.15f)
        val accentInk = if (isDark) accent else ColorMath.mixAccentInkLight(accent)
        val accentText = ColorMath.getContrastTextColor(accent)

        return if (isDark) {
            SwonalPalette(
                isDark = true,
                accentPrimary = accent,
                accentGlow = accentGlow,
                accentMuted = accentMuted,
                accentInk = accentInk,
                accentTextColor = accentText,
                bgCanvas = 0xFF06090E.toInt(),
                bgCanvasCenter = 0xFF0A101D.toInt(),
                bgSurfaceTop = 0xE0101726.toInt(),
                bgSurfaceBottom = 0xEB0A0F1A.toInt(),
                bgSurfaceSolid = 0xF00C121E.toInt(),
                bgElevated = 0xB81A2438.toInt(),
                bgSecondaryBar = 0xC7121A2A.toInt(),
                bgDialog = 0xF50A0F19.toInt(),
                textPrimary = 0xFFF8FAFC.toInt(),
                textMuted = 0xFF94A3B8.toInt(),
                borderSubtle = 0x12FFFFFF,
                borderTopHighlight = 0x24FFFFFF,
                borderBottomFade = 0x08FFFFFF,
                gridLineColor = 0x0CFFFFFF,
                notchTickColor = 0x40FFFFFF,
                dialIdleCenter = 0xF21C273C.toInt(),
                dialIdleMid = 0xFA0C121E.toInt(),
                dialIdleEdge = 0xFF070A12.toInt(),
                warningBg = 0x26F59E0B,
                warningBorder = 0x66F59E0B,
                warningText = 0xFFFBBF24.toInt(),
                criticalBg = 0x26EF4444,
                criticalBorder = 0xFFEF4444.toInt(),
                criticalText = 0xFFFCA5A5.toInt(),
                successBg = 0x4010B981,
                successText = 0xFF6EE7B7.toInt()
            )
        } else {
            SwonalPalette(
                isDark = false,
                accentPrimary = accent,
                accentGlow = accentGlow,
                accentMuted = accentMuted,
                accentInk = accentInk,
                accentTextColor = accentText,
                bgCanvas = 0xFFE9EEF5.toInt(),
                bgCanvasCenter = 0xFFFFFFFF.toInt(),
                bgSurfaceTop = 0xF5FFFFFF.toInt(),
                bgSurfaceBottom = 0xF0F7F9FC.toInt(),
                bgSurfaceSolid = 0xF7FFFFFF.toInt(),
                bgElevated = 0xD9E2E8F0.toInt(),
                bgSecondaryBar = 0xE0EEF2F8.toInt(),
                bgDialog = 0xF7FFFFFF.toInt(),
                textPrimary = 0xFF0F172A.toInt(),
                textMuted = 0xFF475569.toInt(),
                borderSubtle = 0x290F172A,
                borderTopHighlight = 0x240F172A,
                borderBottomFade = 0x380F172A,
                gridLineColor = 0x090F172A,
                notchTickColor = 0x400F172A,
                dialIdleCenter = 0xFFFFFFFF.toInt(),
                dialIdleMid = 0xFFF1F5F9.toInt(),
                dialIdleEdge = 0xFFE2E8F0.toInt(),
                warningBg = 0x2EF59E0B,
                warningBorder = 0x73D97706,
                warningText = 0xFF78350F.toInt(),
                criticalBg = 0x24EF4444,
                criticalBorder = 0xFFDC2626.toInt(),
                criticalText = 0xFF991B1B.toInt(),
                successBg = 0x3310B981,
                successText = 0xFF065F46.toInt()
            )
        }
    }

    fun dp(context: Context): Float = context.resources.displayMetrics.density
}
