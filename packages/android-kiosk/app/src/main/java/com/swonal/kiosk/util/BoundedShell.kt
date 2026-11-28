package com.swonal.kiosk.util

import java.io.InputStream

/**
 * Runs a process with a hard time limit. Used by the boot hook, where `su` can block for over a
 * minute right after BOOT_COMPLETED on a Tonal V1 (Magisk busy finishing boot).
 *
 * minSdk is 23, so `Process.waitFor(timeout, unit)` (API 26) isn't available: this polls
 * `exitValue()` and drains stdout/stderr on daemon threads so a full pipe can't block the child.
 * On timeout the process is destroyed. With `su`, Magisk may still run the command later; boot
 * hook commands are safe to run late (they are the commands that were intended anyway).
 *
 * No android.* imports, so it runs in JVM unit tests.
 */
object BoundedShell {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean
    ) {
        val isSuccess: Boolean get() = !timedOut && exitCode == 0
    }

    fun su(command: String, timeoutMs: Long): Result = run(arrayOf("su", "-c", command), timeoutMs)

    fun run(argv: Array<String>, timeoutMs: Long, pollMs: Long = 50L): Result {
        val process = try {
            Runtime.getRuntime().exec(argv)
        } catch (e: Exception) {
            return Result(-1, "", e.message ?: "Failed to start ${argv.firstOrNull()}", timedOut = false)
        }
        runCatching { process.outputStream.close() }
        val out = StringBuffer()
        val err = StringBuffer()
        val outThread = drain(process.inputStream, out)
        val errThread = drain(process.errorStream, err)

        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var exitCode: Int? = null
        while (exitCode == null) {
            exitCode = try {
                process.exitValue()
            } catch (_: IllegalThreadStateException) {
                null
            }
            if (exitCode == null) {
                if (System.nanoTime() >= deadline) break
                try {
                    Thread.sleep(pollMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        if (exitCode == null) {
            runCatching { process.destroy() }
            outThread.join(JOIN_MS)
            errThread.join(JOIN_MS)
            return Result(-1, out.toString().trim(), err.toString().trim(), timedOut = true)
        }
        // A child that outlives its parent could keep the pipes open: never wait on them forever.
        outThread.join(JOIN_MS)
        errThread.join(JOIN_MS)
        return Result(exitCode, out.toString().trim(), err.toString().trim(), timedOut = false)
    }

    private fun drain(stream: InputStream, into: StringBuffer): Thread =
        Thread {
            runCatching {
                stream.bufferedReader().use { reader ->
                    val buf = CharArray(1024)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        into.append(buf, 0, n)
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

    private const val JOIN_MS = 1_000L
}
