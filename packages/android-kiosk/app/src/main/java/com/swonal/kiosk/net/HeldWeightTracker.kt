package com.swonal.kiosk.net

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * State that makes "never change the motor while it's engaged" and "remember the set the lifter actually
 * did" safe:
 *
 * 1. **Held settings**: a weight and/or attachment that became due while the weight was engaged (next
 *    routine step, profile switch, Work-In rotation). Applied on the first telemetry frame showing the
 *    weight off, and only forgotten once it was actually sent to the motor, so a lagging second telemetry
 *    source, a missed release edge or an unreachable motor can't drop it. The attachment is held too: on
 *    the BAR it drives the bar-tilt safety cutout, so it must never change mid-lift.
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
    ) {
        val isEmpty: Boolean get() = weightLb == null && mode == null && modifierPct == null && accessory == null
    }

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
        val appReleased: Boolean = false,
        /** The set was on an AUTO routine step. */
        val autoStep: Boolean = false,
        /** Identifies this release, so it is logged at most once (auto-log vs Complete Set). */
        val releaseId: Long = 0L,
        /** A safety cutout ended this set (decided at release, not when it is logged later). */
        val safetyCutout: Boolean = false
    )

    /** Who and what the current set is, captured when the weight was engaged. */
    data class SetContext(
        val lifterId: String?,
        val exerciseId: String?,
        val routineTargetReps: Int?,
        val autoStep: Boolean = false,
        /**
         * The set was already logged while the weight stayed engaged (Complete Set mid-lift). The weight in
         * hand still belongs to that logged step, not to the step now on screen, so this release is neither
         * remembered nor logged for the new step.
         */
        val carriedOver: Boolean = false
    )

    private val held = AtomicReference<Held?>(null)
    private val context = AtomicReference<SetContext?>(null)
    private val finished = AtomicReference<FinishedSet?>(null)
    private val appRelease = AtomicBoolean(false)
    private val releaseIds = AtomicLong(0L)
    private val lastLoggedReleaseId = AtomicLong(0L)

    val currentHeld: Held? get() = held.get()

    /** Holds [settings] until the weight is released (replacing any earlier held settings). */
    fun hold(settings: Held) = held.set(settings)

    /** Forgets the held settings (newer ones were applied, or a new exercise / step was chosen). */
    fun clear() = held.set(null)

    /**
     * The lifter changed the weight or mode themselves with the weight off: drop a held weight/mode, keep a
     * held attachment (a +/- tap says nothing about the attachment).
     */
    fun clearHeldWeight() {
        while (true) {
            val cur = held.get() ?: return
            if (cur.weightLb == null && cur.mode == null && cur.modifierPct == null) return
            val next = cur.copy(weightLb = null, mode = null, modifierPct = null).takeUnless { it.isEmpty }
            if (held.compareAndSet(cur, next)) return
        }
    }

    /** The lifter picked an attachment themselves: drop a held attachment, keep a held weight. */
    fun clearHeldAccessory() {
        while (true) {
            val cur = held.get() ?: return
            if (cur.accessory == null) return
            val next = cur.copy(accessory = null, rememberedAccessory = false).takeUnless { it.isEmpty }
            if (held.compareAndSet(cur, next)) return
        }
    }

    /** The app is about to turn the weight off itself (not the lifter's release). */
    fun markAppRelease() = appRelease.set(true)

    /** Whether the release being handled now was the app's doing; resets the flag. */
    fun takeAppRelease(): Boolean = appRelease.getAndSet(false)

    /**
     * Called for every telemetry frame. Returns the held settings to apply now when the weight is off.
     * Call [confirmApplied] once they were sent to the motor; until then they are offered again on later frames.
     */
    fun heldToApply(weightEnabled: Boolean): Held? = if (weightEnabled) null else held.get()

    /** Forgets [applied] if it is still the held settings (newer ones held meanwhile are kept). */
    fun confirmApplied(applied: Held) {
        held.compareAndSet(applied, null)
    }

    /**
     * Weight just engaged: the set now belongs to this lifter, exercise and rep goal, whatever is active
     * later. A finished set that was never logged no longer applies (a 0-rep Complete Set on this new lift
     * must not log the previous one's reps).
     */
    fun onEngaged(
        activeUserId: String?,
        exerciseId: String? = null,
        routineTargetReps: Int? = null,
        autoStep: Boolean = false
    ) {
        appRelease.set(false)
        finished.set(null)
        context.set(
            if (activeUserId != null || exerciseId != null) {
                SetContext(activeUserId, exerciseId, routineTargetReps, autoStep)
            } else {
                null
            }
        )
    }

    /**
     * A set was logged while the weight stays engaged (Complete Set mid-lift). Whatever is lifted until the
     * release still belongs to [lifterId] on [loggedExerciseId] at the weight already loaded; it is
     * carried over rather than counted as a set of the step now on screen.
     */
    fun onLoggedWhileEngaged(lifterId: String?, loggedExerciseId: String?) {
        finished.set(null)
        context.set(SetContext(lifterId, loggedExerciseId, routineTargetReps = null, carriedOver = true))
    }

    /** Who and what the current set is, or null if the engage wasn't seen. */
    val currentContext: SetContext? get() = context.get()

    /** Lifter to log the next set for: whoever engaged the weight, else the active lifter. */
    fun lifterForSet(activeUserId: String?): String? = context.get()?.lifterId ?: activeUserId

    /** A new id for a release being captured. */
    fun nextReleaseId(): Long = releaseIds.incrementAndGet()

    /** The weight was released with reps: remember exactly what was lifted (replaces an earlier release). */
    fun onReleased(set: FinishedSet) = finished.set(set)

    /** Updates the captured release (e.g. with its safety-cutout verdict) if it is still the tracked one. */
    fun replaceFinished(old: FinishedSet, new: FinishedSet) {
        finished.compareAndSet(old, new)
    }

    /** The finished set for [exerciseId], if the last release was on that exercise and isn't logged yet. */
    fun finishedSetFor(exerciseId: String): FinishedSet? =
        finished.get()?.takeIf { it.exerciseId == exerciseId && !isLogged(it.releaseId) }

    /** Whether the release [releaseId] was already logged (by hand or automatically). */
    fun isLogged(releaseId: Long): Boolean = releaseId != 0L && lastLoggedReleaseId.get() >= releaseId

    /** Exercise or routine step changed: an unlogged finished set no longer applies. */
    fun forgetFinishedSet() = finished.set(null)

    /** A set was logged: the next set starts fresh. [releaseId] is the release it came from, if any. */
    fun onSetLogged(releaseId: Long = 0L) {
        if (releaseId != 0L) lastLoggedReleaseId.accumulateAndGet(releaseId) { a, b -> maxOf(a, b) }
        context.set(null)
        finished.set(null)
    }

    /**
     * A set was logged outside the kiosk's own flow (Companion / API). Only clears the tracked set when it is
     * the same lifter and exercise, so a partner's phone log can't wipe the set someone is doing on the
     * kiosk. Returns true when it matched.
     */
    fun onExternalSetLogged(userId: String, exerciseId: String): Boolean {
        val fin = finished.get()
        val ctx = context.get()
        val matchesFinished = fin != null && fin.lifterId == userId && fin.exerciseId == exerciseId
        val matchesContext = fin == null && ctx != null && ctx.lifterId == userId && ctx.exerciseId == exerciseId
        if (!matchesFinished && !matchesContext) return false
        fin?.let { f -> if (f.releaseId != 0L) lastLoggedReleaseId.accumulateAndGet(f.releaseId) { a, b -> maxOf(a, b) } }
        context.compareAndSet(ctx, null)
        finished.compareAndSet(fin, null)
        return true
    }
}
