package com.swonal.kiosk.model

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AccessoriesTest {

    @Test
    fun `routine item pick beats the lifter default, which beats the catalog accessory`() {
        assertEquals("ROPE", Accessories.resolve("BAR", itemAccessory = "ROPE", userDefault = "HANDLES"))
        assertEquals("HANDLES", Accessories.resolve("BAR", itemAccessory = null, userDefault = "HANDLES"))
        assertEquals("BAR", Accessories.resolve("BAR", itemAccessory = null, userDefault = null))
        assertEquals("HANDLES", Accessories.resolve("bar", itemAccessory = "handles"))
    }

    @Test
    fun `bodyweight moves ignore the lifter default but honor an explicit item pick`() {
        assertEquals("BODYWEIGHT", Accessories.resolve("BODYWEIGHT", userDefault = "HANDLES"))
        assertEquals("BAR", Accessories.resolve("BODYWEIGHT", itemAccessory = "BAR", userDefault = "HANDLES"))
    }

    @Test
    fun `unknown or blank values are treated as unset`() {
        assertNull(Accessories.normalize("KETTLEBELL"))
        assertNull(Accessories.normalize("BODYWEIGHT"))
        assertNull(Accessories.normalize(" "))
        assertNull(Accessories.normalize(null))
        assertEquals("ROPE", Accessories.normalize(" rope "))
        assertEquals("BAR", Accessories.resolve("BAR", itemAccessory = "KETTLEBELL", userDefault = "SANDBAG"))
        assertEquals("HANDLES", Accessories.resolve("", userDefault = null))
    }

    @Test
    fun `picker cycles default, handles, bar, rope and back to default`() {
        var current: String? = null
        val seen = (1..4).map { current = Accessories.cycle(current); current }
        assertEquals(listOf("HANDLES", "BAR", "ROPE", null), seen)
        assertEquals("Per exercise", Accessories.defaultLabel(null))
        assertEquals("HANDLES", Accessories.defaultLabel("handles"))
    }

    @Test
    fun `routine steps carry each item's accessory pick, and old routines decode without one`() {
        val blocks = listOf(
            WorkoutBlock(
                blockType = "SUPERSET",
                items = listOf(
                    WorkoutBlockItem(exerciseId = "a", sets = 2, accessory = "HANDLES"),
                    WorkoutBlockItem(exerciseId = "b", sets = 1)
                )
            )
        )
        val steps = Units.flattenTemplateBlocks(blocks)
        assertEquals(listOf("a" to "HANDLES", "b" to null, "a" to "HANDLES"), steps.map { it.exerciseId to it.accessory })

        val legacy = SwonalJson.decodeFromString<List<WorkoutBlock>>(
            """[{"blockType":"STRAIGHT_SET","restSeconds":60,"items":[{"exerciseId":"x","sets":3,"targetReps":10}]}]"""
        )
        assertNull(legacy[0].items[0].accessory)

        val roundTrip = SwonalJson.decodeFromString<List<WorkoutBlock>>(SwonalJson.encodeToString(blocks))
        assertEquals("HANDLES", roundTrip[0].items[0].accessory)
        assertNull(roundTrip[0].items[1].accessory)
    }
}
