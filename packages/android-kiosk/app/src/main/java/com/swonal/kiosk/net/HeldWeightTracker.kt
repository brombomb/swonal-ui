package com.swonal.kiosk.net

import java.util.concurrent.atomic.AtomicReference

/**
 * State that makes "never change the motor while it's engaged" and "remember the set the lifter actually
 * did" safe:
 *
 * 1. **Held settings**: a weight and/or attachment that became due while the weight was engaged (next
 *    routine step, profile switch, Work-In rotation). Applied on the first telemetry frame showing the
 *    weight off, and only forgotten once the motor accepted it, so a lagging second telemetry source or a
 *    missed release edge can't drop it. The attachment is held too: on the BAR it drives the bar-tilt
 *    safety cutout, so it must never change mid-lift.
 * 2. **The set's context**: whoever was active, and which exercise / rep goal was on screen, when the
 *    weight was engaged. The set belongs to them even if someone switches profile, or the exercise or
 *    step changes (the app switches before it disengages), before the weight is released.
 * 3. **The finished set**: weight, mode, attachment and reps captured the moment the weight is released,
 *    before any held settings load, so logging it later (Complete Set) records what was really lifted.
 *
 * Pure and thread-safe so it can be unit-tested without Android.
 */
class HeldWeightTracker {

    data class Held(
        val weightLb: Double?,
        val mode: String?,
        val modifierPct: Int?,
        val accessory: String?,
        /** Exercise these settings are for, and whether the accessory is the lifter's remembered one. */
        val exerciseId: String? = null,
        val rememberedAccessory: Boolean = false
    )

    data class FinishedSet(
        val lifterId: String?,
        val exerciseId: String,
        val weightLb: Double,
        val mode: String,
        val modifierPct: Int,
        val accessory: String,
        val reps: Int,
        val routineTargetReps: Int?,
        /** The app (not the lifter) turned the weight off: leaving Swonal, switching exercise mid-lift, watchdog. */
        val appReleased: Boolean = false
    )

    private val held = AtomicReference<Held?>(null)
    /** Who and what the current set is, captured when the weight was engaged. */
    data class SetContext(val lifterId: String?, val exerciseId: String?, val routineTargetReps: Int?)

    private val context = AtomicReference<SetContext?>(null)
    private val finished = AtomicReference<FinishedSet?>(null)
    private val appRelease = java.util.concurrent.atomic.AtomicBoolean(false)

    val currentHeld: Held? get() = held.get()

    /** Holds [settings] until the weight is released (replacing any earlier held settings). */
    fun hold(settings: Held) = held.set(settings)

    /** Forgets the held settings (newer ones were applied, or the lifter changed the weight themselves). */
    fun clear() = held.set(null)

    /** The lifter picked an attachment themselves: drop a held attachment, keep a held weight. */
    fun clearHeldAccessory() {
        while (true) {
            val cur = held.get() ?: return
            if (cur.accessory == null) return
            val next = if (cur.weightLb == null) null else cur.copy(accessory = null)
            if (held.compareAndSet(cur, next)) return
        }
    }

    /** The app is about to turn the weight off itself (not the lifter's release). */
    fun markAppRelease() = appRelease.set(true)

    /** Whether the release being handled now was the app's doing; resets the flag. */
    fun takeAppRelease(): Boolean = appRelease.getAndSet(false)

    /**
     * Called for every telemetry frame. Returns the held settings to apply now when the weight is off.
     * Call [confirmApplied] once the motor took them; until then they are offered again on later frames.
     */
    fun heldToApply(weightEnabled: Boolean): Held? = if (weightEnabled) null else held.get()

    /** Forgets [applied] if it is still the held settings (newer ones held meanwhile are kept). */
    fun confirmApplied(applied: Held) {
        held.compareAndSet(applied, null)
    }

    /**
     * Weight just engaged (or a set was logged while it stays engaged): the set now belongs to this
     * lifter, exercise and rep goal, whatever is active later.
     */
    fun onEngaged(activeUserId: String?, exerciseId: String? = null, routineTargetReps: Int? = null) {
        appRelease.set(false)
        if (activeUserId != null || exerciseId != null) {
            context.set(SetContext(activeUserId, exerciseId, routineTargetReps))
        }
    }

    /** Who and what the current set is, or null if the engage wasn't seen. */
    val currentContext: SetContext? get() = context.get()

    /** Lifter to log the next set for: whoever engaged the weight, else the active lifter. */
    fun lifterForSet(activeUserId: String?): String? = context.get()?.lifterId ?: activeUserId

    /** The weight was released with reps: remember exactly what was lifted (replaces an earlier release). */
    fun onReleased(set: FinishedSet) = finished.set(set)

    /** The finished set for [exerciseId], if the last release was on that exercise. */
    fun finishedSetFor(exerciseId: String): FinishedSet? = finished.get()?.takeIf { it.exerciseId == exerciseId }

    /** Exercise or routine step changed: an unlogged finished set no longer applies. */
    fun forgetFinishedSet() = finished.set(null)

    /** A set was logged: the next set starts fresh. */
    fun onSetLogged() {
        context.set(null)
        finished.set(null)
    }
}
