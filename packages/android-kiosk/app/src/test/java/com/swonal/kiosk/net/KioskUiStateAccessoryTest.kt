package com.swonal.kiosk.net

import com.swonal.kiosk.model.Exercise
import com.swonal.kiosk.model.SharedSessionState
import com.swonal.kiosk.model.Units
import com.swonal.kiosk.model.User
import com.swonal.kiosk.model.WorkoutBlock
import com.swonal.kiosk.model.WorkoutBlockItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KioskUiStateAccessoryTest {

    private val bench = Exercise(id = "bench", name = "Bench Press", muscleGroup = "Chest", accessory = "BAR")
    private val row = Exercise(id = "row", name = "Row", muscleGroup = "Back", accessory = "HANDLES")

    private val steps = Units.flattenTemplateBlocks(
        listOf(
            WorkoutBlock(
                blockType = "STRAIGHT_SET",
                items = listOf(
                    WorkoutBlockItem(exerciseId = "bench", sets = 1),
                    WorkoutBlockItem(exerciseId = "row", sets = 1, accessory = "ROPE")
                )
            )
        )
    )

    private val state = KioskUiState(
        users = listOf(User(id = "ana", name = "Ana"), User(id = "ben", name = "Ben")),
        exercises = listOf(bench, row),
        sessionState = SharedSessionState(activeUserId = "ana"),
        activeExerciseId = "bench",
        routineSteps = steps,
        currentStepIdx = 0,
        preferredAccessories = mapOf("ben" to "HANDLES")
    )

    @Test
    fun `active lifter without a default gets the exercise's own accessory`() {
        assertNull(state.preferredAccessory)
        assertEquals("BAR", state.activeAccessory)
    }

    @Test
    fun `another lifter's default applies when resolving for them, like the next Work-In partner`() {
        assertEquals("HANDLES", state.accessoryFor(bench, steps[0], userId = "ben"))
        assertEquals("BAR", state.accessoryFor(bench, steps[0], userId = "ana"))
    }

    @Test
    fun `a routine item's pick beats every lifter's default`() {
        assertEquals("ROPE", state.accessoryFor(row, steps[1], userId = "ben"))
        assertEquals("ROPE", state.accessoryFor(row, steps[1]))
    }

    @Test
    fun `switching the active lifter switches whose default applies`() {
        val asBen = state.copy(sessionState = SharedSessionState(activeUserId = "ben"))
        assertEquals("HANDLES", asBen.activeAccessory)
        assertEquals("HANDLES", asBen.accessoryFor(bench))
    }
}
