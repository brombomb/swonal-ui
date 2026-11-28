package com.swonal.kiosk.model

import com.swonal.kiosk.model.SetRepsPolicy.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SetRepTrackerTest {

    private data class Frame(val reps: Int, val enabled: Boolean)

    /** Recorded on a real Tonal V1 (daemon WebSocket, printed on change): the board zeroes reps with weight off. */
    private val v1FourReps = listOf(
        Frame(0, true),  // 08:26:39 weight on
        Frame(2, true),  // 08:26:44 (the board reports the first rep late)
        Frame(3, true),
        Frame(4, true),
        Frame(0, false), // 08:26:51 weight off: reps reset in the same frame
    )

    private fun SetRepTracker.replay(frames: List<Frame>, key: String = KEY): Int {
        var reps = 0
        frames.forEach { reps = onFrame(it.reps, key) }
        return reps
    }

    @Test
    fun `V1 frames - Complete and Log Set after weight off logs 4, no dialog`() {
        val tracker = SetRepTracker()
        assertEquals(4, tracker.replay(v1FourReps))
        assertEquals(Decision.Log(4), SetRepsPolicy.decide(null, tracker.setReps, null))
        assertEquals(Decision.Log(4), SetRepsPolicy.decide(null, tracker.setReps, 10))
    }

    @Test
    fun `V1 frames - routine auto-log on disengage with target 4 logs 4`() {
        val tracker = SetRepTracker()
        var autoLogged: Int? = null
        var wasEnabled = false
        for (f in v1FourReps) {
            val setReps = tracker.onFrame(f.reps, KEY)
            if (wasEnabled && !f.enabled) autoLogged = SetRepTracker.autoLogReps(setReps, targetReps = 4)
            wasEnabled = f.enabled
        }
        assertEquals(4, autoLogged)
        // The raw disengage frame alone (the old check) would not have auto-logged.
        assertNull(SetRepTracker.autoLogReps(0, targetReps = 4))
    }

    @Test
    fun `auto-log needs the target and at least one rep`() {
        assertNull(SetRepTracker.autoLogReps(3, targetReps = 4))
        assertEquals(5, SetRepTracker.autoLogReps(5, targetReps = 4))
        assertNull(SetRepTracker.autoLogReps(0, targetReps = 0))
    }

    @Test
    fun `count shows while lifting and is kept after weight off`() {
        val tracker = SetRepTracker()
        assertEquals(listOf(0, 2, 3, 4, 4), v1FourReps.map { tracker.onFrame(it.reps, KEY) })
    }

    @Test
    fun `after logging the next set starts from 0`() {
        val tracker = SetRepTracker()
        tracker.replay(v1FourReps)
        tracker.reset() // set logged
        assertEquals(0, tracker.setReps)
        assertEquals(0, tracker.onFrame(0, KEY)) // weight on again
        assertEquals(Decision.Confirm(10), SetRepsPolicy.decide(null, tracker.setReps, null))
        assertEquals(1, tracker.onFrame(1, KEY))
        assertEquals(6, tracker.replay(listOf(Frame(6, true), Frame(0, false))))
    }

    @Test
    fun `logged with the weight still on - the board's leftover count is not counted again`() {
        val tracker = SetRepTracker()
        tracker.replay(listOf(Frame(0, true), Frame(4, true)))
        tracker.reset() // logged 4 while engaged; the board keeps showing 4
        assertEquals(0, tracker.onFrame(4, KEY))
        assertEquals(0, tracker.onFrame(0, KEY)) // weight off: board zeroes, nothing new to log
        assertEquals(Decision.Confirm(10), SetRepsPolicy.decide(null, tracker.setReps, null))
    }

    @Test
    fun `logged with the weight still on, then more reps - only the new reps count`() {
        val tracker = SetRepTracker()
        tracker.replay(listOf(Frame(0, true), Frame(4, true)))
        tracker.reset()
        assertEquals(2, tracker.replay(listOf(Frame(4, true), Frame(5, true), Frame(6, true), Frame(0, false))))
    }

    @Test
    fun `exercise, step or session change resets to 0`() {
        val tracker = SetRepTracker()
        tracker.replay(v1FourReps)
        assertEquals(0, tracker.onFrame(0, "session-1:1:ex-row"))
        assertEquals(Decision.Confirm(8), SetRepsPolicy.decide(null, tracker.setReps, 8))
    }

    @Test
    fun `key change mid-set does not carry the board's count into the new set`() {
        val tracker = SetRepTracker()
        tracker.replay(listOf(Frame(0, true), Frame(5, true)))
        assertEquals(0, tracker.onFrame(5, "session-1:1:ex-row"))
        assertEquals(1, tracker.onFrame(6, "session-1:1:ex-row"))
    }

    @Test
    fun `re-grip within a set - the latest engagement's count wins`() {
        val tracker = SetRepTracker()
        tracker.replay(v1FourReps)
        assertEquals(4, tracker.onFrame(0, KEY)) // weight on again, nothing counted yet: keep 4
        assertEquals(4, tracker.onFrame(0, KEY)) // weight off again without reps: still 4
        assertEquals(3, tracker.replay(listOf(Frame(0, true), Frame(2, true), Frame(3, true), Frame(0, false))))
    }

    @Test
    fun `second set without logging is logged as itself, not the max or the sum`() {
        val tracker = SetRepTracker()
        tracker.replay(listOf(Frame(0, true), Frame(8, true), Frame(0, false)))
        assertEquals(5, tracker.replay(listOf(Frame(0, true), Frame(5, true), Frame(0, false))))
    }

    @Test
    fun `a board that keeps the count at weight off works the same`() {
        val tracker = SetRepTracker()
        assertEquals(4, tracker.replay(listOf(Frame(0, true), Frame(4, true), Frame(4, false))))
    }

    @Test
    fun `negative board counts are treated as 0`() {
        val tracker = SetRepTracker()
        assertEquals(0, tracker.onFrame(-1, KEY))
    }

    @Test
    fun `explicit rep counts are unaffected`() {
        val tracker = SetRepTracker()
        tracker.replay(v1FourReps)
        assertEquals(Decision.Log(12), SetRepsPolicy.decide(12, tracker.setReps, 10))
    }

    private companion object {
        const val KEY = "session-1:0:ex-bench"
    }
}
