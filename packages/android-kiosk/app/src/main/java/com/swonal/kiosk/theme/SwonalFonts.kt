package com.swonal.kiosk.theme

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.swonal.kiosk.R

/**
 * Cached static Inter & JetBrains Mono Typefaces (API 23 compatible — static TTF weights, not variable fonts).
 */
object SwonalFonts {
    @Volatile private var interRegular: Typeface? = null
    @Volatile private var interSemiBold: Typeface? = null
    @Volatile private var interBold: Typeface? = null
    @Volatile private var interExtraBold: Typeface? = null
    @Volatile private var interBlack: Typeface? = null

    @Volatile private var monoMedium: Typeface? = null
    @Volatile private var monoBold: Typeface? = null
    @Volatile private var monoExtraBold: Typeface? = null

    fun interRegular(context: Context): Typeface =
        interRegular ?: load(context, R.font.inter_400, Typeface.SANS_SERIF).also { interRegular = it }

    fun interSemiBold(context: Context): Typeface =
        interSemiBold ?: load(context, R.font.inter_600, Typeface.DEFAULT_BOLD).also { interSemiBold = it }

    fun interBold(context: Context): Typeface =
        interBold ?: load(context, R.font.inter_700, Typeface.DEFAULT_BOLD).also { interBold = it }

    fun interExtraBold(context: Context): Typeface =
        interExtraBold ?: load(context, R.font.inter_800, Typeface.DEFAULT_BOLD).also { interExtraBold = it }

    fun interBlack(context: Context): Typeface =
        interBlack ?: load(context, R.font.inter_900, Typeface.DEFAULT_BOLD).also { interBlack = it }

    fun monoMedium(context: Context): Typeface =
        monoMedium ?: load(context, R.font.jetbrains_mono_500, Typeface.MONOSPACE).also { monoMedium = it }

    fun monoBold(context: Context): Typeface =
        monoBold ?: load(context, R.font.jetbrains_mono_700, Typeface.MONOSPACE).also { monoBold = it }

    fun monoExtraBold(context: Context): Typeface =
        monoExtraBold ?: load(context, R.font.jetbrains_mono_800, Typeface.MONOSPACE).also { monoExtraBold = it }

    private fun load(context: Context, resId: Int, fallback: Typeface): Typeface {
        return runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull() ?: fallback
    }
}
