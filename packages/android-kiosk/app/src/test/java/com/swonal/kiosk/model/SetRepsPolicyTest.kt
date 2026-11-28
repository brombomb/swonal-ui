package com.swonal.kiosk.model

import com.swonal.kiosk.model.SetRepsPolicy.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SetRepsPolicyTest {

    @Test
    fun `counted reps are logged as-is`() {
        assertEquals(Decision.Log(7), SetRepsPolicy.decide(null, 7, 10))
        assertEquals(Decision.Log(3), SetRepsPolicy.decide(null, 3, null))
    }

    @Test
    fun `explicit rep count always wins, even with the counter at 0`() {
        assertEquals(Decision.Log(12), SetRepsPolicy.decide(12, 0, 10))
        assertEquals(Decision.Log(8), SetRepsPolicy.decide(8, 5, 10))
    }

    @Test
    fun `zero counted reps asks, prefilled with the step target`() {
        assertEquals(Decision.Confirm(8), SetRepsPolicy.decide(null, 0, 8))
    }

    @Test
    fun `zero counted reps in Free Lift asks, prefilled with 10`() {
        assertEquals(Decision.Confirm(10), SetRepsPolicy.decide(null, 0, null))
    }

    @Test
    fun `negative counter is treated as nothing counted`() {
        assertEquals(Decision.Confirm(10), SetRepsPolicy.decide(null, -1, null))
    }

    @Test
    fun `suggestion stays within 1 to 500`() {
        assertEquals(10, SetRepsPolicy.suggestedReps(0))
        assertEquals(10, SetRepsPolicy.suggestedReps(-3))
        assertEquals(500, SetRepsPolicy.suggestedReps(900))
        assertEquals(1, SetRepsPolicy.suggestedReps(1))
    }

    @Test
    fun `entered reps must be 1 to 500`() {
        assertEquals(12, SetRepsPolicy.parseEnteredReps("12"))
        assertEquals(5, SetRepsPolicy.parseEnteredReps(" 5 "))
        assertEquals(500, SetRepsPolicy.parseEnteredReps("500"))
        assertNull(SetRepsPolicy.parseEnteredReps(""))
        assertNull(SetRepsPolicy.parseEnteredReps("0"))
        assertNull(SetRepsPolicy.parseEnteredReps("501"))
        assertNull(SetRepsPolicy.parseEnteredReps("-4"))
        assertNull(SetRepsPolicy.parseEnteredReps("abc"))
    }
}
