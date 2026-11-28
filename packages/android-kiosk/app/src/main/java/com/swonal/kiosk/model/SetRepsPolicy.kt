package com.swonal.kiosk.model

/**
 * Decides how many reps a "Complete & Log Set" should store (upstream issue #16).
 *
 * A set used to fall back to the step's target reps (or 10 in Free Lift) when the rep
 * counter read 0, so an accidental tap stored a fake set and could show "NEW PR!".
 * Now a 0 count without an explicit rep count asks the user instead.
 *
 * Pure Kotlin (no Android types) so it runs in JVM unit tests.
 */
object SetRepsPolicy {
    /** Default suggestion in Free Lift, where there is no target. */
    const val FREE_LIFT_DEFAULT_REPS: Int = 10

    /** Same upper bound the server and [com.swonal.kiosk.engine.LocalWorkoutEngine] accept. */
    const val MAX_REPS: Int = 500

    sealed class Decision {
        /** Log the set with [reps] now. */
        data class Log(val reps: Int) : Decision()

        /** Nothing was counted: ask the user, prefilled with [suggestedReps]. */
        data class Confirm(val suggestedReps: Int) : Decision()
    }

    /**
     * @param repsOverride an explicit count from the caller (auto-log, the confirm dialog); always used as-is.
     * @param countedReps the live rep counter.
     * @param targetReps the routine step's target, or null in Free Lift.
     */
    fun decide(repsOverride: Int?, countedReps: Int, targetReps: Int?): Decision = when {
        repsOverride != null -> Decision.Log(repsOverride)
        countedReps > 0 -> Decision.Log(countedReps)
        else -> Decision.Confirm(suggestedReps(targetReps))
    }

    /** Prefill for the confirm dialog: the step target, or 10 in Free Lift, kept within 1..[MAX_REPS]. */
    fun suggestedReps(targetReps: Int?): Int {
        val target = targetReps ?: FREE_LIFT_DEFAULT_REPS
        return if (target < 1) FREE_LIFT_DEFAULT_REPS else target.coerceAtMost(MAX_REPS)
    }

    /** Parses what the user entered; null (don't log) for blank, 0, non-numbers or more than [MAX_REPS]. */
    fun parseEnteredReps(text: String): Int? {
        val n = text.trim().toIntOrNull() ?: return null
        return if (n in 1..MAX_REPS) n else null
    }
}
