"""System Execution Context Switcher between Stock Tonal Android app and swonal-ui."""
from __future__ import annotations

import asyncio
import logging
import shlex
import subprocess
from urllib.parse import urlparse
from typing import Any, Dict

logger = logging.getLogger("tonal-daemon.switcher")

STOCK_APP_PACKAGE = "com.tonal.apps.machineclient2.beta"
LAUNCHER_PACKAGE = "com.tonal.tonallauncher"
SWONAL_KIOSK_COMPONENT = "com.swonal.kiosk/.ui.MainActivity"


class ModeSwitcher:
    """Coordinates safe hardware handoff between Custom swonal-ui and Stock Tonal app."""

    def __init__(self, motor_bridge: Any, ble_manager: Any, server_url: str):
        self.motor_bridge = motor_bridge
        self.ble_manager = ble_manager
        self.server_url = server_url

    async def switch_to_stock(self) -> Dict[str, Any]:
        """Zero load, release serial/BLE locks, and launch Stock Tonal app."""
        logger.info("Executing Mode Transition: CUSTOM -> STOCK TONAL APP")
        try:
            # 1. Safely zero load and disable power stage
            await self.motor_bridge.disable_resistance()

            # 2. Shut down BLE observers
            await self.ble_manager.disconnect_all()

            # 3. Release serial lock
            await self.motor_bridge.release_port()

            if getattr(self.motor_bridge, "mock_mode", False):
                logger.info("Mock mode: simulated Android handoff to %s", STOCK_APP_PACKAGE)
                return {
                    "status": "success",
                    "mode": "STOCK",
                    "detail": f"Simulated transition to Stock Tonal mode ({STOCK_APP_PACKAGE})",
                }

            # 4. Invoke native Android package commands via su
            cmd = (
                "su -c '"
                f"pm enable {STOCK_APP_PACKAGE} && "
                "pm enable com.riptlabs.mcb.service && "
                "chmod 0666 /dev/ttyACM0 2>/dev/null || true; "
                "am startservice com.swonal.kiosk/.service.StockOverlayService && "
                f"monkey -p {STOCK_APP_PACKAGE} -c android.intent.category.LAUNCHER 1"
                "'"
            )
            process = await asyncio.create_subprocess_shell(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE
            )
            stdout, stderr = await process.communicate()
            if process.returncode != 0:
                err_msg = stderr.decode().strip() or f"su exited with {process.returncode}"
                return {"status": "error", "message": err_msg}

            return {
                "status": "success",
                "mode": "STOCK",
                "detail": stdout.decode().strip(),
            }
        except Exception as exc:
            logger.error("Failure during transition to stock mode: %s", exc)
            return {"status": "error", "message": str(exc)}

    async def switch_to_custom(self) -> Dict[str, Any]:
        """Stop Stock Tonal app, release lock-task mode, re-acquire hardware, and open swonal-ui."""
        logger.info("Executing Mode Transition: STOCK TONAL APP -> CUSTOM SWONAL-UI")
        try:
            if getattr(self.motor_bridge, "mock_mode", False):
                await self.motor_bridge.initialize()
                await self.ble_manager.start()
                logger.info("Mock mode: simulated Android handoff to Custom swonal-ui")
                return {
                    "status": "success",
                    "mode": "CUSTOM",
                    "detail": "Simulated transition to Custom swonal-ui mode",
                }

            # 1. Release LockTaskMode (kiosk pinning) via Tonal launcher broadcast and stop stock processes
            cmd_stop = (
                "su -c '"
                "am broadcast -a com.tonal.apps.machineclient2.EXIT_KIOSK && "
                f"am force-stop {STOCK_APP_PACKAGE} && "
                f"am force-stop {LAUNCHER_PACKAGE} && "
                "am force-stop com.riptlabs.mcb.service && "
                "pm disable com.riptlabs.mcb.service 2>/dev/null || true; "
                "chown 0:0 /dev/ttyACM0 2>/dev/null || true; "
                "chmod 0600 /dev/ttyACM0 2>/dev/null || true; "
                "am stopservice com.swonal.kiosk/.service.StockOverlayService"
                "'"
            )
            proc = await asyncio.create_subprocess_shell(
                cmd_stop, stdout=subprocess.PIPE, stderr=subprocess.PIPE
            )
            await proc.communicate()

            # 2. Wait for system USB lock release
            await asyncio.sleep(1.0)

            # 3. Acquire serial controller
            await self.motor_bridge.initialize()

            # 4. Re-establish BLE background listeners
            await self.ble_manager.start()

            # 5. Launch custom native kiosk activity, fallback to browser if not installed
            parsed_url = urlparse(self.server_url)
            safe_url = self.server_url if parsed_url.scheme in ("http", "https") else "http://127.0.0.1:3000"
            inner_cmd = (
                f"am start -n {SWONAL_KIOSK_COMPONENT} || "
                f"am start -a android.intent.action.VIEW -d {shlex.quote(safe_url)} com.android.chrome"
            )
            proc_launch = await asyncio.create_subprocess_exec(
                "su", "-c", inner_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE
            )
            await proc_launch.communicate()

            return {"status": "success", "mode": "CUSTOM"}
        except Exception as exc:
            logger.error("Failure during transition to custom mode: %s", exc)
            return {"status": "error", "message": str(exc)}
