package com.swonal.kiosk.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes privileged system shell commands via root (su).
 */
object RootShell {
    private const val TAG = "RootShell"

    suspend fun run(command: String): CommandResult = withContext(Dispatchers.IO) {
        runSync(command)
    }

    fun runSync(command: String): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            CommandResult(code, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run root command: $command", e)
            CommandResult(-1, "", e.message ?: "Unknown error")
        }
    }

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }
}
