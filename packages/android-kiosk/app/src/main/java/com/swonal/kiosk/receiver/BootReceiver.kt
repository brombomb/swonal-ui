package com.swonal.kiosk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.swonal.kiosk.net.SwonalPreferences
import com.swonal.kiosk.ui.MainActivity
import com.swonal.kiosk.util.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ensures Swonal Kiosk takes priority on tablet boot: leaves Tonal's lock-task mode, optionally
 * keeps Wi-Fi ADB on, and hands the motor to the Swonal daemon only when it is installed and
 * "Take over motor on boot" is on. The decision itself lives in [BootPlanner].
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "BOOT_COMPLETED received. Ensuring Kiosk lock is clear and launching Swonal.")

            // Keep the receiver alive until the root shell commands finish; without goAsync()
            // Android may kill the process mid-command once onReceive() returns.
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    runBootSequence(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Swonal on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun runBootSequence(context: Context) {
        val prefs = SwonalPreferences(context)
        val takeOverMotor = prefs.takeOverMotorOnBoot
        val keepWifiAdb = prefs.keepWifiAdbOnBoot

        val probe = RootShell.run(BootPlanner.daemonProbeCommand())
        val daemon = BootPlanner.parseDaemonProbe(probe.exitCode, probe.stdout)
        if (daemon == DaemonPresence.UNKNOWN) {
            Log.e(
                TAG,
                "Root shell probe for the Swonal daemon failed (exit=${probe.exitCode}, " +
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
        for (command in plan.commands) {
            val result = RootShell.run(command)
            if (!result.isSuccess) {
                Log.w(TAG, "Boot command exited ${result.exitCode}: $command (${result.stderr})")
            }
        }
        prefs.recordBootMotorOutcome(plan.motorOutcome, daemon)

        // Launch Swonal MainActivity
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(launchIntent)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
