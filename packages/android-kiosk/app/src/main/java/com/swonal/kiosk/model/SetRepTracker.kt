package com.swonal.kiosk.model

/**
 * Holds the current set's rep count across the motor board's own counter resets.
 *
 * The Tonal V1 motor board zeroes its rep counter in the same telemetry frame that reports the
 * weight turning off. Recorded on a real V1 (rep_count / weight_enabled, printed on change):
 *
 *     0 on → 2 on → 3 on → 4 on → 0 off
 *
 * So by the time the user taps "Complete & Log Set", or the disengage auto-log runs, the raw
 * `rep_count` already reads 0 although 4 reps were counted and shown a second earlier.
 *
 * Rules:
 * - The set count follows the board's count while it is above zero, and is kept when the board
 *   drops back to 0 (weight off), until the set is logged or a new set starts.
 * - A later engagement that counts reps again replaces it: the latest engagement wins. Counts are
 *   not added up across a re-grip, and the highest count is not kept, so a second set done without
 *   logging the first is logged as itself, never as the sum or the larger of the two.
 * - [reset] (a set was logged, reps were reset) and a change of [setKey] (exercise, routine step or
 *   session changed) start a new set at 0. Whatever the board still shows at that moment (a set
 *   logged with the weight still on) belongs to the previous set: it becomes a baseline that is
 *   subtracted until the board count drops again, so those reps are never counted twice.
 *
 * Pure Kotlin (no Android types) so it runs in JVM unit tests. Not thread-safe: the caller
 * serialises access (SwonalRepository uses its telemetry lock).
 */
class SetRepTracker {
    /** Reps of the current set: what COMPLETED REPS shows and what a log without an explicit count stores. */
    var setReps: Int = 0
        private set

    private var baseline = 0
    private var lastBoardReps = 0
    private var setKey: String? = null

    /**
     * Feed one telemetry frame.
     * @param boardReps the board's raw rep counter from the frame.
     * @param setKey identifies the current set's context (session, routine step, exercise); a change starts a new set.
     * @return the current set's reps after this frame.
     */
    fun onFrame(boardReps: Int, setKey: String): Int {
        val reps = boardReps.coerceAtLeast(0)
        val previousKey = this.setKey
        this.setKey = setKey
        if (previousKey != null && previousKey != setKey) reset()
        if (reps < baseline) baseline = 0 // the board restarted its count
        lastBoardReps = reps
        val counted = reps - baseline
        if (counted > 0) setReps = counted
        return setReps
    }

    /** Start a new set at 0 (set logged, reps reset, workout ended). */
    fun reset() {
        baseline = lastBoardReps
        setReps = 0
    }

    companion object {
        /**
         * Reps to auto-log when the weight turns off during a routine step, or null to not auto-log.
         * Uses the held set count, not the disengage frame's raw count (0 on a V1).
         */
        fun autoLogReps(setReps: Int, targetReps: Int): Int? =
            if (setReps > 0 && setReps >= targetReps) setReps else null
    }
}
