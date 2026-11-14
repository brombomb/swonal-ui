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
from src.motor_bridge import MotorBridge


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

    # Simulate stale heartbeat older than 0.2s
    bridge.last_heartbeat = time.time() - 0.5
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
        assert cal_resp.json()["zero_offsets"] == {"left": 0.0, "right": 0.0}

        zero_resp = client.post("/api/calibration/zero")
        assert zero_resp.status_code == 200
        assert zero_resp.json()["status"] == "calibrated"
