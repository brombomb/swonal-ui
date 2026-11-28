package com.swonal.kiosk.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeldWeightTrackerTest {

    private val w45 = HeldWeightTracker.Held(45.0, "standard", 0, "BAR")
    private val w35 = HeldWeightTracker.Held(35.0, "chains", 25, "HANDLES")

    private fun set(exerciseId: String = "bench", lifter: String = "alex", weight: Double = 80.0, reps: Int = 8) =
        HeldWeightTracker.FinishedSet(lifter, exerciseId, weight, "standard", 0, "BAR", reps, 10)

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
        // Nothing known: keep the previous context
        t.onEngaged(null, null, null)
        assertEquals("curl", t.currentContext?.exerciseId)
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
}
