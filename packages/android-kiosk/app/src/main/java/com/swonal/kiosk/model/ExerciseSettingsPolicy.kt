package com.swonal.kiosk.model

import kotlin.math.round

/** Weight / mode / accessory to load for an exercise. Null fields mean "leave as is". */
data class ExerciseSettings(
    val weightLb: Double?,
    val mode: String?,
    val modifierPct: Int?,
    val accessory: String?,
    /** True when these come from the lifter's remembered settings (not catalog/routine defaults). */
    val remembered: Boolean
)

/** How a finished set changed the lifter's remembered weight (Auto-Adjust ON, routine sets only). */
sealed class WeightChange {
    data class Adjusted(val newWeightLb: Double, val pctChange: Int, val repDelta: Int) : WeightChange()
    data class AtMaximum(val weightLb: Double) : WeightChange()
    data class AtMinimum(val weightLb: Double) : WeightChange()
}

/** What to remember after a finished set, and how the weight changed (null = unchanged). */
data class SetOutcome(
    val pref: ExercisePref,
    val change: WeightChange?
)

/**
 * Pure decision rules for remembered settings and Auto-Adjust Weights (no I/O, unit-testable).
 *
 * - When a set finishes (weight released with at least one rep), the lifter's weight, mode, modifier and
 *   accessory are remembered per exercise. With Auto-Adjust ON and a routine rep goal, the remembered
 *   weight is the *adjusted* weight for next time.
 * - Loading a routine step or Free Lift exercise then simply uses the remembered settings. A routine's own
 *   weight is only the starting weight for a lifter's first time on an exercise.
 */
object ExerciseSettingsPolicy {

    /**
     * @param prMaxWeightLb the lifter's best weight on this exercise (used for first-time AUTO steps and
     *   for a Work-In partner's first time)
     * @param workInPartner true when a Partner Work-In rotation hands this step to the next partner
     */
    fun forRoutineStep(
        step: RoutineStep,
        exercise: Exercise?,
        pref: ExercisePref?,
        autoAdjustOn: Boolean,
        prMaxWeightLb: Double?,
        workInPartner: Boolean = false
    ): ExerciseSettings {
        if (pref == null) {
            val firstTimeLb = if (workInPartner && prMaxWeightLb != null && prMaxWeightLb > 0.0) {
                // Work-In keeps its personal start: 85% of the partner's best weight
                prBasedStartLb(prMaxWeightLb)
            } else {
                AutoAdjust.firstTimeWeightLb(step.targetWeightLb, step.autoWeight, autoAdjustOn, prMaxWeightLb)
            }
            return ExerciseSettings(
                weightLb = firstTimeLb,
                mode = step.mode,
                modifierPct = step.modifierPct,
                accessory = exercise?.accessory,
                remembered = false
            )
        }
        return ExerciseSettings(pref.weightLb, pref.mode, pref.modifierPct, pref.accessory, remembered = true)
    }

    /** Modes that add load on top of the weight (chains / eccentric / smart flex). */
    private val LOAD_MODES = setOf("chains", "eccentric", "smart_flex")

    /**
     * Free Lift: the remembered settings, else the suggestion. On an exercise the lifter has never done,
     * load-adding modes (chains / eccentric / smart flex) from the previous exercise reset to standard so
     * they don't leak onto it; assist modes like Spotter stay as they are.
     */
    fun forFreeLift(
        exercise: Exercise,
        pref: ExercisePref?,
        fallbackWeightLb: Double?,
        currentMode: String? = null
    ): ExerciseSettings {
        if (pref == null) {
            val resetMode = currentMode?.lowercase(java.util.Locale.US) in LOAD_MODES
            return ExerciseSettings(
                fallbackWeightLb,
                if (resetMode) "standard" else null,
                if (resetMode) 0 else null,
                exercise.accessory,
                remembered = false
            )
        }
        return ExerciseSettings(pref.weightLb, pref.mode, pref.modifierPct, pref.accessory, remembered = true)
    }

    /** 85% of a best weight, rounded to 5 lb (the existing Work-In / profile-switch suggestion). */
    fun prBasedStartLb(prMaxWeightLb: Double): Double =
        (round((prMaxWeightLb * 0.85) / 5.0) * 5.0).coerceIn(Units.MIN_WEIGHT_LB, Units.MAX_WEIGHT_LB)

    fun afterSet(
        userId: String,
        exerciseId: String,
        weightLb: Double,
        mode: String,
        modifierPct: Int,
        accessory: String,
        reps: Int,
        routineTargetReps: Int?,
        autoAdjustOn: Boolean,
        unit: String,
        /** A safety cutout (e.g. bar tilt) ended the set: remember it, but don't treat it as a missed goal. */
        safetyCutout: Boolean = false
    ): SetOutcome {
        var savedLb = weightLb
        var change: WeightChange? = null
        if (autoAdjustOn && routineTargetReps != null && !safetyCutout) {
            val result = AutoAdjust.nextWeightLb(weightLb, reps, routineTargetReps, unit)
            if (result.adjusted) {
                // What the motor will actually accept (dynamic modes like chains lower the ceiling)
                val applied = Units.clampWeightForDynamicMode(result.weightLb, mode, Units.effectiveModifierPct(mode, modifierPct))
                change = when {
                    result.pctChange > 0 && applied <= weightLb -> WeightChange.AtMaximum(weightLb)
                    result.pctChange < 0 && applied >= weightLb -> WeightChange.AtMinimum(weightLb)
                    else -> {
                        savedLb = applied
                        WeightChange.Adjusted(applied, result.pctChange, result.repDelta)
                    }
                }
            }
        }
        return SetOutcome(
            pref = ExercisePref(
                userId = userId,
                exerciseId = exerciseId,
                weightLb = savedLb,
                mode = mode,
                modifierPct = modifierPct,
                accessory = accessory,
                lastReps = reps,
                lastTargetReps = routineTargetReps
            ),
            change = change
        )
    }

    /**
     * Message for a weight change. [exerciseName] is shown when the next set isn't this exercise (superset,
     * last set, end of workout), so the lifter never mistakes it for the weight that loads next.
     */
    fun changeMessage(change: WeightChange, unit: String, exerciseName: String?, nextSetIsSameExercise: Boolean): String {
        val prefix = if (nextSetIsSameExercise || exerciseName == null) null else exerciseName
        return when (change) {
            is WeightChange.Adjusted -> {
                val reason = AutoAdjust.describe(AutoAdjust.Result(change.newWeightLb, change.pctChange, change.repDelta))
                val head = if (prefix != null) "$prefix next time" else "Next set"
                "$head: ${format(change.newWeightLb, unit)} ($reason)"
            }
            is WeightChange.AtMaximum ->
                (if (prefix != null) "$prefix: m" else "M") + "aximum weight reached (${format(change.weightLb, unit)})"
            is WeightChange.AtMinimum ->
                (if (prefix != null) "$prefix: m" else "M") + "inimum weight reached (${format(change.weightLb, unit)})"
        }
    }

    fun format(weightLb: Double, unit: String): String =
        "${Units.formatCompactWeight(weightLb, unit)} ${if (Units.isKg(unit)) "kg" else "lb"}"

    /** Message when a new lifter's/step's weight is waiting for the weight to be released. */
    fun deferredMessage(weightLb: Double, unit: String, lifterName: String?): String {
        val who = lifterName?.let { "$it's weight" } ?: "Next weight"
        return "$who (${format(weightLb, unit)}) loads when you release the weight"
    }

    /**
     * Toast after a set is logged: the set's weight change (prefixed with the lifter's name when someone
     * else lifts next) and, separately, the weight that is waiting for release.
     */
    fun postSetMessage(
        changeText: String?,
        lifterName: String?,
        someoneElseNext: Boolean,
        deferredWeightLb: Double?,
        deferredUnit: String,
        nextLifterName: String?
    ): String? {
        val parts = listOfNotNull(
            changeText?.let { if (someoneElseNext && lifterName != null) "$lifterName: $it" else it },
            deferredWeightLb?.let { deferredMessage(it, deferredUnit, if (someoneElseNext) nextLifterName else null) }
        )
        return parts.joinToString("\n").ifBlank { null }
    }
}
