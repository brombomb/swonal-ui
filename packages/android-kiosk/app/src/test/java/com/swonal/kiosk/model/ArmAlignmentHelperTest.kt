package com.swonal.kiosk.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArmAlignmentHelperTest {

    private fun frame(
        h: Pair<Int, Int>,
        r: Pair<Int, Int>,
        a: Pair<Int, Int>,
        accessory: String = "HANDLES",
        leftUnlocked: Boolean = false,
        rightUnlocked: Boolean = false
    ) = TelemetryFrame(
        accessory = accessory,
        armPositions = ArmPositions(
            height = ArmAxis(h.first, h.second),
            rotation = ArmAxis(r.first, r.second, leftUnlocked = leftUnlocked, rightUnlocked = rightUnlocked),
            angle = ArmAxis(a.first, a.second)
        )
    )

    private val row = Exercise(
        id = "ex-row",
        name = "Seated Row",
        muscleGroup = "Back",
        accessory = "HANDLES",
        recommendedHeight = 9,
        recommendedRotation = 4,
        recommendedAngle = 4,
        allowedRotations = listOf(4, 5)
    )

    @Test
    fun `allowed sets accept any listed value and target text is compact`() {
        val s = ArmAlignmentHelper.evaluate(frame(9 to 9, 5 to 4, 4 to 4), row)
        assertEquals(ArmAlignmentStatus.ALIGNED, s.status)
        assertTrue(s.leftMatched && s.rightMatched && s.armsMatched)
        assertEquals("H9 R4–5 A4 • HANDLES", s.targetText)

        val off = ArmAlignmentHelper.evaluate(frame(9 to 9, 6 to 4, 4 to 4), row)
        assertEquals(ArmAlignmentStatus.ADJUST, off.status)
        assertFalse(off.leftMatched)
        assertTrue(off.rightMatched)
    }

    @Test
    fun `empty allowed list falls back to the recommended value only`() {
        val ex = row.copy(allowedRotations = emptyList())
        assertEquals(ArmAlignmentStatus.ALIGNED, ArmAlignmentHelper.evaluate(frame(9 to 9, 4 to 4, 4 to 4), ex).status)
        assertEquals(ArmAlignmentStatus.ADJUST, ArmAlignmentHelper.evaluate(frame(9 to 9, 5 to 5, 4 to 4), ex).status)
        assertEquals("H9 R4 A4", ArmPositionSets.targetCoords(ex))
    }

    @Test
    fun `two-arm exercise needs both arms and any unlocked arm reports UNLOCKED`() {
        val unlocked = ArmAlignmentHelper.evaluate(frame(9 to 9, 4 to 4, 4 to 4, rightUnlocked = true), row)
        assertEquals(ArmAlignmentStatus.UNLOCKED, unlocked.status)
        assertTrue(unlocked.leftMatched)
        assertFalse(unlocked.rightMatched)
        assertTrue(unlocked.badgeText.startsWith("LOCK R ARM"))
        assertTrue(unlocked.rightLiveText.endsWith("UNLOCKED"))

        val wrongAccessory = ArmAlignmentHelper.evaluate(frame(9 to 9, 4 to 4, 4 to 4, accessory = "ROPE"), row)
        assertEquals(ArmAlignmentStatus.ADJUST, wrongAccessory.status)
        assertTrue(wrongAccessory.badgeText.contains("ATTACH HANDLES"))
    }

    @Test
    fun `unilateral exercise is aligned when either arm matches`() {
        val ex = row.copy(unilateral = true)
        val leftOnly = ArmAlignmentHelper.evaluate(frame(9 to 1, 4 to 1, 4 to 0), ex)
        assertEquals(ArmAlignmentStatus.ALIGNED, leftOnly.status)
        assertTrue(leftOnly.leftMatched)
        assertFalse(leftOnly.rightMatched)
        assertTrue(leftOnly.armsMatched)
        assertEquals("ARMS ALIGNED • L:H9 R4 A4 • HANDLES", leftOnly.badgeText)

        val rightOnly = ArmAlignmentHelper.evaluate(frame(1 to 9, 1 to 5, 0 to 4), ex)
        assertEquals(ArmAlignmentStatus.ALIGNED, rightOnly.status)
        assertTrue(rightOnly.badgeText.contains("R:H9 R5 A4"))

        // Idle arm moving does not matter while the working arm is locked in place.
        val otherArmUnlocked = ArmAlignmentHelper.evaluate(frame(9 to 3, 4 to 2, 4 to 1, rightUnlocked = true), ex)
        assertEquals(ArmAlignmentStatus.ALIGNED, otherArmUnlocked.status)

        val noneMatchUnlocked = ArmAlignmentHelper.evaluate(frame(3 to 3, 2 to 2, 1 to 1, leftUnlocked = true), ex)
        assertEquals(ArmAlignmentStatus.UNLOCKED, noneMatchUnlocked.status)

        val noneMatch = ArmAlignmentHelper.evaluate(frame(3 to 3, 2 to 2, 1 to 1), ex)
        assertEquals(ArmAlignmentStatus.ADJUST, noneMatch.status)
        assertFalse(noneMatch.armsMatched)

        val wrongAccessory = ArmAlignmentHelper.evaluate(frame(9 to 1, 4 to 1, 4 to 1, accessory = "BAR"), ex)
        assertEquals(ArmAlignmentStatus.ADJUST, wrongAccessory.status)
        assertTrue(wrongAccessory.badgeText.contains("ATTACH HANDLES"))
    }

    @Test
    fun `folded angle 0 never matches A1 and is shown as A0`() {
        val ex = row.copy(recommendedAngle = 1, allowedAngles = listOf(1, 2))
        val folded = ArmAlignmentHelper.evaluate(frame(9 to 9, 4 to 4, 0 to 0), ex)
        assertEquals(ArmAlignmentStatus.ADJUST, folded.status)
        assertFalse(folded.leftMatched || folded.rightMatched)
        assertTrue(folded.badgeText.contains("H9 R4 A0"))
        assertEquals("H9 R4 A0 (folded)", folded.leftLiveText)
        assertFalse(ArmPositionSets.angleMatches(ex, 0))
        assertTrue(ArmPositionSets.angleMatches(ex, 1))
    }

    @Test
    fun `telemetry JSON keeps angle 0 instead of clamping to 1`() {
        val json = """{"arm_positions":{"height":{"left":9,"right":9},"rotation":{"left":1,"right":1},"angle":{"left":0,"right":0}}}"""
        val frame = SwonalJson.decodeFromString(TelemetryFrame.serializer(), json)
        assertEquals(0, frame.armPositions.angle.left)
        assertEquals(0, frame.armPositions.angle.right)
    }

    @Test
    fun `formatSet compacts contiguous runs with an en dash`() {
        assertEquals("4–5", ArmPositionSets.formatSet(listOf(5, 4)))
        assertEquals("4,6", ArmPositionSets.formatSet(listOf(4, 6)))
        assertEquals("1–3,5", ArmPositionSets.formatSet(listOf(3, 1, 2, 5, 5)))
        assertEquals("7", ArmPositionSets.formatSet(listOf(7)))
        assertEquals("", ArmPositionSets.formatSet(emptyList()))
        assertEquals(
            "H8–9  R4,6  A2",
            ArmPositionSets.targetCoords(
                row.copy(allowedHeights = listOf(8, 9), allowedRotations = listOf(4, 6), recommendedAngle = 2),
                sep = "  "
            )
        )
    }

    @Test
    fun `parseCsv handles empty, spaces, junk, duplicates and out-of-range values`() {
        val r = ArmPositionSets.ROTATION_RANGE
        assertEquals(emptyList<Int>(), ArmPositionSets.parseCsv("", r))
        assertEquals(emptyList<Int>(), ArmPositionSets.parseCsv(null, r))
        assertEquals(emptyList<Int>(), ArmPositionSets.parseCsv(" , ,", r))
        assertEquals(listOf(4, 5), ArmPositionSets.parseCsv(" 5 , 4 ", r))
        assertEquals(listOf(2, 6), ArmPositionSets.parseCsv("6,x,2,,4.5,abc", r))
        assertEquals(listOf(1, 6), ArmPositionSets.parseCsv("0,1,6,7,-1,1", r))
        assertEquals(listOf(12), ArmPositionSets.parseCsv("12,13", ArmPositionSets.HEIGHT_RANGE))
        assertEquals(emptyList<Int>(), ArmPositionSets.parseCsv("0", ArmPositionSets.ANGLE_RANGE))
        assertEquals("4,5", ArmPositionSets.toCsv(listOf(4, 5)))
        assertEquals("", ArmPositionSets.toCsv(emptyList()))
    }

    @Test
    fun `normalize adds the recommended value and drops out-of-range entries`() {
        val r = ArmPositionSets.ROTATION_RANGE
        assertEquals(listOf(3, 4, 5), ArmPositionSets.normalize(listOf(5, 4), recommended = 3, range = r))
        assertEquals(listOf(4, 5), ArmPositionSets.normalize(listOf(5, 4, 4, 9), recommended = 4, range = r))
        assertEquals(emptyList<Int>(), ArmPositionSets.normalize(emptyList(), recommended = 4, range = r))
        assertEquals(emptyList<Int>(), ArmPositionSets.normalize(listOf(0, 7), recommended = 4, range = r))
    }
}
