package com.swonal.kiosk.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnitsAndMathTest {

    @Test
    fun `canonical LB to KG conversions and half-pound formatting match server-app`() {
        assertEquals(22.5, Units.lbToKg(50.0), 0.0001)
        assertEquals(22.7, Units.lbToKgExact(50.0), 0.0001)
        assertEquals(45.5, Units.lbToKg(100.0), 0.0001)
        assertEquals(45.4, Units.lbToKgExact(100.0), 0.0001)
        assertEquals("45.5", Units.formatDialWeightString(100.0, "KG", exact = false))
        assertEquals("45.4", Units.formatDialWeightString(100.0, "KG", exact = true))
        assertEquals("100", Units.formatDialWeightString(100.0, "LB", exact = false))
        // Preserves half-pound precision rather than rounding 22.5 -> 23
        assertEquals("22.5", Units.formatDialWeightString(22.5, "LB", exact = false))
        assertEquals("22.5", Units.formatDialWeightString(22.5, "LB", exact = true))
    }

    @Test
    fun `parseDisplayWeightToLb and stepWeightLb handle LB and KG bounds accurately`() {
        assertEquals(5.0, Units.parseDisplayWeightToLb(1.0, "LB"), 0.001)
        assertEquals(200.0, Units.parseDisplayWeightToLb(250.0, "LB"), 0.001)
        assertEquals(49.5, Units.parseDisplayWeightToLb(22.5, "KG"), 0.001)
        assertEquals(5.0, Units.parseDisplayWeightToLb(1.0, "KG"), 0.001)
        assertEquals(200.0, Units.parseDisplayWeightToLb(120.0, "KG"), 0.001)

        assertEquals(5.0, Units.stepWeightLb(5.0, -5.0, "LB"), 0.001)
        assertEquals(200.0, Units.stepWeightLb(198.0, 5.0, "LB"), 0.001)
        assertEquals(30.0, Units.stepWeightLb(25.0, 5.0, "LB"), 0.001)

        // KG stepping (+/- 0.5 kg and +/- 2.5 kg)
        val upHalfKg = Units.stepWeightLb(50.0, 0.5, "KG") // 22.5 + 0.5 = 23.0 kg -> 50.5 lb
        assertEquals(50.5, upHalfKg, 0.5)
        val upLargeKg = Units.stepWeightLb(50.0, 2.5, "KG") // 22.5 + 2.5 = 25.0 kg -> 55.0 lb
        assertEquals(55.0, upLargeKg, 0.5)
        assertEquals(5.0, Units.stepWeightLb(5.5, -2.5, "KG"), 0.001)
        assertEquals(200.0, Units.stepWeightLb(198.0, 2.5, "KG"), 0.001)

        // Eccentric +25% multiplier = 1.25 -> max base load is 160.0 lb
        assertEquals(160.0, Units.clampWeightForDynamicMode(180.0, "eccentric", 25), 0.001)
        assertEquals(180.0, Units.clampWeightForDynamicMode(180.0, "spotter", 25), 0.001)
        assertEquals(0, Units.effectiveModifierPct("spotter", 25))
        assertEquals(25, Units.effectiveModifierPct("chains", 25))
    }

    @Test
    fun `velocity conversion and cable travel percent clamp cleanly`() {
        assertEquals(10.0, Units.convertVelocity(25.4, "LB"), 0.001)
        assertEquals(25.4, Units.convertVelocity(25.4, "KG"), 0.001)
        assertEquals("10.0 in/s", Units.formatVelocity(25.4, "LB"))
        assertEquals("25.4 cm/s", Units.formatVelocity(25.4, "KG"))
        assertEquals(0f, Units.cableTravelPercent(-10.0), 0.001f)
        assertEquals(50f, Units.cableTravelPercent(100.0), 0.001f)
        assertEquals(100f, Units.cableTravelPercent(250.0), 0.001f)
        assertEquals("1:05", Units.formatCountdown(65))
        assertEquals("1h 2m", Units.formatDurationHuman(3725))
        assertEquals("4m 05s", Units.formatDurationHuman(245))
        assertEquals("42s", Units.formatDurationHuman(42))
    }

    @Test
    fun `Epley 1RM formula matches server-app`() {
        assertEquals(100.0, Units.estimateEpley1Rm(100.0, 1), 0.001)
        assertEquals(133.3, Units.estimateEpley1Rm(100.0, 10), 0.001)
    }

    @Test
    fun `symmetry metrics classify balanced drift critical and clamp puck symmetrically`() {
        val balanced = SymmetryMetrics.from(
            TelemetryFrame(
                leftCable = CableData(position = 50.0, loadLb = 25.0),
                rightCable = CableData(position = 58.0, loadLb = 25.0)
            )
        )
        assertEquals(SymmetryStatus.BALANCED, balanced.status)
        assertEquals(50, balanced.leftLoadPct)

        val drift = SymmetryMetrics.from(
            TelemetryFrame(
                leftCable = CableData(position = 50.0, loadLb = 30.0),
                rightCable = CableData(position = 70.0, loadLb = 20.0)
            )
        )
        assertEquals(SymmetryStatus.DRIFT, drift.status)
        assertEquals(60, drift.leftLoadPct)
        assertEquals(40, drift.rightLoadPct)

        val extremeLeft = SymmetryMetrics.from(
            TelemetryFrame(leftCable = CableData(position = 150.0), rightCable = CableData(position = 0.0))
        )
        val extremeRight = SymmetryMetrics.from(
            TelemetryFrame(leftCable = CableData(position = 0.0), rightCable = CableData(position = 150.0))
        )
        assertEquals(SymmetryStatus.CRITICAL, extremeRight.status)
        // Symmetric bounds around 50%: 4.0 and 96.0
        assertEquals(4.0f, extremeLeft.puckPercent, 0.01f)
        assertEquals(96.0f, extremeRight.puckPercent, 0.01f)
    }

    @Test
    fun `VBT tracker detects fatigue alert and resets on disengage or reset_reps`() {
        val tracker = VbtTracker()
        tracker.update(
            TelemetryFrame(
                weightEnabled = true,
                repCount = 0,
                leftCable = CableData(velocity = 80.0),
                rightCable = CableData(velocity = 80.0)
            )
        )
        val afterRep1 = tracker.update(
            TelemetryFrame(
                weightEnabled = true,
                repCount = 1,
                leftCable = CableData(velocity = 75.0),
                rightCable = CableData(velocity = 75.0)
            )
        )
        assertFalse(afterRep1.fatigueAlert)
        assertEquals(0.0, afterRep1.velocityDropPct, 0.1)

        tracker.update(
            TelemetryFrame(
                weightEnabled = true,
                repCount = 1,
                leftCable = CableData(velocity = 52.0),
                rightCable = CableData(velocity = 52.0)
            )
        )
        val afterRep2 = tracker.update(
            TelemetryFrame(
                weightEnabled = true,
                repCount = 2,
                leftCable = CableData(velocity = 50.0),
                rightCable = CableData(velocity = 50.0)
            )
        )
        assertTrue(afterRep2.fatigueAlert)
        assertEquals(35.0, afterRep2.velocityDropPct, 0.5)

        // Reset reps while weight remains engaged must clear stale fatigue & velocity drop
        val afterResetReps = tracker.update(
            TelemetryFrame(
                weightEnabled = true,
                repCount = 0,
                leftCable = CableData(velocity = 0.0),
                rightCable = CableData(velocity = 0.0)
            )
        )
        assertFalse(afterResetReps.fatigueAlert)
        assertEquals(0.0, afterResetReps.velocityDropPct, 0.01)
    }

    @Test
    fun `flattenTemplateBlocks interleaves supersets and circuits including uneven set counts`() {
        val blocks = listOf(
            WorkoutBlock(
                blockType = "WARMUP",
                restSeconds = 30,
                items = listOf(WorkoutBlockItem(exerciseId = "ex-w", sets = 1, targetReps = 12, targetWeightLb = 20.0))
            ),
            WorkoutBlock(
                blockType = "SUPERSET",
                restSeconds = 45,
                items = listOf(
                    WorkoutBlockItem(exerciseId = "ex-b", sets = 3, targetReps = 10, targetWeightLb = 30.0),
                    WorkoutBlockItem(exerciseId = "ex-c", sets = 2, targetReps = 10, targetWeightLb = 20.0)
                )
            ),
            WorkoutBlock(
                blockType = "CIRCUIT",
                restSeconds = 60,
                items = listOf(
                    WorkoutBlockItem(exerciseId = "ex-d", sets = 2, targetReps = 15, targetWeightLb = 25.0),
                    WorkoutBlockItem(exerciseId = "ex-e", sets = 2, targetReps = 15, targetWeightLb = 25.0)
                )
            )
        )
        val steps = Units.flattenTemplateBlocks(blocks)
        assertEquals(
            listOf("ex-w", "ex-b", "ex-c", "ex-b", "ex-c", "ex-b", "ex-d", "ex-e", "ex-d", "ex-e"),
            steps.map { it.exerciseId }
        )
    }

    @Test
    fun `color contrast and light mode accent ink mix match CSS spec`() {
        val cyan = ColorMath.parseHexColor("#00F0FF")
        val darkBlue = ColorMath.parseHexColor("#1D4ED8")
        assertEquals(0xFF07090E.toInt(), ColorMath.getContrastTextColor(cyan))
        assertEquals(0xFFFFFFFF.toInt(), ColorMath.getContrastTextColor(darkBlue))
        assertEquals(0xFF00F0FF.toInt(), ColorMath.parseHexColor("not-a-color"))

        // 52% #00F0FF + 48% #0F172A -> R=7, G=136, B=153 -> #078899
        val ink = ColorMath.mixAccentInkLight(cyan)
        assertEquals("#078899", ColorMath.toHexString(ink))
    }

    @Test
    fun `ArmAlignmentHelper detects aligned, adjust, and unlocked lock-pin states`() {
        val ex = Exercise(
            id = "ex-curl",
            name = "Barbell Bicep Curl",
            muscleGroup = "Arms",
            accessory = "BAR",
            recommendedHeight = 1,
            recommendedRotation = 2,
            recommendedAngle = 1
        )

        val alignedFrame = TelemetryFrame(
            accessory = "BAR",
            armPositions = ArmPositions(
                height = ArmAxis(1, 1),
                rotation = ArmAxis(2, 2, leftUnlocked = false, rightUnlocked = false),
                angle = ArmAxis(1, 1)
            )
        )
        val aligned = ArmAlignmentHelper.evaluate(alignedFrame, ex)
        assertEquals(ArmAlignmentStatus.ALIGNED, aligned.status)
        assertTrue(aligned.leftMatched && aligned.rightMatched && aligned.accessoryMatched)
        assertTrue(aligned.badgeText.startsWith("ARMS ALIGNED"))

        val adjustFrame = alignedFrame.copy(
            armPositions = alignedFrame.armPositions.copy(height = ArmAxis(4, 1))
        )
        val adjust = ArmAlignmentHelper.evaluate(adjustFrame, ex)
        assertEquals(ArmAlignmentStatus.ADJUST, adjust.status)
        assertFalse(adjust.leftMatched)
        assertTrue(adjust.rightMatched)

        val unlockedFrame = alignedFrame.copy(
            armPositions = alignedFrame.armPositions.copy(
                rotation = ArmAxis(2, 2, leftUnlocked = false, rightUnlocked = true)
            )
        )
        val unlocked = ArmAlignmentHelper.evaluate(unlockedFrame, ex)
        assertEquals(ArmAlignmentStatus.UNLOCKED, unlocked.status)
        assertTrue(unlocked.rightUnlocked)
        assertTrue(unlocked.badgeText.startsWith("LOCK R ARM"))
    }
}
