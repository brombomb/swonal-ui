package com.swonal.kiosk.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Runs real `sh` processes on the host JVM (macOS / Linux CI). */
class BoundedShellTest {

    @Test
    fun `captures exit code and output of a quick command`() {
        val r = BoundedShell.run(arrayOf("sh", "-c", "echo hello; echo oops 1>&2; exit 3"), timeoutMs = 10_000)
        assertFalse(r.timedOut)
        assertEquals(3, r.exitCode)
        assertEquals("hello", r.stdout)
        assertEquals("oops", r.stderr)
        assertFalse(r.isSuccess)
    }

    @Test
    fun `success means exit 0 within the limit`() {
        val r = BoundedShell.run(arrayOf("sh", "-c", "true"), timeoutMs = 10_000)
        assertTrue(r.isSuccess)
    }

    @Test
    fun `a command that hangs is stopped at the time limit`() {
        val start = System.nanoTime()
        val r = BoundedShell.run(arrayOf("sh", "-c", "echo started; sleep 30"), timeoutMs = 300)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(r.timedOut)
        assertFalse(r.isSuccess)
        assertEquals(-1, r.exitCode)
        assertTrue(elapsedMs < 10_000, "took $elapsedMs ms")
    }

    @Test
    fun `a missing binary is a failure, not a timeout`() {
        val r = BoundedShell.run(arrayOf("/nonexistent/definitely-not-su"), timeoutMs = 1_000)
        assertFalse(r.timedOut)
        assertEquals(-1, r.exitCode)
        assertFalse(r.isSuccess)
    }

    @Test
    fun `large output does not block the child`() {
        val r = BoundedShell.run(arrayOf("sh", "-c", "i=0; while [ \$i -lt 20000 ]; do echo line-\$i; i=\$((i+1)); done"), timeoutMs = 20_000)
        assertFalse(r.timedOut)
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.endsWith("line-19999"))
    }
}
