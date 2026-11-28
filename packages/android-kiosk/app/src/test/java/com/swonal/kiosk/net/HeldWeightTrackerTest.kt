package com.swonal.kiosk.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeldWeightTrackerTest {

    private val w45 = HeldWeightTracker.Held(45.0, "standard", 0, "BAR")
    private val w35 = HeldWeightTracker.Held(35.0, "chains", 25, "HANDLES")

    private fun set(
        exerciseId: String = "bench",
        lifter: String = "alex",
        weight: Double = 80.0,
        reps: Int = 8,
        releaseId: Long = 0L
    ) = HeldWeightTracker.FinishedSet(lifter, exerciseId, weight, "standard", 0, "BAR", reps, 10, releaseId = releaseId)

    @Test
    fun `held settings are only offered while the weight is off`() {
        val t = HeldWeightTracker()
        t.hold(w45)
        assertNull(t.heldToApply(weightEnabled = true))
        assertEquals(w45, t.heldToApply(weightEnabled = false))
    }

    @Test
    fun `held settings stay until the motor accepts them`() {
        val t = HeldWeightTracker()
        t.hold(w45)
        assertEquals(w45, t.heldToApply(weightEnabled = false))
        // Not confirmed (other telemetry source still engaged): offered again, also after a missed edge
        assertEquals(w45, t.heldToApply(weightEnabled = false))
        t.confirmApplied(w45)
        assertNull(t.heldToApply(weightEnabled = false))
    }

    @Test
    fun `attachment-only hold is kept like a weight`() {
        val t = HeldWeightTracker()
        val accessoryOnly = HeldWeightTracker.Held(null, null, null, "ROPE")
        t.hold(accessoryOnly)
        assertNull(t.heldToApply(weightEnabled = true))
        assertEquals(accessoryOnly, t.heldToApply(weightEnabled = false))
    }

    @Test
    fun `latest held settings win and confirming older ones keeps the newer`() {
        val t = HeldWeightTracker()
        t.hold(w45)
        t.hold(w35)
        assertEquals(w35, t.heldToApply(weightEnabled = false))
        t.confirmApplied(w45)
        assertEquals(w35, t.currentHeld)
    }

    @Test
    fun `lifter's own change clears held settings`() {
        val t = HeldWeightTracker()
        t.hold(w45)
        t.clear()
        assertNull(t.heldToApply(weightEnabled = false))
    }

    @Test
    fun `set belongs to whoever engaged the weight until it is logged`() {
        val t = HeldWeightTracker()
        assertEquals("alex", t.lifterForSet("alex"))
        t.onEngaged("alex")
        assertEquals("alex", t.lifterForSet("taylor"))
        t.onSetLogged()
        assertEquals("taylor", t.lifterForSet("taylor"))
        t.onEngaged(null)
        assertEquals("taylor", t.lifterForSet("taylor"))
    }

    @Test
    fun `finished set keeps what was lifted even after a held weight loads`() {
        val t = HeldWeightTracker()
        t.onEngaged("alex")
        t.hold(w35) // Taylor switched in mid-set
        t.onReleased(set(weight = 80.0))
        t.confirmApplied(t.heldToApply(weightEnabled = false)!!) // Taylor's 35 lb loads
        val finished = t.finishedSetFor("bench")
        assertEquals(80.0, finished?.weightLb)
        assertEquals("alex", finished?.lifterId)
    }

    @Test
    fun `finished set is per exercise, replaced on re-release, and cleared on log or step change`() {
        val t = HeldWeightTracker()
        t.onReleased(set(reps = 8))
        assertNull(t.finishedSetFor("row"))
        t.onReleased(set(reps = 10)) // rest-pause: total reps so far
        assertEquals(10, t.finishedSetFor("bench")?.reps)
        t.forgetFinishedSet()
        assertNull(t.finishedSetFor("bench"))
        t.onReleased(set())
        t.onSetLogged()
        assertNull(t.finishedSetFor("bench"))
    }

    @Test
    fun `set context keeps the exercise and rep goal from when the weight was engaged`() {
        val t = HeldWeightTracker()
        assertNull(t.currentContext)
        t.onEngaged("alex", "bench", 10)
        // The app switches to Row (or the next step) before it disengages: the set is still Bench's
        assertEquals(HeldWeightTracker.SetContext("alex", "bench", 10), t.currentContext)
        // Complete Set while still engaged: the next set starts on the new step right away
        t.onSetLogged()
        assertNull(t.currentContext)
        t.onEngaged("alex", "row", 12)
        assertEquals("row", t.currentContext?.exerciseId)
        assertEquals(12, t.currentContext?.routineTargetReps)
        // Free Lift has no rep goal
        t.onEngaged("taylor", "curl", null)
        assertEquals(HeldWeightTracker.SetContext("taylor", "curl", null), t.currentContext)
        // Nothing known: no stale context (the set falls back to whoever is active)
        t.onEngaged(null, null, null)
        assertNull(t.currentContext)
    }

    @Test
    fun `lifter's own attachment pick drops a held attachment but keeps a held weight`() {
        val t = HeldWeightTracker()
        t.hold(w35)
        t.clearHeldAccessory()
        assertEquals(HeldWeightTracker.Held(35.0, "chains", 25, null), t.currentHeld)
        t.hold(HeldWeightTracker.Held(null, null, null, "ROPE"))
        t.clearHeldAccessory()
        assertNull(t.currentHeld)
    }

    @Test
    fun `app release flag applies to the next release only and resets on engage`() {
        val t = HeldWeightTracker()
        assertEquals(false, t.takeAppRelease())
        t.markAppRelease()
        assertEquals(true, t.takeAppRelease())
        assertEquals(false, t.takeAppRelease()) // consumed
        t.markAppRelease()
        t.onEngaged("alex", "bench", 10) // a new lift starts: stale flag from a disengage that never happened
        assertEquals(false, t.takeAppRelease())
    }

    // --- Review follow-ups ---

    @Test
    fun `engaging again invalidates an unlogged finished set`() {
        val t = HeldWeightTracker()
        t.onEngaged("alex", "bench", 10)
        t.onReleased(set(reps = 8))
        // Lifter picks the weight up again without logging; a 0-rep Complete Set must not log the old 8 reps
        t.onEngaged("alex", "bench", 10)
        assertNull(t.finishedSetFor("bench"))
    }

    @Test
    fun `a mid-lift Complete Set carries the rest of the lift over to the logged step`() {
        val t = HeldWeightTracker()
        t.onEngaged("alex", "bench", 10)
        t.onSetLogged()
        t.onLoggedWhileEngaged("alex", "bench")
        val ctx = t.currentContext!!
        assertTrue(ctx.carriedOver)
        assertEquals("bench", ctx.exerciseId) // not the lateral raise now on screen
        assertEquals("alex", t.lifterForSet("taylor"))
        // The next real lift starts fresh
        t.onEngaged("alex", "raise", 15)
        assertFalse(t.currentContext!!.carriedOver)
        assertEquals("raise", t.currentContext!!.exerciseId)
    }

    @Test
    fun `a release is logged at most once`() {
        val t = HeldWeightTracker()
        val id = t.nextReleaseId()
        t.onReleased(set(releaseId = id))
        assertFalse(t.isLogged(id))
        t.onSetLogged(id) // auto-log
        assertTrue(t.isLogged(id))
        t.onReleased(set(releaseId = id)) // a late duplicate of the same release
        assertNull(t.finishedSetFor("bench"))
        val next = t.nextReleaseId()
        t.onReleased(set(releaseId = next))
        assertEquals(next, t.finishedSetFor("bench")?.releaseId)
    }

    @Test
    fun `a weight change with the weight off keeps a held attachment`() {
        val t = HeldWeightTracker()
        t.hold(w45)
        t.clearHeldWeight()
        assertEquals(HeldWeightTracker.Held(null, null, null, "BAR"), t.currentHeld)
        t.hold(HeldWeightTracker.Held(45.0, "standard", 0, null))
        t.clearHeldWeight()
        assertNull(t.currentHeld)
    }

    @Test
    fun `an external log only clears the matching lifter's set`() {
        val t = HeldWeightTracker()
        t.onEngaged("alex", "bench", 10)
        t.onReleased(set(lifter = "alex", exerciseId = "bench", releaseId = t.nextReleaseId()))
        // Taylor logs a curl from their phone: Alex's released bench set is untouched
        assertFalse(t.onExternalSetLogged("taylor", "curl"))
        assertEquals("alex", t.finishedSetFor("bench")?.lifterId)
        assertFalse(t.onExternalSetLogged("alex", "row"))
        assertTrue(t.onExternalSetLogged("alex", "bench"))
        assertNull(t.finishedSetFor("bench"))
        // Mid-lift (nothing released yet): matches the engaged set's lifter and exercise
        t.onEngaged("alex", "row", 12)
        assertFalse(t.onExternalSetLogged("taylor", "row"))
        assertEquals("alex", t.currentContext?.lifterId)
        assertTrue(t.onExternalSetLogged("alex", "row"))
        assertNull(t.currentContext)
    }

    @Test
    fun `the cutout verdict replaces the captured release only if it is still current`() {
        val t = HeldWeightTracker()
        val first = set(releaseId = t.nextReleaseId())
        t.onReleased(first)
        t.replaceFinished(first, first.copy(safetyCutout = true))
        assertTrue(t.finishedSetFor("bench")!!.safetyCutout)
        val second = set(reps = 10, releaseId = t.nextReleaseId())
        t.onReleased(second)
        t.replaceFinished(first, first.copy(safetyCutout = false)) // stale: ignored
        assertEquals(10, t.finishedSetFor("bench")?.reps)
    }
}
