package com.swonal.kiosk.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

object Units {
    const val LB_TO_KG = 0.45359237
    const val KG_TO_LB = 1.0 / LB_TO_KG
    const val MIN_WEIGHT_LB = 5.0
    const val MAX_WEIGHT_LB = 200.0
    const val CM_PER_INCH = 2.54
    const val MAX_CABLE_POSITION = 200.0

    fun isKg(unit: String): Boolean = unit.equals("KG", ignoreCase = true)

    fun lbToKg(weightLb: Double): Double = round((weightLb * LB_TO_KG) * 2.0) / 2.0

    fun lbToKgExact(weightLb: Double): Double = round((weightLb * LB_TO_KG) * 10.0) / 10.0

    fun formatDisplayWeight(weightLb: Double, unit: String, exact: Boolean = false): Double {
        return if (isKg(unit)) {
            if (exact) lbToKgExact(weightLb) else lbToKg(weightLb)
        } else {
            round(weightLb * 10.0) / 10.0
        }
    }

    fun formatDialWeightString(weightLb: Double, unit: String, exact: Boolean): String {
        val value = formatDisplayWeight(weightLb, unit, exact)
        return String.format(Locale.US, "%.1f", value).removeSuffix(".0")
    }

    fun formatCompactWeight(weightLb: Double, unit: String, exact: Boolean = false): String {
        val value = formatDisplayWeight(weightLb, unit, exact)
        return String.format(Locale.US, "%.1f", value).removeSuffix(".0")
    }

    fun formatExactWeight(weightLb: Double, unit: String): String {
        val value = formatDisplayWeight(weightLb, unit, exact = true)
        return String.format(Locale.US, "%.1f", value)
    }

    fun parseDisplayWeightToLb(displayValue: Double, unit: String): Double {
        val rawLb = if (isKg(unit)) displayValue * KG_TO_LB else displayValue
        val halfRounded = round(rawLb * 2.0) / 2.0
        return halfRounded.coerceIn(MIN_WEIGHT_LB, MAX_WEIGHT_LB)
    }

    fun stepWeightLb(currentLb: Double, deltaDisplay: Double, unit: String): Double {
        val nextLb = if (isKg(unit)) {
            val currentKg = lbToKg(currentLb)
            round(((currentKg + deltaDisplay) * KG_TO_LB) * 2.0) / 2.0
        } else {
            round((currentLb + deltaDisplay) * 2.0) / 2.0
        }
        return nextLb.coerceIn(MIN_WEIGHT_LB, MAX_WEIGHT_LB)
    }

    fun dynamicModeDefaultPct(mode: String): Int = when (mode.lowercase(Locale.US)) {
        "eccentric" -> 25
        "chains" -> 25
        else -> 0
    }

    fun effectiveModifierPct(mode: String, modifierPct: Int): Int {
        return when (mode.lowercase(Locale.US)) {
            "eccentric", "chains" -> modifierPct.coerceIn(0, 100)
            else -> 0
        }
    }

    fun clampWeightForDynamicMode(weightLb: Double, profileMode: String, modifierPct: Int): Double {
        val clamped = (round(weightLb * 2.0) / 2.0).coerceIn(MIN_WEIGHT_LB, MAX_WEIGHT_LB)
        val effMod = effectiveModifierPct(profileMode, modifierPct)
        val multiplier = 1.0 + effMod / 100.0
        return if (clamped * multiplier > MAX_WEIGHT_LB) {
            floor((MAX_WEIGHT_LB / multiplier) * 2.0) / 2.0
        } else {
            clamped
        }
    }

    fun convertVelocity(cmPerSec: Double, unit: String): Double {
        return if (isKg(unit)) cmPerSec else cmPerSec / CM_PER_INCH
    }

    fun velocityUnitLabel(unit: String): String = if (isKg(unit)) "cm/s" else "in/s"

    fun formatVelocity(cmPerSec: Double, unit: String): String {
        val converted = convertVelocity(cmPerSec, unit)
        return String.format(Locale.US, "%.1f %s", converted, velocityUnitLabel(unit))
    }

    fun formatVelocityValue(cmPerSec: Double, unit: String): String {
        val converted = convertVelocity(cmPerSec, unit)
        return String.format(Locale.US, "%.1f", converted)
    }

    fun cableTravelPercent(position: Double): Float {
        return ((position / MAX_CABLE_POSITION) * 100.0).coerceIn(0.0, 100.0).toFloat()
    }

    fun estimateEpley1Rm(weightLb: Double, reps: Int): Double {
        val raw = if (reps <= 1) weightLb else weightLb * (1.0 + reps / 30.0)
        return round(raw * 10.0) / 10.0
    }

    fun flattenTemplateBlocks(blocks: List<WorkoutBlock>): List<RoutineStep> {
        val result = mutableListOf<RoutineStep>()
        blocks.forEachIndexed { bIdx, block ->
            val maxSets = block.items.maxOfOrNull { it.sets } ?: 0
            val interleaved = block.blockType == "SUPERSET" || block.blockType == "CIRCUIT"
            if (interleaved) {
                for (s in 1..maxSets) {
                    block.items.forEachIndexed { iIdx, item ->
                        if (s <= item.sets) {
                            result += RoutineStep(
                                blockIndex = bIdx,
                                blockType = block.blockType,
                                itemIndex = iIdx,
                                exerciseId = item.exerciseId,
                                setNumber = s,
                                totalSetsInItem = item.sets,
                                targetReps = item.targetReps,
                                targetWeightLb = item.targetWeightLb,
                                mode = item.mode,
                                modifierPct = item.modifierPct,
                                restSeconds = block.restSeconds
                            )
                        }
                    }
                }
            } else {
                block.items.forEachIndexed { iIdx, item ->
                    for (s in 1..item.sets) {
                        result += RoutineStep(
                            blockIndex = bIdx,
                            blockType = block.blockType,
                            itemIndex = iIdx,
                            exerciseId = item.exerciseId,
                            setNumber = s,
                            totalSetsInItem = item.sets,
                            targetReps = item.targetReps,
                            targetWeightLb = item.targetWeightLb,
                            mode = item.mode,
                            modifierPct = item.modifierPct,
                            restSeconds = block.restSeconds
                        )
                    }
                }
            }
        }
        return result
    }

    fun formatCountdown(totalSeconds: Int): String {
        val safe = max(0, totalSeconds)
        val mins = safe / 60
        val secs = safe % 60
        return String.format(Locale.US, "%d:%02d", mins, secs)
    }

    fun formatDurationHuman(durationSec: Long): String {
        val safe = max(0L, durationSec)
        val hours = safe / 3600
        val mins = (safe % 3600) / 60
        val secs = safe % 60
        return when {
            hours > 0 -> "${hours}h ${mins}m"
            mins > 0 -> String.format(Locale.US, "%dm %02ds", mins, secs)
            else -> "${secs}s"
        }
    }
}

enum class SymmetryStatus { BALANCED, DRIFT, CRITICAL }

data class SymmetryMetrics(
    val positionDelta: Double = 0.0,
    val signedBalance: Double = 0.0,
    val leftLoadPct: Int = 50,
    val rightLoadPct: Int = 50,
    val status: SymmetryStatus = SymmetryStatus.BALANCED
) {
    val puckPercent: Float
        get() {
            // Apply ±8.0 deadzone and dampened scaling so normal bilateral reps feel smooth and stable
            val adjusted = when {
                abs(signedBalance) <= 8.0 -> 0.0
                signedBalance > 0 -> signedBalance - 8.0
                else -> signedBalance + 8.0
            }
            return (50.0 + adjusted * 0.35).coerceIn(6.0, 94.0).toFloat()
        }

    companion object {
        fun from(frame: TelemetryFrame, smoothedSigned: Double? = null): SymmetryMetrics {
            val rawSigned = frame.rightCable.position - frame.leftCable.position
            val signed = smoothedSigned ?: rawSigned
            val delta = abs(signed)
            val totalLoad = frame.leftCable.loadLb + frame.rightCable.loadLb
            val leftPct = if (totalLoad > 0.0) ((frame.leftCable.loadLb / totalLoad) * 100.0).roundToInt() else 50
            val rightPct = if (totalLoad > 0.0) 100 - leftPct else 50
            val status = when {
                delta > 80.0 -> SymmetryStatus.CRITICAL
                delta > 35.0 -> SymmetryStatus.DRIFT
                else -> SymmetryStatus.BALANCED
            }
            return SymmetryMetrics(
                positionDelta = delta,
                signedBalance = signed,
                leftLoadPct = leftPct,
                rightLoadPct = rightPct,
                status = status
            )
        }
    }
}

data class VbtMetrics(
    val currentVelocityCmSec: Double = 0.0,
    val peakSetVelocityCmSec: Double = 0.0,
    val firstRepPeakCmSec: Double = 0.0,
    val lastRepPeakCmSec: Double = 0.0,
    val velocityDropPct: Double = 0.0,
    val fatigueAlert: Boolean = false,
    val symmetry: SymmetryMetrics = SymmetryMetrics()
) {
    val gaugeFillPercent: Float
        get() = if (currentVelocityCmSec >= 0.5) {
            min(100.0, (currentVelocityCmSec / max(peakSetVelocityCmSec, 60.0)) * 100.0).toFloat()
        } else {
            min(100.0, velocityDropPct * 2.0).toFloat()
        }
}

class VbtTracker {
    private var lastWeightEnabled = false
    private var lastRepCount = 0
    private var currentRepPeakVel = 0.0
    private var firstRepPeakVel = 0.0
    private var lastRepPeakVel = 0.0
    private var peakSetVelocity = 0.0
    private var velocityDropPct = 0.0
    private var fatigueAlert = false
    private var prevLeftPos: Double? = null
    private var prevRightPos: Double? = null
    private var prevSampleMs: Long = 0L
    private var smoothedVelocity = 0.0
    private var smoothedSignedBalance = 0.0

    fun update(frame: TelemetryFrame): VbtMetrics {
        val nowMs = System.currentTimeMillis()
        var leftVel = abs(frame.leftCable.velocity)
        var rightVel = abs(frame.rightCable.velocity)

        // Fallback to position-derivative velocity when hardware cable_speed is 0 while cables move
        val pL = prevLeftPos
        val pR = prevRightPos
        if (pL != null && pR != null && prevSampleMs > 0L) {
            val dtSec = (nowMs - prevSampleMs) / 1000.0
            if (dtSec in 0.015..0.5) {
                val deltaL = abs(frame.leftCable.position - pL)
                val deltaR = abs(frame.rightCable.position - pR)
                val derivL = if (deltaL >= 0.6) (deltaL / dtSec) * 0.5 else 0.0
                val derivR = if (deltaR >= 0.6) (deltaR / dtSec) * 0.5 else 0.0
                if (leftVel < 0.1 && derivL >= 2.0) leftVel = derivL
                if (rightVel < 0.1 && derivR >= 2.0) rightVel = derivR
            }
        }
        prevLeftPos = frame.leftCable.position
        prevRightPos = frame.rightCable.position
        prevSampleMs = nowMs

        // Use active cable velocity (works for both bilateral and unilateral exercises)
        val rawVel = when {
            leftVel > 2.0 && rightVel > 2.0 -> (leftVel + rightVel) / 2.0
            else -> max(leftVel, rightVel)
        }.let { if (it < 2.5) 0.0 else it }

        smoothedVelocity = if (rawVel < 2.5) {
            smoothedVelocity * 0.65
        } else {
            0.18 * rawVel + 0.82 * smoothedVelocity
        }
        val avgVel = if (smoothedVelocity < 1.5) 0.0 else smoothedVelocity

        // Smooth symmetry balance to prevent jitter
        val rawSigned = frame.rightCable.position - frame.leftCable.position
        smoothedSignedBalance = 0.25 * rawSigned + 0.75 * smoothedSignedBalance

        if (!frame.weightEnabled && lastWeightEnabled) {
            resetSet()
        } else if (!frame.weightEnabled && frame.repCount == 0) {
            resetSet()
        } else if (frame.weightEnabled) {
            // Reset VBT state if rep counter was explicitly cleared mid-engagement
            if (frame.repCount < lastRepCount) {
                resetSet()
            }
            if (rawVel > currentRepPeakVel) currentRepPeakVel = rawVel
            if (rawVel > peakSetVelocity) peakSetVelocity = rawVel

            if (frame.repCount > lastRepCount) {
                val repPeak = max(currentRepPeakVel, rawVel)
                if (firstRepPeakVel == 0.0 && repPeak > 0.1) {
                    firstRepPeakVel = repPeak
                }
                lastRepPeakVel = repPeak
                currentRepPeakVel = 0.0
                velocityDropPct = if (firstRepPeakVel > 0.0) {
                    max(0.0, ((firstRepPeakVel - repPeak) / firstRepPeakVel) * 100.0)
                } else {
                    0.0
                }
                fatigueAlert = velocityDropPct >= 25.0 && frame.repCount >= 2
            }
        }
        lastWeightEnabled = frame.weightEnabled
        lastRepCount = frame.repCount
        return VbtMetrics(
            currentVelocityCmSec = avgVel,
            peakSetVelocityCmSec = peakSetVelocity,
            firstRepPeakCmSec = firstRepPeakVel,
            lastRepPeakCmSec = lastRepPeakVel,
            velocityDropPct = velocityDropPct,
            fatigueAlert = fatigueAlert,
            symmetry = SymmetryMetrics.from(frame, smoothedSignedBalance)
        )
    }

    fun resetSet() {
        currentRepPeakVel = 0.0
        firstRepPeakVel = 0.0
        lastRepPeakVel = 0.0
        peakSetVelocity = 0.0
        velocityDropPct = 0.0
        fatigueAlert = false
        smoothedVelocity = 0.0
    }
}

object ColorMath {
    fun parseHexColor(hex: String, fallback: Int = 0xFF00F0FF.toInt()): Int {
        val cleaned = hex.trim().removePrefix("#")
        return when (cleaned.length) {
            6 -> runCatching { (0xFF000000L or cleaned.toLong(16)).toInt() }.getOrDefault(fallback)
            8 -> runCatching { cleaned.toLong(16).toInt() }.getOrDefault(fallback)
            else -> fallback
        }
    }

    fun getContrastTextColor(bgColor: Int): Int {
        val r = ((bgColor shr 16) and 0xFF) / 255.0
        val g = ((bgColor shr 8) and 0xFF) / 255.0
        val b = (bgColor and 0xFF) / 255.0
        fun lin(s: Double) = if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        val lum = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        return if (lum > 0.42) 0xFF07090E.toInt() else 0xFFFFFFFF.toInt()
    }

    /** Light mode --accent-ink: color-mix(in srgb, accent 52%, #0f172a) */
    fun mixAccentInkLight(accentColor: Int): Int {
        val ar = (accentColor shr 16) and 0xFF
        val ag = (accentColor shr 8) and 0xFF
        val ab = accentColor and 0xFF
        val r = (0.52 * ar + 0.48 * 15).roundToInt().coerceIn(0, 255)
        val g = (0.52 * ag + 0.48 * 23).roundToInt().coerceIn(0, 255)
        val b = (0.52 * ab + 0.48 * 42).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun withAlpha(color: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255f).roundToInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    fun toHexString(color: Int): String {
        return String.format(Locale.US, "#%06X", color and 0x00FFFFFF)
    }
}
