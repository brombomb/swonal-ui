"""Bleak GATT client for Tonal Smart Handles and Smart Bar button events."""

import asyncio
import inspect
import logging
import time
from typing import Any, Callable, Optional

from bleak import BleakClient

logger = logging.getLogger("tonal-daemon.ble")

VALID_BLE_ACTIONS = {"toggle_weight", "spotify_next", "emergency_stop"}


class BLEHandleManager:
    """Listens for GATT button notifications on Smart Handles / Smart Bar."""

    def __init__(
        self,
        config: dict,
        event_callback: Callable[[dict], Any],
        mock_mode: bool = False,
    ):
        self.config = config.get("bluetooth", {})
        self.event_callback = event_callback
        self.mock_mode = mock_mode or bool(
            config.get("daemon", {}).get("mock_mode", False)
        )
        self.clients: list[BleakClient] = []
        self._tasks: list[asyncio.Task] = []
        self._running = False
        self._last_click_time = 0.0
        self._click_count = 0
        self._press_start_time = 0.0
        self._pending_single_handle: Optional[asyncio.TimerHandle] = None

    async def start(self) -> None:
        """Start BLE listener tasks for configured accessories."""
        self._running = True
        if self.mock_mode:
            logger.info(
                "Mock mode active: skipping physical BLE GATT connection loop "
                "(simulated handle events enabled)."
            )
            return

        macs = [
            self.config.get("handle_left_mac"),
            self.config.get("handle_right_mac"),
            self.config.get("bar_control_mac"),
        ]
        active_macs = [m for m in macs if m]
        if not active_macs:
            logger.info("No BLE accessories configured. Skipping BLE scanner.")
            return

        for mac in active_macs:
            task = asyncio.create_task(self._connect_and_listen(mac))
            self._tasks.append(task)

    async def _connect_and_listen(self, mac: str) -> None:
        uuid = self.config.get(
            "gatt_characteristic_uuid", "00002a37-0000-1000-8000-00805f9b34fb"
        )
        while self._running:
            try:
                logger.info("Connecting to BLE hardware device: %s", mac)
                async with BleakClient(mac, timeout=10.0) as client:
                    self.clients.append(client)
                    logger.info("Connected to BLE handle %s", mac)
                    await client.start_notify(uuid, self._notification_handler)
                    while self._running and client.is_connected:
                        await asyncio.sleep(1.0)
                    if client in self.clients:
                        self.clients.remove(client)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning(
                    "BLE connection lost for %s: %s. Retrying in 5s...", mac, exc
                )
                await asyncio.sleep(5.0)

    def _dispatch_event(self, event: dict) -> None:
        res = self.event_callback(event)
        if inspect.isawaitable(res):
            try:
                loop = asyncio.get_running_loop()
                loop.create_task(res)
            except RuntimeError:
                pass

    def _notification_handler(
        self, sender: Any, data: bytearray, now: Optional[float] = None
    ) -> None:
        """Decode single click (<350ms), double click, and long press (>1000ms)."""
        if not data:
            return
        ts = time.time() if now is None else now
        is_pressed = (data[0] & 0x01) == 1

        if is_pressed:
            self._press_start_time = ts
            return

        if self._press_start_time <= 0.0:
            return

        duration_ms = (ts - self._press_start_time) * 1000.0
        self._press_start_time = 0.0

        if duration_ms > 1000.0:
            if self._pending_single_handle is not None:
                self._pending_single_handle.cancel()
                self._pending_single_handle = None
            self._click_count = 0
            self._dispatch_event(
                {"type": "handle_event", "action": "emergency_stop", "source": "ble"}
            )
            return

        if ts - self._last_click_time < 0.35:
            self._click_count += 1
        else:
            self._click_count = 1
        self._last_click_time = ts

        if self._click_count == 1:
            try:
                loop = asyncio.get_running_loop()
                self._pending_single_handle = loop.call_later(
                    0.38, self._process_single_click
                )
            except RuntimeError:
                self._process_single_click()
        elif self._click_count == 2:
            if self._pending_single_handle is not None:
                self._pending_single_handle.cancel()
                self._pending_single_handle = None
            self._click_count = 0
            self._dispatch_event(
                {"type": "handle_event", "action": "spotify_next", "source": "ble"}
            )

    def _process_single_click(self) -> None:
        if self._click_count == 1:
            self._click_count = 0
            self._pending_single_handle = None
            self._dispatch_event(
                {"type": "handle_event", "action": "toggle_weight", "source": "ble"}
            )

    async def simulate_action(self, action: str) -> dict:
        """Emit a simulated Smart Handle / Bar button event for offline testing."""
        normalized = action.strip().lower()
        if normalized not in VALID_BLE_ACTIONS:
            raise ValueError(f"Unsupported simulated BLE action: {action}")
        event = {
            "type": "handle_event",
            "action": normalized,
            "source": "mock",
            "timestamp": time.time(),
        }
        res = self.event_callback(event)
        if inspect.isawaitable(res):
            await res
        return event

    async def disconnect_all(self) -> None:
        """Stop all BLE listener tasks and disconnect clients."""
        self._running = False
        if self._pending_single_handle is not None:
            self._pending_single_handle.cancel()
            self._pending_single_handle = None

        for task in self._tasks:
            if not task.done():
                task.cancel()
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks.clear()

        for client in list(self.clients):
            try:
                if client.is_connected:
                    await client.disconnect()
            except Exception as exc:
                logger.warning("Failed disconnecting BLE client: %s", exc)
        self.clients.clear()
