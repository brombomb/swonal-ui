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
    private const val UNSET = Long.MIN_VALUE

    /**
     * Monotonic millisecond clock. Swappable because `android.os.SystemClock` is a throwing
     * stub in JVM unit tests, and code under test (e.g. the embedded server) notes activity.
     * Never read during class init, so tests can replace it before first use.
     */
    @Volatile
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    private val lastActivityMs = AtomicLong(UNSET)

    fun noteActivity() {
        lastActivityMs.set(clock())
    }

    fun idleMs(): Long {
        val now = clock()
        // If nothing has been noted yet, the idle window starts at the first query.
        lastActivityMs.compareAndSet(UNSET, now)
        return now - lastActivityMs.get()
    }
}
