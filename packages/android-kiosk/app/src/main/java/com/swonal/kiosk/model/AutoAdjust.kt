package com.swonal.kiosk.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Rep-goal driven weight auto-adjustment for Exercise Routines.
 *
 * Compares the reps completed on the last set of an exercise against that set's rep goal:
 *  - missed by 2 or more  -> -10%
 *  - missed by 1          -> -5%
 *  - hit exactly          -> unchanged
 *  - above by 1           -> +5%
 *  - above by 2 or more   -> +10%
 *
 * The result is floored to a whole number in the lifter's display unit (LB or KG) and always
 * moves at least 1 display unit when an adjustment applies, so light loads never get stuck
 * (e.g. 15 lb * 1.05 = 15.75 would otherwise floor back to 15). Final weight is clamped to the
 * motor range [Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB].
 */
object AutoAdjust {

    /** First-time weight for an "Auto" routine step when the lifter has no lifting history on it. */
    const val AUTO_FIRST_TIME_WEIGHT_LB = 20.0

    /** Default weight for a new routine exercise when the lifter's Auto-Adjust Weights is OFF. */
    const val FIXED_DEFAULT_WEIGHT_LB = 45.0

    /**
     * Starting weight for a lifter's very first time on a routine step (nothing remembered yet).
     * "Auto" steps for lifters with Auto-Adjust ON start at 85% of their best weight on the exercise
     * (rounded to 5 lb) if they have history, else [AUTO_FIRST_TIME_WEIGHT_LB]. Everyone else starts
     * at the routine's own weight.
     */
    fun firstTimeWeightLb(stepWeightLb: Double, autoWeight: Boolean, autoAdjustOn: Boolean, prMaxWeightLb: Double?): Double {
        if (!autoWeight || !autoAdjustOn) return stepWeightLb.coerceIn(Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB)
        if (prMaxWeightLb != null && prMaxWeightLb > 0.0) {
            return (round((prMaxWeightLb * 0.85) / 5.0) * 5.0).coerceIn(Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB)
        }
        return AUTO_FIRST_TIME_WEIGHT_LB
    }

    data class Result(
        val weightLb: Double,
        val pctChange: Int,
        val repDelta: Int
    ) {
        val adjusted: Boolean get() = pctChange != 0
    }

    fun pctForRepDelta(repDelta: Int): Int = when {
        repDelta <= -2 -> -10
        repDelta == -1 -> -5
        repDelta == 0 -> 0
        repDelta == 1 -> 5
        else -> 10
    }

    fun nextWeightLb(lastWeightLb: Double, lastReps: Int, targetReps: Int, unit: String = "LB"): Result {
        val repDelta = lastReps - targetReps
        val pct = pctForRepDelta(repDelta)
        if (pct == 0) {
            return Result(lastWeightLb.coerceIn(Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB), 0, repDelta)
        }
        val isKg = Units.isKg(unit)
        // Work from the weight as the lifter sees it (kg is shown to 0.5 kg; the motor stores 0.5 lb steps,
        // so a "18 kg" setting is really 17.92 kg and must still count as 18 here).
        val display = Units.formatDisplayWeight(lastWeightLb, unit)
        val raw = display * (1.0 + pct / 100.0)
        // Small tolerance so 100.0 * 1.05 = 105.00000000000001 style float noise doesn't matter.
        val floored = floor(raw + 1e-9)
        val nextDisplay = if (pct > 0) {
            maxOf(floored, floor(display + 1e-9) + 1.0)
        } else {
            minOf(floored, ceil(display - 1e-9) - 1.0)
        }
        val nextLb = if (isKg) nextDisplay / Units.LB_TO_KG else nextDisplay
        return Result(nextLb.coerceIn(Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB), pct, repDelta)
    }

    /** Short reason for an adjustment, e.g. "↓5%, missed rep goal by 1 rep"; null when unchanged. */
    fun describe(result: Result): String? {
        if (!result.adjusted) return null
        val arrow = if (result.pctChange > 0) "↑" else "↓"
        val pct = kotlin.math.abs(result.pctChange)
        val reps = kotlin.math.abs(result.repDelta)
        val repWord = if (reps == 1) "rep" else "reps"
        val why = if (result.repDelta > 0) "beat rep goal by $reps $repWord" else "missed rep goal by $reps $repWord"
        return "$arrow$pct%, $why"
    }
}
