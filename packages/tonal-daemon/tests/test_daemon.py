"""Unit and integration tests for packages/tonal-daemon."""

import asyncio
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi.testclient import TestClient
import pytest

from src.ble_handles import BLEHandleManager
import src.main as daemon_main
from src.mode_switcher import ModeSwitcher
from src.motor_bridge import (
    MSG_ARM_POSITION,
    MSG_CART_POSITION,
    MSG_COLUMN_ROTATION,
    MotorBridge,
    decode_arm_position,
    decode_cart_position,
    decode_column_rotation,
)


def make_test_config(mock_mode: bool = True, watchdog_timeout_sec: float = 5.0) -> dict:
    return {
        "daemon": {
            "host": "127.0.0.1",
            "port": 8765,
            "mock_mode": mock_mode,
            "watchdog_timeout_sec": watchdog_timeout_sec,
            "server_url": "http://localhost:3000",
        },
        "serial": {
            "port": "/dev/ttyACM0",
            "baudrate": 115200,
            "expected_board": "501-0100_rev004",
            "expected_firmware": "5.2.18.0",
        },
        "safety": {
            "allow_motor_commands": True,
            "minimum_resistance_lb": 5.0,
            "maximum_resistance_lb": 200.0,
            "bar_tilt_cutoff_enabled": True,
            "bar_tilt_threshold_delta": 35.0,
            "bar_tilt_dwell_ms": 250,
        },
        "bluetooth": {
            "handle_left_mac": "AA:BB:CC:DD:EE:01",
            "gatt_characteristic_uuid": "00002a37-0000-1000-8000-00805f9b34fb",
        },
    }


@pytest.mark.asyncio
async def test_safety_bounds_enforced():
    bridge = MotorBridge(make_test_config(mock_mode=True))

    await bridge.set_target_weight(5.0, profile="standard", modifier_pct=0)
    assert bridge.target_weight_lb == 5.0

    await bridge.set_target_weight(200.0, profile="standard", modifier_pct=0)
    assert bridge.target_weight_lb == 200.0

    with pytest.raises(ValueError, match="safety bounds"):
        await bridge.set_target_weight(4.5, profile="standard", modifier_pct=0)

    with pytest.raises(ValueError, match="safety bounds"):
        await bridge.set_target_weight(201.0, profile="standard", modifier_pct=0)

    # 170 lb with +25% eccentric = 212.5 lb -> exceeds 200 lb limit
    with pytest.raises(ValueError, match="safety bounds"):
        await bridge.set_target_weight(170.0, profile="eccentric", modifier_pct=25)

    # 160 lb with +25% chains = 200.0 lb -> valid
    await bridge.set_target_weight(160.0, profile="chains", modifier_pct=25)
    assert bridge.target_weight_lb == 160.0
    assert bridge.profile_mode == "chains"


@pytest.mark.asyncio
async def test_heartbeat_watchdog_disables_resistance():
    bridge = MotorBridge(make_test_config(mock_mode=True, watchdog_timeout_sec=0.2))
    events = []
    bridge.register_telemetry_callback(events.append)

    await bridge.enable_resistance()
    assert bridge.weight_enabled is True
    assert bridge.state == "ACTIVE_WEIGHT_ON"

    # Simulate stale heartbeat older than 0.2s (monotonic clock)
    bridge.last_heartbeat = time.monotonic() - 0.5
    triggered = await bridge._check_watchdog()

    assert triggered is True
    assert bridge.weight_enabled is False
    assert bridge.state == "IDLE_WEIGHT_OFF"
    assert any(e.get("reason") == "WATCHDOG_HEARTBEAT_TIMEOUT" for e in events)


@pytest.mark.asyncio
async def test_bar_tilt_safety_cutout():
    bridge = MotorBridge(make_test_config(mock_mode=True))
    events = []
    bridge.register_telemetry_callback(events.append)

    # 1. Should NOT trigger with HANDLES even with large delta
    bridge.set_accessory("HANDLES")
    await bridge.enable_resistance()
    assert await bridge._check_bar_tilt_safety(120.0, 20.0) is False
    await asyncio.sleep(0.28)
    assert await bridge._check_bar_tilt_safety(120.0, 20.0) is False
    assert bridge.weight_enabled is True

    # 2. Switch to BAR: delta <= 35.0 should NOT trigger
    bridge.set_accessory("BAR")
    assert await bridge._check_bar_tilt_safety(100.0, 70.0) is False
    await asyncio.sleep(0.28)
    assert await bridge._check_bar_tilt_safety(100.0, 70.0) is False
    assert bridge.weight_enabled is True

    # 3. Delta > 35.0 on BAR: starts dwell timer, does not cut immediately before 250ms
    assert await bridge._check_bar_tilt_safety(120.0, 75.0) is False
    assert bridge.weight_enabled is True

    # 4. After 250ms dwell with delta > 35.0, cuts out resistance and emits safety_event
    await asyncio.sleep(0.27)
    triggered = await bridge._check_bar_tilt_safety(120.0, 75.0)
    assert triggered is True
    assert bridge.weight_enabled is False
    assert bridge.state == "IDLE_WEIGHT_OFF"
    safety_events = [e for e in events if e.get("type") == "safety_event"]
    assert len(safety_events) == 1
    assert safety_events[0]["reason"] == "BAR_TILT_CUTOUT"
    assert safety_events[0]["delta"] == 45.0


@pytest.mark.asyncio
async def test_mock_simulator_reps_and_dynamic_modes():
    bridge = MotorBridge(make_test_config(mock_mode=True))
    await bridge.set_target_weight(50.0, profile="eccentric", modifier_pct=25)
    await bridge.enable_resistance()

    observed_applied = []
    reps_completed = 0
    # Step ~3.2 seconds at 30Hz (enough for 1 full 2*pi cycle at 2.2 rad/s)
    for _ in range(100):
        l_pos, r_pos, l_vel, r_vel, applied, rep_done = bridge._mock_sim.step(
            dt=0.033,
            weight_enabled=True,
            target_weight_lb=50.0,
            profile_mode="eccentric",
            modifier_pct=25,
            current_rep_count=reps_completed,
        )
        observed_applied.append(applied)
        if rep_done:
            reps_completed += 1
        assert 0.0 <= l_pos <= 200.0
        assert 0.0 <= r_pos <= 200.0

    assert reps_completed >= 1
    assert max(observed_applied) == pytest.approx(62.5, abs=0.1)
    assert min(observed_applied) == pytest.approx(50.0, abs=0.1)


@pytest.mark.asyncio
async def test_ble_handle_clicks_and_long_press():
    events = []
    manager = BLEHandleManager(make_test_config(), events.append, mock_mode=True)

    # Single click (<350ms press) -> toggle_weight after 380ms debounce
    manager._notification_handler(0, bytearray([0x01]), now=100.0)
    manager._notification_handler(0, bytearray([0x00]), now=100.1)
    await asyncio.sleep(0.45)
    assert any(e["action"] == "toggle_weight" for e in events)

    # Double click -> spotify_next
    events.clear()
    manager._notification_handler(0, bytearray([0x01]), now=200.0)
    manager._notification_handler(0, bytearray([0x00]), now=200.05)
    manager._notification_handler(0, bytearray([0x01]), now=200.15)
    manager._notification_handler(0, bytearray([0x00]), now=200.20)
    assert len(events) == 1
    assert events[0]["action"] == "spotify_next"

    # Long press (>1000ms) -> emergency_stop
    events.clear()
    manager._notification_handler(0, bytearray([0x01]), now=300.0)
    manager._notification_handler(0, bytearray([0x00]), now=301.2)
    assert len(events) == 1
    assert events[0]["action"] == "emergency_stop"


@pytest.mark.asyncio
async def test_mode_switcher_mock_and_hardware_transitions():
    # Mock mode transition
    bridge = MotorBridge(make_test_config(mock_mode=True))
    await bridge.initialize()
    await bridge.enable_resistance()
    ble = BLEHandleManager(make_test_config(mock_mode=True), lambda _: None, mock_mode=True)
    await ble.start()
    switcher = ModeSwitcher(bridge, ble, "http://localhost:3000")

    stock_res = await switcher.switch_to_stock()
    assert stock_res["status"] == "success"
    assert stock_res["mode"] == "STOCK"
    assert bridge.weight_enabled is False
    assert bridge.state == "STOCK_MODE"

    custom_res = await switcher.switch_to_custom()
    assert custom_res["status"] == "success"
    assert custom_res["mode"] == "CUSTOM"
    assert bridge.state == "IDLE_WEIGHT_OFF"

    await bridge.release_port()

    # Non-mock transition checks su -c subprocess calls with fake controller
    fake_ctrl = MagicMock()
    fake_ctrl.get_board_info = AsyncMock(
        return_value=SimpleNamespace(board="501-0100_rev004", firmware="5.2.18.0")
    )
    fake_ctrl.bring_up = AsyncMock()
    fake_ctrl.disable_power = AsyncMock()
    fake_ctrl.close = AsyncMock()
    fake_ctrl.read_telemetry_frame = AsyncMock(return_value={})

    hw_bridge = MotorBridge(make_test_config(mock_mode=False), controller=fake_ctrl)
    await hw_bridge.initialize()
    hw_ble = BLEHandleManager(make_test_config(mock_mode=False), lambda _: None, mock_mode=True)
    hw_switcher = ModeSwitcher(hw_bridge, hw_ble, "http://192.168.1.50:3000")

    mock_proc = MagicMock()
    mock_proc.returncode = 0
    mock_proc.communicate = AsyncMock(return_value=(b"ok", b""))

    with patch("asyncio.create_subprocess_shell", AsyncMock(return_value=mock_proc)) as sub_mock:
        res = await hw_switcher.switch_to_stock()
        assert res["status"] == "success"
        assert hw_bridge.state == "STOCK_MODE"
        assert "com.tonal.app" in sub_mock.call_args.args[0]


def test_fastapi_rest_and_websocket_integration(monkeypatch):
    monkeypatch.setenv("MOCK_TONAL", "true")
    daemon_main._cli_mock_override = True

    with TestClient(daemon_main.app) as client:
        health = client.get("/health")
        assert health.status_code == 200
        body = health.json()
        assert body["status"] == "ok"
        assert body["mock_mode"] is True
        assert body["mode"] == "CUSTOM"

        with client.websocket_connect("/ws") as ws:
            ws.send_json({"type": "ping"})
            got_pong = False
            got_telemetry = False
            for _ in range(10):
                msg = ws.receive_json()
                if msg.get("type") == "pong":
                    got_pong = True
                elif msg.get("type") == "telemetry":
                    got_telemetry = True
                if got_pong and got_telemetry:
                    break
            assert got_pong is True
            assert got_telemetry is True

            ws.send_json(
                {
                    "type": "set_weight",
                    "weight_lb": 85,
                    "profile": "chains",
                    "modifier_pct": 25,
                }
            )
            ws.send_json({"type": "enable_weight"})

            saw_enabled = False
            for _ in range(15):
                msg = ws.receive_json()
                if msg.get("type") == "telemetry" and msg.get("weight_enabled"):
                    assert msg["target_weight_lb"] == 85.0
                    assert msg["profile_mode"] == "chains"
                    saw_enabled = True
                    break
            assert saw_enabled is True

            # Trigger simulated BLE emergency_stop via REST
            ble_resp = client.post("/api/mock/ble-event", json={"action": "emergency_stop"})
            assert ble_resp.status_code == 200
            assert daemon_main.motor.weight_enabled is False

        stock_resp = client.post("/api/mode/stock")
        assert stock_resp.status_code == 200
        assert stock_resp.json()["mode"] == "STOCK"

        custom_resp = client.post("/api/mode/custom")
        assert custom_resp.status_code == 200
        assert custom_resp.json()["mode"] == "CUSTOM"

        cal_resp = client.get("/api/calibration")
        assert cal_resp.status_code == 200
        cal_body = cal_resp.json()
        assert cal_body["zero_offsets"] == {"left": 0.0, "right": 0.0}
        assert isinstance(cal_body["bar_tilt_dwell_ms"], int)
        assert cal_body["bar_tilt_dwell_ms"] == 250

        zero_resp = client.post("/api/calibration/zero")
        assert zero_resp.status_code == 200
        assert zero_resp.json()["status"] == "calibrated"

        # Dedicated safety-critical /api/motor/disable endpoint works in mock mode
        asyncio.run(daemon_main.motor.enable_resistance())
        assert daemon_main.motor.weight_enabled is True
        dis_resp = client.post("/api/motor/disable")
        assert dis_resp.status_code == 200
        assert dis_resp.json()["weight_enabled"] is False
        assert daemon_main.motor.weight_enabled is False


def test_mock_endpoints_rejected_on_live_hardware_and_motor_disable_works(monkeypatch):
    monkeypatch.setenv("MOCK_TONAL", "true")
    daemon_main._cli_mock_override = True

    with TestClient(daemon_main.app) as client:
        # Simulate live hardware mode (mock_mode = False, ble = None)
        daemon_main.motor.mock_mode = False
        orig_ble = daemon_main.ble
        daemon_main.ble = None
        try:
            assert client.post("/api/mock/ble-event", json={"action": "toggle_weight"}).status_code == 403
            bar_tilt = client.post("/api/mock/bar-tilt", json={"auto_enable": True, "delta": 48.0})
            assert bar_tilt.status_code == 403
            assert daemon_main.motor.weight_enabled is False
            assert client.post("/api/mock/arm-positions", json={"height_left": 2}).status_code == 403

            # Dedicated /api/motor/disable STILL works when mock_mode=False and ble=None
            daemon_main.motor.weight_enabled = True
            daemon_main.motor.state = "ACTIVE_WEIGHT_ON"
            dis_resp = client.post("/api/motor/disable")
            assert dis_resp.status_code == 200
            assert dis_resp.json()["weight_enabled"] is False
            assert daemon_main.motor.weight_enabled is False
        finally:
            daemon_main.ble = orig_ble
            daemon_main.motor.mock_mode = True


@pytest.mark.asyncio
async def test_swolectl_arm_sensor_packets_and_sync_controller_telemetry():
    # 1. Verify raw 0x1020 / 0x1021 / 0x1025 decoders match swolectl wire format
    assert decode_cart_position(bytes([3, 5])) == (3, 5)
    assert decode_column_rotation(bytes([0, 2, 1, 1])) == (2, False, 1, True)
    # 0x1025: telescope=2, angle=1, side=0 (left), rot_locked=1 (bit 20), both_locked=1 (bit 21)
    left_locked_word = (2) | (1 << 8) | (0 << 16) | (1 << 20) | (1 << 21)
    left_arm = decode_arm_position(left_locked_word.to_bytes(4, "little"))
    assert left_arm["side"] == "left"
    assert left_arm["angle"] == 1
    assert left_arm["is_rotation_locked"] is True
    assert left_arm["is_rotation_and_telescope_locked"] is True

    # Right arm unlocked (bit 21 = 0)
    right_unlocked_word = (2) | (2 << 8) | (1 << 16) | (1 << 20)
    right_arm = decode_arm_position(right_unlocked_word.to_bytes(4, "little"))
    assert right_arm["side"] == "right"
    assert right_arm["angle"] == 2
    assert right_arm["is_rotation_and_telescope_locked"] is False

    # 2. Verify MotorBridge reads live swolectl.Controller attributes (sync API, no read_telemetry_frame)
    class FakeSwolectlController:
        def __init__(self):
            self.brought_up = False
            self.closed = False
            self.last_announcement = SimpleNamespace(firmware_version="5.2.18.0")
            self.cart_position = SimpleNamespace(left=1, right=9)
            self.column_rotation = SimpleNamespace(
                left_position=0,
                left_unlocked=True,
                right_position=0,
                right_unlocked=True,
            )
            self.arm_positions = {
                "left": SimpleNamespace(
                    telescope_position=2,
                    rotation_position=37,
                    is_rotation_locked=False,
                    is_rotation_and_telescope_locked=False,
                ),
                "right": SimpleNamespace(
                    telescope_position=6,
                    rotation_position=138,
                    is_rotation_locked=False,
                    is_rotation_and_telescope_locked=False,
                ),
            }
            self.telemetry = SimpleNamespace(
                left_cable_position=12.4,
                right_cable_position=12.6,
                left_cable_speed=5.2,
                right_cable_speed=5.1,
                applied_resistance_lb=45.0,
                rep_count=3,
                device_enabled=True,
            )

        def bring_up(self):
            self.brought_up = True

        def enable_resistance(self):
            self.telemetry.device_enabled = True

        def disable_resistance(self):
            self.telemetry.device_enabled = False

        def close(self):
            self.closed = True

    ctrl = FakeSwolectlController()
    bridge = MotorBridge(make_test_config(mock_mode=False), controller=ctrl)
    await bridge.initialize()
    assert ctrl.brought_up is True
    assert bridge.state == "IDLE_WEIGHT_OFF"

    # Simulate live Tonal V1 0x1021 raw wire format (from libmcb-service.so @ 0x4d328):
    # [left_unlk, left_R, right_unlk, right_R]
    bridge._on_swolectl_frame(
        SimpleNamespace(message_type=MSG_COLUMN_ROTATION, payload=bytes.fromhex("00010003"), source=2)
    )
    frame = bridge._build_frame_from_swolectl()
    assert frame.arm_positions.height == {"left": 1, "right": 9}
    assert frame.arm_positions.rotation == {
        "left": 1,
        "right": 3,
        "left_unlocked": False,
        "right_unlocked": False,
    }
    assert frame.arm_positions.angle == {"left": 1, "right": 5}
    assert frame.rep_count == 3
    assert frame.applied_resistance_lb == 45.0

    # Verify live capture: Left=5, Right=3 -> 00050003
    bridge._on_swolectl_frame(
        SimpleNamespace(message_type=MSG_COLUMN_ROTATION, payload=bytes.fromhex("00050003"), source=2)
    )
    frame_r53 = bridge._build_frame_from_swolectl()
    assert frame_r53.arm_positions.rotation["left"] == 5
    assert frame_r53.arm_positions.rotation["right"] == 3

    # 0x2003 button frames (Tonal V1, fw 5.2.18.0): [8] = (button << 4) | event (1=press, 3=release),
    # [9] = 0x01/0x00 for handles + bar button, 0x21/0x20 for the arm rotation-release button.
    btn_events = []
    bridge.register_ble_button_callback(lambda ev: btn_events.append(ev))

    def send_button(hex_payload: str) -> None:
        bridge._last_mcb_button_ts = 0.0  # bypass the 0.4 s debounce between test frames
        bridge._on_swolectl_frame(
            SimpleNamespace(message_type=0x2003, payload=bytes.fromhex(hex_payload), source=2)
        )

    # Arm rotation-release button (adjusting an arm): press + release must NOT toggle weight
    send_button("ed232000c800030001210000fe130100fe130100")
    send_button("ed232000c8000300032000009815010098150100")
    send_button("ed232000c800030002210000a0150100a0150100")
    assert btn_events == []

    # Handle button 1 (one end): press toggles, release does not
    send_button("aabbcc002c01030001010000ffffffff65010000")
    send_button("aabbcc002c010300030000001101000065010000")
    assert len(btn_events) == 1
    assert btn_events[0]["action"] == "toggle_weight"

    # Handle button 2 (other end, [8] = 0x11 press / 0x13 release): also toggles, like the stock app
    send_button("aabbcc002c01030011010000ffffffff65010000")
    send_button("aabbcc002c010300130000001101000065010000")
    assert len(btn_events) == 2

    # Bar button press toggles
    send_button("ddeeff004c040200010100002505000025050000")
    assert len(btn_events) == 3

    # Simulate physical user unlocking right arm telescope/rotation pin (raw_flags bit 20/21 set)
    ctrl.arm_positions["right"].is_rotation_locked = True
    ctrl.arm_positions["right"].is_rotation_and_telescope_locked = True
    frame_unlk = bridge._build_frame_from_swolectl()
    assert frame_unlk.arm_positions.rotation["left_unlocked"] is False
    assert frame_unlk.arm_positions.rotation["right_unlocked"] is True

    # Simulate raw packet updates (0x1021 wire format: [left_unlk, left_pos, right_unlk, right_pos])
    bridge.update_hardware_arm_packet(MSG_CART_POSITION, bytes([4, 4]))
    bridge.update_hardware_arm_packet(MSG_COLUMN_ROTATION, bytes([1, 3, 0, 3]))
    bridge.update_hardware_arm_packet(MSG_ARM_POSITION, left_locked_word.to_bytes(4, "little"))
    assert bridge._hardware_arm_positions.height == {"left": 4, "right": 4}
    assert bridge._hardware_arm_positions.rotation["left_unlocked"] is True
    assert bridge._hardware_arm_positions.rotation["right_unlocked"] is False

    # Verify NaN and Infinity resistance are rejected by safety bounds
    with pytest.raises(ValueError, match="Non-finite"):
        await bridge.set_target_weight(float("nan"))
    with pytest.raises(ValueError, match="Non-finite"):
        await bridge.set_target_weight(float("inf"))

    await bridge.release_port()
    assert ctrl.closed is True


def test_librespot_bridge_endpoints(monkeypatch):
    monkeypatch.setenv("MOCK_TONAL", "true")
    daemon_main._cli_mock_override = True

    with TestClient(daemon_main.app) as client:
        st = client.get("/api/spotify/status")
        assert st.status_code == 200
        body = st.json()
        assert body["device_name"] == "Tonal Speakers"
        assert body["pcm_stream_port"] == 41478

        auth_res = client.post("/api/spotify/auth", json={"access_token": "mock-keymaster-token"})
        assert auth_res.status_code == 200
        assert auth_res.json()["authenticated_user"] == "mock-spotify-user"

        wake_res = client.post("/api/spotify/wake")
        assert wake_res.status_code == 200
        assert wake_res.json()["running"] is True


async def test_inactivity_auto_cutoff_disables_resistance():
    cfg = make_test_config(mock_mode=True)
    cfg.setdefault("safety", {})["inactivity_cutoff_sec"] = 45.0
    bridge = MotorBridge(cfg)
    bridge.state = "IDLE_WEIGHT_OFF"
    events = []
    bridge.register_telemetry_callback(lambda ev: events.append(ev))

    await bridge.enable_resistance()
    assert bridge.weight_enabled is True

    # First check establishes baseline cable position
    assert await bridge._check_inactivity_safety(10.0, 10.0, 0.0, 0.0) is False
    assert bridge.weight_enabled is True

    # Simulate 46 seconds elapsed with stationary cables -> must auto-disengage
    bridge._last_cable_activity_ts -= 46.0
    assert await bridge._check_inactivity_safety(10.2, 10.1, 0.0, 0.0) is True
    assert bridge.weight_enabled is False
    assert any(ev.get("reason") == "INACTIVITY_AUTO_CUTOFF" for ev in events)


async def test_maintenance_lock_disengages_and_blocks_enable():
    bridge = MotorBridge(make_test_config(mock_mode=True))
    bridge.state = "IDLE_WEIGHT_OFF"
    await bridge.enable_resistance()
    assert bridge.weight_enabled is True

    res = await bridge.set_maintenance_lock(True)
    assert res["maintenance_locked"] is True
    assert res["weight_enabled"] is False
    assert bridge.weight_enabled is False

    with pytest.raises(RuntimeError, match="maintenance lock"):
        await bridge.enable_resistance()

    await bridge.set_maintenance_lock(False)
    await bridge.enable_resistance()
    assert bridge.weight_enabled is True


@pytest.mark.asyncio
async def test_uncommanded_hardware_engage_is_disengaged():
    """The motor board latching resistance on without a daemon command must be turned off."""

    class LatchController:
        def __init__(self):
            self.telemetry = SimpleNamespace(device_enabled=True)
            self.disable_calls = 0

        def disable_resistance(self):
            self.disable_calls += 1
            self.telemetry.device_enabled = False

    ctrl = LatchController()
    bridge = MotorBridge(make_test_config(mock_mode=False), controller=ctrl)
    bridge.state = "IDLE_WEIGHT_OFF"
    events = []
    bridge.register_telemetry_callback(lambda ev: events.append(ev))
    assert bridge.weight_enabled is False

    # First sighting only starts the grace window
    assert await bridge._check_uncommanded_engage(True) is False
    assert ctrl.disable_calls == 0
    # Still latched after the grace window -> disengage + safety event
    bridge._uncommanded_latch_since -= bridge.UNCOMMANDED_LATCH_GRACE_SEC + 0.1
    assert await bridge._check_uncommanded_engage(True) is True
    assert ctrl.disable_calls == 1
    assert any(ev.get("reason") == "UNCOMMANDED_HARDWARE_ENGAGE" for ev in events)

    # Latch cleared -> guard resets
    assert await bridge._check_uncommanded_engage(False) is False
    assert bridge._uncommanded_latch_since is None


@pytest.mark.asyncio
async def test_uncommanded_engage_guard_ignores_commanded_and_mock():
    class Ctrl:
        def __init__(self):
            self.telemetry = SimpleNamespace(device_enabled=False)
            self.disable_calls = 0
            self.configured_profile = object()

        def enable_resistance(self):
            self.telemetry.device_enabled = True

        def disable_resistance(self):
            self.disable_calls += 1
            self.telemetry.device_enabled = False

    ctrl = Ctrl()
    bridge = MotorBridge(make_test_config(mock_mode=False), controller=ctrl)
    bridge.state = "IDLE_WEIGHT_OFF"

    # Commanded engage: daemon flag is True, so a hardware latch is expected
    await bridge.enable_resistance()
    bridge._uncommanded_latch_since = time.monotonic() - 5.0
    assert await bridge._check_uncommanded_engage(True) is False

    # Just after a disable the latch may lag a moment; within the settle window after an
    # enable command nothing fires either
    bridge.weight_enabled = False
    bridge._last_enable_cmd_ts = time.monotonic()
    bridge._uncommanded_latch_since = time.monotonic() - 5.0
    assert await bridge._check_uncommanded_engage(True) is False
    assert ctrl.disable_calls == 0

    # Mock mode never acts
    mock_bridge = MotorBridge(make_test_config(mock_mode=True))
    mock_bridge._uncommanded_latch_since = time.monotonic() - 5.0
    assert await mock_bridge._check_uncommanded_engage(True) is False



def test_target_weight_starts_at_configured_minimum():
    # A fixed 45 lb start target meant a handle press right after a daemon start engaged 45 lb
    # nobody chose (seen on a Tonal V1 once the cap was above 45).
    cfg = make_test_config(mock_mode=True)
    cfg["safety"]["minimum_resistance_lb"] = 7.0
    cfg["safety"]["maximum_resistance_lb"] = 60.0
    assert MotorBridge(cfg).target_weight_lb == 7.0

def test_load_config_reads_yaml_and_logs_source(tmp_path, caplog):
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text('daemon:\n  host: "127.0.0.1"\nsafety:\n  allow_motor_commands: false\n')
    with caplog.at_level("INFO", logger="tonal-daemon"):
        cfg = daemon_main.load_config(str(cfg_file))
    assert cfg["daemon"]["host"] == "127.0.0.1"
    assert cfg["safety"]["allow_motor_commands"] is False
    assert f"Loaded daemon config from {cfg_file}" in caplog.text


def test_load_config_without_pyyaml_reports_ignored_file(tmp_path, monkeypatch, caplog):
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text('safety:\n  allow_motor_commands: false\n')
    monkeypatch.setattr(daemon_main, "yaml", None)
    with caplog.at_level("INFO", logger="tonal-daemon"):
        cfg = daemon_main.load_config(str(cfg_file))
    # Falls back to built-in defaults, but says so loudly instead of silently.
    assert cfg["safety"]["allow_motor_commands"] is True
    assert "PyYAML is not installed" in caplog.text
    assert "IGNORED" in caplog.text


def test_lan_exposure_warning(monkeypatch):
    monkeypatch.delenv("DAEMON_API_TOKEN", raising=False)
    lan_no_token = {"daemon": {"host": "0.0.0.0"}}
    assert "no api_token" in daemon_main._lan_exposure_warning(lan_no_token, mock_mode=False)
    assert daemon_main._lan_exposure_warning({}, mock_mode=False) is not None  # default host is 0.0.0.0
    assert daemon_main._lan_exposure_warning(lan_no_token, mock_mode=True) is None
    assert daemon_main._lan_exposure_warning({"daemon": {"host": "127.0.0.1"}}, mock_mode=False) is None
    assert daemon_main._lan_exposure_warning(
        {"daemon": {"host": "0.0.0.0", "api_token": "s3cret"}}, mock_mode=False
    ) is None
    monkeypatch.setenv("DAEMON_API_TOKEN", "s3cret")
    assert daemon_main._lan_exposure_warning(lan_no_token, mock_mode=False) is None
