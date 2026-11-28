package com.swonal.kiosk.model

import java.util.Locale

/**
 * Tonal attachments a routine item or a lifter can pick, and the rule for which one an exercise
 * actually uses. BODYWEIGHT is a property of the exercise, never a pick.
 */
object Accessories {
    const val HANDLES = "HANDLES"
    const val BAR = "BAR"
    const val ROPE = "ROPE"
    const val BODYWEIGHT = "BODYWEIGHT"

    /** Attachments a lifter can choose, in the order the pickers cycle through them. */
    val SELECTABLE: List<String> = listOf(HANDLES, BAR, ROPE)

    /** Upper-cased [raw] when it names a selectable attachment; null when unset or unknown. */
    fun normalize(raw: String?): String? {
        val v = raw?.trim()?.uppercase(Locale.US) ?: return null
        return v.takeIf { it in SELECTABLE }
    }

    /**
     * The attachment an exercise uses, most specific first: the routine item's own pick, then the
     * lifter's default, then the exercise's catalog accessory. Bodyweight movements stay bodyweight
     * unless the routine item itself picks an attachment.
     */
    fun resolve(exerciseAccessory: String?, itemAccessory: String? = null, userDefault: String? = null): String {
        normalize(itemAccessory)?.let { return it }
        val catalog = exerciseAccessory?.trim()?.uppercase(Locale.US)?.ifEmpty { null } ?: HANDLES
        if (catalog == BODYWEIGHT) return catalog
        return normalize(userDefault) ?: catalog
    }

    /** Settings label for a lifter's default: the attachment, or "Per exercise" when unset. */
    fun defaultLabel(userDefault: String?): String = normalize(userDefault) ?: PER_EXERCISE_LABEL

    const val PER_EXERCISE_LABEL = "Per exercise"

    /** Next value for a picker tap: null (use default) -> HANDLES -> BAR -> ROPE -> null. */
    fun cycle(current: String?): String? {
        val idx = SELECTABLE.indexOf(normalize(current))
        return if (idx == SELECTABLE.lastIndex) null else SELECTABLE[idx + 1]
    }
}
