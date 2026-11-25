"""Hardware emulation engine for offline development and automated testing."""
from __future__ import annotations

import math
import time
from typing import Dict, Tuple, Union


class MockMotorSimulator:
    """Simulates 30Hz Tonal V1 cable positions, velocities, rep cycles, and dynamic modes."""

    def __init__(self, min_weight: float = 5.0, max_weight: float = 200.0):
        self.min_weight = min_weight
        self.max_weight = max_weight
        self.rep_progress = 0.0
        self.sim_time = 0.0
        self.arm_positions: Dict[str, Dict[str, Union[int, bool]]] = {
            "height": {"left": 4, "right": 4},
            "rotation": {
                "left": 2,
                "right": 2,
                "left_unlocked": False,
                "right_unlocked": False,
            },
            "angle": {"left": 1, "right": 1},
        }
        self._tilt_delta: float = 0.0
        self._tilt_until: float = 0.0

    def set_arm_positions(
        self,
        height_left: int,
        height_right: int,
        rot_left: int,
        rot_right: int,
        angle_left: int,
        angle_right: int,
        left_unlocked: bool = False,
        right_unlocked: bool = False,
    ) -> None:
        """Update simulated 0x1020/0x1021/0x1025 arm position telemetry."""
        self.arm_positions = {
            "height": {"left": int(height_left), "right": int(height_right)},
            "rotation": {
                "left": int(rot_left),
                "right": int(rot_right),
                "left_unlocked": bool(left_unlocked),
                "right_unlocked": bool(right_unlocked),
            },
            "angle": {"left": int(angle_left), "right": int(angle_right)},
        }

    def inject_bar_tilt(self, delta: float = 45.0, duration_sec: float = 0.6) -> None:
        """Inject an unbalanced left/right cable position offset for testing bar tilt cutout."""
        self._tilt_delta = max(0.0, float(delta))
        self._tilt_until = time.time() + max(0.3, float(duration_sec))

    def clear_bar_tilt(self) -> None:
        """Clear any active simulated bar tilt injection."""
        self._tilt_delta = 0.0
        self._tilt_until = 0.0

    def reset_cycle(self) -> None:
        """Reset current rep wave phase."""
        self.rep_progress = 0.0

    def step(
        self,
        dt: float,
        weight_enabled: bool,
        target_weight_lb: float,
        profile_mode: str,
        modifier_pct: int,
        current_rep_count: int,
    ) -> Tuple[float, float, float, float, float, bool]:
        """Advance simulation by dt seconds and return cable & load state.

        Returns:
            (left_pos, right_pos, left_vel, right_vel, applied_weight_lb, rep_completed)
        """
        self.sim_time += dt
        rep_completed = False

        if not weight_enabled:
            self.rep_progress = 0.0
            return 10.0, 10.0, 0.0, 0.0, 0.0, False

        # Advance at ~2.2 rad/s (~2.85s per complete concentric + eccentric rep)
        self.rep_progress += dt * 2.2
        if self.rep_progress >= 2.0 * math.pi:
            self.rep_progress -= 2.0 * math.pi
            rep_completed = True

        # Smooth 0..1 extension wave starting at 0 when engaged
        extension = (math.sin(self.rep_progress - math.pi / 2.0) + 1.0) * 0.5
        base_pos = 15.0 + extension * 145.0
        vel = math.cos(self.rep_progress - math.pi / 2.0) * 35.0

        applied = float(target_weight_lb)
        if profile_mode == "eccentric":
            # Add modifier percentage during lowering (negative velocity) phase
            if vel < 0:
                applied = target_weight_lb * (1.0 + modifier_pct / 100.0)
        elif profile_mode == "chains":
            # Progressive accommodating resistance increasing with cable extension
            applied = target_weight_lb * (1.0 + extension * (modifier_pct / 100.0))
        elif profile_mode == "spotter":
            # Simulated spotter assist when velocity drops near peak/bottom stall
            if abs(vel) < 6.0 and extension > 0.2:
                applied = max(self.min_weight, target_weight_lb * 0.85)
        elif profile_mode == "burnout":
            # Gradually taper weight after initial reps to model burnout drop sets
            drop_steps = max(0, current_rep_count - 2)
            taper_factor = max(0.5, 1.0 - drop_steps * 0.05)
            applied = max(self.min_weight, target_weight_lb * taper_factor)
        elif profile_mode == "smart_flex":
            # Dynamic peak-strength curve adding resistance through mid-ROM
            peak_curve = math.sin(extension * math.pi)
            applied = target_weight_lb * (1.0 + peak_curve * (min(60, modifier_pct) / 100.0))

        applied = min(self.max_weight, max(self.min_weight, applied))

        now = time.time()
        if now < self._tilt_until and self._tilt_delta > 0:
            left_pos = min(200.0, base_pos + self._tilt_delta)
            right_pos = max(0.0, left_pos - self._tilt_delta)
        else:
            self._tilt_delta = 0.0
            left_pos = base_pos
            right_pos = max(0.0, base_pos * 0.99)

        left_vel = vel
        right_vel = vel * 0.99
        return (
            round(left_pos, 1),
            round(right_pos, 1),
            round(left_vel, 1),
            round(right_vel, 1),
            round(applied, 1),
            rep_completed,
        )
