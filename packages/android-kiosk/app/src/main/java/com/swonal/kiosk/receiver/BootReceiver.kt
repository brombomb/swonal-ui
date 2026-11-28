package com.swonal.kiosk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.ui.MainActivity
import com.swonal.kiosk.util.BoundedShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ensures Swonal Kiosk takes priority on tablet boot: leaves Tonal's lock-task mode, optionally
 * keeps Wi-Fi ADB on, and hands the motor to the Swonal daemon only when it is installed and
 * "Take over motor on boot" is on. The decision itself lives in [BootPlanner].
 *
 * The broadcast is deliberately NOT held open (no goAsync) for the root work: on a Tonal V1 the
 * first `su` after BOOT_COMPLETED can block for over 60 s while Magisk finishes boot, which
 * exceeds the background broadcast timeout and gets the process killed (ANR) before the kiosk
 * opens. Instead the kiosk is launched first, which keeps the process in the foreground, and the
 * root commands run afterwards with a time limit each.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "BOOT_COMPLETED received. Launching Swonal, then clearing the Kiosk lock in the background.")
            val appContext = context.applicationContext
            launchKiosk(appContext)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    runBootSequence(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Swonal on boot", e)
                }
            }
        }
    }

    private fun runBootSequence(context: Context) {
        val prefs = SwonalPreferences(context)
        prefs.clearBootMotorOutcome()
        val takeOverMotor = prefs.takeOverMotorOnBoot
        val keepWifiAdb = prefs.keepWifiAdbOnBoot

        // Release any leftover LockTaskMode from TonalLauncher, then launch again in case
        // Tonal's lock-task mode blocked the first launch.
        val exitKiosk = runRoot(BootPlanner.EXIT_TONAL_KIOSK)
        if (exitKiosk.isSuccess) launchKiosk(context)

        val probe = BoundedShell.su(BootPlanner.daemonProbeCommand(), BootPlanner.ROOT_COMMAND_TIMEOUT_MS)
        val daemon = BootPlanner.parseDaemonProbe(probe.exitCode, probe.stdout, probe.timedOut)
        if (daemon == DaemonPresence.UNKNOWN) {
            Log.e(
                TAG,
                "Root shell probe for the Swonal daemon failed (timedOut=${probe.timedOut} after " +
                    "${BootPlanner.ROOT_COMMAND_TIMEOUT_MS} ms limit, exit=${probe.exitCode}, " +
                    "stdout='${probe.stdout}', stderr='${probe.stderr}'). Leaving the motor untouched."
            )
        } else {
            Log.i(TAG, "Swonal daemon probe: $daemon (${probe.stdout})")
        }

        val plan = BootPlanner.plan(daemon, takeOverMotor, keepWifiAdb)
        Log.i(
            TAG,
            "Boot plan: takeOverMotor=$takeOverMotor keepWifiAdb=$keepWifiAdb daemon=$daemon " +
                "-> motor ${plan.motorOutcome}"
        )
        for (command in plan.afterKioskExit) {
            runRoot(command)
        }
        prefs.recordBootMotorOutcome(plan.motorOutcome, daemon)
    }

    private fun runRoot(command: String): BoundedShell.Result {
        val result = BoundedShell.su(command, BootPlanner.ROOT_COMMAND_TIMEOUT_MS)
        when {
            result.timedOut -> Log.w(
                TAG,
                "Boot command timed out after ${BootPlanner.ROOT_COMMAND_TIMEOUT_MS} ms (Magisk may still run it): $command"
            )
            !result.isSuccess -> Log.w(TAG, "Boot command exited ${result.exitCode}: $command (${result.stderr})")
        }
        return result
    }

    private fun launchKiosk(context: Context) {
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Swonal MainActivity on boot", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
