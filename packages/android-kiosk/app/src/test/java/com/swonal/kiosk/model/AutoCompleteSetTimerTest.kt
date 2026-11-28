package com.swonal.kiosk.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AutoCompleteSetTimerTest {

    private val frameMs = 33L
    private val bottom = 60.0
    private val top = 120.0

    /** Drives an [AutoCompleteSetTimer] with simulated 30Hz telemetry. */
    private inner class Sim(var durationSec: Int = 10, var context: String = "user:ex:0") {
        val timer = AutoCompleteSetTimer()
        var now = 1_000_000L
        var reps = 0
        var weightOn = true
        var left = bottom
        var right = bottom
        var fired = 0

        fun frame(leftVel: Double = 0.0, rightVel: Double = 0.0): Boolean {
            val f = TelemetryFrame(
                weightEnabled = weightOn,
                repCount = reps,
                leftCable = CableData(position = left, velocity = leftVel),
                rightCable = CableData(position = right, velocity = rightVel)
            )
            val result = timer.onFrame(now, f, context, durationSec)
            if (result) fired++
            now += frameMs
            return result
        }

        /** Stay put (both cables) for [ms]. */
        fun hold(ms: Long) {
            val end = now + ms
            while (now < end) frame()
        }

        /** One full rep (bottom → top → bottom, ~2.8 s); the rep is counted at the bottom. */
        fun rep(useLeft: Boolean = true, useRight: Boolean = true) {
            val steps = (2_800L / frameMs).toInt()
            for (i in 1..steps) {
                val phase = 2.0 * PI * i / steps
                val ext = (1.0 - cos(phase)) / 2.0
                val pos = bottom + ext * (top - bottom)
                val vel = sin(phase) * 40.0
                if (useLeft) left = pos
                if (useRight) right = pos
                frame(if (useLeft) vel else 0.0, if (useRight) vel else 0.0)
            }
            left = if (useLeft) bottom else left
            right = if (useRight) bottom else right
            reps++
            frame()
        }
    }

    @Test
    fun `seconds setting is Off by default and clamps exact values`() {
        assertEquals(0, AutoCompleteSetTimer.normalizeSeconds(0))
        assertEquals(0, AutoCompleteSetTimer.normalizeSeconds(-4))
        assertEquals(3, AutoCompleteSetTimer.normalizeSeconds(1))
        assertEquals(7, AutoCompleteSetTimer.normalizeSeconds(7))
        assertEquals(60, AutoCompleteSetTimer.normalizeSeconds(600))
        assertEquals("Off", AutoCompleteSetTimer.label(0))
        assertEquals("7 sec", AutoCompleteSetTimer.label(7))
        assertEquals(listOf(0, 5, 10, 20), AutoCompleteSetTimer.QUICK_CHOICES_SECONDS)
    }

    @Test
    fun `Off never starts a countdown`() {
        val sim = Sim(durationSec = 0)
        sim.rep()
        sim.weightOn = false
        sim.hold(30_000)
        assertNull(sim.timer.countdown)
        assertEquals(0, sim.fired)
    }

    @Test
    fun `nothing happens before the first rep`() {
        val sim = Sim()
        sim.hold(15_000)
        sim.weightOn = false
        sim.hold(15_000)
        assertNull(sim.timer.countdown)
        assertEquals(0, sim.fired)
    }

    @Test
    fun `turning the weight off after a rep starts the countdown immediately and fires once`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.weightOn = false
        sim.left = 0.0
        sim.right = 0.0
        sim.frame()
        val cd = sim.timer.countdown
        assertNotNull(cd)
        assertEquals(5, cd!!.secondsRemaining(sim.now))
        sim.hold(4_800)
        assertEquals(0, sim.fired)
        sim.hold(400)
        assertEquals(1, sim.fired)
        sim.hold(20_000)
        assertEquals(1, sim.fired, "must not fire again until the next set")
    }

    @Test
    fun `weight on - idle at the bottom starts after the settle window and fires`() {
        val sim = Sim(durationSec = 10)
        sim.rep()
        sim.rep()
        sim.hold(AutoCompleteSetTimer.SETTLE_MS - 200)
        assertNull(sim.timer.countdown, "still inside the settle window")
        sim.hold(400)
        assertNotNull(sim.timer.countdown)
        sim.hold(9_500)
        assertEquals(0, sim.fired)
        sim.hold(600)
        assertEquals(1, sim.fired)
    }

    @Test
    fun `a slow grind or hold mid-rep never starts the countdown`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        // Pull halfway up and stall there (sticking point / isometric hold).
        sim.left = 95.0
        sim.right = 95.0
        sim.hold(20_000)
        assertNull(sim.timer.countdown)
        assertEquals(0, sim.fired)
    }

    @Test
    fun `holding at the top right after rep 1 does not count as rest`() {
        val sim = Sim(durationSec = 5)
        // Concentric up to the top; this time the rep is counted at the top.
        val steps = 40
        for (i in 1..steps) {
            sim.left = bottom + (top - bottom) * i / steps
            sim.right = sim.left
            sim.frame(30.0, 30.0)
        }
        sim.reps = 1
        sim.hold(20_000)
        assertNull(sim.timer.countdown)
        assertEquals(0, sim.fired)
    }

    @Test
    fun `a new rep during the countdown restarts it`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.hold(2_000)
        val first = sim.timer.countdown
        assertNotNull(first)
        sim.rep()
        assertNull(sim.timer.countdown, "a new rep clears the countdown")
        sim.hold(1_500)
        val second = sim.timer.countdown
        assertNotNull(second)
        assertTrue(second!!.startedAtMs > first!!.startedAtMs)
        assertEquals(0, sim.fired)
    }

    @Test
    fun `moving out of the rest zone cancels the countdown`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.hold(2_000)
        assertNotNull(sim.timer.countdown)
        sim.left = bottom + 20.0
        sim.right = bottom + 20.0
        sim.frame(25.0, 25.0)
        assertNull(sim.timer.countdown)
        sim.hold(10_000)
        assertEquals(0, sim.fired, "still out of the rest zone")
    }

    @Test
    fun `letting the handles retract during the countdown does not cancel it`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.hold(1_500)
        assertNotNull(sim.timer.countdown)
        // Walk the handles back to the arm: cables retract well below the rep bottom.
        for (i in 1..30) {
            sim.left = bottom - i * 2.0
            sim.right = bottom - i * 2.0
            sim.frame(-20.0, -20.0)
        }
        assertNotNull(sim.timer.countdown)
        sim.hold(5_000)
        assertEquals(1, sim.fired)
    }

    @Test
    fun `pulling out again after retracting cancels the countdown`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.hold(1_500)
        sim.left = 30.0
        sim.right = 30.0
        sim.frame(-10.0, -10.0)
        assertNotNull(sim.timer.countdown)
        sim.left = 36.0
        sim.right = 36.0
        sim.frame(15.0, 15.0)
        assertNull(sim.timer.countdown)
    }

    @Test
    fun `small jitter at rest does not cancel the countdown`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.hold(1_200)
        assertNotNull(sim.timer.countdown)
        val end = sim.now + 6_000
        var flip = false
        while (sim.now < end) {
            flip = !flip
            sim.left = bottom + if (flip) 1.5 else -1.0
            sim.right = bottom + if (flip) -1.0 else 1.5
            sim.frame(if (flip) 1.5 else -1.5, if (flip) -1.5 else 1.5)
        }
        assertEquals(1, sim.fired)
    }

    @Test
    fun `cancel stops the countdown until the next rep`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.hold(1_500)
        assertNotNull(sim.timer.countdown)
        sim.timer.cancel()
        sim.hold(20_000)
        assertNull(sim.timer.countdown)
        assertEquals(0, sim.fired)
        sim.rep()
        sim.hold(1_500)
        assertNotNull(sim.timer.countdown, "re-armed by the next rep")
    }

    @Test
    fun `cancel with the weight off stays cancelled`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.weightOn = false
        sim.frame()
        sim.timer.cancel()
        sim.hold(20_000)
        assertEquals(0, sim.fired)
    }

    @Test
    fun `a context change (exercise, weight, lifter) resets the countdown`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.hold(1_500)
        assertNotNull(sim.timer.countdown)
        sim.context = "user:ex:0:+5lb"
        sim.frame()
        assertNull(sim.timer.countdown)
        // Reps still on the counter, so it re-arms from scratch for the new context.
        sim.hold(1_500)
        assertNotNull(sim.timer.countdown)
    }

    @Test
    fun `after firing, the rep reset starts a fresh set that can fire again`() {
        val sim = Sim(durationSec = 3)
        sim.rep()
        sim.hold(5_000)
        assertEquals(1, sim.fired)
        sim.reps = 0 // daemon resets reps after the set is logged
        sim.hold(10_000)
        assertEquals(1, sim.fired)
        sim.rep()
        sim.hold(5_000)
        assertEquals(2, sim.fired)
    }

    @Test
    fun `re-engaging the weight after a release restarts with the settle window`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.weightOn = false
        sim.frame()
        assertNotNull(sim.timer.countdown)
        sim.hold(2_000)
        sim.weightOn = true
        sim.frame()
        assertNull(sim.timer.countdown)
        sim.hold(AutoCompleteSetTimer.SETTLE_MS + 200)
        assertNotNull(sim.timer.countdown)
        assertEquals(0, sim.fired)
    }

    @Test
    fun `single-arm sets ignore the idle cable`() {
        val sim = Sim(durationSec = 5)
        sim.right = 140.0 // unused handle parked somewhere far out
        sim.rep(useLeft = true, useRight = false)
        sim.rep(useLeft = true, useRight = false)
        sim.hold(1_500)
        assertNotNull(sim.timer.countdown)
        sim.hold(5_000)
        assertEquals(1, sim.fired)
    }

    @Test
    fun `turning the setting Off mid-countdown clears it`() {
        val sim = Sim(durationSec = 5)
        sim.rep()
        sim.hold(1_500)
        assertNotNull(sim.timer.countdown)
        sim.durationSec = 0
        sim.frame()
        assertNull(sim.timer.countdown)
        sim.hold(10_000)
        assertEquals(0, sim.fired)
    }

    @Test
    fun `countdown helpers`() {
        val cd = AutoCompleteCountdown(startedAtMs = 1_000L, endsAtMs = 11_000L)
        assertEquals(10, cd.secondsRemaining(1_000L))
        assertEquals(10, cd.secondsRemaining(1_001L))
        assertEquals(1, cd.secondsRemaining(10_999L))
        assertEquals(0, cd.secondsRemaining(11_000L))
        assertEquals(0.5f, cd.elapsedFraction(6_000L))
        assertFalse(cd.elapsedFraction(50_000L) > 1f)
    }
}
