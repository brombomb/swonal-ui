package com.swonal.kiosk.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExerciseSettingsPolicyTest {

    private val bench = Exercise(id = "bench", name = "Bench Press", muscleGroup = "Chest", accessory = "BAR")

    private fun step(weightLb: Double = 60.0, auto: Boolean = false, targetReps: Int = 10, mode: String = "standard") =
        RoutineStep(
            blockIndex = 0, blockType = "STRAIGHT_SET", itemIndex = 0, exerciseId = bench.id,
            setNumber = 1, totalSetsInItem = 3, targetReps = targetReps, targetWeightLb = weightLb,
            mode = mode, modifierPct = 0, restSeconds = 60, autoWeight = auto
        )

    private fun pref(weightLb: Double, mode: String = "chains", pct: Int = 25, accessory: String = "ROPE") =
        ExercisePref(userId = "u1", exerciseId = bench.id, weightLb = weightLb, mode = mode, modifierPct = pct, accessory = accessory)

    private fun afterSet(
        weightLb: Double,
        reps: Int,
        target: Int?,
        autoAdjust: Boolean = true,
        mode: String = "standard",
        pct: Int = 0,
        unit: String = "LB"
    ) = ExerciseSettingsPolicy.afterSet("u1", bench.id, weightLb, mode, pct, "BAR", reps, target, autoAdjust, unit)

    private fun msg(change: WeightChange?, unit: String = "LB", sameNext: Boolean = true) =
        change?.let { ExerciseSettingsPolicy.changeMessage(it, unit, bench.name, sameNext) }

    // --- Loading a routine step ---

    @Test
    fun `first time on a routine step uses the routine's weight, mode and catalog accessory`() {
        val s = ExerciseSettingsPolicy.forRoutineStep(step(weightLb = 60.0, mode = "eccentric"), bench, null, autoAdjustOn = true, prMaxWeightLb = 100.0)
        assertEquals(60.0, s.weightLb)
        assertEquals("eccentric", s.mode)
        assertEquals("BAR", s.accessory)
        assertFalse(s.remembered)
    }

    @Test
    fun `first time on an Auto step depends on the lifter's auto-adjust setting`() {
        val auto = step(weightLb = 45.0, auto = true)
        assertEquals(20.0, ExerciseSettingsPolicy.forRoutineStep(auto, bench, null, autoAdjustOn = true, prMaxWeightLb = null).weightLb)
        assertEquals(85.0, ExerciseSettingsPolicy.forRoutineStep(auto, bench, null, autoAdjustOn = true, prMaxWeightLb = 100.0).weightLb)
        assertEquals(45.0, ExerciseSettingsPolicy.forRoutineStep(auto, bench, null, autoAdjustOn = false, prMaxWeightLb = 100.0).weightLb)
    }

    @Test
    fun `Work-In partner's first time starts at 85 percent of their best, else the routine weight`() {
        val fixed = step(weightLb = 45.0)
        assertEquals(85.0, ExerciseSettingsPolicy.forRoutineStep(fixed, bench, null, false, prMaxWeightLb = 100.0, workInPartner = true).weightLb)
        assertEquals(45.0, ExerciseSettingsPolicy.forRoutineStep(fixed, bench, null, false, prMaxWeightLb = null, workInPartner = true).weightLb)
        // Not a Work-In hand-off: a fixed routine weight ignores the PR
        assertEquals(45.0, ExerciseSettingsPolicy.forRoutineStep(fixed, bench, null, false, prMaxWeightLb = 100.0, workInPartner = false).weightLb)
        // Remembered settings still win for a partner who has done it before
        assertEquals(42.0, ExerciseSettingsPolicy.forRoutineStep(fixed, bench, pref(42.0), false, prMaxWeightLb = 100.0, workInPartner = true).weightLb)
    }

    @Test
    fun `remembered settings override the routine's weight, mode and accessory`() {
        val s = ExerciseSettingsPolicy.forRoutineStep(step(weightLb = 60.0, auto = true), bench, pref(42.0), autoAdjustOn = true, prMaxWeightLb = 100.0)
        assertEquals(42.0, s.weightLb) // already adjusted when it was saved; never adjusted twice
        assertEquals("chains", s.mode)
        assertEquals(25, s.modifierPct)
        assertEquals("ROPE", s.accessory)
        assertTrue(s.remembered)
    }

    @Test
    fun `missing exercise leaves the attachment alone`() {
        val s = ExerciseSettingsPolicy.forRoutineStep(step(), exercise = null, pref = null, autoAdjustOn = false, prMaxWeightLb = null)
        assertNull(s.accessory)
    }

    // --- Free Lift ---

    @Test
    fun `free lift restores remembered settings`() {
        val remembered = ExerciseSettingsPolicy.forFreeLift(bench, pref(42.0), fallbackWeightLb = 85.0)
        assertEquals(42.0, remembered.weightLb)
        assertEquals("chains", remembered.mode)
        assertEquals(25, remembered.modifierPct)
        assertTrue(remembered.remembered)
    }

    @Test
    fun `free lift on a new exercise resets load modes but keeps spotter`() {
        listOf("chains", "eccentric", "smart_flex", "CHAINS").forEach { current ->
            val fresh = ExerciseSettingsPolicy.forFreeLift(bench, null, fallbackWeightLb = 85.0, currentMode = current)
            assertEquals("standard", fresh.mode, current)
            assertEquals(0, fresh.modifierPct, current)
        }
        listOf("spotter", "burnout", "standard", null).forEach { current ->
            val kept = ExerciseSettingsPolicy.forFreeLift(bench, null, fallbackWeightLb = 85.0, currentMode = current)
            assertNull(kept.mode, current.toString()) // leave the current mode as is
            assertNull(kept.modifierPct, current.toString())
        }
        val fresh = ExerciseSettingsPolicy.forFreeLift(bench, null, fallbackWeightLb = 85.0, currentMode = "chains")
        assertEquals(85.0, fresh.weightLb)
        assertEquals("BAR", fresh.accessory)
        assertFalse(fresh.remembered)
        assertNull(ExerciseSettingsPolicy.forFreeLift(bench, null, fallbackWeightLb = null).weightLb)
    }

    // --- After a set ---

    @Test
    fun `routine set with auto-adjust ON saves the adjusted weight`() {
        val o = afterSet(50.0, reps = 8, target = 10)
        assertEquals(45.0, o.pref.weightLb)
        assertEquals(8, o.pref.lastReps)
        assertEquals(10, o.pref.lastTargetReps)
        assertEquals(WeightChange.Adjusted(45.0, -10, -2), o.change)
        assertEquals("Next set: 45 lb (↓10%, missed rep goal by 2 reps)", msg(o.change))

        val up = afterSet(50.0, reps = 11, target = 10)
        assertEquals(52.0, up.pref.weightLb)
        assertEquals("Next set: 52 lb (↑5%, beat rep goal by 1 rep)", msg(up.change))
    }

    @Test
    fun `hitting the goal, auto-adjust OFF, or free lift save the weight as used`() {
        listOf(
            afterSet(50.0, reps = 10, target = 10),
            afterSet(50.0, reps = 6, target = 10, autoAdjust = false),
            afterSet(50.0, reps = 6, target = null)
        ).forEach { o ->
            assertEquals(50.0, o.pref.weightLb)
            assertNull(o.change)
        }
    }

    @Test
    fun `remembered mode and accessory come from the set`() {
        val o = ExerciseSettingsPolicy.afterSet("u1", bench.id, 50.0, "eccentric", 20, "ROPE", 10, 10, true, "LB")
        assertEquals("eccentric", o.pref.mode)
        assertEquals(20, o.pref.modifierPct)
        assertEquals("ROPE", o.pref.accessory)
    }

    @Test
    fun `routine then free lift then routine keeps the adjusted weight without adjusting twice`() {
        val monday = afterSet(50.0, reps = 8, target = 10)
        assertEquals(45.0, monday.pref.weightLb)
        val tuesdayLoad = ExerciseSettingsPolicy.forFreeLift(bench, monday.pref, fallbackWeightLb = null)
        assertEquals(45.0, tuesdayLoad.weightLb)
        val tuesday = afterSet(tuesdayLoad.weightLb!!, reps = 3, target = null)
        assertEquals(45.0, tuesday.pref.weightLb)
        val wednesday = ExerciseSettingsPolicy.forRoutineStep(step(weightLb = 60.0), bench, tuesday.pref, autoAdjustOn = true, prMaxWeightLb = null)
        assertEquals(45.0, wednesday.weightLb)
    }

    @Test
    fun `rest-pause re-release recomputes from the lifted weight and total reps`() {
        // First release after 8 of 10 reps at 50 lb: -10% remembered
        assertEquals(45.0, afterSet(50.0, reps = 8, target = 10).pref.weightLb)
        // Lifter keeps going (still 50 lb on the motor) and reaches 10 total: back to 50 lb, no change
        val resumed = afterSet(50.0, reps = 10, target = 10)
        assertEquals(50.0, resumed.pref.weightLb)
        assertNull(resumed.change)
    }

    @Test
    fun `a safety cutout is remembered but never adjusts the weight`() {
        val o = ExerciseSettingsPolicy.afterSet("u1", bench.id, 50.0, "standard", 0, "BAR", 4, 10, true, "LB", safetyCutout = true)
        assertEquals(50.0, o.pref.weightLb)
        assertEquals(4, o.pref.lastReps)
        assertNull(o.change)
    }

    @Test
    fun `maximum and minimum weight give feedback instead of a fake change`() {
        val max = afterSet(200.0, reps = 12, target = 10)
        assertEquals(200.0, max.pref.weightLb)
        assertEquals("Maximum weight reached (200 lb)", msg(max.change))

        val min = afterSet(5.0, reps = 6, target = 10)
        assertEquals(5.0, min.pref.weightLb)
        assertEquals("Minimum weight reached (5 lb)", msg(min.change))
    }

    @Test
    fun `chains ceiling is the maximum, and a partial step up to it still counts as a change`() {
        val capped = afterSet(160.0, reps = 12, target = 10, mode = "chains", pct = 25)
        assertEquals(160.0, capped.pref.weightLb)
        assertEquals("Maximum weight reached (160 lb)", msg(capped.change))

        val partial = afterSet(150.0, reps = 12, target = 10, mode = "chains", pct = 25)
        assertEquals(160.0, partial.pref.weightLb)
        assertEquals("Next set: 160 lb (↑10%, beat rep goal by 2 reps)", msg(partial.change))
    }

    @Test
    fun `kg lifters see kg in messages`() {
        val startLb = 20.0 / Units.LB_TO_KG
        val o = afterSet(startLb, reps = 11, target = 10, unit = "KG")
        assertEquals(21.0, Units.lbToKg(o.pref.weightLb))
        assertEquals("Next set: 21 kg (↑5%, beat rep goal by 1 rep)", msg(o.change, unit = "KG"))
    }

    // --- Messages ---

    @Test
    fun `message names the exercise when the next set is a different exercise`() {
        val change = afterSet(50.0, reps = 9, target = 10).change
        assertEquals("Bench Press next time: 47 lb (↓5%, missed rep goal by 1 rep)", msg(change, sameNext = false))
        assertEquals("Bench Press: maximum weight reached (200 lb)", msg(afterSet(200.0, 12, 10).change, sameNext = false))
        assertEquals("Bench Press: minimum weight reached (5 lb)", msg(afterSet(5.0, 6, 10).change, sameNext = false))
    }

    @Test
    fun `post-set message keeps the change and the held weight separate`() {
        // Superset: Bench changed, Row's 60 lb is waiting for release
        assertEquals(
            "Bench Press next time: 47 lb (↓5%, missed rep goal by 1 rep)\nNext weight (60 lb) loads when you release the weight",
            ExerciseSettingsPolicy.postSetMessage(
                changeText = msg(afterSet(50.0, 9, 10).change, sameNext = false),
                lifterName = "Alex", someoneElseNext = false,
                deferredWeightLb = 60.0, deferredUnit = "LB", nextLifterName = null
            )
        )
        // Someone else lifts next: name both, in the next lifter's unit
        assertEquals(
            "Alex: Next set: 45 lb (↓10%, missed rep goal by 2 reps)\nTaylor's weight (16 kg) loads when you release the weight",
            ExerciseSettingsPolicy.postSetMessage(
                changeText = msg(afterSet(50.0, 8, 10).change),
                lifterName = "Alex", someoneElseNext = true,
                deferredWeightLb = 35.0, deferredUnit = "KG", nextLifterName = "Taylor"
            )
        )
        // Nothing to say
        assertNull(ExerciseSettingsPolicy.postSetMessage(null, "Alex", false, null, "LB", null))
        // Only a held weight
        assertEquals(
            "Next weight (45 lb) loads when you release the weight",
            ExerciseSettingsPolicy.postSetMessage(null, "Alex", false, 45.0, "LB", null)
        )
    }
}
