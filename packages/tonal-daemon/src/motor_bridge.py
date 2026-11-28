"""Motor controller abstraction, safety watchdog, and Phase 1.5 Bar Tilt Cutout."""
from __future__ import annotations

import asyncio
import inspect
import json
import logging
import math
import os
from pathlib import Path
import struct
import time
from typing import Any, Callable, Dict, Optional, Union

from pydantic import BaseModel, Field

if not hasattr(BaseModel, "model_dump"):
    BaseModel.model_dump = BaseModel.dict  # type: ignore[attr-defined]

from src.mock_motor import MockMotorSimulator

logger = logging.getLogger("tonal-daemon.motor")

VALID_ACCESSORIES = {"HANDLES", "BAR", "ROPE", "BODYWEIGHT"}
VALID_PROFILES = {"standard", "eccentric", "chains", "spotter", "burnout", "smart_flex"}

MSG_CART_POSITION = 0x1020
MSG_COLUMN_ROTATION = 0x1021
MSG_ARM_POSITION = 0x1025
MSG_BLE_CONNECTION = 0x1031
MSG_BLE_CONTROL = 0x1032
MSG_BLE_BUTTON = 0x1033
MSG_BLE_BATT_LEVEL = 0x1037
MSG_BLE_CONNECTION_V2 = 0x2000
MSG_BLE_BUTTON_V2 = 0x2003
MSG_BLE_BATT_V2 = 0x2007

# Physical Tonal V1 rotation degree thresholds (0x1025 rotation_position: ~37° -> R1, ~88° -> R2, ~138° -> R3)
ROTATION_NOTCH_BOUNDARIES = (62, 112)
ROTATION_HYSTERESIS_DEG = 8


def quantize_arm_rotation_notch(raw_value: int, prev_notch: int = 1) -> int:
    """Convert raw 0x1025 rotation_position degrees (or already-discrete 1..3 notch) into R1..R3."""
    if raw_value <= 3:
        return max(1, int(raw_value))
    b1, b2 = ROTATION_NOTCH_BOUNDARIES
    h = ROTATION_HYSTERESIS_DEG
    if prev_notch <= 1:
        if raw_value >= b2 + h:
            return 3
        if raw_value >= b1 + h:
            return 2
        return 1
    if prev_notch == 2:
        if raw_value < b1 - h:
            return 1
        if raw_value >= b2 + h:
            return 3
        return 2
    # prev_notch >= 3
    if raw_value < b1 - h:
        return 1
    if raw_value < b2 - h:
        return 2
    return 3


ANGLE_FOLDED = 0


def decode_telescope_angle_notch(raw_telescope: int) -> Optional[int]:
    """Convert the raw 0x1025 angle detent (swolectl's telescope_position) into the reported angle.

    Seen on a Tonal V1 (firmware 5.2.18.0), setting each detent by hand: raw 1 = arm folded,
    2 = pointing down (~36 deg) ... 6 = pointing up (~135 deg), 0 = between detents.
    Reported: folded -> 0 (ANGLE_FOLDED), 2..6 -> A1..A5, between -> None (keep the last value).
    Folded used to read A1, the same as pointing down, so a folded arm looked aligned.
    """
    raw = int(raw_telescope)
    if raw <= 0:
        return None
    if raw == 1:
        return ANGLE_FOLDED
    return min(5, raw - 1)


def decode_cart_position(payload: bytes) -> tuple[int, int]:
    """Decode swolectl 0x1020 (2 bytes: left height, right height)."""
    if len(payload) != 2:
        raise ValueError("cart position (0x1020) must be 2 bytes")
    return int(payload[0]), int(payload[1])


def decode_column_rotation(payload: bytes) -> tuple[int, bool, int, bool]:
    """Decode swolectl 0x1021 (4 bytes: [left_unlocked, left_pos, right_unlocked, right_pos])."""
    if len(payload) != 4:
        raise ValueError("column rotation (0x1021) must be 4 bytes")
    return int(payload[1]), bool(payload[0]), int(payload[3]), bool(payload[2])


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


def _is_accessory_button_press(payload: bytes) -> bool:
    """True only for a handle/bar button press in a 0x2003 frame (see _on_swolectl_frame).

    Seen on a Tonal V1 (firmware 5.2.18.0): byte 8 = (button << 4) | event, byte 9 = 0x01 on
    press for handles and the bar button, 0x21 for the arm rotation-release button (rejected:
    it only unlocks the arm). Each handle has two buttons, one at each end, which both toggle
    weight on the stock app: button 1 = 0x01, button 2 = 0x11. The motor board also engages
    by itself on a button-2 press, so ignoring it leaves resistance the daemon doesn't track.
    """
    if len(payload) < 10:
        return False
    return payload[8] in (0x01, 0x11) and payload[9] == 0x01


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


class ArmRotationState(BaseModel):
    left: int = 2
    right: int = 2
    left_unlocked: bool = False
    right_unlocked: bool = False

    def __getitem__(self, key: str) -> Any:
        return getattr(self, key)

    def get(self, key: str, default: Any = None) -> Any:
        return getattr(self, key, default)

    def __eq__(self, other: Any) -> bool:
        if isinstance(other, dict):
            return self.model_dump() == other
        return super().__eq__(other)


class ArmPositions(BaseModel):
    height: Dict[str, int] = Field(default_factory=lambda: {"left": 4, "right": 4})
    rotation: ArmRotationState = Field(default_factory=ArmRotationState)
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
        self.inactivity_cutoff_sec = float(safety_cfg.get("inactivity_cutoff_sec", 45.0))
        self.watchdog_timeout_sec = float(
            config.get("daemon", {}).get("watchdog_timeout_sec", 5.0)
        )
        self._bar_tilt_start_time: Optional[float] = None
        self._last_cable_activity_ts: float = time.monotonic()
        self._last_activity_cable_pos: Optional[tuple[float, float]] = None

        self.state = "DISCONNECTED"
        self.weight_enabled = False
        self.maintenance_locked = False
        # Start at the configured minimum, not a fixed 45 lb: a handle/bar press right after the
        # daemon starts (or the board engaging on its own) must not apply a weight nobody chose.
        self.target_weight_lb = self.min_weight
        self.profile_mode = "standard"
        self.modifier_pct = 0
        self.rep_count = 0
        self.active_accessory = "HANDLES"
        self.cable_zero_offsets = {"left": 0.0, "right": 0.0}
        self.last_calibrated_at: Optional[float] = None

        self.last_heartbeat = time.monotonic()
        self.last_interaction = time.monotonic()
        self._cmd_lock = asyncio.Lock()
        self._telemetry_callbacks: list[Callable[[dict], Any]] = []
        self._loop_task: Optional[asyncio.Task] = None
        self._controller = controller
        self._arm_cache_path = Path(
            config.get("daemon", {}).get(
                "arm_cache_path", "/data/local/swonal-arm-positions.json"
            )
        )
        self._hardware_arm_positions = self._load_cached_arm_positions()
        self._last_saved_arm_key = ""
        self._hardware_arm_0x1025_unlocked = {"left": False, "right": False}
        self._ble_button_callbacks: list[Callable[[dict], Any]] = []
        self._last_mcb_button_ts: float = 0.0
        # Uncommanded-engage guard: the motor board can latch resistance on by itself
        # (e.g. a handle button it handles in firmware) while weight_enabled stays False,
        # which would bypass the watchdog and inactivity cutoffs.
        self._last_enable_cmd_ts: float = 0.0
        self._uncommanded_latch_since: Optional[float] = None
        self._last_uncommanded_alert_ts: float = 0.0
        self._connected_ble_accessories: dict[int, dict[str, Any]] = {}
        self._recent_aux_frames: list[dict[str, Any]] = []
        self._raw_column_rotation_bytes: Optional[bytes] = None
        self._prev_cable_pos: Optional[tuple[float, float]] = None
        self._prev_cable_ts: float = 0.0
        self._smoothed_cable_vel: tuple[float, float] = (0.0, 0.0)
        self._mock_sim = MockMotorSimulator(
            min_weight=self.min_weight, max_weight=self.max_weight
        )

    def register_ble_button_callback(self, cb: Callable[[dict], Any]) -> None:
        """Register callback invoked when MCB/Bobble relays a 0x1033 BLE_BUTTON event."""
        self._ble_button_callbacks.append(cb)

    def _on_swolectl_frame(self, frame: Any) -> None:
        """Handle asynchronous MCB/Bobble protocol frames (0x1031 BLE_CONNECTION, 0x1033 BLE_BUTTON, 0x1037 BLE_BATT_LEVEL)."""
        msg_type = getattr(frame, "message_type", None)
        payload = getattr(frame, "payload", b"") or b""
        source = int(getattr(frame, "source", 0))

        if msg_type == MSG_COLUMN_ROTATION and len(payload) == 4:
            raw_bytes = bytes(payload)
            if raw_bytes != self._raw_column_rotation_bytes:
                logger.info(
                    "MCB COLUMN_ROTATION (0x1021) raw=%s (L_unlk=%d L_pos=%d R_unlk=%d R_pos=%d)",
                    raw_bytes.hex(),
                    raw_bytes[0],
                    raw_bytes[1],
                    raw_bytes[2],
                    raw_bytes[3],
                )
            self._raw_column_rotation_bytes = raw_bytes

        # Record non-periodic/auxiliary protocol frames (BLE, device announcements, etc.) for diagnostics
        if msg_type not in (0x1024, 0x102E, 0x1020, 0x1021, 0x1025, 0x1001, 0x1018, 0x1019, 0x1010):
            entry = {
                "msg_type": hex(int(msg_type)) if msg_type is not None else "none",
                "source": source,
                "payload_hex": payload.hex(),
                "ts": round(time.time(), 2),
            }
            self._recent_aux_frames.append(entry)
            if len(self._recent_aux_frames) > 20:
                self._recent_aux_frames = self._recent_aux_frames[-20:]
            logger.info("MCB AUX FRAME type=%s src=%d payload=%s", entry["msg_type"], source, entry["payload_hex"])

        if msg_type in (MSG_BLE_CONNECTION, MSG_BLE_CONNECTION_V2):
            acc_key = int.from_bytes(payload[:4], "little") if len(payload) >= 4 else source
            logger.info("MCB BLE_CONNECTION (%s) src=%d payload=%s", hex(int(msg_type)), source, payload.hex())
            self._connected_ble_accessories[acc_key] = {
                "source": source,
                "device_id": payload[:8].hex() if len(payload) >= 8 else str(source),
                "payload_hex": payload.hex(),
                "last_seen": time.time(),
            }
        elif msg_type in (MSG_BLE_BATT_LEVEL, MSG_BLE_BATT_V2):
            if payload:
                acc_key = int.from_bytes(payload[:4], "little") if len(payload) >= 4 else source
                acc = self._connected_ble_accessories.setdefault(acc_key, {"source": source})
                acc["battery_payload_hex"] = payload.hex()
                acc["last_seen"] = time.time()
        elif msg_type in (MSG_BLE_BUTTON, MSG_BLE_BUTTON_V2):
            logger.info("MCB BLE_BUTTON (%s) src=%d payload=%s", hex(int(msg_type)), source, payload.hex())
            now = time.monotonic()
            if msg_type == MSG_BLE_BUTTON_V2:
                # Physical Tonal V1 0x2003 payload: [0:8]=device_id, [8]=(button << 4) | event
                # (event 0x1=press, 0x3=release), [9]=button state.
                # Handles and the bar button report [9]=0x01 on press / 0x00 on release.
                # The arm modules' rotation-release button uses the same message with
                # [9]=0x21/0x20; pressing it to adjust an arm must never toggle resistance.
                if not _is_accessory_button_press(payload):
                    return
            else:
                if len(payload) >= 2 and payload[1] == 0:
                    return
            if now - self._last_mcb_button_ts < 0.40:
                return
            self._last_mcb_button_ts = now
            event = {
                "type": "handle_event",
                "action": "toggle_weight",
                "source": "mcb_ble",
                "accessory_source": source,
                "payload_hex": payload.hex(),
                "timestamp": time.time(),
            }
            for cb in list(self._ble_button_callbacks):
                try:
                    cb(event)
                except Exception as exc:
                    logger.error("BLE button callback error: %s", exc)

    def _load_cached_arm_positions(self) -> ArmPositions:
        if not self.mock_mode and self._arm_cache_path.exists():
            try:
                raw = json.loads(self._arm_cache_path.read_text(encoding="utf-8"))
                arms = ArmPositions(**raw)
                arms.height = {
                    "left": max(1, min(12, int(arms.height.get("left", 1)))),
                    "right": max(1, min(12, int(arms.height.get("right", 1)))),
                }
                # Same scales as the live path: raw rotation detent 1..7 passed through,
                # angle 0 (folded) .. 5.
                arms.rotation = ArmRotationState(
                    left=max(1, min(7, int(arms.rotation.left))),
                    right=max(1, min(7, int(arms.rotation.right))),
                    left_unlocked=False,
                    right_unlocked=False,
                )
                arms.angle = {
                    "left": max(ANGLE_FOLDED, min(5, int(arms.angle.get("left", 1)))),
                    "right": max(ANGLE_FOLDED, min(5, int(arms.angle.get("right", 1)))),
                }
                return arms
            except Exception:
                pass
        return ArmPositions()

    def _save_cached_arm_positions(self, arms: ArmPositions) -> None:
        if self.mock_mode:
            return
        key = f"{arms.height}|{arms.rotation.left}:{arms.rotation.right}:{arms.rotation.left_unlocked}:{arms.rotation.right_unlocked}|{arms.angle}"
        if key == self._last_saved_arm_key:
            return
        self._last_saved_arm_key = key
        try:
            self._arm_cache_path.parent.mkdir(parents=True, exist_ok=True)
            self._arm_cache_path.write_text(
                json.dumps(arms.model_dump()), encoding="utf-8"
            )
        except Exception:
            pass

    def update_hardware_arm_packet(self, message_type: int, payload: bytes) -> ArmPositions:
        """Decode raw 0x1020/0x1021/0x1025 arm sensor payloads into live hardware arm state."""
        height = dict(self._hardware_arm_positions.height)
        rot_state = self._hardware_arm_positions.rotation
        rotation: Dict[str, Any] = {
            "left": int(rot_state.left),
            "right": int(rot_state.right),
            "left_unlocked": bool(rot_state.left_unlocked),
            "right_unlocked": bool(rot_state.right_unlocked),
        }
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
            quant_angle = decode_telescope_angle_notch(decoded["telescope_position"])
            if quant_angle is not None:
                angle[side] = quant_angle
            unlocked = not (
                decoded["is_rotation_locked"] and decoded["is_rotation_and_telescope_locked"]
            )
            self._hardware_arm_0x1025_unlocked[side] = unlocked
            rotation[f"{side}_unlocked"] = bool(rotation.get(f"{side}_unlocked", False) or unlocked)

        self._hardware_arm_positions = ArmPositions(
            height=height,
            rotation=ArmRotationState(**rotation),
            angle=angle,
        )
        self._save_cached_arm_positions(self._hardware_arm_positions)
        return self._hardware_arm_positions

    def _read_swolectl_arm_positions(self) -> ArmPositions:
        """Read live 0x1020/0x1021/0x1025 arm positions from a swolectl.Controller instance."""
        ctrl = self._controller
        if ctrl is None:
            return self._hardware_arm_positions

        # Drain internal queue so swolectl._frames doesn't grow unbounded at 30Hz
        frames_q = getattr(ctrl, "_frames", None)
        if frames_q is not None and hasattr(frames_q, "empty") and hasattr(frames_q, "get_nowait"):
            try:
                while not frames_q.empty():
                    frames_q.get_nowait()
            except Exception:
                pass

        height = dict(self._hardware_arm_positions.height)
        rot_state = self._hardware_arm_positions.rotation
        rotation: Dict[str, Any] = {
            "left": int(rot_state.left),
            "right": int(rot_state.right),
            "left_unlocked": bool(rot_state.left_unlocked),
            "right_unlocked": bool(rot_state.right_unlocked),
        }
        angle = dict(self._hardware_arm_positions.angle)

        # 1. Horizontal Column Rotation (R) & Moving/Unlocked state from raw 0x1021
        # Disassembled from Tonal McbDevice::processUpstreamMessage (libmcb-service.so @ 0x4d328):
        #   byte 0 = left_unlocked  (0=locked, non-zero=moving/unlocked)
        #   byte 1 = left_position  (raw column detent: 1 = stowed, 2..6 = latching positions, 0 = between;
        #            seen on a Tonal V1, fw 5.2.18.0; passed through as R1..R6)
        #   byte 2 = right_unlocked (0=locked, non-zero=moving/unlocked)
        #   byte 3 = right_position (raw column detent)
        col_rot = getattr(ctrl, "column_rotation", None)
        arm_map = getattr(ctrl, "arm_positions", None)

        left_unlk = False
        right_unlk = False

        raw_1021 = self._raw_column_rotation_bytes
        if raw_1021 is not None and len(raw_1021) == 4:
            left_unlk = bool(raw_1021[0])
            left_r_raw = int(raw_1021[1])
            right_unlk = bool(raw_1021[2])
            right_r_raw = int(raw_1021[3])
            if 1 <= left_r_raw <= 7:
                rotation["left"] = left_r_raw
            if 1 <= right_r_raw <= 7:
                rotation["right"] = right_r_raw
        elif col_rot is not None and hasattr(col_rot, "left_position") and hasattr(col_rot, "right_position"):
            # Fallback when running against unit-test mock controller without raw 0x1021 frame callback
            if int(col_rot.left_position) != 0:
                left_unlk = True
            if int(col_rot.right_position) != 0:
                right_unlk = True

        if isinstance(arm_map, dict):
            for side in ("left", "right"):
                arm = arm_map.get(side)
                if arm is None:
                    continue
                rot_flag = bool(getattr(arm, "is_rotation_locked", False))
                both_flag = bool(getattr(arm, "is_rotation_and_telescope_locked", False))
                # Physical Tonal V1 0x1025 reports raw_flags=0x0/0x1 (bits 20 & 21 == 0) when LOCKED,
                # and sets bit 20/21 when UNLOCKED/in-transition.
                if rot_flag or both_flag:
                    if side == "left":
                        left_unlk = True
                    else:
                        right_unlk = True

        # 2. Vertical Cart Height (0x1020: 1..12)
        cart = getattr(ctrl, "cart_position", None)
        if cart is not None and hasattr(cart, "left") and hasattr(cart, "right"):
            cand_l = int(cart.left)
            cand_r = int(cart.right)
            if 1 <= cand_l <= 12:
                height["left"] = cand_l
            if 1 <= cand_r <= 12:
                height["right"] = cand_r

        # 3. Vertical Arm Angle (0x1025 detent: folded -> A0, 2..6 -> A1..A5, between -> keep last)
        if isinstance(arm_map, dict):
            for side in ("left", "right"):
                arm = arm_map.get(side)
                if arm is None:
                    continue

                if hasattr(arm, "telescope_position"):
                    raw_tele = int(getattr(arm, "telescope_position", 2))
                    quant_angle = decode_telescope_angle_notch(raw_tele)
                    if quant_angle is not None:
                        angle[side] = quant_angle
                elif raw_1021 is None:
                    # Fallback for mock controllers that only expose rotation_position
                    raw_rot_deg = int(getattr(arm, "rotation_position", getattr(arm, "angle", rotation[side])))
                    rotation[side] = quantize_arm_rotation_notch(raw_rot_deg, prev_notch=rotation[side])

        rotation["left_unlocked"] = bool(left_unlk)
        rotation["right_unlocked"] = bool(right_unlk)

        self._hardware_arm_positions = ArmPositions(
            height=height,
            rotation=ArmRotationState(**rotation),
            angle=angle,
        )
        self._save_cached_arm_positions(self._hardware_arm_positions)
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

        if not os.path.exists(self.port):
            import glob
            candidates = sorted(glob.glob("/dev/ttyACM*"))
            if candidates:
                logger.warning("Configured port %s missing; using %s", self.port, candidates[0])
                self.port = candidates[0]
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
                sig_params = inspect.signature(SafetyPolicy).parameters
                if "compatible_firmware_versions" in sig_params:
                    safety_kwargs["compatible_firmware_versions"] = frozenset({expected_fw})
                if "allow_unverified_firmware" in sig_params:
                    safety_kwargs["allow_unverified_firmware"] = True
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
        if hasattr(self._controller, "add_frame_callback") and callable(
            self._controller.add_frame_callback
        ):
            self._controller.add_frame_callback(self._on_swolectl_frame)
        await self.poll_arm_sensors()

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

    async def poll_arm_sensors(self) -> None:
        """Query router component 9 (0x1005 / 0x1026) and component 1 so static arm height/rotation/angle sensors report immediately."""
        if self.mock_mode or self._controller is None:
            return
        if hasattr(self._controller, "send_raw") and callable(self._controller.send_raw):
            try:
                msg_1005 = bytes.fromhex("a5 3a 00 00 66 00 01 00 02 00 01 00")
                await _invoke_controller(self._controller.send_raw, 0x0000, b"", destination=1)
                await _invoke_controller(
                    self._controller.send_raw, 0x1005, msg_1005, destination=9
                )
                await _invoke_controller(self._controller.send_raw, 0x0001, b"", destination=1)
                await asyncio.sleep(0.05)
                await _invoke_controller(self._controller.send_raw, 0x0001, b"", destination=1)
                await asyncio.sleep(0.05)
                await _invoke_controller(self._controller.send_raw, 0x0002, b"", destination=1)
                await _invoke_controller(self._controller.send_raw, 0x0002, b"", destination=1)
                await _invoke_controller(
                    self._controller.send_raw, 0x1026, struct.pack("<H", 9), destination=9
                )
                await _invoke_controller(
                    self._controller.send_raw, 0x1026, struct.pack("<H", 2), destination=2
                )
                # Enable automatic Smart Handle / Bar discovery & link on nRF52 Bobble hub (router src=2 & dest=9)
                for ble_dest in (2, 9):
                    await _invoke_controller(
                        self._controller.send_raw,
                        MSG_BLE_CONTROL,
                        bytes([1, 1, 1, 1, 1, 1]),
                        destination=ble_dest,
                    )
            except Exception as exc:
                logger.debug("Arm sensor query skipped: %s", exc)

    def update_heartbeat(self) -> None:
        """Refresh watchdog heartbeat timestamp (monotonic clock, immune to NTP jumps)."""
        now = time.monotonic()
        self.last_heartbeat = now
        self.last_interaction = now

    async def _set_target_weight_unlocked(
        self, weight_lb: float, profile: str = "standard", modifier_pct: int = 0
    ) -> None:
        if profile not in VALID_PROFILES:
            raise ValueError(f"Unsupported resistance profile: {profile}")
        if modifier_pct < 0 or modifier_pct > 100:
            raise ValueError(f"Modifier percentage out of bounds: {modifier_pct}")
        if not math.isfinite(weight_lb):
            raise ValueError(f"Non-finite resistance requested: {weight_lb}")

        multiplier = 1.0 + (
            modifier_pct / 100.0 if profile in ("eccentric", "chains", "smart_flex") else 0.0
        )
        total_load = weight_lb * multiplier
        if not math.isfinite(total_load) or total_load > self.max_weight or weight_lb < self.min_weight:
            raise ValueError(
                f"Requested resistance {total_load:.1f}lb exceeds safety bounds "
                f"({self.min_weight}-{self.max_weight}lb)"
            )

        self.target_weight_lb = round(float(weight_lb), 1)
        self.profile_mode = profile
        self.modifier_pct = int(modifier_pct)
        self.last_interaction = time.monotonic()

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
                        "smart_flex": ResistanceMode.SMART_FLEX,
                    }
                    sw_mode = mode_map.get(self.profile_mode, ResistanceMode.BASIC)
                    intensity = float(self.modifier_pct)
                    if sw_mode in (ResistanceMode.ECCENTRIC, ResistanceMode.SMART_FLEX):
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

    async def set_target_weight(
        self, weight_lb: float, profile: str = "standard", modifier_pct: int = 0
    ) -> None:
        """Validate safety bounds and configure target resistance profile."""
        async with self._cmd_lock:
            await self._set_target_weight_unlocked(weight_lb, profile, modifier_pct)

    async def _enable_resistance_unlocked(self) -> None:
        if self.maintenance_locked:
            raise RuntimeError("Cannot enable resistance: system update maintenance lock active")
        if self.state == "FAULT":
            raise RuntimeError("Cannot enable resistance: Motor controller in FAULT state")
        if self.state == "STOCK_MODE":
            raise RuntimeError("Cannot enable resistance while in STOCK_MODE")
        self._last_enable_cmd_ts = time.monotonic()
        if self._controller and not self.mock_mode:
            if getattr(self._controller, "configured_profile", None) is None:
                await self._set_target_weight_unlocked(
                    self.target_weight_lb, self.profile_mode, self.modifier_pct
                )
            if hasattr(self._controller, "enable_resistance") and callable(
                self._controller.enable_resistance
            ):
                await _invoke_controller(self._controller.enable_resistance)
            elif hasattr(self._controller, "enable_power") and callable(
                self._controller.enable_power
            ):
                await _invoke_controller(self._controller.enable_power)
        self.weight_enabled = True
        self.state = "ACTIVE_WEIGHT_ON"
        self._last_cable_activity_ts = time.monotonic()
        self._last_activity_cable_pos = None
        self.update_heartbeat()

    async def enable_resistance(self) -> None:
        """Engage motor resistance (serialized under command lock)."""
        async with self._cmd_lock:
            await self._enable_resistance_unlocked()

    async def _disable_resistance_unlocked(self) -> None:
        self._bar_tilt_start_time = None
        self._mock_sim.clear_bar_tilt()
        self.last_interaction = time.monotonic()
        if self._controller and not self.mock_mode:
            try:
                if hasattr(self._controller, "disable_resistance") and callable(
                    self._controller.disable_resistance
                ):
                    await _invoke_controller(self._controller.disable_resistance)
                elif hasattr(self._controller, "disable_power") and callable(
                    self._controller.disable_power
                ):
                    await _invoke_controller(self._controller.disable_power)
            except Exception as exc:
                # Keep weight_enabled=True and enter FAULT so watchdogs keep retrying disengage
                logger.critical("Hardware disable_resistance FAILED: %s", exc)
                self.state = "FAULT"
                raise
        self.weight_enabled = False
        if self.state not in ("FAULT", "STOCK_MODE"):
            self.state = "IDLE_WEIGHT_OFF"

    async def disable_resistance(self) -> None:
        """Zero and disengage motor resistance (serialized under command lock)."""
        async with self._cmd_lock:
            await self._disable_resistance_unlocked()

    async def set_maintenance_lock(self, locked: bool) -> dict:
        """Engage/release maintenance lock (immediately disengages weight and blocks all engagement paths)."""
        async with self._cmd_lock:
            self.maintenance_locked = bool(locked)
            if self.maintenance_locked and self.weight_enabled:
                await self._disable_resistance_unlocked()
            return {
                "maintenance_locked": self.maintenance_locked,
                "weight_enabled": self.weight_enabled,
                "state": self.state,
            }

    async def toggle_resistance(self) -> bool:
        """Toggle weight ON/OFF and return resulting enabled state."""
        async with self._cmd_lock:
            if self.weight_enabled:
                await self._disable_resistance_unlocked()
            else:
                await self._enable_resistance_unlocked()
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
            "raw_arm_sensors": self.get_raw_arm_sensors(),
        }

    def get_raw_arm_sensors(self) -> dict:
        """Return raw decoded 0x1020/0x1021/0x1025 sensor attributes from swolectl.Controller for diagnostics."""
        ctrl = self._controller
        if ctrl is None:
            return {"connected": False}
        cart = getattr(ctrl, "cart_position", None)
        col = getattr(ctrl, "column_rotation", None)
        arms = getattr(ctrl, "arm_positions", None) or {}
        return {
            "connected": True,
            "connected_ble_accessories": list(self._connected_ble_accessories.values()),
            "recent_aux_frames": list(self._recent_aux_frames),
            "raw_0x1021_hex": self._raw_column_rotation_bytes.hex() if self._raw_column_rotation_bytes else None,
            "cart_position": (
                {"left": int(cart.left), "right": int(cart.right)}
                if cart is not None
                else None
            ),
            "column_rotation": (
                {
                    "left_position": int(col.left_position),
                    "left_unlocked": bool(col.left_unlocked),
                    "right_position": int(col.right_position),
                    "right_unlocked": bool(col.right_unlocked),
                }
                if col is not None
                else None
            ),
            "arm_positions": {
                side: {
                    "telescope_position": int(getattr(arm, "telescope_position", 0)),
                    "rotation_position": int(getattr(arm, "rotation_position", 0)),
                    "side": int(getattr(arm, "side", 0)),
                    "is_rotation_locked": bool(getattr(arm, "is_rotation_locked", False)),
                    "is_rotation_and_telescope_locked": bool(
                        getattr(arm, "is_rotation_and_telescope_locked", False)
                    ),
                    "raw_flags": hex(int(getattr(arm, "raw_flags", 0))),
                }
                for side, arm in arms.items()
                if arm is not None
            },
        }

    def zero_cable_offsets(self) -> dict:
        """Capture current resting cable encoder positions as zero offsets (must be weight-off)."""
        if self.weight_enabled:
            raise RuntimeError("Cannot zero cable calibration while weight is engaged")
        motor_t = getattr(self._controller, "telemetry", None) if self._controller is not None else None
        if motor_t is not None and not self.mock_mode:
            self.cable_zero_offsets = {
                "left": round(float(getattr(motor_t, "left_cable_position", 0.0)), 1),
                "right": round(float(getattr(motor_t, "right_cable_position", 0.0)), 1),
            }
        else:
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
            now = time.monotonic()
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
                    "timestamp": time.time(),
                }
                self._emit_event(event)
                return True
        else:
            self._bar_tilt_start_time = None
        return False

    async def _check_watchdog(self) -> bool:
        """Emergency disable resistance if client heartbeat expired (monotonic clock)."""
        now = time.monotonic()
        if now - self.last_heartbeat > self.watchdog_timeout_sec:
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

    async def _check_inactivity_safety(
        self, left_pos: float, right_pos: float, left_vel: float, right_vel: float
    ) -> bool:
        """Auto-disengage resistance if cables remain stationary for `inactivity_cutoff_sec` (default 45s)."""
        now = time.monotonic()
        if not self.weight_enabled or self.inactivity_cutoff_sec <= 0:
            self._last_cable_activity_ts = now
            self._last_activity_cable_pos = None
            return False

        if self._last_activity_cable_pos is None:
            self._last_activity_cable_pos = (left_pos, right_pos)
            self._last_cable_activity_ts = now
            return False

        moved = (
            abs(left_pos - self._last_activity_cable_pos[0]) >= 2.0
            or abs(right_pos - self._last_activity_cable_pos[1]) >= 2.0
            or abs(left_vel) >= 2.0
            or abs(right_vel) >= 2.0
        )
        if moved:
            self._last_activity_cable_pos = (left_pos, right_pos)
            self._last_cable_activity_ts = now
            return False

        if now - self._last_cable_activity_ts >= self.inactivity_cutoff_sec:
            logger.warning(
                "Inactivity Auto-Cutoff triggered after %.1fs with no cable movement!",
                self.inactivity_cutoff_sec,
            )
            await self.disable_resistance()
            self._last_activity_cable_pos = None
            self._emit_event(
                {
                    "type": "safety_event",
                    "reason": "INACTIVITY_AUTO_CUTOFF",
                    "timeout_sec": self.inactivity_cutoff_sec,
                    "timestamp": time.time(),
                }
            )
            return True
        return False

    UNCOMMANDED_LATCH_GRACE_SEC = 0.5
    ENABLE_CMD_SETTLE_SEC = 1.5

    async def _check_uncommanded_engage(self, hw_enabled: bool) -> bool:
        """Disengage if the motor board reports resistance on that the daemon did not command."""
        now = time.monotonic()
        if not hw_enabled or self.weight_enabled or self.mock_mode or self.state == "STOCK_MODE":
            self._uncommanded_latch_since = None
            return False
        if now - self._last_enable_cmd_ts < self.ENABLE_CMD_SETTLE_SEC:
            return False
        if self._uncommanded_latch_since is None:
            self._uncommanded_latch_since = now
            return False
        if now - self._uncommanded_latch_since < self.UNCOMMANDED_LATCH_GRACE_SEC:
            return False
        if now - self._last_uncommanded_alert_ts < 2.0:
            return False
        self._last_uncommanded_alert_ts = now
        logger.critical(
            "Motor board reports resistance ENGAGED that the daemon did not command "
            "(e.g. a handle button handled by firmware). Disengaging."
        )
        self._emit_event(
            {
                "type": "safety_event",
                "reason": "UNCOMMANDED_HARDWARE_ENGAGE",
                "timestamp": time.time(),
            }
        )
        try:
            await self.disable_resistance()
        except Exception as exc:
            logger.critical("Could not disengage uncommanded resistance: %s", exc)
        return True

    async def _hardware_telemetry_loop(self) -> None:
        while True:
            try:
                await self._check_watchdog()

                if hasattr(self._controller, "read_telemetry_frame") and callable(
                    self._controller.read_telemetry_frame
                ):
                    raw = await _invoke_controller(self._controller.read_telemetry_frame)
                    frame = self._parse_hardware_frame(raw)
                else:
                    frame = self._build_frame_from_swolectl()

                await self._check_uncommanded_engage(bool(frame.weight_enabled))
                await self._check_bar_tilt_safety(
                    frame.left_cable.position, frame.right_cable.position
                )
                await self._check_inactivity_safety(
                    frame.left_cable.position,
                    frame.right_cable.position,
                    frame.left_cable.velocity,
                    frame.right_cable.velocity,
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
            now_mono = time.monotonic()
            if self._prev_cable_pos is not None and self._prev_cable_ts > 0.0:
                dt = now_mono - self._prev_cable_ts
                if 0.015 <= dt <= 0.5:
                    delta_l = abs(left_pos - self._prev_cable_pos[0])
                    delta_r = abs(right_pos - self._prev_cable_pos[1])
                    # Deadzone (>= 0.4) + conservative scale so micro-jitter never spikes VBT
                    raw_dl = (delta_l / dt) * 0.5 if delta_l >= 0.4 else 0.0
                    raw_dr = (delta_r / dt) * 0.5 if delta_r >= 0.4 else 0.0
                    sm_l = 0.18 * raw_dl + 0.82 * self._smoothed_cable_vel[0]
                    sm_r = 0.18 * raw_dr + 0.82 * self._smoothed_cable_vel[1]
                    if sm_l < 1.0:
                        sm_l = 0.0
                    if sm_r < 1.0:
                        sm_r = 0.0
                    self._smoothed_cable_vel = (sm_l, sm_r)
                    if abs(left_vel) < 0.1 and sm_l >= 1.5:
                        left_vel = round(sm_l, 1)
                    if abs(right_vel) < 0.1 and sm_r >= 1.5:
                        right_vel = round(sm_r, 1)
            self._prev_cable_pos = (left_pos, right_pos)
            self._prev_cable_ts = now_mono
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
                await self._check_inactivity_safety(left_pos, right_pos, left_vel, right_vel)

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
        """Disengage motor power, stop telemetry loop, and release serial port for Stock mode / shutdown."""
        async with self._cmd_lock:
            try:
                await self._disable_resistance_unlocked()
            except Exception as exc:
                logger.error("Failed to disable resistance before port release: %s", exc)
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
