package com.swonal.kiosk.util

import kotlin.math.abs

/**
 * Pure decision logic for the optional "Sleep screen after inactivity" setting.
 *
 * Kept free of Android dependencies so it can be unit tested on the JVM; the
 * Android side lives in [com.swonal.kiosk.ui.ScreenSleepController].
 */
object ScreenSleepPolicy {
    /** Selectable timeouts in minutes. `0` means Off (the default). */
    val TIMEOUT_CHOICES_MINUTES: List<Int> = listOf(0, 5, 10, 15, 30, 60)

    /** How long the "Going to sleep" countdown is shown before the screen turns off. */
    const val WARNING_WINDOW_MS: Long = 30_000L

    /** Cable travel / velocity that counts as user activity (mirrors tonal-daemon's inactivity cutoff). */
    const val CABLE_ACTIVITY_THRESHOLD: Double = 2.0

    /**
     * The controller ticks every second while the screen is on. A much longer gap means the
     * CPU was suspended (screen off) or the activity was stopped, so the idle clock is stale.
     */
    const val TICK_GAP_RESET_MS: Long = 5_000L

    sealed class Decision {
        /** Setting is Off: never sleep, never warn. */
        object Disabled : Decision()

        /** Something important is happening (weight engaged, rest timer, update): treat as activity. */
        object Blocked : Decision()

        /** Idle, but not yet inside the warning window. */
        object Awake : Decision()

        /** Inside the warning window; [secondsLeft] is always >= 1. */
        data class Warning(val secondsLeft: Int) : Decision()

        /** Timeout reached: blank the screen (a touch wakes it). */
        object Sleep : Decision()
    }

    /** Unknown or legacy values fall back to Off so a bad pref can never cause surprise sleeps. */
    fun normalizeTimeout(minutes: Int): Int =
        if (minutes in TIMEOUT_CHOICES_MINUTES) minutes else 0

    /** Next value when the settings row is tapped (Off → 5 → 10 → 15 → 30 → 60 → Off). */
    fun nextTimeout(currentMinutes: Int): Int {
        val idx = TIMEOUT_CHOICES_MINUTES.indexOf(normalizeTimeout(currentMinutes))
        return TIMEOUT_CHOICES_MINUTES[(idx + 1) % TIMEOUT_CHOICES_MINUTES.size]
    }

    fun label(minutes: Int): String {
        val m = normalizeTimeout(minutes)
        return if (m == 0) "Off" else "$m min"
    }

    fun decide(timeoutMinutes: Int, idleMs: Long, blocked: Boolean): Decision {
        val minutes = normalizeTimeout(timeoutMinutes)
        if (minutes == 0) return Decision.Disabled
        if (blocked) return Decision.Blocked
        val remainingMs = minutes * 60_000L - idleMs.coerceAtLeast(0L)
        return when {
            remainingMs <= 0L -> Decision.Sleep
            remainingMs <= WARNING_WINDOW_MS -> Decision.Warning(((remainingMs + 999L) / 1000L).toInt())
            else -> Decision.Awake
        }
    }

    /**
     * "Sleep now" from the Quick Preferences power row works whatever the timeout setting is,
     * so it can't use [decide]. Wake when something important starts (weight engaged from a
     * phone, a rest timer, an update) or when any activity has been noted since the screen
     * blanked: `nowMs - idleMs` is the time of the last noted activity.
     */
    fun shouldWakeFromManualSleep(
        sleepStartedMs: Long,
        nowMs: Long,
        idleMs: Long,
        blocked: Boolean
    ): Boolean = blocked || (nowMs - idleMs) > sleepStartedMs

    /** True when the time since the previous tick is too long to trust the idle clock. */
    fun isTickGap(previousTickMs: Long?, nowMs: Long): Boolean =
        previousTickMs != null && (nowMs - previousTickMs) >= TICK_GAP_RESET_MS

    /**
     * True when the cables moved far enough from the last baseline (or are moving fast enough)
     * to count as someone using the machine. A null baseline never counts as movement.
     */
    fun cableMoved(
        baselineLeft: Double?,
        baselineRight: Double?,
        left: Double,
        right: Double,
        leftVelocity: Double,
        rightVelocity: Double
    ): Boolean {
        if (baselineLeft == null || baselineRight == null) return false
        return abs(left - baselineLeft) >= CABLE_ACTIVITY_THRESHOLD ||
            abs(right - baselineRight) >= CABLE_ACTIVITY_THRESHOLD ||
            abs(leftVelocity) >= CABLE_ACTIVITY_THRESHOLD ||
            abs(rightVelocity) >= CABLE_ACTIVITY_THRESHOLD
    }
}
