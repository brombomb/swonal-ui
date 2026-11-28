package com.swonal.kiosk.model

enum class ArmAlignmentStatus {
    ALIGNED,
    ADJUST,
    UNLOCKED
}

data class ArmAlignmentState(
    val status: ArmAlignmentStatus,
    val leftMatched: Boolean,
    val rightMatched: Boolean,
    /** Both arms for two-arm exercises; either arm for unilateral ones. */
    val armsMatched: Boolean,
    val accessoryMatched: Boolean,
    val leftUnlocked: Boolean,
    val rightUnlocked: Boolean,
    val badgeText: String,
    val leftLiveText: String,
    val rightLiveText: String,
    val targetText: String
)

/**
 * Acceptable arm-position sets per exercise. Tonal V1 scales: height 1 (bottom) … 9 (top, up to 12 allowed),
 * rotation 1 (stowed) … 6, angle 0 (folded, live reading only) / 1 (down) … 5 (up).
 * An empty allowed list means only the recommended value counts.
 */
object ArmPositionSets {
    val HEIGHT_RANGE = 1..12
    val ROTATION_RANGE = 1..6
    val ANGLE_RANGE = 1..5
    const val ANGLE_FOLDED = 0

    /** Parses a stored comma list ("4, 5") into sorted distinct ints, ignoring junk and out-of-range values. */
    fun parseCsv(raw: String?, range: IntRange): List<Int> =
        raw.orEmpty().split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in range }
            .distinct()
            .sorted()

    fun toCsv(values: List<Int>): String = values.joinToString(",")

    /** Drops out-of-range values; a non-empty list always includes the recommended value. */
    fun normalize(allowed: List<Int>, recommended: Int, range: IntRange): List<Int> {
        val clean = allowed.filter { it in range }.distinct()
        if (clean.isEmpty()) return emptyList()
        return (if (recommended in range && recommended !in clean) clean + recommended else clean).sorted()
    }

    fun effective(allowed: List<Int>, recommended: Int): List<Int> = allowed.ifEmpty { listOf(recommended) }

    fun heightSet(ex: Exercise): List<Int> = effective(ex.allowedHeights, ex.recommendedHeight)
    fun rotationSet(ex: Exercise): List<Int> = effective(ex.allowedRotations, ex.recommendedRotation)
    fun angleSet(ex: Exercise): List<Int> = effective(ex.allowedAngles, ex.recommendedAngle)

    fun heightMatches(ex: Exercise, h: Int): Boolean = h in heightSet(ex)
    fun rotationMatches(ex: Exercise, r: Int): Boolean = r in rotationSet(ex)
    fun angleMatches(ex: Exercise, a: Int): Boolean = a in angleSet(ex)

    /** Compact set text: [4,5] -> "4–5", [4,6] -> "4,6", [1,2,3,5] -> "1–3,5". */
    fun formatSet(values: List<Int>): String {
        val sorted = values.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = start
        for (v in sorted.drop(1) + Int.MIN_VALUE) {
            if (v == prev + 1) {
                prev = v
                continue
            }
            parts += if (start == prev) "$start" else "$start–$prev"
            start = v
            prev = v
        }
        return parts.joinToString(",")
    }

    fun formatHeight(ex: Exercise): String = formatSet(heightSet(ex))
    fun formatRotation(ex: Exercise): String = formatSet(rotationSet(ex))
    fun formatAngle(ex: Exercise): String = formatSet(angleSet(ex))

    /** Target coordinates, e.g. "H9 R4–5 A4". */
    fun targetCoords(ex: Exercise, sep: String = " "): String =
        "H${formatHeight(ex)}${sep}R${formatRotation(ex)}${sep}A${formatAngle(ex)}"

    /** Live reading, e.g. "H9 R4 A0" (angle 0 = folded). */
    fun liveCoords(h: Int, r: Int, a: Int): String = "H$h R$r A$a"
}

object ArmAlignmentHelper {

    fun evaluate(frame: TelemetryFrame, ex: Exercise): ArmAlignmentState {
        val arms = frame.armPositions
        val leftUnlocked = arms.rotation.leftUnlocked || arms.height.leftUnlocked || arms.angle.leftUnlocked
        val rightUnlocked = arms.rotation.rightUnlocked || arms.height.rightUnlocked || arms.angle.rightUnlocked

        fun armMatches(unlocked: Boolean, h: Int, r: Int, a: Int): Boolean = !unlocked &&
            ArmPositionSets.heightMatches(ex, h) &&
            ArmPositionSets.rotationMatches(ex, r) &&
            ArmPositionSets.angleMatches(ex, a)

        val leftMatched = armMatches(leftUnlocked, arms.height.left, arms.rotation.left, arms.angle.left)
        val rightMatched = armMatches(rightUnlocked, arms.height.right, arms.rotation.right, arms.angle.right)
        val armsMatched = if (ex.unilateral) leftMatched || rightMatched else leftMatched && rightMatched

        val accessoryMatched = frame.accessory.equals(ex.accessory, ignoreCase = true)

        val targetCoords = ArmPositionSets.targetCoords(ex)
        val targetText = "$targetCoords • ${ex.accessory}"

        val leftCoords = ArmPositionSets.liveCoords(arms.height.left, arms.rotation.left, arms.angle.left)
        val rightCoords = ArmPositionSets.liveCoords(arms.height.right, arms.rotation.right, arms.angle.right)
        val liveCoordsSummary = if (leftCoords == rightCoords) {
            leftCoords
        } else {
            "L:$leftCoords R:$rightCoords"
        }
        // Unilateral with one matching arm: report the working arm only.
        val matchedCoords = when {
            !ex.unilateral || (leftMatched && rightMatched) -> leftCoords
            leftMatched -> "L:$leftCoords"
            else -> "R:$rightCoords"
        }

        val anyUnlocked = leftUnlocked || rightUnlocked
        val status = when {
            ex.unilateral && armsMatched && accessoryMatched -> ArmAlignmentStatus.ALIGNED
            ex.unilateral && !armsMatched && anyUnlocked -> ArmAlignmentStatus.UNLOCKED
            ex.unilateral -> ArmAlignmentStatus.ADJUST
            anyUnlocked -> ArmAlignmentStatus.UNLOCKED
            armsMatched && accessoryMatched -> ArmAlignmentStatus.ALIGNED
            else -> ArmAlignmentStatus.ADJUST
        }

        val badgeText = when (status) {
            ArmAlignmentStatus.UNLOCKED -> {
                val which = when {
                    leftUnlocked && rightUnlocked -> "LOCK ARMS"
                    leftUnlocked -> "LOCK L ARM"
                    else -> "LOCK R ARM"
                }
                "$which • $liveCoordsSummary"
            }
            ArmAlignmentStatus.ALIGNED -> "ARMS ALIGNED • $matchedCoords • ${frame.accessory}"
            ArmAlignmentStatus.ADJUST -> {
                if (armsMatched && !accessoryMatched) {
                    "ARMS $matchedCoords ✓ • ATTACH ${ex.accessory}"
                } else {
                    "LIVE $liveCoordsSummary → TARGET $targetCoords"
                }
            }
        }

        fun liveText(coords: String, angle: Int, unlocked: Boolean) = buildString {
            append(coords)
            if (angle == ArmPositionSets.ANGLE_FOLDED) append(" (folded)")
            if (unlocked) append(" • UNLOCKED")
        }

        return ArmAlignmentState(
            status = status,
            leftMatched = leftMatched,
            rightMatched = rightMatched,
            armsMatched = armsMatched,
            accessoryMatched = accessoryMatched,
            leftUnlocked = leftUnlocked,
            rightUnlocked = rightUnlocked,
            badgeText = badgeText,
            leftLiveText = liveText(leftCoords, arms.angle.left, leftUnlocked),
            rightLiveText = liveText(rightCoords, arms.angle.right, rightUnlocked),
            targetText = targetText
        )
    }
}
