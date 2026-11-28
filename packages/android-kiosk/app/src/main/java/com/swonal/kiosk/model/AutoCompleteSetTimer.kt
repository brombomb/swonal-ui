package com.swonal.kiosk.model

import kotlin.math.abs
import kotlin.math.ceil

/** A running "Auto-Complete Set" countdown. Times are `System.currentTimeMillis()` based. */
data class AutoCompleteCountdown(
    val startedAtMs: Long,
    val endsAtMs: Long
) {
    val totalMs: Long
        get() = (endsAtMs - startedAtMs).coerceAtLeast(1L)

    fun secondsRemaining(nowMs: Long): Int {
        val diff = endsAtMs - nowMs
        return if (diff > 0L) ceil(diff / 1000.0).toInt() else 0
    }

    /** 0.0 when the countdown just started, 1.0 when it has run out. */
    fun elapsedFraction(nowMs: Long): Float =
        ((nowMs - startedAtMs).toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
}

/**
 * Pure decision logic for the optional per-profile "Auto-Complete Set" timer.
 *
 * Feed it every telemetry frame via [onFrame]. When the countdown runs out, [onFrame]
 * returns `true` exactly once and the caller logs the set through the exact same path as
 * the "Complete & Log Set" button.
 *
 * When the countdown starts (only once at least 1 rep has been counted for this set):
 * - **Weight turned off:** immediately.
 * - **Weight on:** once every cable used in this set has been nearly still
 *   (< [STILL_VELOCITY] cm/s) *and* back in its rest zone (within [REST_ZONE_CM] of the
 *   shortest cable length seen since the first rep, and at least [BELOW_TOP_CM] below the
 *   set's highest point) for [SETTLE_MS]. The rest-zone rule keeps
 *   the timer from starting during a slow grind or an isometric hold away from the bottom,
 *   so finishing a routine (which turns the weight off) can never happen under load.
 *
 * What restarts it from the beginning:
 * - a new rep, the weight being turned back on, or any context change (lifter, exercise,
 *   routine step, weight, mode) — the caller encodes those in `contextKey`;
 * - with the weight on: a used cable leaving its rest zone or being pulled out more than
 *   [MOVE_RESET_CM] past where the countdown started (letting it retract is fine).
 *
 * [cancel] (the ✕ button) stops the current countdown; it re-arms on the next rep or a new set.
 *
 * Kept free of Android dependencies so it can be unit tested on the JVM. Not thread-safe:
 * the repository calls it under its telemetry lock.
 */
class AutoCompleteSetTimer {

    companion object {
        /** Exact-seconds range offered in Settings. `0` means Off (the default). */
        const val MIN_SECONDS: Int = 3
        const val MAX_SECONDS: Int = 60

        /** Quick choices shown in the Settings picker (Off, 5, 10, 20). */
        val QUICK_CHOICES_SECONDS: List<Int> = listOf(0, 5, 10, 20)

        /** Cable speed (cm/s) below which a cable counts as still. */
        const val STILL_VELOCITY: Double = 2.0

        /** How far (cm) above the set's lowest cable position still counts as "at rest". */
        const val REST_ZONE_CM: Double = 10.0

        /** How long the cables must be still and at rest before the countdown starts (weight on). */
        const val SETTLE_MS: Long = 1_000L

        /** Pulling a cable out this far (cm) past the countdown's starting point cancels it (weight on). */
        const val MOVE_RESET_CM: Double = 4.0

        /** A cable that traveled at least this far (cm) during the set is "in use". */
        const val USED_TRAVEL_CM: Double = 15.0

        /** A used cable must also be at least this far (cm) below the set's highest point to be "at rest". */
        const val BELOW_TOP_CM: Double = 10.0

        /** Unknown, negative or out-of-range values: 0 stays Off, everything else is clamped. */
        fun normalizeSeconds(seconds: Int): Int = when {
            seconds <= 0 -> 0
            else -> seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        }

        fun label(seconds: Int): String {
            val s = normalizeSeconds(seconds)
            return if (s == 0) "Off" else "$s sec"
        }
    }

    private class CableTrack {
        var setMin = Double.NaN
        var setMax = Double.NaN
        /** Lowest position seen since the first rep of this set (NaN until then). */
        var restMin = Double.NaN
        var anchor = Double.NaN

        fun reset() {
            setMin = Double.NaN
            setMax = Double.NaN
            restMin = Double.NaN
            anchor = Double.NaN
        }

        fun track(pos: Double, repsCounted: Boolean) {
            setMin = if (setMin.isNaN()) pos else minOf(setMin, pos)
            setMax = if (setMax.isNaN()) pos else maxOf(setMax, pos)
            if (repsCounted) {
                restMin = if (restMin.isNaN()) pos else minOf(restMin, pos)
            }
        }

        val travel: Double
            get() = if (setMin.isNaN() || setMax.isNaN()) 0.0 else setMax - setMin

        /**
         * Near the bottom of this set's reps, and clearly below the highest point of the set
         * (so a hold at the top right after rep 1 — when [restMin] is still the top — never counts).
         */
        fun inRestZone(pos: Double): Boolean {
            val nearBottom = restMin.isNaN() || pos <= restMin + REST_ZONE_CM
            val belowTop = setMax.isNaN() || pos <= setMax - BELOW_TOP_CM
            return nearBottom && belowTop
        }
    }

    private val left = CableTrack()
    private val right = CableTrack()

    private var contextKey: String? = null
    private var lastRepCount: Int = 0
    private var prevWeightEnabled: Boolean = false
    private var settleSinceMs: Long? = null

    /** Set after the countdown fired or was cancelled; cleared by a new rep or a new set. */
    private var suppressed: Boolean = false

    /** The running countdown, or `null` when none is showing. */
    var countdown: AutoCompleteCountdown? = null
        private set

    /** The ✕ button: stop this countdown. It re-arms on the next rep or the next set. */
    fun cancel() {
        countdown = null
        settleSinceMs = null
        suppressed = true
    }

    /**
     * @param durationSec the active lifter's setting; `0` (Off) disables the countdown.
     * @param contextKey anything that, when changed, means "this is a different set"
     *   (lifter, exercise, routine step, target weight, mode).
     * @return `true` exactly once, when the countdown runs out.
     */
    fun onFrame(nowMs: Long, frame: TelemetryFrame, contextKey: String, durationSec: Int): Boolean {
        val reps = frame.repCount
        val duration = normalizeSeconds(durationSec)

        // 1. A new set: different context, or the rep counter was reset.
        if (contextKey != this.contextKey || reps < lastRepCount) {
            this.contextKey = contextKey
            left.reset()
            right.reset()
            stopCountdown()
            suppressed = false
        }

        // 2. A new rep re-arms (and restarts) the timer.
        if (reps > lastRepCount) {
            stopCountdown()
            suppressed = false
        }
        lastRepCount = reps

        val repsCounted = reps >= 1
        val l = frame.leftCable
        val r = frame.rightCable
        left.track(l.position, repsCounted)
        right.track(r.position, repsCounted)

        // 3. Turning the weight on or off restarts the countdown.
        val weightOn = frame.weightEnabled
        if (weightOn != prevWeightEnabled) {
            stopCountdown()
        }
        prevWeightEnabled = weightOn

        if (duration == 0 || !repsCounted || suppressed) {
            stopCountdown()
            return false
        }

        if (!weightOn) {
            // 4a. Weight off with at least 1 rep: start right away.
            if (countdown == null) startCountdown(nowMs, duration, l.position, r.position)
        } else {
            // 4b. Weight on: only the cables used in this set matter.
            // If neither cable traveled far enough to tell, watch both and use stillness alone.
            var useLeft = left.travel >= USED_TRAVEL_CM
            var useRight = right.travel >= USED_TRAVEL_CM
            val checkZone = useLeft || useRight
            if (!checkZone) {
                useLeft = true
                useRight = true
            }

            if (countdown != null) {
                val leftMoved = useLeft && ((checkZone && !left.inRestZone(l.position)) || moved(left, l.position))
                val rightMoved = useRight && ((checkZone && !right.inRestZone(r.position)) || moved(right, r.position))
                if (leftMoved || rightMoved) stopCountdown()
            }

            if (countdown == null) {
                val leftAtRest = !useLeft ||
                    (abs(l.velocity) < STILL_VELOCITY && (!checkZone || left.inRestZone(l.position)))
                val rightAtRest = !useRight ||
                    (abs(r.velocity) < STILL_VELOCITY && (!checkZone || right.inRestZone(r.position)))
                if (leftAtRest && rightAtRest) {
                    val since = settleSinceMs ?: nowMs.also { settleSinceMs = it }
                    if (nowMs - since >= SETTLE_MS) {
                        startCountdown(nowMs, duration, l.position, r.position)
                    }
                } else {
                    settleSinceMs = null
                }
            }
        }

        // 5. Ran out: fire once, then stay quiet until the next rep or set.
        val running = countdown
        if (running != null && nowMs >= running.endsAtMs) {
            stopCountdown()
            suppressed = true
            return true
        }
        return false
    }

    /**
     * Pulling the cable out again cancels; letting it retract (returning the handles) does not,
     * and the anchor follows it down so a later pull is measured from the new low point.
     */
    private fun moved(track: CableTrack, pos: Double): Boolean {
        if (track.anchor.isNaN()) return false
        if (pos < track.anchor) track.anchor = pos
        return pos - track.anchor > MOVE_RESET_CM
    }

    private fun startCountdown(nowMs: Long, durationSec: Int, leftPos: Double, rightPos: Double) {
        countdown = AutoCompleteCountdown(startedAtMs = nowMs, endsAtMs = nowMs + durationSec * 1000L)
        left.anchor = leftPos
        right.anchor = rightPos
        settleSinceMs = null
    }

    private fun stopCountdown() {
        countdown = null
        settleSinceMs = null
        left.anchor = Double.NaN
        right.anchor = Double.NaN
    }
}
