"""System Execution Context Switcher between Stock Tonal Android app and swonal-ui."""

import asyncio
import logging
import subprocess
from typing import Any, Dict

logger = logging.getLogger("tonal-daemon.switcher")


class ModeSwitcher:
    """Coordinates safe hardware handoff between Custom swonal-ui and Stock com.tonal.app."""

    def __init__(self, motor_bridge: Any, ble_manager: Any, server_url: str):
        self.motor_bridge = motor_bridge
        self.ble_manager = ble_manager
        self.server_url = server_url

    async def switch_to_stock(self) -> Dict[str, Any]:
        """Zero load, release serial/BLE locks, and launch Stock com.tonal.app."""
        logger.info("Executing Mode Transition: CUSTOM -> STOCK TONAL APP")
        try:
            # 1. Safely zero load and disable power stage
            await self.motor_bridge.disable_resistance()

            # 2. Shut down BLE observers
            await self.ble_manager.disconnect_all()

            # 3. Release serial lock
            await self.motor_bridge.release_port()

            if getattr(self.motor_bridge, "mock_mode", False):
                logger.info("Mock mode: simulated Android handoff to com.tonal.app")
                return {
                    "status": "success",
                    "mode": "STOCK",
                    "detail": "Simulated transition to Stock Tonal mode",
                }

            # 4. Invoke native Android package commands via su
            cmd = (
                "su -c '"
                "pm enable com.tonal.app && "
                "monkey -p com.tonal.app -c android.intent.category.LAUNCHER 1"
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
        """Stop Stock Tonal app, re-acquire motor/BLE hardware, and open swonal-ui."""
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

            # 1. Force kill stock app process holding resources
            cmd_stop = "su -c 'am force-stop com.tonal.app'"
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

            # 5. Launch custom fullscreen UI browser activity
            cmd_launch = (
                f"su -c 'am start -a android.intent.action.VIEW "
                f"-d \"{self.server_url}\" com.android.chrome'"
            )
            proc_launch = await asyncio.create_subprocess_shell(
                cmd_launch, stdout=subprocess.PIPE, stderr=subprocess.PIPE
            )
            await proc_launch.communicate()

            return {"status": "success", "mode": "CUSTOM"}
        except Exception as exc:
            logger.error("Failure during transition to custom mode: %s", exc)
            return {"status": "error", "message": str(exc)}
