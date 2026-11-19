"""Motor controller abstraction, safety watchdog, and Phase 1.5 Bar Tilt Cutout."""
from __future__ import annotations

import asyncio
import inspect
import logging
import os
import struct
import time
from typing import Any, Callable, Dict, Optional, Union

from pydantic import BaseModel, Field

if not hasattr(BaseModel, "model_dump"):
    BaseModel.model_dump = BaseModel.dict  # type: ignore[attr-defined]

from src.mock_motor import MockMotorSimulator

logger = logging.getLogger("tonal-daemon.motor")

VALID_ACCESSORIES = {"HANDLES", "BAR", "ROPE", "BODYWEIGHT"}
VALID_PROFILES = {"standard", "eccentric", "chains", "spotter", "burnout"}

MSG_CART_POSITION = 0x1020
MSG_COLUMN_ROTATION = 0x1021
MSG_ARM_POSITION = 0x1025


def decode_cart_position(payload: bytes) -> tuple[int, int]:
    """Decode swolectl 0x1020 (2 bytes: left height, right height)."""
    if len(payload) != 2:
        raise ValueError("cart position (0x1020) must be 2 bytes")
    return int(payload[0]), int(payload[1])


def decode_column_rotation(payload: bytes) -> tuple[int, bool, int, bool]:
    """Decode swolectl 0x1021 (4 bytes: left_pos, left_unlocked, right_pos, right_unlocked)."""
    if len(payload) != 4:
        raise ValueError("column rotation (0x1021) must be 4 bytes")
    return int(payload[0]), bool(payload[1]), int(payload[2]), bool(payload[3])


def decode_arm_position(payload: bytes) -> dict[str, Any]:
    """Decode swolectl 0x1025 (4-byte LE per-side telescope, angle, side, lock flags)."""
    if len(payload) != 4:
        raise ValueError("arm position (0x1025) must be 4 bytes")
    packed = int.from_bytes(payload, "little")
    side_id = (packed >> 16) & 0x0F
    return {
        "telescope_position": packed & 0xFF,
        "angle": (packed >> 8) & 0xFF,
        "side": "left" if side_id == 0 else "right",
        "is_rotation_locked": bool(packed & (1 << 20)),
        "is_rotation_and_telescope_locked": bool(packed & (1 << 21)),
    }


async def _to_thread(fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
    if hasattr(asyncio, "to_thread"):
        return await asyncio.to_thread(fn, *args, **kwargs)
    import functools
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, functools.partial(fn, *args, **kwargs))


async def _invoke_controller(fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
    """Invoke either an async mock method or a blocking synchronous swolectl method."""
    if inspect.iscoroutinefunction(fn):
        return await fn(*args, **kwargs)
    result = await _to_thread(fn, *args, **kwargs)
    if inspect.isawaitable(result):
        return await result
    return result


class CableState(BaseModel):
    position: float = 0.0
    velocity: float = 0.0
    load_lb: float = 0.0


class ArmPositions(BaseModel):
    height: Dict[str, int] = Field(default_factory=lambda: {"left": 4, "right": 4})
    rotation: Dict[str, Union[int, bool]] = Field(
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
        self.cable_zero_offsets = {"left": 0.0, "right": 0.0}
        self.last_calibrated_at: Optional[float] = None

        self.last_heartbeat = time.time()
        self.last_interaction = time.time()
        self._telemetry_callbacks: list[Callable[[dict], Any]] = []
        self._loop_task: Optional[asyncio.Task] = None
        self._controller = controller
        self._hardware_arm_positions = ArmPositions()
        self._hardware_arm_0x1025_unlocked = {"left": False, "right": False}
        self._mock_sim = MockMotorSimulator(
            min_weight=self.min_weight, max_weight=self.max_weight
        )

    def update_hardware_arm_packet(self, message_type: int, payload: bytes) -> ArmPositions:
        """Decode raw 0x1020/0x1021/0x1025 arm sensor payloads into live hardware arm state."""
        height = dict(self._hardware_arm_positions.height)
        rotation = dict(self._hardware_arm_positions.rotation)
        angle = dict(self._hardware_arm_positions.angle)

        if message_type == MSG_CART_POSITION:
            left_h, right_h = decode_cart_position(payload)
            height = {"left": left_h, "right": right_h}
        elif message_type == MSG_COLUMN_ROTATION:
            left_r, left_unlk, right_r, right_unlk = decode_column_rotation(payload)
            rotation = {
                "left": left_r,
                "right": right_r,
                "left_unlocked": bool(left_unlk or self._hardware_arm_0x1025_unlocked["left"]),
                "right_unlocked": bool(right_unlk or self._hardware_arm_0x1025_unlocked["right"]),
            }
        elif message_type == MSG_ARM_POSITION:
            decoded = decode_arm_position(payload)
            side = decoded["side"]
            angle[side] = int(decoded["angle"])
            unlocked = not (
                decoded["is_rotation_locked"] and decoded["is_rotation_and_telescope_locked"]
            )
            self._hardware_arm_0x1025_unlocked[side] = unlocked
            rotation[f"{side}_unlocked"] = bool(rotation.get(f"{side}_unlocked", False) or unlocked)

        self._hardware_arm_positions = ArmPositions(
            height=height,
            rotation=rotation,
            angle=angle,
        )
        return self._hardware_arm_positions

    def _read_swolectl_arm_positions(self) -> ArmPositions:
        """Read live 0x1020/0x1021/0x1025 arm positions from a swolectl.Controller instance."""
        ctrl = self._controller
        if ctrl is None:
            return self._hardware_arm_positions

        height = dict(self._hardware_arm_positions.height)
        rotation = dict(self._hardware_arm_positions.rotation)
        angle = dict(self._hardware_arm_positions.angle)

        cart = getattr(ctrl, "cart_position", None)
        if cart is not None and hasattr(cart, "left") and hasattr(cart, "right"):
            height = {"left": int(cart.left), "right": int(cart.right)}

        col_rot = getattr(ctrl, "column_rotation", None)
        left_unlk = bool(rotation.get("left_unlocked", False))
        right_unlk = bool(rotation.get("right_unlocked", False))
        if col_rot is not None and hasattr(col_rot, "left_position") and hasattr(col_rot, "right_position"):
            left_unlk = bool(getattr(col_rot, "left_unlocked", False))
            right_unlk = bool(getattr(col_rot, "right_unlocked", False))
            rotation["left"] = int(col_rot.left_position)
            rotation["right"] = int(col_rot.right_position)

        arm_map = getattr(ctrl, "arm_positions", None)
        if isinstance(arm_map, dict):
            for side in ("left", "right"):
                arm = arm_map.get(side)
                if arm is None:
                    continue
                if hasattr(arm, "angle"):
                    angle[side] = int(arm.angle)
                elif hasattr(arm, "rotation_position"):
                    angle[side] = int(arm.rotation_position)
                rot_locked = bool(getattr(arm, "is_rotation_locked", True))
                both_locked = bool(getattr(arm, "is_rotation_and_telescope_locked", True))
                if not (rot_locked and both_locked):
                    if side == "left":
                        left_unlk = True
                    else:
                        right_unlk = True

        rotation["left_unlocked"] = left_unlk
        rotation["right_unlocked"] = right_unlk

        self._hardware_arm_positions = ArmPositions(
            height=height,
            rotation=rotation,
            angle=angle,
        )
        return self._hardware_arm_positions

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

                expected_fw = self.config.get("serial", {}).get("expected_firmware", "5.2.18.0")
                safety_kwargs: dict[str, Any] = {
                    "allow_motor_commands": bool(
                        self.config.get("safety", {}).get("allow_motor_commands", True)
                    ),
                    "minimum_resistance_lb": self.min_weight,
                    "maximum_resistance_lb": self.max_weight,
                }
                if "compatible_firmware_versions" in inspect.signature(SafetyPolicy).parameters:
                    safety_kwargs["compatible_firmware_versions"] = frozenset({expected_fw})
                safety = SafetyPolicy(**safety_kwargs)
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

        expected_board = self.config.get("serial", {}).get(
            "expected_board", "501-0100_rev004"
        )
        expected_fw = self.config.get("serial", {}).get("expected_firmware", "5.2.18.0")

        if hasattr(self._controller, "get_board_info") and callable(self._controller.get_board_info):
            board_info = await _invoke_controller(self._controller.get_board_info)
            if board_info.board != expected_board or board_info.firmware != expected_fw:
                self.state = "FAULT"
                raise RuntimeError(
                    f"Firmware mismatch: Detected {board_info.board} {board_info.firmware}"
                )

        await _invoke_controller(self._controller.bring_up)

        announcement = getattr(self._controller, "last_announcement", None)
        if announcement is not None:
            detected_fw = getattr(announcement, "firmware_version", None)
            if isinstance(detected_fw, str) and detected_fw and detected_fw != expected_fw:
                self.state = "FAULT"
                raise RuntimeError(
                    f"Firmware mismatch: Detected {detected_fw} (expected {expected_fw})"
                )

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
            if hasattr(self._controller, "configure_resistance") and callable(
                self._controller.configure_resistance
            ):
                try:
                    from swolectl.messages import ResistanceMode, ResistanceProfile

                    mode_map = {
                        "standard": ResistanceMode.BASIC,
                        "eccentric": ResistanceMode.ECCENTRIC,
                        "chains": ResistanceMode.CHAINS,
                        "spotter": ResistanceMode.SPOTTER,
                        "burnout": ResistanceMode.DROP_SET,
                    }
                    sw_mode = mode_map.get(self.profile_mode, ResistanceMode.BASIC)
                    intensity = float(self.modifier_pct)
                    if sw_mode is ResistanceMode.ECCENTRIC:
                        intensity = min(60.0, intensity)
                    profile_obj = ResistanceProfile.for_mode(
                        self.target_weight_lb,
                        sw_mode,
                        intensity_percent=intensity,
                    )
                    await _invoke_controller(self._controller.configure_resistance, profile_obj)
                except ImportError:
                    await _invoke_controller(
                        self._controller.configure_resistance, self.target_weight_lb
                    )
            elif hasattr(self._controller, "set_profile") and callable(self._controller.set_profile):
                await _invoke_controller(
                    self._controller.set_profile,
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
            if hasattr(self._controller, "enable_resistance") and callable(
                self._controller.enable_resistance
            ):
                await _invoke_controller(self._controller.enable_resistance)
            elif hasattr(self._controller, "enable_power") and callable(
                self._controller.enable_power
            ):
                await _invoke_controller(self._controller.enable_power)

    async def disable_resistance(self) -> None:
        """Zero and disengage motor resistance."""
        self.weight_enabled = False
        self._bar_tilt_start_time = None
        self._mock_sim.clear_bar_tilt()
        if self.state not in ("FAULT", "STOCK_MODE"):
            self.state = "IDLE_WEIGHT_OFF"
        self.last_interaction = time.time()
        if self._controller and not self.mock_mode:
            if hasattr(self._controller, "disable_resistance") and callable(
                self._controller.disable_resistance
            ):
                await _invoke_controller(self._controller.disable_resistance)
            elif hasattr(self._controller, "disable_power") and callable(
                self._controller.disable_power
            ):
                await _invoke_controller(self._controller.disable_power)

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

    def get_calibration_status(self) -> dict:
        """Return cable zero-offset and hardware board calibration diagnostics."""
        return {
            "state": self.state,
            "mock_mode": self.mock_mode,
            "port": self.port,
            "expected_board": self.config.get("serial", {}).get("expected_board", "501-0100_rev004"),
            "expected_firmware": self.config.get("serial", {}).get("expected_firmware", "5.2.18.0"),
            "zero_offsets": dict(self.cable_zero_offsets),
            "last_calibrated_at": self.last_calibrated_at,
            "bar_tilt_threshold_delta": self.bar_tilt_threshold,
            "bar_tilt_dwell_ms": int(self.bar_tilt_dwell_ms),
        }

    def zero_cable_offsets(self) -> dict:
        """Zero left/right cable encoder offsets (must be weight-off)."""
        if self.weight_enabled:
            raise RuntimeError("Cannot zero cable calibration while weight is engaged")
        self.cable_zero_offsets = {"left": 0.0, "right": 0.0}
        self.last_calibrated_at = time.time()
        self._mock_sim.reset_cycle()
        return self.get_calibration_status()

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

                if hasattr(self._controller, "read_telemetry_frame") and callable(
                    self._controller.read_telemetry_frame
                ):
                    raw = await _invoke_controller(self._controller.read_telemetry_frame)
                    frame = self._parse_hardware_frame(raw)
                else:
                    frame = self._build_frame_from_swolectl()

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

    def _build_frame_from_swolectl(self) -> TelemetryFrame:
        """Build a unified 30Hz TelemetryFrame from live swolectl.Controller state."""
        ctrl = self._controller
        arm_positions = self._read_swolectl_arm_positions()
        motor_t = getattr(ctrl, "telemetry", None) if ctrl is not None else None
        if motor_t is not None:
            left_pos = round(
                float(getattr(motor_t, "left_cable_position", 0.0))
                - self.cable_zero_offsets["left"],
                1,
            )
            right_pos = round(
                float(getattr(motor_t, "right_cable_position", 0.0))
                - self.cable_zero_offsets["right"],
                1,
            )
            left_vel = round(float(getattr(motor_t, "left_cable_speed", 0.0)), 1)
            right_vel = round(float(getattr(motor_t, "right_cable_speed", 0.0)), 1)
            applied = round(
                float(
                    getattr(
                        motor_t,
                        "applied_resistance_lb",
                        getattr(motor_t, "total_weight_lb", 0.0),
                    )
                ),
                1,
            )
            reps = int(getattr(motor_t, "rep_count", self.rep_count))
            self.rep_count = reps
            hw_enabled = bool(getattr(motor_t, "device_enabled", self.weight_enabled))
            return TelemetryFrame(
                type="telemetry",
                mode="STOCK" if self.state == "STOCK_MODE" else "CUSTOM",
                weight_enabled=hw_enabled,
                target_weight_lb=self.target_weight_lb,
                applied_resistance_lb=applied if hw_enabled else 0.0,
                profile_mode=self.profile_mode,
                modifier_pct=self.modifier_pct,
                rep_count=reps,
                accessory=self.active_accessory,
                left_cable=CableState(
                    position=left_pos,
                    velocity=left_vel,
                    load_lb=round(applied / 2.0, 1) if hw_enabled else 0.0,
                ),
                right_cable=CableState(
                    position=right_pos,
                    velocity=right_vel,
                    load_lb=round(applied / 2.0, 1) if hw_enabled else 0.0,
                ),
                arm_positions=arm_positions,
            )
        return TelemetryFrame(
            type="telemetry",
            mode="STOCK" if self.state == "STOCK_MODE" else "CUSTOM",
            weight_enabled=self.weight_enabled,
            target_weight_lb=self.target_weight_lb,
            profile_mode=self.profile_mode,
            modifier_pct=self.modifier_pct,
            rep_count=self.rep_count,
            accessory=self.active_accessory,
            arm_positions=arm_positions,
        )

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

    def _parse_hardware_frame(self, raw: Union[bytes, dict]) -> TelemetryFrame:
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
                arm_positions=self._read_swolectl_arm_positions(),
            )
        return TelemetryFrame(
            mode="STOCK" if self.state == "STOCK_MODE" else "CUSTOM",
            weight_enabled=self.weight_enabled,
            target_weight_lb=self.target_weight_lb,
            profile_mode=self.profile_mode,
            modifier_pct=self.modifier_pct,
            rep_count=self.rep_count,
            accessory=self.active_accessory,
            arm_positions=self._read_swolectl_arm_positions(),
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
            await _invoke_controller(self._controller.close)
            self._controller = None
        self.state = "STOCK_MODE"
