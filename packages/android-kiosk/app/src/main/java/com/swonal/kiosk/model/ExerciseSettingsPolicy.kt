package com.swonal.kiosk.model

import kotlin.math.round

/** Weight / mode / accessory to load for an exercise. Null fields mean "leave as is". */
data class ExerciseSettings(
    val weightLb: Double?,
    val mode: String?,
    val modifierPct: Int?,
    val accessory: String?,
    /** True when the weight/mode come from the lifter's remembered settings (not catalog/routine defaults). */
    val remembered: Boolean,
    /** True when [accessory] is the lifter's remembered attachment rather than the catalog's. */
    val accessoryRemembered: Boolean = false
)

/** How a finished set changed the lifter's remembered weight (Auto-Adjust ON, AUTO routine steps only). */
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
 * - A routine step's own prescription wins: its mode and modifier always load, and so does its weight
 *   unless it is an AUTO step and the lifter has Auto-Adjust ON. Only then is the lifter's remembered
 *   weight from their last AUTO set at the *same rep goal* used (adjusted by how that set went).
 * - Free Lift restores what the lifter last did in Free Lift on that exercise. Free Lift and routine
 *   memories are kept apart, so a heavy Free Lift single never becomes a routine weight.
 * - A remembered attachment is never restored on an exercise whose catalog attachment is the BAR unless it
 *   is the BAR: the daemon only runs its bar-tilt cutout with the BAR attachment active.
 */
object ExerciseSettingsPolicy {

    /**
     * @param pref the lifter's remembered settings for this exercise at this step's rep goal
     *   ([ExercisePref.routineScope]); ignored unless the step is AUTO and Auto-Adjust is ON
     * @param lastAccessory the attachment the lifter last used on this exercise (any scope), if any
     * @param prMaxWeightLb / [prOneRepMaxLb] the lifter's best weight and estimated 1RM on this exercise
     *   (first-time AUTO steps, and a Work-In partner's first time)
     * @param workInPartner true when a Partner Work-In rotation hands this step to the next partner
     */
    fun forRoutineStep(
        step: RoutineStep,
        exercise: Exercise?,
        pref: ExercisePref?,
        autoAdjustOn: Boolean,
        prMaxWeightLb: Double?,
        workInPartner: Boolean = false,
        prOneRepMaxLb: Double? = null,
        lastAccessory: String? = null
    ): ExerciseSettings {
        val autoStep = step.autoWeight && autoAdjustOn
        val rememberedPref = pref?.takeIf { autoStep && it.scope == ExercisePref.routineScope(step.targetReps) }
        val weightLb = when {
            rememberedPref != null -> rememberedPref.weightLb
            // Work-In keeps its personal start on fixed steps: 85% of the partner's best weight
            workInPartner && !autoStep && prMaxWeightLb != null && prMaxWeightLb > 0.0 -> prBasedStartLb(prMaxWeightLb)
            else -> AutoAdjust.firstTimeWeightLb(
                step.targetWeightLb, step.autoWeight, autoAdjustOn, prMaxWeightLb, prOneRepMaxLb, step.targetReps
            )
        }
        val (accessory, accessoryRemembered) = accessoryFor(exercise, rememberedPref?.accessory ?: lastAccessory)
        return ExerciseSettings(
            weightLb = weightLb,
            // The step's mode always loads: a remembered chains/eccentric mode must never add load to a
            // step written as standard or Spotter
            mode = step.mode,
            modifierPct = step.modifierPct,
            accessory = accessory,
            remembered = rememberedPref != null,
            accessoryRemembered = accessoryRemembered
        )
    }

    /** Modes that add load on top of the weight (chains / eccentric / smart flex). */
    private val LOAD_MODES = setOf("chains", "eccentric", "smart_flex")

    /**
     * Free Lift: the remembered Free Lift settings, else the suggestion. On an exercise the lifter has never
     * done, load-adding modes (chains / eccentric / smart flex) from the previous exercise reset to standard
     * so they don't leak onto it; assist modes like Spotter stay as they are.
     */
    fun forFreeLift(
        exercise: Exercise,
        pref: ExercisePref?,
        fallbackWeightLb: Double?,
        currentMode: String? = null
    ): ExerciseSettings {
        val freePref = pref?.takeIf { it.scope == ExercisePref.SCOPE_FREE_LIFT }
        if (freePref == null) {
            val resetMode = currentMode?.lowercase(java.util.Locale.US) in LOAD_MODES
            return ExerciseSettings(
                fallbackWeightLb,
                if (resetMode) "standard" else null,
                if (resetMode) 0 else null,
                exercise.accessory,
                remembered = false
            )
        }
        val (accessory, accessoryRemembered) = accessoryFor(exercise, freePref.accessory)
        return ExerciseSettings(
            freePref.weightLb, freePref.mode, freePref.modifierPct, accessory,
            remembered = true,
            accessoryRemembered = accessoryRemembered
        )
    }

    /**
     * Attachment to load: the remembered one, except that a BAR exercise only ever restores the BAR
     * automatically. Anything else there is the lifter's pick each time, since with another attachment
     * active the daemon skips the bar-tilt safety cutout. Returns the attachment and whether it is the
     * remembered one.
     */
    fun accessoryFor(exercise: Exercise?, remembered: String?): Pair<String?, Boolean> {
        val catalog = exercise?.accessory?.uppercase(java.util.Locale.US)
        val rem = remembered?.uppercase(java.util.Locale.US)
        return when {
            rem == null -> catalog to false
            catalog == null -> null to false
            catalog == "BAR" && rem != "BAR" -> catalog to false
            else -> rem to (rem != catalog)
        }
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
        safetyCutout: Boolean = false,
        /** The set was on an AUTO routine step (the only routine steps that use remembered weights). */
        autoStep: Boolean = false
    ): SetOutcome? {
        // Fixed-weight routine steps always load their own prescription, so there is nothing to remember
        if (routineTargetReps != null && !(autoStep && autoAdjustOn)) return null
        var savedLb = weightLb
        var change: WeightChange? = null
        if (routineTargetReps != null && !safetyCutout) {
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
                lastTargetReps = routineTargetReps,
                scope = ExercisePref.scopeFor(routineTargetReps)
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
