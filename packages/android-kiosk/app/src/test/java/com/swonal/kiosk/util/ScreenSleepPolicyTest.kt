package com.swonal.kiosk.util

import com.swonal.kiosk.util.ScreenSleepPolicy.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenSleepPolicyTest {

    private val minute = 60_000L

    @Test
    fun `off by default and for unknown values`() {
        assertEquals(0, ScreenSleepPolicy.normalizeTimeout(0))
        assertEquals(0, ScreenSleepPolicy.normalizeTimeout(7))
        assertEquals(0, ScreenSleepPolicy.normalizeTimeout(-5))
        assertEquals(Decision.Disabled, ScreenSleepPolicy.decide(0, 10 * 60 * minute, blocked = false))
        assertEquals(Decision.Disabled, ScreenSleepPolicy.decide(7, 10 * 60 * minute, blocked = false))
    }

    @Test
    fun `settings row cycles through all choices and wraps to Off`() {
        val seen = mutableListOf<Int>()
        var current = 0
        repeat(ScreenSleepPolicy.TIMEOUT_CHOICES_MINUTES.size) {
            current = ScreenSleepPolicy.nextTimeout(current)
            seen += current
        }
        assertEquals(listOf(5, 10, 15, 30, 60, 0), seen)
        assertEquals(5, ScreenSleepPolicy.nextTimeout(42))
    }

    @Test
    fun `labels`() {
        assertEquals("Off", ScreenSleepPolicy.label(0))
        assertEquals("15 min", ScreenSleepPolicy.label(15))
        assertEquals("Off", ScreenSleepPolicy.label(3))
    }

    @Test
    fun `stays awake before the warning window`() {
        assertEquals(Decision.Awake, ScreenSleepPolicy.decide(5, 0L, blocked = false))
        assertEquals(Decision.Awake, ScreenSleepPolicy.decide(5, 4 * minute, blocked = false))
        assertEquals(Decision.Awake, ScreenSleepPolicy.decide(5, 5 * minute - 30_001L, blocked = false))
    }

    @Test
    fun `warns during the last 30 seconds with a rounded-up countdown`() {
        assertEquals(Decision.Warning(30), ScreenSleepPolicy.decide(5, 5 * minute - 30_000L, blocked = false))
        assertEquals(Decision.Warning(30), ScreenSleepPolicy.decide(5, 5 * minute - 29_500L, blocked = false))
        assertEquals(Decision.Warning(1), ScreenSleepPolicy.decide(5, 5 * minute - 1L, blocked = false))
    }

    @Test
    fun `sleeps once the timeout is reached`() {
        assertEquals(Decision.Sleep, ScreenSleepPolicy.decide(5, 5 * minute, blocked = false))
        assertEquals(Decision.Sleep, ScreenSleepPolicy.decide(60, 3 * 60 * minute, blocked = false))
    }

    @Test
    fun `never warns or sleeps while blocked`() {
        assertEquals(Decision.Blocked, ScreenSleepPolicy.decide(5, 5 * minute - 10_000L, blocked = true))
        assertEquals(Decision.Blocked, ScreenSleepPolicy.decide(5, 99 * minute, blocked = true))
    }

    @Test
    fun `negative idle time is treated as just active`() {
        assertEquals(Decision.Awake, ScreenSleepPolicy.decide(5, -1_000L, blocked = false))
    }

    @Test
    fun `tick gap detects suspended CPU or stopped activity`() {
        assertFalse(ScreenSleepPolicy.isTickGap(null, 1_000_000L))
        assertFalse(ScreenSleepPolicy.isTickGap(1_000L, 2_000L))
        assertFalse(ScreenSleepPolicy.isTickGap(1_000L, 5_999L))
        assertTrue(ScreenSleepPolicy.isTickGap(1_000L, 6_000L))
        // e.g. screen off + deep sleep for two hours, then the power button
        assertTrue(ScreenSleepPolicy.isTickGap(1_000L, 1_000L + 2 * 60 * minute))
    }

    @Test
    fun `cable movement uses the daemon's 2 unit threshold`() {
        assertFalse(ScreenSleepPolicy.cableMoved(null, null, 50.0, 50.0, 0.0, 0.0))
        assertFalse(ScreenSleepPolicy.cableMoved(10.0, 10.0, 11.9, 8.1, 1.9, -1.9))
        assertTrue(ScreenSleepPolicy.cableMoved(10.0, 10.0, 12.0, 10.0, 0.0, 0.0))
        assertTrue(ScreenSleepPolicy.cableMoved(10.0, 10.0, 10.0, 7.5, 0.0, 0.0))
        assertTrue(ScreenSleepPolicy.cableMoved(10.0, 10.0, 10.0, 10.0, 0.0, -2.5))
    }
}
