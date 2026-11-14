"""Motor controller abstraction, safety watchdog, and Phase 1.5 Bar Tilt Cutout."""

import asyncio
import logging
import os
import struct
import time
from typing import Any, Callable, Dict, Optional

from pydantic import BaseModel, Field

from src.mock_motor import MockMotorSimulator

logger = logging.getLogger("tonal-daemon.motor")

VALID_ACCESSORIES = {"HANDLES", "BAR", "ROPE", "BODYWEIGHT"}
VALID_PROFILES = {"standard", "eccentric", "chains", "spotter", "burnout"}


class CableState(BaseModel):
    position: float = 0.0
    velocity: float = 0.0
    load_lb: float = 0.0


class ArmPositions(BaseModel):
    height: Dict[str, int] = Field(default_factory=lambda: {"left": 4, "right": 4})
    rotation: Dict[str, int | bool] = Field(
        default_factory=lambda: {
            "left": 2,
            "right": 2,
            "left_unlocked": False,
            "right_unlocked": False,
        }
    )
    angle: Dict[str, int] = Field(default_factory=lambda: {"left": 1, "right": 1})


class TelemetryFrame(BaseModel):
    type: str = "telemetry"
    mode: str = "CUSTOM"
    weight_enabled: bool = False
    target_weight_lb: float = 45.0
    applied_resistance_lb: float = 0.0
    profile_mode: str = "standard"
    modifier_pct: int = 0
    rep_count: int = 0
    accessory: str = "HANDLES"
    left_cable: CableState = Field(default_factory=CableState)
    right_cable: CableState = Field(default_factory=CableState)
    arm_positions: ArmPositions = Field(default_factory=ArmPositions)


class MotorBridge:
    """Manages communication with Tonal V1 Luminary Micro controller or offline mock."""

    def __init__(self, config: dict, controller: Any = None):
        self.config = config
        env_mock = os.getenv("MOCK_TONAL", "").strip().lower() in {"1", "true", "yes"}
        self.mock_mode = env_mock or bool(config.get("daemon", {}).get("mock_mode", False))
        self.port = config.get("serial", {}).get("port", "/dev/ttyACM0")

        safety_cfg = config.get("safety", {})
        self.max_weight = float(safety_cfg.get("maximum_resistance_lb", 200.0))
        self.min_weight = float(safety_cfg.get("minimum_resistance_lb", 5.0))
        self.bar_tilt_enabled = bool(safety_cfg.get("bar_tilt_cutoff_enabled", True))
        self.bar_tilt_threshold = float(safety_cfg.get("bar_tilt_threshold_delta", 35.0))
        self.bar_tilt_dwell_ms = float(safety_cfg.get("bar_tilt_dwell_ms", 250.0))
        self.watchdog_timeout_sec = float(
            config.get("daemon", {}).get("watchdog_timeout_sec", 5.0)
        )
        self._bar_tilt_start_time: Optional[float] = None

        self.state = "DISCONNECTED"
        self.weight_enabled = False
        self.target_weight_lb = 45.0
        self.profile_mode = "standard"
        self.modifier_pct = 0
        self.rep_count = 0
        self.active_accessory = "HANDLES"

        self.last_heartbeat = time.time()
        self.last_interaction = time.time()
        self._telemetry_callbacks: list[Callable[[dict], Any]] = []
        self._loop_task: Optional[asyncio.Task] = None
        self._controller = controller
        self._mock_sim = MockMotorSimulator(
            min_weight=self.min_weight, max_weight=self.max_weight
        )

    async def initialize(self) -> None:
        """Start hardware or mock motor loop."""
        self.update_heartbeat()
        if self.mock_mode:
            logger.info("Initializing Motor Bridge in MOCK MODE")
            self.state = "IDLE_WEIGHT_OFF"
            if self._loop_task is None or self._loop_task.done():
                self._loop_task = asyncio.create_task(self._mock_telemetry_loop())
            return

        logger.info("Connecting to physical Tonal motor on %s", self.port)
        try:
            if self._controller is None:
                # Native swolectl integration boundary (guarded so mock/test runs without hardware pkg)
                from swolectl import Controller, SafetyPolicy

                safety = SafetyPolicy(
                    allow_motor_commands=bool(
                        self.config.get("safety", {}).get("allow_motor_commands", True)
                    ),
                    minimum_resistance_lb=self.min_weight,
                    maximum_resistance_lb=self.max_weight,
                )
                self._controller = Controller(port=self.port, safety=safety)
            await self.bring_up()
        except Exception as exc:
            logger.error("Failed to bind motor controller hardware: %s", exc)
            self.state = "FAULT"
            raise

    async def bring_up(self) -> None:
        """Verify board/firmware revision and start hardware telemetry polling."""
        if self.mock_mode:
            self.state = "IDLE_WEIGHT_OFF"
            return

        board_info = await self._controller.get_board_info()
        expected_board = self.config.get("serial", {}).get(
            "expected_board", "501-0100_rev004"
        )
        expected_fw = self.config.get("serial", {}).get("expected_firmware", "5.2.18.0")
        if board_info.board != expected_board or board_info.firmware != expected_fw:
            self.state = "FAULT"
            raise RuntimeError(
                f"Firmware mismatch: Detected {board_info.board} {board_info.firmware}"
            )

        await self._controller.bring_up()
        self.state = "IDLE_WEIGHT_OFF"
        if self._loop_task is None or self._loop_task.done():
            self._loop_task = asyncio.create_task(self._hardware_telemetry_loop())

    def update_heartbeat(self) -> None:
        """Refresh watchdog heartbeat timestamp."""
        now = time.time()
        self.last_heartbeat = now
        self.last_interaction = now

    async def set_target_weight(
        self, weight_lb: float, profile: str = "standard", modifier_pct: int = 0
    ) -> None:
        """Validate safety bounds and configure target resistance profile."""
        if profile not in VALID_PROFILES:
            raise ValueError(f"Unsupported resistance profile: {profile}")
        if modifier_pct < 0 or modifier_pct > 100:
            raise ValueError(f"Modifier percentage out of bounds: {modifier_pct}")

        multiplier = 1.0 + (
            modifier_pct / 100.0 if profile in ("eccentric", "chains") else 0.0
        )
        total_load = weight_lb * multiplier
        if total_load > self.max_weight or weight_lb < self.min_weight:
            raise ValueError(
                f"Requested resistance {total_load:.1f}lb exceeds safety bounds "
                f"({self.min_weight}-{self.max_weight}lb)"
            )

        self.target_weight_lb = round(float(weight_lb), 1)
        self.profile_mode = profile
        self.modifier_pct = int(modifier_pct)
        self.last_interaction = time.time()

        if self._controller and not self.mock_mode:
            await self._controller.set_profile(
                weight_lb=self.target_weight_lb,
                mode=self.profile_mode,
                modifier_pct=self.modifier_pct,
            )

    async def enable_resistance(self) -> None:
        """Engage motor resistance."""
        if self.state == "FAULT":
            raise RuntimeError("Cannot enable resistance: Motor controller in FAULT state")
        if self.state == "STOCK_MODE":
            raise RuntimeError("Cannot enable resistance while in STOCK_MODE")
        self.weight_enabled = True
        self.state = "ACTIVE_WEIGHT_ON"
        self.update_heartbeat()
        if self._controller and not self.mock_mode:
            await self._controller.enable_power()

    async def disable_resistance(self) -> None:
        """Zero and disengage motor resistance."""
        self.weight_enabled = False
        self._bar_tilt_start_time = None
        self._mock_sim.clear_bar_tilt()
        if self.state not in ("FAULT", "STOCK_MODE"):
            self.state = "IDLE_WEIGHT_OFF"
        self.last_interaction = time.time()
        if self._controller and not self.mock_mode:
            await self._controller.disable_power()

    async def toggle_resistance(self) -> bool:
        """Toggle weight ON/OFF and return resulting enabled state."""
        if self.weight_enabled:
            await self.disable_resistance()
        else:
            await self.enable_resistance()
        return self.weight_enabled

    def set_accessory(self, accessory: str) -> None:
        """Set active attached accessory (HANDLES, BAR, ROPE, BODYWEIGHT)."""
        normalized = accessory.upper().strip()
        if normalized not in VALID_ACCESSORIES:
            raise ValueError(f"Unsupported accessory: {accessory}")
        self.active_accessory = normalized
        if normalized != "BAR":
            self._bar_tilt_start_time = None

    def set_arm_positions(
        self,
        height_left: int,
        height_right: int,
        rot_left: int,
        rot_right: int,
        angle_left: int,
        angle_right: int,
    ) -> None:
        """Update simulated arm positions (0x1020, 0x1021, 0x1025) in mock mode."""
        self._mock_sim.set_arm_positions(
            height_left=height_left,
            height_right=height_right,
            rot_left=rot_left,
            rot_right=rot_right,
            angle_left=angle_left,
            angle_right=angle_right,
        )

    def reset_reps(self) -> None:
        """Reset current set rep counter to zero."""
        self.rep_count = 0
        self._mock_sim.reset_cycle()

    def trigger_bar_tilt(self, delta: float = 45.0, duration_sec: float = 0.6) -> None:
        """Inject simulated bar tilt offset in mock mode."""
        self._mock_sim.inject_bar_tilt(delta=delta, duration_sec=duration_sec)

    def register_telemetry_callback(self, cb: Callable[[dict], Any]) -> None:
        """Register callback invoked on telemetry and safety events."""
        self._telemetry_callbacks.append(cb)

    def _emit_event(self, payload: dict) -> None:
        for cb in list(self._telemetry_callbacks):
            try:
                cb(payload)
            except Exception as exc:
                logger.error("Telemetry callback error: %s", exc)

    async def _check_bar_tilt_safety(self, left_pos: float, right_pos: float) -> bool:
        """Phase 1.5 Bar Tilt Safety Cutout ('Auto-Unrack').

        Disables resistance if left and right cables diverge beyond threshold
        for at least `bar_tilt_dwell_ms` while using the Smart Bar.
        Returns True if cutout triggered.
        """
        if (
            not self.bar_tilt_enabled
            or not self.weight_enabled
            or self.active_accessory != "BAR"
        ):
            self._bar_tilt_start_time = None
            return False

        delta = abs(left_pos - right_pos)
        if delta > self.bar_tilt_threshold:
            now = time.time()
            if self._bar_tilt_start_time is None:
                self._bar_tilt_start_time = now
            elif (now - self._bar_tilt_start_time) * 1000.0 >= self.bar_tilt_dwell_ms:
                logger.warning("Bar Tilt Cutout Triggered! Cable delta=%.1f", delta)
                await self.disable_resistance()
                self._bar_tilt_start_time = None
                event = {
                    "type": "safety_event",
                    "reason": "BAR_TILT_CUTOUT",
                    "delta": round(delta, 1),
                    "timestamp": now,
                }
                self._emit_event(event)
                return True
        else:
            self._bar_tilt_start_time = None
        return False

    async def _check_watchdog(self) -> bool:
        """Emergency disable resistance if client heartbeat expired."""
        if time.time() - self.last_heartbeat > self.watchdog_timeout_sec:
            if self.weight_enabled:
                logger.warning("Heartbeat missed! Emergency disabling resistance.")
                await self.disable_resistance()
                self._emit_event(
                    {
                        "type": "safety_event",
                        "reason": "WATCHDOG_HEARTBEAT_TIMEOUT",
                        "timestamp": time.time(),
                    }
                )
                return True
        return False

    async def _hardware_telemetry_loop(self) -> None:
        while True:
            try:
                await self._check_watchdog()

                if time.time() - self.last_interaction > 900:  # 15 min idle autosuspend
                    if self.state == "IDLE_WEIGHT_OFF" and self._controller:
                        logger.info("Entering autosuspend idle state, closing serial port.")
                        await self.release_port()
                        return

                raw = await self._controller.read_telemetry_frame()
                frame = self._parse_hardware_frame(raw)
                await self._check_bar_tilt_safety(
                    frame.left_cable.position, frame.right_cable.position
                )
                self._emit_event(frame.model_dump())
                await asyncio.sleep(0.033)  # ~30Hz
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error("Error in hardware telemetry loop: %s", exc)
                await asyncio.sleep(0.1)

    async def _mock_telemetry_loop(self) -> None:
        try:
            while True:
                await asyncio.sleep(0.033)
                await self._check_watchdog()

                (
                    left_pos,
                    right_pos,
                    left_vel,
                    right_vel,
                    applied,
                    rep_done,
                ) = self._mock_sim.step(
                    dt=0.033,
                    weight_enabled=self.weight_enabled,
                    target_weight_lb=self.target_weight_lb,
                    profile_mode=self.profile_mode,
                    modifier_pct=self.modifier_pct,
                    current_rep_count=self.rep_count,
                )
                if rep_done:
                    self.rep_count += 1

                await self._check_bar_tilt_safety(left_pos, right_pos)

                frame = TelemetryFrame(
                    type="telemetry",
                    mode="STOCK" if self.state == "STOCK_MODE" else "CUSTOM",
                    weight_enabled=self.weight_enabled,
                    target_weight_lb=self.target_weight_lb,
                    applied_resistance_lb=applied if self.weight_enabled else 0.0,
                    profile_mode=self.profile_mode,
                    modifier_pct=self.modifier_pct,
                    rep_count=self.rep_count,
                    accessory=self.active_accessory,
                    left_cable=CableState(
                        position=left_pos,
                        velocity=left_vel,
                        load_lb=round(applied / 2.0, 1) if self.weight_enabled else 0.0,
                    ),
                    right_cable=CableState(
                        position=right_pos,
                        velocity=right_vel,
                        load_lb=round(applied / 2.0, 1) if self.weight_enabled else 0.0,
                    ),
                    arm_positions=ArmPositions(**self._mock_sim.arm_positions),
                )
                self._emit_event(frame.model_dump())
        except asyncio.CancelledError:
            logger.info("Mock telemetry loop stopped.")

    def _parse_hardware_frame(self, raw: bytes | dict) -> TelemetryFrame:
        """Decode raw 0x1024/0x1020/0x1021/0x1025 telemetry payload into TelemetryFrame."""
        if isinstance(raw, dict):
            return TelemetryFrame(**raw)
        if isinstance(raw, (bytes, bytearray)) and len(raw) >= 24:
            # Standard packed little-endian telemetry struct (left_pos, right_pos, left_vel, right_vel, load, reps)
            left_pos, right_pos, left_vel, right_vel, applied, reps = struct.unpack(
                "<fffffI", raw[:24]
            )
            return TelemetryFrame(
                type="telemetry",
                mode="STOCK" if self.state == "STOCK_MODE" else "CUSTOM",
                weight_enabled=self.weight_enabled,
                target_weight_lb=self.target_weight_lb,
                applied_resistance_lb=round(applied, 1),
                profile_mode=self.profile_mode,
                modifier_pct=self.modifier_pct,
                rep_count=int(reps),
                accessory=self.active_accessory,
                left_cable=CableState(
                    position=round(left_pos, 1),
                    velocity=round(left_vel, 1),
                    load_lb=round(applied / 2.0, 1),
                ),
                right_cable=CableState(
                    position=round(right_pos, 1),
                    velocity=round(right_vel, 1),
                    load_lb=round(applied / 2.0, 1),
                ),
                arm_positions=ArmPositions(**self._mock_sim.arm_positions),
            )
        return TelemetryFrame(
            mode="STOCK" if self.state == "STOCK_MODE" else "CUSTOM",
            weight_enabled=self.weight_enabled,
            target_weight_lb=self.target_weight_lb,
            profile_mode=self.profile_mode,
            modifier_pct=self.modifier_pct,
            rep_count=self.rep_count,
            accessory=self.active_accessory,
        )

    async def release_port(self) -> None:
        """Stop telemetry loop, disengage power, and release serial port for Stock mode."""
        self.weight_enabled = False
        if self._loop_task and not self._loop_task.done():
            self._loop_task.cancel()
            try:
                await self._loop_task
            except asyncio.CancelledError:
                pass
        self._loop_task = None

        if self._controller:
            await self._controller.close()
            self._controller = None
        self.state = "STOCK_MODE"
