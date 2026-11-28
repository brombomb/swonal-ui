package com.swonal.kiosk.util

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide "last time someone used the Tonal" clock for the screen sleep timeout.
 *
 * Fed by screen touches (activities + dialogs + popups), Companion commands, voice
 * commands, and cable movement. Uses the monotonic [SystemClock.elapsedRealtime].
 */
object IdleActivityTracker {
    private val lastActivityMs = AtomicLong(SystemClock.elapsedRealtime())

    fun noteActivity() {
        lastActivityMs.set(SystemClock.elapsedRealtime())
    }

    fun idleMs(): Long = SystemClock.elapsedRealtime() - lastActivityMs.get()
}
