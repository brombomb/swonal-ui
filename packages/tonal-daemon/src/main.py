"""FastAPI entry point and WebSocket telemetry/command hub (:8765) for tonal-daemon."""

import argparse
import asyncio
from contextlib import asynccontextmanager
import json
import logging
import os
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import yaml

from src.ble_handles import BLEHandleManager
from src.mode_switcher import ModeSwitcher
from src.motor_bridge import MotorBridge

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("tonal-daemon")

BASE_DIR = Path(__file__).resolve().parent.parent

active_websockets: list[WebSocket] = []
motor: Optional[MotorBridge] = None
ble: Optional[BLEHandleManager] = None
switcher: Optional[ModeSwitcher] = None
_cli_mock_override: Optional[bool] = None


class MockBleEventRequest(BaseModel):
    action: str = "toggle_weight"


class MockBarTiltRequest(BaseModel):
    delta: float = 45.0
    duration_sec: float = 0.6
    auto_enable: bool = False


class MockArmPositionsRequest(BaseModel):
    accessory: Optional[str] = None
    height_left: int = 4
    height_right: int = 4
    rot_left: int = 2
    rot_right: int = 2
    angle_left: int = 1
    angle_right: int = 1


def load_config(path: Optional[str] = None) -> dict:
    """Load daemon configuration from config.yaml or fallback to config.example.yaml."""
    candidates = []
    if path:
        candidates.append(Path(path))
    candidates.extend([BASE_DIR / "config.yaml", BASE_DIR / "config.example.yaml"])

    for candidate in candidates:
        if candidate.exists():
            with open(candidate, "r", encoding="utf-8") as handle:
                cfg = yaml.safe_load(handle) or {}
                break
    else:
        cfg = {
            "daemon": {"host": "0.0.0.0", "port": 8765, "mock_mode": False, "watchdog_timeout_sec": 5.0},
            "serial": {"port": "/dev/ttyACM0", "baudrate": 115200, "expected_board": "501-0100_rev004", "expected_firmware": "5.2.18.0"},
            "safety": {"allow_motor_commands": True, "minimum_resistance_lb": 5.0, "maximum_resistance_lb": 200.0, "bar_tilt_cutoff_enabled": True, "bar_tilt_threshold_delta": 35.0, "bar_tilt_dwell_ms": 250},
            "bluetooth": {},
        }

    if _cli_mock_override is True:
        cfg.setdefault("daemon", {})["mock_mode"] = True
    elif os.getenv("MOCK_TONAL", "").strip().lower() in {"1", "true", "yes"}:
        cfg.setdefault("daemon", {})["mock_mode"] = True
    return cfg


async def _broadcast_payload(payload: str) -> None:
    stale: list[WebSocket] = []
    for ws in list(active_websockets):
        try:
            await ws.send_text(payload)
        except Exception:
            stale.append(ws)
    for ws in stale:
        if ws in active_websockets:
            active_websockets.remove(ws)


@asynccontextmanager
async def lifespan(_: FastAPI):
    """Initialize and cleanly tear down MotorBridge, BLEHandleManager, and ModeSwitcher."""
    global motor, ble, switcher
    config = load_config()
    motor = MotorBridge(config)

    async def handle_ble_event(event: dict) -> None:
        if motor is not None:
            action = event.get("action")
            if action == "toggle_weight":
                await motor.toggle_resistance()
            elif action == "emergency_stop":
                await motor.disable_resistance()
        await _broadcast_payload(json.dumps(event))

    ble = BLEHandleManager(config, handle_ble_event, mock_mode=motor.mock_mode)
    server_url = config.get("daemon", {}).get(
        "server_url", config.get("server_url", "http://192.168.1.100:3000")
    )
    switcher = ModeSwitcher(motor, ble, server_url)

    def broadcast_telemetry(data: dict) -> None:
        if not active_websockets:
            return
        payload = json.dumps(data)
        try:
            loop = asyncio.get_running_loop()
            loop.create_task(_broadcast_payload(payload))
        except RuntimeError:
            pass

    motor.register_telemetry_callback(broadcast_telemetry)
    await motor.initialize()
    await ble.start()

    try:
        yield
    finally:
        if ble is not None:
            await ble.disconnect_all()
        if motor is not None:
            await motor.release_port()


app = FastAPI(title="Swonal Tonal Hardware Daemon", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def get_health():
    if motor is None:
        raise HTTPException(status_code=503, detail="Daemon not initialized")
    return {
        "status": "ok",
        "state": motor.state,
        "mode": "STOCK" if motor.state == "STOCK_MODE" else "CUSTOM",
        "weight_enabled": motor.weight_enabled,
        "target_weight_lb": motor.target_weight_lb,
        "profile_mode": motor.profile_mode,
        "modifier_pct": motor.modifier_pct,
        "accessory": motor.active_accessory,
        "rep_count": motor.rep_count,
        "mock_mode": motor.mock_mode,
    }


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    active_websockets.append(websocket)
    try:
        while True:
            msg = await websocket.receive_text()
            data = json.loads(msg)
            msg_type = data.get("type")

            if msg_type == "ping":
                if motor is not None:
                    motor.update_heartbeat()
                await websocket.send_text(json.dumps({"type": "pong"}))
            elif msg_type == "set_weight":
                try:
                    await motor.set_target_weight(
                        weight_lb=float(data["weight_lb"]),
                        profile=data.get("profile", "standard"),
                        modifier_pct=int(data.get("modifier_pct", 0)),
                    )
                except (ValueError, KeyError) as exc:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": str(exc)})
                    )
            elif msg_type == "toggle_weight":
                try:
                    await motor.toggle_resistance()
                except RuntimeError as exc:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": str(exc)})
                    )
            elif msg_type == "enable_weight":
                try:
                    await motor.enable_resistance()
                except RuntimeError as exc:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": str(exc)})
                    )
            elif msg_type == "disable_weight":
                await motor.disable_resistance()
            elif msg_type == "set_accessory":
                try:
                    motor.set_accessory(str(data["accessory"]))
                except (ValueError, KeyError) as exc:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": str(exc)})
                    )
            elif msg_type == "set_arm_positions":
                motor.set_arm_positions(
                    height_left=int(data.get("height_left", 4)),
                    height_right=int(data.get("height_right", 4)),
                    rot_left=int(data.get("rot_left", 2)),
                    rot_right=int(data.get("rot_right", 2)),
                    angle_left=int(data.get("angle_left", 1)),
                    angle_right=int(data.get("angle_right", 1)),
                )
            elif msg_type == "reset_reps":
                motor.reset_reps()
            elif msg_type == "simulate_ble":
                try:
                    await ble.simulate_action(str(data["action"]))
                except (ValueError, KeyError) as exc:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": str(exc)})
                    )
            elif msg_type == "simulate_bar_tilt":
                motor.trigger_bar_tilt(
                    delta=float(data.get("delta", 45.0)),
                    duration_sec=float(data.get("duration_sec", 0.6)),
                )
    except WebSocketDisconnect:
        if websocket in active_websockets:
            active_websockets.remove(websocket)
    except Exception as exc:
        logger.error("WebSocket processing error: %s", exc)
        if websocket in active_websockets:
            active_websockets.remove(websocket)


@app.post("/api/mode/stock")
async def route_mode_stock():
    res = await switcher.switch_to_stock()
    if res["status"] == "error":
        raise HTTPException(status_code=500, detail=res["message"])
    return res


@app.post("/api/mode/custom")
async def route_mode_custom():
    res = await switcher.switch_to_custom()
    if res["status"] == "error":
        raise HTTPException(status_code=500, detail=res["message"])
    return res


@app.post("/api/mock/ble-event")
async def route_mock_ble_event(req: MockBleEventRequest):
    if ble is None:
        raise HTTPException(status_code=503, detail="BLE manager unavailable")
    try:
        event = await ble.simulate_action(req.action)
        return {"status": "ok", "event": event}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/mock/bar-tilt")
async def route_mock_bar_tilt(req: MockBarTiltRequest):
    if motor is None:
        raise HTTPException(status_code=503, detail="Motor bridge unavailable")
    if req.auto_enable:
        motor.set_accessory("BAR")
        if not motor.weight_enabled:
            await motor.enable_resistance()
    motor.trigger_bar_tilt(delta=req.delta, duration_sec=req.duration_sec)
    return {
        "status": "ok",
        "delta": req.delta,
        "duration_sec": req.duration_sec,
        "accessory": motor.active_accessory,
        "weight_enabled": motor.weight_enabled,
    }


@app.post("/api/mock/arm-positions")
async def route_mock_arm_positions(req: MockArmPositionsRequest):
    if motor is None:
        raise HTTPException(status_code=503, detail="Motor bridge unavailable")
    if req.accessory:
        try:
            motor.set_accessory(req.accessory)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
    motor.set_arm_positions(
        height_left=req.height_left,
        height_right=req.height_right,
        rot_left=req.rot_left,
        rot_right=req.rot_right,
        angle_left=req.angle_left,
        angle_right=req.angle_right,
    )
    return {"status": "ok", "accessory": motor.active_accessory}


@app.get("/api/calibration")
async def route_get_calibration():
    if motor is None:
        raise HTTPException(status_code=503, detail="Motor bridge unavailable")
    return motor.get_calibration_status()


@app.post("/api/calibration/zero")
async def route_zero_calibration():
    if motor is None:
        raise HTTPException(status_code=503, detail="Motor bridge unavailable")
    try:
        return {"status": "calibrated", **motor.zero_cable_offsets()}
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc


def entry_point() -> None:
    global _cli_mock_override
    parser = argparse.ArgumentParser(description="swonal-ui Hardware Daemon")
    parser.add_argument(
        "--mock", action="store_true", help="Enable mock hardware loop"
    )
    args = parser.parse_args()
    if args.mock:
        _cli_mock_override = True

    config = load_config()
    host = config.get("daemon", {}).get("host", "0.0.0.0")
    port = int(os.getenv("DAEMON_PORT", config.get("daemon", {}).get("port", 8765)))

    import uvicorn

    uvicorn.run(app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    entry_point()
