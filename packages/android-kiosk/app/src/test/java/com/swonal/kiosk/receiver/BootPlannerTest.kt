package com.swonal.kiosk.receiver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BootPlannerTest {

    // The exact commands BootReceiver ran before the boot hook was gated (v2.3.3), spelled out
    // literally so a change to the constants can't silently change the default boot.
    private val legacyExitKiosk = "am broadcast -a com.tonal.apps.machineclient2.EXIT_KIOSK"
    private val legacyAdb = "setprop persist.adb.tcp.port 5555; setprop service.adb.tcp.port 5555; stop adbd; start adbd"
    private val legacyTakeover =
        "pm disable com.riptlabs.mcb.service; am force-stop com.riptlabs.mcb.service; chown 0:0 /dev/ttyACM0; chmod 0600 /dev/ttyACM0"
    private val enableStock = "pm enable com.riptlabs.mcb.service"

    @Test
    fun `defaults with the daemon installed run exactly the legacy commands in order`() {
        val plan = BootPlanner.plan(DaemonPresence.INSTALLED, takeOverMotor = true, keepWifiAdb = true)
        assertEquals(listOf(legacyExitKiosk, legacyAdb, legacyTakeover), plan.commands)
        assertEquals(MotorBootOutcome.TAKEN_OVER, plan.motorOutcome)
    }

    @Test
    fun `every combination of daemon, takeover and adb settings`() {
        data class Case(
            val daemon: DaemonPresence,
            val takeOver: Boolean,
            val adb: Boolean,
            val commands: List<String>,
            val outcome: MotorBootOutcome
        )

        fun adb(on: Boolean) = if (on) listOf(legacyAdb) else emptyList()
        val cases = mutableListOf<Case>()
        for (adbOn in listOf(true, false)) {
            val head = listOf(legacyExitKiosk) + adb(adbOn)
            cases += Case(DaemonPresence.INSTALLED, true, adbOn, head + legacyTakeover, MotorBootOutcome.TAKEN_OVER)
            cases += Case(DaemonPresence.NOT_FOUND, true, adbOn, head, MotorBootOutcome.SKIPPED_DAEMON_NOT_FOUND)
            cases += Case(DaemonPresence.UNKNOWN, true, adbOn, head, MotorBootOutcome.SKIPPED_ROOT_UNAVAILABLE)
            for (daemon in DaemonPresence.values()) {
                cases += Case(daemon, false, adbOn, head + enableStock, MotorBootOutcome.RETURNED_TO_STOCK)
            }
        }
        assertEquals(DaemonPresence.values().size * 2 * 2, cases.size)

        for (c in cases) {
            val plan = BootPlanner.plan(c.daemon, c.takeOver, c.adb)
            val label = "daemon=${c.daemon} takeOver=${c.takeOver} adb=${c.adb}"
            assertEquals(c.commands, plan.commands, label)
            assertEquals(c.outcome, plan.motorOutcome, label)
        }
    }

    @Test
    fun `motor is only taken over when the daemon is installed and the setting is on`() {
        for (daemon in DaemonPresence.values()) for (takeOver in listOf(true, false)) for (adb in listOf(true, false)) {
            val commands = BootPlanner.plan(daemon, takeOver, adb).commands
            val takesPort = commands.any { "pm disable" in it || "chown" in it || "chmod" in it }
            assertEquals(daemon == DaemonPresence.INSTALLED && takeOver, takesPort, "daemon=$daemon takeOver=$takeOver adb=$adb")
        }
    }

    @Test
    fun `adb off never touches adb props or adbd`() {
        for (daemon in DaemonPresence.values()) for (takeOver in listOf(true, false)) {
            val commands = BootPlanner.plan(daemon, takeOver, keepWifiAdb = false).commands
            assertFalse(commands.any { "adb" in it }, "daemon=$daemon takeOver=$takeOver: $commands")
        }
    }

    @Test
    fun `takeover off re-enables stock and leaves port permissions alone`() {
        val commands = BootPlanner.plan(DaemonPresence.INSTALLED, takeOverMotor = false, keepWifiAdb = true).commands
        assertTrue(enableStock in commands)
        assertFalse(commands.any { "ttyACM0" in it })
    }

    @Test
    fun `probe covers installer and runbook layouts and only runs as root`() {
        val probe = BootPlanner.daemonProbeCommand()
        for (marker in listOf(
            "/data/adb/service.d/98-swonal-daemon.sh",
            "/data/swonal/packages/tonal-daemon/src/main.py",
            "/data/adb/service.d/99-swonal.sh",
            "/data/local/swonal-daemon/src/main.py"
        )) {
            assertTrue("'$marker'" in probe, marker)
        }
        assertTrue(probe.startsWith("if [ \"\$(id -u)\" != \"0\" ]"))
    }

    @Test
    fun `probe parsing`() {
        assertEquals(
            DaemonPresence.INSTALLED,
            BootPlanner.parseDaemonProbe(0, "swonal-daemon-probe:found:/data/adb/service.d/98-swonal-daemon.sh")
        )
        assertEquals(
            DaemonPresence.INSTALLED,
            BootPlanner.parseDaemonProbe(0, "some magisk noise\nswonal-daemon-probe:found:/data/local/swonal-daemon/src/main.py\n")
        )
        assertEquals(DaemonPresence.NOT_FOUND, BootPlanner.parseDaemonProbe(0, "swonal-daemon-probe:absent"))
        // Root shell failures must never read as "not installed"
        assertEquals(DaemonPresence.UNKNOWN, BootPlanner.parseDaemonProbe(2, "swonal-daemon-probe:not-root"))
        assertEquals(DaemonPresence.UNKNOWN, BootPlanner.parseDaemonProbe(1, ""))
        assertEquals(DaemonPresence.UNKNOWN, BootPlanner.parseDaemonProbe(-1, ""))
        assertEquals(DaemonPresence.UNKNOWN, BootPlanner.parseDaemonProbe(0, ""))
        assertEquals(DaemonPresence.UNKNOWN, BootPlanner.parseDaemonProbe(1, "swonal-daemon-probe:absent"))
    }

    @Test
    fun `a timed-out probe is unknown even if it printed something`() {
        assertEquals(DaemonPresence.UNKNOWN, BootPlanner.parseDaemonProbe(-1, "", timedOut = true))
        assertEquals(
            DaemonPresence.UNKNOWN,
            BootPlanner.parseDaemonProbe(0, "swonal-daemon-probe:found:/data/adb/service.d/98-swonal-daemon.sh", timedOut = true)
        )
        assertEquals(DaemonPresence.UNKNOWN, BootPlanner.parseDaemonProbe(0, "swonal-daemon-probe:absent", timedOut = true))
    }

    @Test
    fun `unknown daemon leaves the motor untouched and explains why`() {
        for (adb in listOf(true, false)) {
            val plan = BootPlanner.plan(DaemonPresence.UNKNOWN, takeOverMotor = true, keepWifiAdb = adb)
            assertEquals(MotorBootOutcome.SKIPPED_ROOT_UNAVAILABLE, plan.motorOutcome)
            assertFalse(plan.commands.any { "riptlabs" in it || "ttyACM0" in it }, plan.commands.toString())
        }
        val notice = BootPlanner.notice(MotorBootOutcome.SKIPPED_ROOT_UNAVAILABLE, DaemonPresence.UNKNOWN)!!
        assertTrue("didn't answer within 30 s" in notice.body, notice.body)
    }

    @Test
    fun `kiosk exit runs first and is not repeated after the probe`() {
        for (daemon in DaemonPresence.values()) for (takeOver in listOf(true, false)) for (adb in listOf(true, false)) {
            val plan = BootPlanner.plan(daemon, takeOver, adb)
            assertEquals(legacyExitKiosk, plan.commands.first())
            assertEquals(plan.afterKioskExit, plan.commands.drop(1))
            assertFalse(legacyExitKiosk in plan.afterKioskExit)
        }
    }

    @Test
    fun `notice is shown for every outcome except a takeover`() {
        assertNull(BootPlanner.notice(MotorBootOutcome.TAKEN_OVER, DaemonPresence.INSTALLED))
        for (outcome in MotorBootOutcome.values().filter { it != MotorBootOutcome.TAKEN_OVER }) {
            assertNotNull(BootPlanner.notice(outcome, DaemonPresence.NOT_FOUND), outcome.name)
        }
        val offWithDaemon = BootPlanner.notice(MotorBootOutcome.RETURNED_TO_STOCK, DaemonPresence.INSTALLED)!!
        assertTrue("boot scripts" in offWithDaemon.body)
        val notFound = BootPlanner.notice(MotorBootOutcome.SKIPPED_DAEMON_NOT_FOUND, DaemonPresence.NOT_FOUND)!!
        assertTrue("pm enable com.riptlabs.mcb.service" in notFound.body)
    }

    @Test
    fun `wire values round trip`() {
        for (o in MotorBootOutcome.values()) assertEquals(o, MotorBootOutcome.fromWire(o.wireValue))
        for (d in DaemonPresence.values()) assertEquals(d, DaemonPresence.fromWire(d.wireValue))
        assertNull(MotorBootOutcome.fromWire(null))
        assertNull(DaemonPresence.fromWire("bogus"))
    }
}
