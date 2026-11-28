package com.swonal.kiosk.receiver

/**
 * Whether the Swonal tonal-daemon is installed on this Tonal, as seen by a root shell at boot.
 * [UNKNOWN] means the root shell itself failed or timed out, so we could not look.
 */
enum class DaemonPresence(val wireValue: String) {
    INSTALLED("installed"),
    NOT_FOUND("not_found"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): DaemonPresence? = values().firstOrNull { it.wireValue == value }
    }
}

/** What the boot hook did with the motor serial port (`/dev/ttyACM0`). */
enum class MotorBootOutcome(val wireValue: String) {
    /** Stock motor service disabled and the port handed to the daemon (same as before this setting existed). */
    TAKEN_OVER("taken_over"),

    /** "Take over motor on boot" is on, but no daemon install was found: stock Tonal left alone. */
    SKIPPED_DAEMON_NOT_FOUND("daemon_not_found"),

    /** "Take over motor on boot" is on, but the root shell failed or timed out, so nothing was changed. */
    SKIPPED_ROOT_UNAVAILABLE("root_unavailable"),

    /** "Take over motor on boot" is off: the stock motor service was re-enabled. */
    RETURNED_TO_STOCK("returned_to_stock");

    companion object {
        fun fromWire(value: String?): MotorBootOutcome? = values().firstOrNull { it.wireValue == value }
    }
}

data class BootPlan(
    /** Root shell commands to run after [BootPlanner.EXIT_TONAL_KIOSK], in order, one `su -c` each. */
    val afterKioskExit: List<String>,
    val motorOutcome: MotorBootOutcome
) {
    /**
     * Every command the boot hook runs, in order. [BootReceiver] runs the first one
     * (leave Tonal's lock-task mode) before the daemon probe, then [afterKioskExit].
     */
    val commands: List<String> get() = listOf(BootPlanner.EXIT_TONAL_KIOSK) + afterKioskExit
}

data class BootNotice(val title: String, val body: String)

/**
 * Pure decision logic for [BootReceiver] (no Android APIs, so it is unit-tested on the JVM).
 *
 * The boot hook decides who owns the 200 lb motor. With the daemon installed and
 * "Take over motor on boot" on (the default), [plan] returns exactly the shell commands
 * the hook has always run, in the same order.
 */
object BootPlanner {
    const val MCB_SERVICE_PACKAGE = "com.riptlabs.mcb.service"

    const val EXIT_TONAL_KIOSK = "am broadcast -a com.tonal.apps.machineclient2.EXIT_KIOSK"
    const val KEEP_WIFI_ADB =
        "setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; stop adbd; start adbd"
    const val TAKE_OVER_MOTOR =
        "pm disable $MCB_SERVICE_PACKAGE; am force-stop $MCB_SERVICE_PACKAGE; chown 0:0 /dev/ttyACM0; chmod 0600 /dev/ttyACM0"
    const val RETURN_MOTOR_TO_STOCK = "pm enable $MCB_SERVICE_PACKAGE"

    /**
     * Upper bound for each boot root command. On a V1, `su` right after BOOT_COMPLETED has been
     * seen blocking for over 60 s; a probe that times out counts as [DaemonPresence.UNKNOWN].
     */
    const val ROOT_COMMAND_TIMEOUT_MS = 30_000L

    /**
     * Files that mean the daemon is installed. Any one is enough: a missed install would leave
     * the stock motor service running next to the daemon, so detection errs towards "installed".
     */
    val DAEMON_INSTALL_MARKERS = listOf(
        // scripts/install-device.sh: Magisk boot script (copy of start-daemon.sh) and the daemon itself
        "/data/adb/service.d/98-swonal-daemon.sh",
        "/data/swonal/packages/tonal-daemon/src/main.py",
        // RUNBOOK manual install (Phase 3/4): boot script and daemon directory
        "/data/adb/service.d/99-swonal.sh",
        "/data/local/swonal-daemon/src/main.py"
    )

    private const val PROBE_PREFIX = "swonal-daemon-probe:"
    private const val PROBE_FOUND = "${PROBE_PREFIX}found:"
    private const val PROBE_ABSENT = "${PROBE_PREFIX}absent"

    /**
     * Read-only root shell probe for [DAEMON_INSTALL_MARKERS]. It refuses to answer unless it
     * runs as uid 0, because a non-root shell cannot see /data/adb and would report "absent".
     */
    fun daemonProbeCommand(): String {
        val markers = DAEMON_INSTALL_MARKERS.joinToString(" ") { "'$it'" }
        return "if [ \"\$(id -u)\" != \"0\" ]; then echo '${PROBE_PREFIX}not-root'; exit 2; fi; " +
            "for f in $markers; do if [ -e \"\$f\" ]; then echo \"$PROBE_FOUND\$f\"; exit 0; fi; done; " +
            "echo '$PROBE_ABSENT'"
    }

    /**
     * Interprets the probe's result. Anything unexpected (su failed or timed out, not root) is
     * [DaemonPresence.UNKNOWN], never "not installed".
     */
    fun parseDaemonProbe(exitCode: Int, stdout: String, timedOut: Boolean = false): DaemonPresence {
        val lines = stdout.lineSequence().map { it.trim() }
        return when {
            timedOut -> DaemonPresence.UNKNOWN
            exitCode == 0 && lines.any { it.startsWith(PROBE_FOUND) } -> DaemonPresence.INSTALLED
            exitCode == 0 && lines.any { it == PROBE_ABSENT } -> DaemonPresence.NOT_FOUND
            else -> DaemonPresence.UNKNOWN
        }
    }

    fun plan(daemon: DaemonPresence, takeOverMotor: Boolean, keepWifiAdb: Boolean): BootPlan {
        val commands = mutableListOf<String>()
        // ADB off: leave the adb props alone rather than actively turning ADB off, so a
        // setting change can never lock someone out of their recovery path.
        if (keepWifiAdb) commands += KEEP_WIFI_ADB

        val outcome = when {
            !takeOverMotor -> {
                // pm disable persists across reboots, so turning the setting off has to undo it.
                // Port permissions are left as they are; the stock service manages its own access.
                commands += RETURN_MOTOR_TO_STOCK
                MotorBootOutcome.RETURNED_TO_STOCK
            }
            daemon == DaemonPresence.INSTALLED -> {
                commands += TAKE_OVER_MOTOR
                MotorBootOutcome.TAKEN_OVER
            }
            daemon == DaemonPresence.NOT_FOUND -> MotorBootOutcome.SKIPPED_DAEMON_NOT_FOUND
            else -> MotorBootOutcome.SKIPPED_ROOT_UNAVAILABLE
        }
        return BootPlan(commands, outcome)
    }

    /** Kiosk banner text for a boot that did not hand the motor to the daemon; null when it did. */
    fun notice(outcome: MotorBootOutcome, daemon: DaemonPresence?): BootNotice? = when (outcome) {
        MotorBootOutcome.TAKEN_OVER -> null
        MotorBootOutcome.SKIPPED_DAEMON_NOT_FOUND -> BootNotice(
            "MOTOR LEFT WITH STOCK TONAL: SWONAL DAEMON NOT FOUND",
            "Swonal can't drive the motor until the daemon is installed (scripts/install-device.sh), then reboot. " +
                "If stock Tonal has no motor either, run 'pm enable $MCB_SERVICE_PACKAGE' as root and reboot."
        )
        MotorBootOutcome.SKIPPED_ROOT_UNAVAILABLE -> BootNotice(
            "MOTOR NOT CHECKED: NO ROOT ACCESS AT BOOT",
            "The kiosk's root shell failed or didn't answer within ${ROOT_COMMAND_TIMEOUT_MS / 1000} s, so it left " +
                "the motor as it was (the daemon's own boot scripts, if installed, still hand it the motor). " +
                "Make sure Magisk grants Swonal Kiosk root. Details: adb logcat -s BootReceiver."
        )
        MotorBootOutcome.RETURNED_TO_STOCK -> BootNotice(
            "MOTOR LEFT WITH STOCK TONAL",
            "'Take over motor on boot' is off (Settings → Admin & System), so the stock motor service was re-enabled. " +
                if (daemon == DaemonPresence.INSTALLED) {
                    "The Swonal daemon's own boot scripts still claim the motor while it is installed: " +
                        "remove the daemon to go fully back to stock, or turn the setting on and reboot."
                } else {
                    "Turn it on and reboot to let the Swonal daemon drive the motor."
                }
        )
    }
}
