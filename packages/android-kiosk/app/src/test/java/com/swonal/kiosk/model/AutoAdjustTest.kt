package com.swonal.kiosk.model

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoAdjustTest {

    @Test
    fun `rep delta maps to percentage change and caps at 10 percent`() {
        assertEquals(-10, AutoAdjust.pctForRepDelta(-5))
        assertEquals(-10, AutoAdjust.pctForRepDelta(-2))
        assertEquals(-5, AutoAdjust.pctForRepDelta(-1))
        assertEquals(0, AutoAdjust.pctForRepDelta(0))
        assertEquals(5, AutoAdjust.pctForRepDelta(1))
        assertEquals(10, AutoAdjust.pctForRepDelta(2))
        assertEquals(10, AutoAdjust.pctForRepDelta(7))
    }

    @Test
    fun `hitting rep goal keeps weight`() {
        val r = AutoAdjust.nextWeightLb(50.0, lastReps = 10, targetReps = 10)
        assertEquals(50.0, r.weightLb)
        assertFalse(r.adjusted)
        assertNull(AutoAdjust.describe(r))
    }

    @Test
    fun `missed and beaten rep goals adjust and floor to whole pounds`() {
        assertEquals(47.0, AutoAdjust.nextWeightLb(50.0, 9, 10).weightLb)   // 47.5 -> 47
        assertEquals(45.0, AutoAdjust.nextWeightLb(50.0, 8, 10).weightLb)   // 45
        assertEquals(52.0, AutoAdjust.nextWeightLb(50.0, 11, 10).weightLb)  // 52.5 -> 52
        assertEquals(55.0, AutoAdjust.nextWeightLb(50.0, 12, 10).weightLb)  // 55
        assertEquals(105.0, AutoAdjust.nextWeightLb(100.0, 11, 10).weightLb) // no float-noise drift
    }

    @Test
    fun `light loads always move at least one pound`() {
        assertEquals(16.0, AutoAdjust.nextWeightLb(15.0, 11, 10).weightLb)  // 15.75 would floor back to 15
        assertEquals(14.0, AutoAdjust.nextWeightLb(15.0, 9, 10).weightLb)   // 14.25 -> 14
        assertEquals(10.0, AutoAdjust.nextWeightLb(11.0, 9, 10).weightLb)   // 10.45 -> 10
        assertEquals(23.0, AutoAdjust.nextWeightLb(22.5, 11, 10).weightLb)  // half-pound start
    }

    @Test
    fun `result is clamped to motor range`() {
        assertEquals(Units.MIN_WEIGHT_LB, AutoAdjust.nextWeightLb(5.0, 3, 10).weightLb)
        assertEquals(Units.MAX_WEIGHT_LB, AutoAdjust.nextWeightLb(195.0, 15, 10).weightLb)
    }

    @Test
    fun `kg lifters get whole kilogram results`() {
        val startLb = 20.0 / Units.LB_TO_KG // 20 kg
        val up = AutoAdjust.nextWeightLb(startLb, 11, 10, unit = "KG")
        assertEquals(21.0, up.weightLb * Units.LB_TO_KG, 1e-9) // 21 kg
        val down = AutoAdjust.nextWeightLb(startLb, 8, 10, unit = "KG")
        assertEquals(18.0, down.weightLb * Units.LB_TO_KG, 1e-9) // 18 kg
    }

    @Test
    fun `describe explains the change`() {
        assertEquals("↓5%, missed rep goal by 1 rep", AutoAdjust.describe(AutoAdjust.nextWeightLb(50.0, 9, 10)))
        assertEquals("↑10%, beat rep goal by 3 reps", AutoAdjust.describe(AutoAdjust.nextWeightLb(50.0, 13, 10)))
        assertTrue(AutoAdjust.nextWeightLb(50.0, 13, 10).adjusted)
    }

    @Test
    fun `first-time weight uses routine weight unless Auto with auto-adjust on`() {
        // Fixed routine weight, or auto-adjust OFF: the routine's weight is the starting weight
        assertEquals(60.0, AutoAdjust.firstTimeWeightLb(60.0, autoWeight = false, autoAdjustOn = true, prMaxWeightLb = 100.0))
        assertEquals(45.0, AutoAdjust.firstTimeWeightLb(45.0, autoWeight = true, autoAdjustOn = false, prMaxWeightLb = 100.0))
        // Auto + ON: 85% of best weight rounded to 5 lb, else 20 lb
        assertEquals(85.0, AutoAdjust.firstTimeWeightLb(45.0, autoWeight = true, autoAdjustOn = true, prMaxWeightLb = 100.0))
        assertEquals(AutoAdjust.AUTO_FIRST_TIME_WEIGHT_LB, AutoAdjust.firstTimeWeightLb(45.0, autoWeight = true, autoAdjustOn = true, prMaxWeightLb = null))
        assertEquals(AutoAdjust.AUTO_FIRST_TIME_WEIGHT_LB, AutoAdjust.firstTimeWeightLb(45.0, autoWeight = true, autoAdjustOn = true, prMaxWeightLb = 0.0))
    }

    @Test
    fun `first-time AUTO weight after a heavy low-rep best fits the step's rep goal`() {
        // Best set 150 x 2 (Epley 1RM 160): 85% of 150 would be 127.5 lb for 12 reps
        val e1rm = Units.estimateEpley1Rm(150.0, 2)
        // 160 / (1 + 12/30) * 0.9 = 102.9 -> rounded down to 100
        assertEquals(100.0, AutoAdjust.firstTimeWeightLb(45.0, true, true, prMaxWeightLb = 150.0, prOneRepMaxLb = e1rm, targetReps = 12))
        // A 5-rep step: 160 / (1 + 5/30) * 0.9 = 123.4 -> 120, still under 85% of 150
        assertEquals(120.0, AutoAdjust.firstTimeWeightLb(45.0, true, true, prMaxWeightLb = 150.0, prOneRepMaxLb = e1rm, targetReps = 5))
        // Never above 85% of the best weight, and rounded down (never up) to 5 lb
        assertEquals(125.0, AutoAdjust.firstTimeWeightLb(45.0, true, true, prMaxWeightLb = 150.0, prOneRepMaxLb = 400.0, targetReps = 3))
        assertEquals(90.0, AutoAdjust.firstTimeWeightLb(45.0, true, true, prMaxWeightLb = 110.0))
    }

    @Test
    fun `Auto flag survives template flattening and old routines are not Auto`() {
        val legacyJson = """[{"blockType":"STRAIGHT_SET","restSeconds":60,"items":[{"exerciseId":"ex1","sets":2,"targetReps":10,"targetWeightLb":45.0}]}]"""
        val legacy = SwonalJson.decodeFromString<List<WorkoutBlock>>(legacyJson)
        assertFalse(legacy[0].items[0].autoWeight)
        assertTrue(Units.flattenTemplateBlocks(legacy).none { it.autoWeight })

        val blocks = listOf(
            WorkoutBlock(
                blockType = "SUPERSET",
                items = listOf(
                    WorkoutBlockItem(exerciseId = "a", sets = 2, autoWeight = true, targetWeightLb = 45.0),
                    WorkoutBlockItem(exerciseId = "b", sets = 2, targetWeightLb = 60.0)
                )
            )
        )
        val roundTripped = SwonalJson.decodeFromString<List<WorkoutBlock>>(SwonalJson.encodeToString(blocks))
        val steps = Units.flattenTemplateBlocks(roundTripped)
        assertEquals(4, steps.size)
        assertTrue(steps.filter { it.exerciseId == "a" }.all { it.autoWeight })
        assertTrue(steps.filter { it.exerciseId == "b" }.none { it.autoWeight })
    }

    @Test
    fun `kg lifters move by at least 1 kg from the weight as shown`() {
        // The motor stores 0.5 lb steps: "18 kg" is really 39.5 lb = 17.92 kg
        val shown18Kg = Units.clampWeightForDynamicMode(18.0 / Units.LB_TO_KG, "standard", 0)
        assertEquals(18.0, Units.lbToKg(shown18Kg))
        val up = AutoAdjust.nextWeightLb(shown18Kg, 11, 10, unit = "KG")
        assertEquals(19.0, Units.lbToKg(Units.clampWeightForDynamicMode(up.weightLb, "standard", 0)))
        val down = AutoAdjust.nextWeightLb(shown18Kg, 9, 10, unit = "KG")
        assertEquals(17.0, Units.lbToKg(Units.clampWeightForDynamicMode(down.weightLb, "standard", 0)))
    }
}
