"""FastAPI entry point and WebSocket telemetry/command hub (:8765) for tonal-daemon."""
from __future__ import annotations

import argparse
import asyncio
from contextlib import asynccontextmanager
import hmac
import json
import logging
import os
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel

try:
    import yaml
except ImportError:
    yaml = None

from src.ble_handles import BLEHandleManager
from src.librespot_bridge import LibrespotBridge
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
librespot: Optional[LibrespotBridge] = None
_cli_mock_override: Optional[bool] = None


class SpotifyAuthRequest(BaseModel):
    access_token: Optional[str] = None
    reset: bool = False


class MaintenanceLockRequest(BaseModel):
    locked: bool = True


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
        if candidate.exists() and yaml is not None:
            try:
                with open(candidate, "r", encoding="utf-8") as handle:
                    cfg = yaml.safe_load(handle) or {}
                    break
            except Exception as e:
                logger.warning(f"Failed to parse config with yaml: {e}")
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
    """Initialize and cleanly tear down MotorBridge, BLEHandleManager, ModeSwitcher, and LibrespotBridge."""
    global motor, ble, switcher, librespot
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

    main_loop = asyncio.get_running_loop()

    def on_mcb_ble_button(event: dict) -> None:
        main_loop.call_soon_threadsafe(
            lambda: main_loop.create_task(handle_ble_event(event))
        )

    motor.register_telemetry_callback(broadcast_telemetry)
    motor.register_ble_button_callback(on_mcb_ble_button)
    await motor.initialize()
    await ble.start()
    librespot = LibrespotBridge(mock_mode=motor.mock_mode)
    await librespot.start()

    try:
        yield
    finally:
        if librespot is not None:
            await librespot.stop()
        if ble is not None:
            await ble.disconnect_all()
        if motor is not None:
            await motor.release_port()


app = FastAPI(title="Swonal Tonal Hardware Daemon", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://127.0.0.1:3000",
        "http://localhost:3000",
        "http://127.0.0.1:8765",
        "http://localhost:8765",
    ],
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)

LOOPBACK_HOSTS = {"127.0.0.1", "::1", "localhost", "testclient"}


def _configured_daemon_token() -> str:
    env_tok = os.getenv("DAEMON_API_TOKEN", "").strip()
    if env_tok:
        return env_tok
    if motor is not None:
        return str(motor.config.get("daemon", {}).get("api_token", "")).strip()
    return ""


def _is_authorized_request(client_host: str, auth_header: Optional[str], query_token: Optional[str] = None) -> bool:
    if client_host in LOOPBACK_HOSTS:
        return True
    expected = _configured_daemon_token()
    if not expected:
        return True
    provided = ""
    if auth_header and auth_header.lower().startswith("bearer "):
        provided = auth_header[7:].strip()
    elif query_token:
        provided = query_token.strip()
    return bool(provided) and hmac.compare_digest(provided, expected)


@app.middleware("http")
async def daemon_auth_middleware(request: Request, call_next):
    if request.url.path == "/health":
        return await call_next(request)
    client_host = getattr(request.client, "host", "") if request.client else ""
    if not _is_authorized_request(
        client_host,
        request.headers.get("authorization"),
        request.query_params.get("token"),
    ):
        return JSONResponse(status_code=401, content={"detail": "Unauthorized daemon request"})
    return await call_next(request)


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
    client_host = getattr(getattr(websocket, "client", None), "host", "")
    if not _is_authorized_request(
        client_host,
        websocket.headers.get("authorization"),
        websocket.query_params.get("token"),
    ):
        await websocket.close(code=4401)
        return
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
                if not motor.mock_mode:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": "Simulated arm overrides are disabled on live hardware"})
                    )
                else:
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
                if not motor.mock_mode:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": "Mock BLE simulation is disabled on live hardware"})
                    )
                else:
                    try:
                        await ble.simulate_action(str(data["action"]))
                    except (ValueError, KeyError) as exc:
                        await websocket.send_text(
                            json.dumps({"type": "error", "message": str(exc)})
                        )
            elif msg_type == "simulate_bar_tilt":
                if not motor.mock_mode:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": "Mock bar tilt simulation is disabled on live hardware"})
                    )
                else:
                    motor.trigger_bar_tilt(
                        delta=float(data.get("delta", 45.0)),
                        duration_sec=float(data.get("duration_sec", 0.6)),
                    )
    except WebSocketDisconnect:
        if websocket in active_websockets:
            active_websockets.remove(websocket)
        client_host = getattr(getattr(websocket, "client", None), "host", "")
        if motor is not None and motor.weight_enabled and (
            client_host in ("127.0.0.1", "::1", "localhost") or not active_websockets
        ):
            logger.warning(
                "Safety disengage: kiosk/last WebSocket client (%s) disconnected while weight was engaged.",
                client_host,
            )
            await motor.disable_resistance()
    except Exception as exc:
        logger.error("WebSocket processing error: %s", exc)
        if websocket in active_websockets:
            active_websockets.remove(websocket)
        client_host = getattr(getattr(websocket, "client", None), "host", "")
        if motor is not None and motor.weight_enabled and (
            client_host in ("127.0.0.1", "::1", "localhost") or not active_websockets
        ):
            await motor.disable_resistance()


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


def _require_mock_mode() -> None:
    if motor is None:
        raise HTTPException(status_code=503, detail="Motor bridge unavailable")
    if not motor.mock_mode:
        raise HTTPException(
            status_code=403,
            detail="Mock simulation endpoints are disabled on live hardware",
        )


@app.post("/api/motor/disable")
async def route_motor_disable():
    """Dedicated safety-critical disengage endpoint (works in both live hardware and mock mode)."""
    if motor is None:
        raise HTTPException(status_code=503, detail="Motor bridge unavailable")
    await motor.disable_resistance()
    return {
        "status": "ok",
        "weight_enabled": motor.weight_enabled,
        "state": motor.state,
    }


@app.post("/api/mock/ble-event")
async def route_mock_ble_event(req: MockBleEventRequest):
    _require_mock_mode()
    if ble is None:
        raise HTTPException(status_code=503, detail="BLE manager unavailable")
    try:
        event = await ble.simulate_action(req.action)
        return {"status": "ok", "event": event}
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/api/mock/bar-tilt")
async def route_mock_bar_tilt(req: MockBarTiltRequest):
    _require_mock_mode()
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
    _require_mock_mode()
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


@app.get("/api/spotify/status")
async def route_spotify_status():
    if librespot is None:
        raise HTTPException(status_code=503, detail="Librespot bridge unavailable")
    return await librespot.get_status_async()


@app.post("/api/spotify/auth")
async def route_spotify_auth(req: SpotifyAuthRequest):
    if librespot is None:
        raise HTTPException(status_code=503, detail="Librespot bridge unavailable")
    return await librespot.restart_process(
        access_token=req.access_token, clear_credentials=req.reset
    )


@app.post("/api/spotify/wake")
async def route_spotify_wake():
    if librespot is None:
        raise HTTPException(status_code=503, detail="Librespot bridge unavailable")
    st = await librespot.get_status_async()
    if not st["running"]:
        return await librespot.restart_process()
    return st


@app.post("/api/motor/maintenance")
async def route_motor_maintenance(req: MaintenanceLockRequest):
    """Acquire/release motor maintenance lock (e.g., during kiosk APK updates)."""
    if motor is None:
        raise HTTPException(status_code=503, detail="Motor bridge unavailable")
    return await motor.set_maintenance_lock(req.locked)


@app.post("/api/adb/reset")
async def route_adb_reset(request: Request):
    """Restart on-device adbd over TCP :5555 if a stale half-open socket wedges Wi-Fi ADB."""
    client_host = getattr(request.client, "host", "") if request.client else ""
    if client_host not in LOOPBACK_HOSTS and not _configured_daemon_token():
        logger.warning("ADB reset requested from %s (set DAEMON_API_TOKEN to enforce auth)", client_host)
    if motor is not None and motor.mock_mode:
        return {"status": "ok", "mock_mode": True}
    proc = await asyncio.create_subprocess_exec(
        "sh",
        "-c",
        "setprop service.adb.tcp.port 5555 && stop adbd && start adbd",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    await asyncio.wait_for(proc.communicate(), timeout=5.0)
    return {"status": "ok", "restarted": True}


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
