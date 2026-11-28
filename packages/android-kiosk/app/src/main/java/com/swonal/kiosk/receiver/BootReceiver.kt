package com.swonal.kiosk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.swonal.kiosk.service.StockOverlayService
import com.swonal.kiosk.ui.MainActivity
import com.swonal.kiosk.util.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ensures Swonal Kiosk takes priority on tablet boot and that the Stock Mode
 * escape hatch overlay is active if stock mode is ever encountered.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "BOOT_COMPLETED received. Ensuring Kiosk lock is clear and launching Swonal.")

            // Keep the receiver alive until the root shell commands finish; without goAsync()
            // Android may kill the process mid-command once onReceive() returns.
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Release any leftover LockTaskMode from TonalLauncher
                    RootShell.run("am broadcast -a com.tonal.apps.machineclient2.EXIT_KIOSK")

                    // Ensure Wi-Fi ADB stays enabled on port 5555 across reboot
                    RootShell.run("setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; stop adbd; start adbd")

                    // Force stock Tonal MCB off motor serial controller and secure /dev/ttyACM0 for Swonal root daemon
                    RootShell.run("pm disable com.riptlabs.mcb.service; am force-stop com.riptlabs.mcb.service; chown 0:0 /dev/ttyACM0; chmod 0600 /dev/ttyACM0")

                    // Launch Swonal MainActivity
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Swonal on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
