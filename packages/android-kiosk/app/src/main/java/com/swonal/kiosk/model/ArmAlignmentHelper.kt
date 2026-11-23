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
    val accessoryMatched: Boolean,
    val leftUnlocked: Boolean,
    val rightUnlocked: Boolean,
    val badgeText: String,
    val leftLiveText: String,
    val rightLiveText: String,
    val targetText: String
)

object ArmAlignmentHelper {

    fun evaluate(frame: TelemetryFrame, ex: Exercise): ArmAlignmentState {
        val arms = frame.armPositions
        val leftUnlocked = arms.rotation.leftUnlocked || arms.height.leftUnlocked || arms.angle.leftUnlocked
        val rightUnlocked = arms.rotation.rightUnlocked || arms.height.rightUnlocked || arms.angle.rightUnlocked

        val leftMatched = !leftUnlocked &&
            arms.height.left == ex.recommendedHeight &&
            arms.rotation.left == ex.recommendedRotation &&
            arms.angle.left == ex.recommendedAngle

        val rightMatched = !rightUnlocked &&
            arms.height.right == ex.recommendedHeight &&
            arms.rotation.right == ex.recommendedRotation &&
            arms.angle.right == ex.recommendedAngle

        val accessoryMatched = frame.accessory.equals(ex.accessory, ignoreCase = true)

        val targetCoords = "H${ex.recommendedHeight} R${ex.recommendedRotation} A${ex.recommendedAngle}"
        val targetText = "$targetCoords • ${ex.accessory}"

        val leftCoords = "H${arms.height.left} R${arms.rotation.left} A${arms.angle.left}"
        val rightCoords = "H${arms.height.right} R${arms.rotation.right} A${arms.angle.right}"
        val liveCoordsSummary = if (leftCoords == rightCoords) {
            leftCoords
        } else {
            "L:$leftCoords R:$rightCoords"
        }

        val status = when {
            leftUnlocked || rightUnlocked -> ArmAlignmentStatus.UNLOCKED
            leftMatched && rightMatched && accessoryMatched -> ArmAlignmentStatus.ALIGNED
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
            ArmAlignmentStatus.ALIGNED -> "ARMS ALIGNED • $leftCoords • ${frame.accessory}"
            ArmAlignmentStatus.ADJUST -> {
                if (leftMatched && rightMatched && !accessoryMatched) {
                    "ARMS $leftCoords ✓ • ATTACH ${ex.accessory}"
                } else {
                    "LIVE $liveCoordsSummary → TARGET $targetCoords"
                }
            }
        }

        val leftLiveText = buildString {
            append(leftCoords)
            if (leftUnlocked) append(" • UNLOCKED")
        }
        val rightLiveText = buildString {
            append(rightCoords)
            if (rightUnlocked) append(" • UNLOCKED")
        }

        return ArmAlignmentState(
            status = status,
            leftMatched = leftMatched,
            rightMatched = rightMatched,
            accessoryMatched = accessoryMatched,
            leftUnlocked = leftUnlocked,
            rightUnlocked = rightUnlocked,
            badgeText = badgeText,
            leftLiveText = leftLiveText,
            rightLiveText = rightLiveText,
            targetText = targetText
        )
    }
}
