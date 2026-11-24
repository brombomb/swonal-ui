# **Project Specification: swonal-ui**

# **Full-Stack Custom Workout Controller, Hardware Bridge & Spotify Integration for Rooted Tonal V1**

## **Executive Summary**

The `swonal-ui` system is an open-source, **standalone-first** architecture engineered to extend and modernize the rooted **Tonal 1 (V1)** smart home gym platform. All core features — low-level hardware motor control (`tonal-daemon`), 60fps native Android Kiosk UI (`android-kiosk`), local SQLite persistence, Spotify PKCE speaker pairing, AI Voice Coach (local rules + BYOK/Ollama), Nostr Swocial, Home Assistant webhooks, Routine Builder, and the embedded `:3000` LAN web server serving the mobile Companion / History / Settings SPA — run **100% on-device on the Tonal V1 tablet** with full offline resilience. Power users can optionally pair an external `packages/server-app` container on a home server, NAS, or cloud VM for bidirectional background sync (`/api/sync/*`, `/ws/sync`), 24/7 offline-Tonal MCP workout queueing, centralized Home Assistant MQTT discovery, and daily rotating SQLite backups.

---

# **1\. System Overview & Architecture**

## **1.1 Hardware & OS Constraints**

The **Tonal V1** hardware platform comprises two main internal execution contexts:

* **Host System:** An integrated MediaTek single-board computer (SBC) running **Android 6.0 (API Level 23\)**.  
* **Motor Controller Board:** A Luminary Micro motor controller (`501-0100_rev004`, running firmware revision `5.2.18.0`), exposed to the MediaTek host via an internal USB CDC ACM serial link accessible at `/dev/ttyACM0` (`115200 8N1`, USB `VID:PID 1cbe:00a1`).

The host Android 6.0 operating system introduces significant constraints:

* The system-provided WebView engine is outdated and incapable of rendering modern modern Web applications (React 18+, CSS Grid/Flexbox, dynamic Web Audio API).  
* Linux SELinux and permission restrictions enforce that `/dev/ttyACM0` can only be read/written by process identities with `root` privileges or specific vendor service UIDs (`uid=1000`).

```
+-------------------------------------------------------------------------------+

|                             TONAL V1 TABLET                                   |
|                                                                               |
|  +-----------------------------------+    +--------------------------------+  |
|  |  Sideloaded Modern Browser APK    |    |  Magisk service.d (Root)       |  |
|  |  (Kiosk View, Portrait 1080x1920) |    |  packages/tonal-daemon         |  |
|  +-----------------+-----------------+    +---------------+----------------+  |
|                    |                                      |                   |
|                    | HTTP/WS                              | Swolectl Engine   |
|                    v                                      v                   |
|  +-----------------------------------+    +--------------------------------+  |
|  |  com.tonal.app (Stock Mode)       |    |  /dev/ttyACM0 Serial Interface |  |
|  |  [Suspended in Custom Mode]       |    |  & BLE Handles Listener        |  |
|  +-----------------------------------+    +---------------+----------------+  |
+-----------------------------------------------------------|-------------------+
                                                            |
                                                            v
                                            +-------------------------------+
                                            | Luminary Micro Board          |
                                            | Firmware 5.2.18.0             |
                                            +-------------------------------+
```

## **1.2 Dual-Tier Operational Architecture**

To solve these constraints, `swonal-ui` uses a monorepo architecture divided into two independent services:

1. **`packages/tonal-daemon` (Execution Context: Root Service on Tonal V1 Android OS)**  
   * Written in Python 3.11+, executed directly on the Android subsystem via Termux or custom Magisk boot environments (`service.d`).  
   * Leverages `swolectl` for direct, low-latency binary serial frames to `/dev/ttyACM0`.  
   * Exposes a high-throughput, asynchronous WebSocket and REST API server (`:8765`) handling telemetry (`0x1024`) and arm position data (`0x1020`, `0x1021`, `0x1025`).  
   * Integrates direct GATT notifications via `bleak` for wireless Tonal Smart Handles and Smart Bar controllers.  
   * Manages process lifecycles and USB device claims during mode transitions between standard stock Tonal operations and custom software control.  
2. **`packages/server-app` (Execution Context: Local Home Server via Docker)**  
   * Containerized Node.js/TypeScript backend powering a modern web server, Prisma/SQLite database, and real-time state synchronization engine.  
   * Serves an optimized UI (1080x1920 portrait resolution) tailored for the Tonal 23.8-inch display, rendered through a modern sideloaded browser engine (e.g., Firefox/Chromium APK).  
   * Provides full program building, routine management, analytics, auto-progressive overload algorithms, and Spotify Connect mini-player management with local `librespot` audio ducking.

---

# **2\. Repository Architecture**

```
swonal-ui/
├── docker-compose.yml
├── README.md
├── packages/
│   ├── tonal-daemon/              # Python root service on Tonal V1 tablet
│   │   ├── pyproject.toml
│   │   ├── config.example.yaml
│   │   ├── start-daemon.sh        # Magisk service.d initialization script
│   │   └── src/
│   │       ├── __init__.py
│   │       ├── main.py            # FastAPI entry point & WebSocket hub (:8765)
│   │       ├── motor_bridge.py    # swolectl wrapper, safety watchdog & telemetry
│   │       ├── ble_handles.py     # Bleak GATT client for Bluetooth accessories
│   │       ├── mode_switcher.py   # Android root process orchestrator (su)
│   │       └── mock_motor.py      # Hardware emulation engine for offline dev
│   └── server-app/                # Node.js/TypeScript application engine
│       ├── Dockerfile
│       ├── package.json
│       ├── tsconfig.json
│       ├── prisma/
│       │   └── schema.prisma      # Database layout for users, workouts & telemetry
│       └── src/
│           ├── server/            # Express/Fastify API, WebSocket proxy, Music & MCP services
│           │   ├── index.ts
│           │   ├── routes/
│           │   ├── services/
│           │   ├── music/         # Pluggable Music Provider Architecture
│           │   │   ├── MusicProvider.ts
│           │   │   ├── Registry.ts
│           │   │   └── providers/
│           │   │       ├── SpotifyProvider.ts
│           │   │       ├── YouTubeMusicProvider.ts
│           │   │       └── AppleMusicProvider.ts
│           │   └── mcp/           # Model Context Protocol Server Layer
│           │       └── mcpServer.ts
│           └── client/            # React + Vite + Tailwind portrait frontend app
│               ├── index.html
│               ├── src/
│               │   ├── components/
│               │   ├── hooks/
│               │   └── App.tsx
```

---

# **3\. Component 1: `packages/tonal-daemon`**

## **3.1 Dependencies & Hard Configuration**

### **`pyproject.toml`**

```
[build-system]
requires = ["setuptools>=61.0"]
build-backend = "setuptools.build_meta"

[project]
name = "tonal-daemon"
version = "1.0.0"
description = "Hardware daemon and serial bridge for Tonal V1 motor controller"
readme = "README.md"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.110.0",
    "uvicorn[standard]>=0.28.0",
    "websockets>=12.0",
    "bleak>=0.21.1",
    "pydantic>=2.6.0",
    "pyyaml>=6.0.1",
    "pyserial-asyncio>=0.6"
]

[project.scripts]
tonal-daemon = "src.main:entry_point"
```

### **`config.example.yaml`**

```
daemon:
  host: "0.0.0.0"
  port: 8765
  mock_mode: false
  watchdog_timeout_sec: 5.0

serial:
  port: "/dev/ttyACM0"
  baudrate: 115200
  expected_board: "501-0100_rev004"
  expected_firmware: "5.2.18.0"

safety:
  allow_motor_commands: true
  minimum_resistance_lb: 5.0
  maximum_resistance_lb: 200.0
  bar_tilt_cutoff_enabled: true
  bar_tilt_threshold_delta: 35.0
  bar_tilt_dwell_ms: 250

bluetooth:
  handle_left_mac: "AA:BB:CC:DD:EE:01"
  handle_right_mac: "AA:BB:CC:DD:EE:02"
  bar_control_mac: "AA:BB:CC:DD:EE:03"
  gatt_characteristic_uuid: "00002a37-0000-1000-8000-00805f9b34fb"
```

## **3.2 Motor Controller Interface (`motor_bridge.py`)**

```py
import asyncio
import logging
import math
import time
from typing import Optional, Dict, Any, Callable
from pydantic import BaseModel

logger = logging.getLogger("tonal-daemon.motor")

class CableState(BaseModel):
    position: float = 0.0
    velocity: float = 0.0
    load_lb: float = 0.0

class ArmPositions(BaseModel):
    height: Dict[str, int] = {"left": 0, "right": 0}
    rotation: Dict[str, int] = {"left": 0, "right": 0, "left_unlocked": False, "right_unlocked": False}
    angle: Dict[str, int] = {"left": 0, "right": 0}

class TelemetryFrame(BaseModel):
    type: str = "telemetry"
    mode: str = "CUSTOM"
    weight_enabled: bool = False
    target_weight_lb: float = 5.0
    applied_resistance_lb: float = 0.0
    profile_mode: str = "standard"
    modifier_pct: int = 0
    rep_count: int = 0
    accessory: str = "HANDLES"
    left_cable: CableState = CableState()
    right_cable: CableState = CableState()
    arm_positions: ArmPositions = ArmPositions()

class MotorBridge:
    def __init__(self, config: dict):
        self.config = config
        self.mock_mode = config.get("daemon", {}).get("mock_mode", False)
        self.port = config.get("serial", {}).get("port", "/dev/ttyACM0")
        self.max_weight = config.get("safety", {}).get("maximum_resistance_lb", 200.0)
        self.min_weight = config.get("safety", {}).get("minimum_resistance_lb", 5.0)
        self.bar_tilt_enabled = config.get("safety", {}).get("bar_tilt_cutoff_enabled", True)
        self.bar_tilt_threshold = config.get("safety", {}).get("bar_tilt_threshold_delta", 35.0)
        self.bar_tilt_dwell_ms = config.get("safety", {}).get("bar_tilt_dwell_ms", 250)
        self._bar_tilt_start_time: Optional[float] = None
        
        self.state = "DISCONNECTED"
        self.weight_enabled = False
        self.target_weight_lb = 10.0
        self.profile_mode = "standard"
        self.modifier_pct = 0
        self.rep_count = 0
        self.active_accessory = "HANDLES"
        
        self.last_heartbeat = time.time()
        self.last_interaction = time.time()
        self._telemetry_callbacks: list[Callable[[dict], None]] = []
        self._loop_task: Optional[asyncio.Task] = None
        self._controller = None

    async def initialize(self):
        if self.mock_mode:
            logger.info("Initializing Motor Bridge in MOCK MODE")
            self.state = "IDLE_WEIGHT_OFF"
            self._loop_task = asyncio.create_task(self._mock_telemetry_loop())
            return

        logger.info(f"Connecting to physical Tonal motor on {self.port}")
        try:
            # Native swolectl integration boundary
            from swolectl import Controller, SafetyPolicy
            safety = SafetyPolicy(
                allow_motor_commands=True,
                minimum_resistance_lb=self.min_weight,
                maximum_resistance_lb=self.max_weight
            )
            self._controller = Controller(port=self.port, safety=safety)
            await self.bring_up()
        except Exception as e:
            logger.error(f"Failed to bind motor controller hardware: {e}")
            self.state = "FAULT"
            raise e

    async def bring_up(self):
        if self.mock_mode:
            self.state = "IDLE_WEIGHT_OFF"
            return
            
        board_info = await self._controller.get_board_info()
        if board_info.board != self.config["serial"]["expected_board"] or \
           board_info.firmware != self.config["serial"]["expected_firmware"]:
            self.state = "FAULT"
            raise RuntimeError(f"Firmware mismatch: Detected {board_info.board} {board_info.firmware}")
            
        await self._controller.bring_up()
        self.state = "IDLE_WEIGHT_OFF"
        self._loop_task = asyncio.create_task(self._hardware_telemetry_loop())

    def update_heartbeat(self):
        self.last_heartbeat = time.time()
        self.last_interaction = time.time()

    async def set_target_weight(self, weight_lb: float, profile: str = "standard", modifier_pct: int = 0):
        total_load = weight_lb * (1.0 + (modifier_pct / 100.0 if profile in ["eccentric", "chains"] else 0.0))
        if total_load > self.max_weight or weight_lb < self.min_weight:
            raise ValueError(f"Requested resistance {total_load}lb exceeds safety bounds ({self.min_weight}-{self.max_weight}lb)")
            
        self.target_weight_lb = weight_lb
        self.profile_mode = profile
        self.modifier_pct = modifier_pct
        self.last_interaction = time.time()
        
        if self._controller and not self.mock_mode:
            await self._controller.set_profile(weight_lb=weight_lb, mode=profile, modifier_pct=modifier_pct)

    async def enable_resistance(self):
        if self.state == "FAULT":
            raise RuntimeError("Cannot enable resistance: Motor controller in FAULT state")
        self.weight_enabled = True
        self.state = "ACTIVE_WEIGHT_ON"
        self.last_interaction = time.time()
        if self._controller and not self.mock_mode:
            await self._controller.enable_power()

    async def disable_resistance(self):
        self.weight_enabled = False
        if self.state != "FAULT":
            self.state = "IDLE_WEIGHT_OFF"
        self.last_interaction = time.time()
        if self._controller and not self.mock_mode:
            await self._controller.disable_power()

    async def toggle_resistance(self):
        if self.weight_enabled:
            await self.disable_resistance()
        else:
            await self.enable_resistance()

    def register_telemetry_callback(self, cb: Callable[[dict], None]):
        self._telemetry_callbacks.append(cb)

    async def _check_bar_tilt_safety(self, left_pos: float, right_pos: float):
        if not self.bar_tilt_enabled or not self.weight_enabled or self.active_accessory != "BAR":
            self._bar_tilt_start_time = None
            return
        delta = abs(left_pos - right_pos)
        if delta > self.bar_tilt_threshold:
            now = time.time()
            if self._bar_tilt_start_time is None:
                self._bar_tilt_start_time = now
            elif (now - self._bar_tilt_start_time) * 1000.0 >= self.bar_tilt_dwell_ms:
                logger.warning(f"Bar Tilt Cutout Triggered! Cable delta={delta:.1f}")
                await self.disable_resistance()
                self._bar_tilt_start_time = None
                event = {"type": "safety_event", "reason": "BAR_TILT_CUTOUT", "delta": round(delta, 1)}
                for cb in self._telemetry_callbacks:
                    cb(event)
        else:
            self._bar_tilt_start_time = None

    async def _hardware_telemetry_loop(self):
        while True:
            try:
                if time.time() - self.last_heartbeat > self.config["daemon"]["watchdog_timeout_sec"]:
                    if self.weight_enabled:
                        logger.warning("Heartbeat missed! Emergency disabling resistance.")
                        await self.disable_resistance()

                if time.time() - self.last_interaction > 900:  # 15 min idle autosuspend
                    if self.state == "IDLE_WEIGHT_OFF" and self._controller:
                        logger.info("Entering autosuspend idle state, closing serial port.")
                        await self.release_port()

                raw = await self._controller.read_telemetry_frame()
                frame = self._parse_hardware_frame(raw)
                await self._check_bar_tilt_safety(frame.left_cable.position, frame.right_cable.position)
                for cb in self._telemetry_callbacks:
                    cb(frame.model_dump())
                await asyncio.sleep(0.033)  # ~30Hz
            except Exception as e:
                logger.error(f"Error in hardware telemetry loop: {e}")
                await asyncio.sleep(0.1)

    async def _mock_telemetry_loop(self):
        sim_time = 0.0
        in_rep = False
        rep_progress = 0.0
        
        while True:
            await asyncio.sleep(0.033)
            sim_time += 0.033
            
            if time.time() - self.last_heartbeat > self.config["daemon"]["watchdog_timeout_sec"]:
                if self.weight_enabled:
                    logger.warning("Mock Watchdog: Heartbeat lost. Disabling resistance.")
                    self.weight_enabled = False
                    self.state = "IDLE_WEIGHT_OFF"

            pos = 0.0
            vel = 0.0
            applied = 0.0
            
            if self.weight_enabled:
                rep_progress += 0.033 * 0.8
                pos = 100.0 + math.sin(rep_progress) * 45.0
                vel = math.cos(rep_progress) * 15.0
                applied = self.target_weight_lb + (math.sin(rep_progress) * (self.target_weight_lb * self.modifier_pct / 100.0))
                
                if rep_progress >= 2 * math.pi:
                    rep_progress = 0.0
                    self.rep_count += 1
            else:
                rep_progress = 0.0

            left_pos_val = round(pos, 1)
            right_pos_val = round(pos * 0.99, 1)
            await self._check_bar_tilt_safety(left_pos_val, right_pos_val)

            frame = TelemetryFrame(
                type="telemetry",
                mode="CUSTOM" if self.state != "STOCK_MODE" else "STOCK",
                weight_enabled=self.weight_enabled,
                target_weight_lb=self.target_weight_lb,
                applied_resistance_lb=round(applied if self.weight_enabled else 0.0, 1),
                profile_mode=self.profile_mode,
                modifier_pct=self.modifier_pct,
                rep_count=self.rep_count,
                accessory=self.active_accessory,
                left_cable=CableState(position=left_pos_val, velocity=round(vel, 1), load_lb=round(applied/2, 1)),
                right_cable=CableState(position=right_pos_val, velocity=round(vel * 0.99, 1), load_lb=round(applied/2, 1)),
                arm_positions=ArmPositions(
                    height={"left": 4, "right": 4},
                    rotation={"left": 2, "right": 2, "left_unlocked": False, "right_unlocked": False},
                    angle={"left": 1, "right": 1}
                )
            )
            for cb in self._telemetry_callbacks:
                cb(frame.model_dump())

    def _parse_hardware_frame(self, raw: bytes) -> TelemetryFrame:
        # Swolectl frame parsing implementation stub mapping 0x1024, 0x1020 payload parameters
        return TelemetryFrame()

    async def release_port(self):
        if self._loop_task:
            self._loop_task.cancel()
        if self._controller:
            await self._controller.close()
            self._controller = None
        self.state = "STOCK_MODE"
```

## **3.3 BLE Accessories Controller (`ble_handles.py`)**

```py
import asyncio
import logging
import time
from typing import Optional, Callable
from bleak import BleakClient, BleakScanner

logger = logging.getLogger("tonal-daemon.ble")

class BLEHandleManager:
    def __init__(self, config: dict, event_callback: Callable[[dict], None]):
        self.config = config.get("bluetooth", {})
        self.event_callback = event_callback
        self.clients: list[BleakClient] = []
        self._running = False
        self._last_click_time = 0.0
        self._click_count = 0
        self._press_start_time = 0.0

    async def start(self):
        self._running = True
        macs = [
            self.config.get("handle_left_mac"),
            self.config.get("handle_right_mac"),
            self.config.get("bar_control_mac")
        ]
        active_macs = [m for m in macs if m]
        if not active_macs:
            logger.info("No BLE accessories configured. Skipping BLE scanner.")
            return

        for mac in active_macs:
            asyncio.create_task(self._connect_and_listen(mac))

    async def _connect_and_listen(self, mac: str):
        uuid = self.config.get("gatt_characteristic_uuid")
        while self._running:
            try:
                logger.info(f"Connecting to BLE hardware device: {mac}")
                async with BleakClient(mac, timeout=10.0) as client:
                    logger.info(f"Connected to BLE handle {mac}")
                    await client.start_notify(uuid, self._notification_handler)
                    while self._running and client.is_connected:
                        await asyncio.sleep(1.0)
            except Exception as e:
                logger.warning(f"BLE connection lost for {mac}: {e}. Retrying in 5s...")
                await asyncio.sleep(5.0)

    def _notification_handler(self, sender: int, data: bytearray):
        now = time.time()
        # Decode button status from GATT telemetry frame
        is_pressed = (data[0] & 0x01) == 1

        if is_pressed:
            self._press_start_time = now
        else:
            duration = (now - self._press_start_time) * 1000
            if duration > 1000:
                # Long press event -> Emergency Kill
                self.event_callback({"type": "handle_event", "action": "emergency_stop"})
                self._click_count = 0
            else:
                # Single/Double click detection
                if now - self._last_click_time < 0.35:
                    self._click_count += 1
                else:
                    self._click_count = 1
                self._last_click_time = now

                if self._click_count == 1:
                    asyncio.get_event_loop().call_later(0.38, self._process_single_click)
                elif self._click_count == 2:
                    self.event_callback({"type": "handle_event", "action": "spotify_next"})
                    self._click_count = 0

    def _process_single_click(self):
        if self._click_count == 1:
            self.event_callback({"type": "handle_event", "action": "toggle_weight"})
            self._click_count = 0

    async def disconnect_all(self):
        self._running = False
        for client in self.clients:
            if client.is_connected:
                await client.disconnect()
        self.clients.clear()
```

## **3.4 System Execution Context Switcher (`mode_switcher.py`)**

```py
import asyncio
import logging
import subprocess
from typing import Dict, Any

logger = logging.getLogger("tonal-daemon.switcher")

class ModeSwitcher:
    def __init__(self, motor_bridge, ble_manager, server_url: str):
        self.motor_bridge = motor_bridge
        self.ble_manager = ble_manager
        self.server_url = server_url

    async def switch_to_stock(self) -> Dict[str, Any]:
        logger.info("Executing Mode Transition: CUSTOM -> STOCK TONAL APP")
        try:
            # 1. Safely zero load and disable power stage
            await self.motor_bridge.disable_resistance()
            
            # 2. Shut down BLE observers
            await self.ble_manager.disconnect_all()
            
            # 3. Release serial lock
            await self.motor_bridge.release_port()
            
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
            
            return {"status": "success", "mode": "STOCK", "detail": stdout.decode()}
        except Exception as e:
            logger.error(f"Failure during transition to stock mode: {e}")
            return {"status": "error", "message": str(e)}

    async def switch_to_custom(() -> Dict[str, Any]:
        logger.info("Executing Mode Transition: STOCK TONAL APP -> CUSTOM SWONAL-UI")
        try:
            # 1. Force kill stock app process holding resources
            cmd_stop = "su -c 'am force-stop com.tonal.app'"
            proc = await asyncio.create_subprocess_shell(cmd_stop)
            await proc.wait()
            
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
            proc_launch = await asyncio.create_subprocess_shell(cmd_launch)
            await proc_launch.wait()
            
            return {"status": "success", "mode": "CUSTOM"}
        except Exception as e:
            logger.error(f"Failure during transition to custom mode: {e}")
            return {"status": "error", "message": str(e)}
```

## **3.5 Daemon Service Core (`main.py`)**

```py
import argparse
import asyncio
import json
import logging
import yaml
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from src.motor_bridge import MotorBridge
from src.ble_handles import BLEHandleManager
from src.mode_switcher import ModeSwitcher

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("tonal-daemon")

app = FastAPI(title="Swonal Tonal Hardware Daemon")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

active_websockets: list[WebSocket] = []
motor: MotorBridge = None
ble: BLEHandleManager = None
switcher: ModeSwitcher = None

def load_config(path: str = "config.yaml") -> dict:
    try:
        with open(path, "r") as f:
            return yaml.safe_load(f)
    except Exception:
        with open("config.example.yaml", "r") as f:
            return yaml.safe_load(f)

@app.on_event("startup")
async def startup_event():
    global motor, ble, switcher
    config = load_config()
    
    motor = MotorBridge(config)
    
    async def handle_ble_event(event: dict):
        if event.get("action") == "toggle_weight":
            await motor.toggle_resistance()
        elif event.get("action") == "emergency_stop":
            await motor.disable_resistance()
            
        payload = json.dumps(event)
        for ws in active_websockets:
            await ws.send_text(payload)

    ble = BLEHandleManager(config, handle_ble_event)
    switcher = ModeSwitcher(motor, ble, config.get("server_url", "http://192.168.1.100:3000"))

    await motor.initialize()
    await ble.start()

    def broadcast_telemetry(data: dict):
        payload = json.dumps(data)
        for ws in active_websockets:
            asyncio.create_task(ws.send_text(payload))

    motor.register_telemetry_callback(broadcast_telemetry)

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    active_websockets.append(websocket)
    try:
        while True:
            msg = await websocket.receive_text()
            data = json.loads(msg)
            
            if data.get("type") == "ping":
                motor.update_heartbeat()
                await websocket.send_text(json.dumps({"type": "pong"}))
            elif data.get("type") == "set_weight":
                await motor.set_target_weight(
                    weight_lb=float(data["weight_lb"]),
                    profile=data.get("profile", "standard"),
                    modifier_pct=int(data.get("modifier_pct", 0))
                )
            elif data.get("type") == "toggle_weight":
                await motor.toggle_resistance()
            elif data.get("type") == "enable_weight":
                await motor.enable_resistance()
            elif data.get("type") == "disable_weight":
                await motor.disable_resistance()
    except WebSocketDisconnect:
        active_websockets.remove(websocket)
    except Exception as e:
        logger.error(f"WebSocket processing error: {e}")
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

def entry_point():
    parser = argparse.ArgumentParser(description="swonal-ui Hardware Daemon")
    parser.add_argument("--mock", action="store_true", help="Enable mock hardware loop")
    args = parser.parse_args()
    
    config = load_config()
    if args.mock:
        config["daemon"]["mock_mode"] = True

    import uvicorn
    uvicorn.run(app, host=config["daemon"]["host"], port=config["daemon"]["port"], log_level="info")

if __name__ == "__main__":
    entry_point()
```

---

# **4\. Component 2: `packages/server-app`**

## **4.1 Persistence Layer (`prisma/schema.prisma`)**

```
datasource db {
  provider = "sqlite"
  url      = "file:/data/swonal.db"
}

generator client {
  provider = "prisma-client-js"
}

model User {
  id                      String             @id @default(uuid())
  name                    String
  avatarColor             String             @default("#3B82F6")
  preferredMusicProvider  String             @default("spotify")
  themeMode               String             @default("dark") // "dark" | "light" | "system"
  accentColor             String             @default("#00F0FF") // Custom hex accent color per user
  weightUnit              String             @default("LB") // "LB" | "KG"
  createdAt               DateTime           @default(now())
  updatedAt               DateTime           @updatedAt
  musicIntegrations       MusicIntegration[]
  templates               WorkoutTemplate[]
  sessions                WorkoutSession[]
  personalRecords         PersonalRecord[]
}

model MusicIntegration {
  id                String   @id @default(uuid())
  userId            String
  user              User     @relation(fields: [userId], references: [id], onDelete: Cascade)
  provider          String   // "spotify" | "youtube_music" | "apple_music" | "amazon_music"
  accessToken       String?
  refreshToken      String?
  tokenExpiry       Int?
  preferredDeviceId String?
  metadataJson      String?
  createdAt         DateTime @default(now())
  updatedAt         DateTime @updatedAt

  @@unique([userId, provider])
}

model Exercise {
  id                  String           @id @default(uuid())
  name                String           @unique
  muscleGroup         String
  accessory           String           @default("HANDLES") // HANDLES, BAR, ROPE, BODYWEIGHT
  recommendedHeight   Int              @default(4)
  recommendedRotation Int              @default(2)
  recommendedAngle    Int              @default(1)
  unilateral          Boolean          @default(false)
  notes               String?
  setLogs             SetLog[]
  personalRecords     PersonalRecord[]
}

model WorkoutTemplate {
  id          String           @id @default(uuid())
  userId      String
  user        User             @relation(fields: [userId], references: [id], onDelete: Cascade)
  name        String
  description String?
  blocksJson  String           // JSON serialized structured layout of warmups, supersets, and blocks
  createdAt   DateTime         @default(now())
  sessions    WorkoutSession[]
}

model WorkoutSession {
  id          String           @id @default(uuid())
  userId      String
  user        User             @relation(fields: [userId], references: [id], onDelete: Cascade)
  templateId  String?
  template    WorkoutTemplate? @relation(fields: [templateId], references: [id], onDelete: SetNull)
  startedAt   DateTime         @default(now())
  completedAt DateTime?
  totalVolume Float            @default(0.0)
  setLogs     SetLog[]
}

model SetLog {
  id             String         @id @default(uuid())
  sessionId      String
  session        WorkoutSession @relation(fields: [sessionId], references: [id], onDelete: Cascade)
  exerciseId     String
  exercise       Exercise       @relation(fields: [exerciseId], references: [id])
  setNumber      Int
  mode           String         @default("standard") // standard, eccentric, chains, spotter, burnout
  weightLb       Float
  repsCompleted  Int
  volumeLb       Float
  rpe            Float?
  timestamp      DateTime       @default(now())
}

model PersonalRecord {
  id             String   @id @default(uuid())
  userId         String
  user           User     @relation(fields: [userId], references: [id], onDelete: Cascade)
  exerciseId     String
  exercise       Exercise @relation(fields: [exerciseId], references: [id], onDelete: Cascade)
  oneRepMaxEst   Float
  maxWeightLb    Float
  updatedAt      DateTime @updatedAt

  @@unique([userId, exerciseId])
}
```

**Canonical Unit Storage Rule:** The `swolectl` hardware controller and SQLite database (`SetLog.weightLb`, `PersonalRecord.maxWeightLb`) always store and communicate canonical floating-point values in **pounds (lb)** to match the Tonal motor controller's native unit system (`5.0` to `200.0` lb). When a user selects `KG`, the UI and MCP presentation layers convert values dynamically (`1 lb = 0.45359237 kg`, rounded to `0.5 kg` increments in the UI dial).

## **4.2 Seed Data: Default Tonal Movement Base**

The system initializes SQLite with standard movements and hardware configuration defaults:

| Exercise Name | Muscle Group | Accessory | Rec. Height | Rec. Rotation | Rec. Angle |
| :---- | :---- | :---- | :---- | :---- | :---- |
| Bench Press | Chest | BAR | 4 | 2 | 1 |
| Standing Overhead Press | Shoulders | BAR | 1 | 2 | 1 |
| Lat Pulldown | Back | BAR | 6 | 2 | 1 |
| Bicep Curl | Arms | HANDLES | 1 | 2 | 1 |
| Tricep Extension | Arms | ROPE | 6 | 2 | 1 |
| Goblet Squat | Legs | BAR | 1 | 2 | 1 |
| Romanian Deadlift | Legs / Posterior | BAR | 1 | 2 | 1 |
| Cable Fly | Chest | HANDLES | 4 | 1 | 2 |
| Single-Arm Low Row | Back | HANDLES | 2 | 3 | 1 |

---

## **4.3 Frontend Interface Implementation**

### **Modern Tonal UI Component (`WorkoutStage.tsx`)**

```
import React, { useState, useEffect } from 'react';
import { Play, Square, FastForward, Music, ShieldAlert } from 'lucide-react';

interface Telemetry {
  weight_enabled: boolean;
  target_weight_lb: number;
  applied_resistance_lb: number;
  profile_mode: string;
  modifier_pct: number;
  rep_count: number;
  left_cable: { position: number; velocity: number; load_lb: number };
  right_cable: { position: number; velocity: number; load_lb: number };
  arm_positions: {
    height: { left: number; right: number };
    rotation: { left: number; right: number };
    angle: { left: number; right: number };
  };
}

export const WorkoutStage: React.FC<{ ws: WebSocket | null; daemonIp: string }> = ({ ws, daemonIp }) => {
  const [telemetry, setTelemetry] = useState<Telemetry>({
    weight_enabled: false,
    target_weight_lb: 45,
    applied_resistance_lb: 0,
    profile_mode: 'standard',
    modifier_pct: 0,
    rep_count: 0,
    left_cable: { position: 0, velocity: 0, load_lb: 0 },
    right_cable: { position: 0, velocity: 0, load_lb: 0 },
    arm_positions: { height: { left: 4, right: 4 }, rotation: { left: 2, right: 2 }, angle: { left: 1, right: 1 } }
  });

  useEffect(() => {
    if (!ws) return;
    const handleMessage = (event: MessageEvent) => {
      const data = JSON.parse(event.data);
      if (data.type === 'telemetry') {
        setTelemetry(data);
      }
    };
    ws.addEventListener('message', handleMessage);
    return () => ws.removeEventListener('message', handleMessage);
  }, [ws]);

  const sendCommand = (cmd: object) => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(cmd));
    }
  };

  const handleWeightChange = (newWeight: number) => {
    sendCommand({
      type: 'set_weight',
      weight_lb: newWeight,
      profile: telemetry.profile_mode,
      modifier_pct: telemetry.modifier_pct
    });
  };

  const setMode = (mode: string, pct: number = 25) => {
    sendCommand({
      type: 'set_weight',
      weight_lb: telemetry.target_weight_lb,
      profile: mode,
      modifier_pct: mode === 'standard' ? 0 : pct
    });
  };

  const toggleWeight = () => {
    sendCommand({ type: 'toggle_weight' });
  };

  const switchStock = async () => {
    if (telemetry.weight_enabled) {
      if (!confirm("Weight is actively ON! Turn off and switch to Stock Tonal Mode?")) return;
    }
    await fetch(`http://${daemonIp}:8765/api/mode/stock`, { method: 'POST' });
  };

  // SVG Gauge Calculations
  const radius = 180;
  const circumference = 2 * Math.PI * radius;
  const progress = Math.min(telemetry.applied_resistance_lb / 200, 1);
  const strokeDashoffset = circumference - progress * circumference;

  return (
    <div className="w-[1080px] h-[1920px] bg-slate-950 text-white flex flex-col justify-between p-8 select-none font-sans overflow-hidden">
      
      {/* TOP STATUS BAR */}
      <div className="flex justify-between items-center bg-slate-900/80 p-6 rounded-2xl border border-slate-800 backdrop-blur-md">
        <div className="flex items-center space-x-4">
          <div className="w-12 h-12 rounded-full bg-blue-600 flex items-center justify-center font-bold text-xl">
            JD
          </div>
          <div>
            <h2 className="text-xl font-semibold">Alex Doe</h2>
            <p className="text-xs text-slate-400">Custom Workout Session</p>
          </div>
        </div>

        <div className="flex items-center space-x-6">
          <div className="flex items-center space-x-2 bg-emerald-950/50 border border-emerald-500/30 px-4 py-2 rounded-full text-emerald-400 text-sm">
            <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-pulse"></span>
            <span className="font-mono">CDC ACM ONLINE | 5.2.18.0</span>
          </div>
          <button 
            onClick={switchStock}
            className="bg-red-950/40 hover:bg-red-900/60 border border-red-700/50 text-red-300 font-medium px-5 py-2.5 rounded-xl transition flex items-center space-x-2"
          >
            <ShieldAlert size={18} />
            <span>Original Tonal</span>
          </button>
        </div>
      </div>

      {/* CENTER STAGE: DIAL + TELEMETRY BARS */}
      <div className="relative flex flex-col items-center justify-center my-auto">
        
        {/* Left Cable ROM Bar */}
        <div className="absolute left-8 top-1/2 -translate-y-1/2 w-8 h-96 bg-slate-900 border border-slate-800 rounded-full flex flex-col-reverse overflow-hidden">
          <div 
            className="w-full bg-blue-500 transition-all duration-75"
            style={{ height: `${Math.min((telemetry.left_cable.position / 200) * 100, 100)}%` }}
          />
        </div>

        {/* Right Cable ROM Bar */}
        <div className="absolute right-8 top-1/2 -translate-y-1/2 w-8 h-96 bg-slate-900 border border-slate-800 rounded-full flex flex-col-reverse overflow-hidden">
          <div 
            className="w-full bg-blue-500 transition-all duration-75"
            style={{ height: `${Math.min((telemetry.right_cable.position / 200) * 100, 100)}%` }}
          />
        </div>

        {/* Central Dynamic Ring Gauge */}
        <div className="relative flex items-center justify-center">
          <svg className="w-[500px] h-[500px] transform -rotate-90">
            <circle
              cx="250" cy="250" r={radius}
              className="stroke-slate-900" strokeWidth="28" fill="transparent"
            />
            <circle
              cx="250" cy="250" r={radius}
              className={`${telemetry.weight_enabled ? 'stroke-blue-500' : 'stroke-slate-700'} transition-all duration-100`}
              strokeWidth="28" strokeDasharray={circumference} strokeDashoffset={strokeDashoffset}
              strokeLinecap="round" fill="transparent"
            />
          </svg>

          {/* Huge Dynamic Touch Target */}
          <button
            onClick={toggleWeight}
            className={`absolute w-[360px] h-[360px] rounded-full flex flex-col items-center justify-center transition-all duration-300 ${
              telemetry.weight_enabled 
                ? 'bg-blue-600/20 shadow-[0_0_80px_rgba(37,99,235,0.4)] border-4 border-blue-400' 
                : 'bg-slate-900/90 border-4 border-slate-800 hover:border-slate-700'
            }`}
          >
            <span className="text-8xl font-black tracking-tighter">
              {Math.round(telemetry.applied_resistance_lb)}
            </span>
            <span className="text-xl uppercase tracking-widest text-slate-400 font-semibold mt-1">
              {telemetry.weight_enabled ? 'Applied LBS' : 'Target LBS'}
            </span>
            <div className={`mt-4 px-6 py-2 rounded-full font-bold text-sm tracking-wider uppercase ${
              telemetry.weight_enabled ? 'bg-blue-500 text-white animate-pulse' : 'bg-slate-800 text-slate-300'
            }`}>
              {telemetry.weight_enabled ? 'WEIGHT ON' : 'WEIGHT OFF'}
            </div>
          </button>
        </div>

        {/* Rep Counting Banner */}
        <div className="mt-8 flex items-center space-x-12">
          <div className="text-center">
            <p className="text-slate-400 text-sm font-medium uppercase tracking-wider">Completed Reps</p>
            <p className="text-6xl font-bold font-mono mt-1">{telemetry.rep_count}</p>
          </div>
          <div className="h-12 w-px bg-slate-800" />
          <div className="text-center">
            <p className="text-slate-400 text-sm font-medium uppercase tracking-wider">Target Weight</p>
            <div className="flex items-center justify-center space-x-3 mt-1">
              <button 
                onClick={() => handleWeightChange(Math.max(5, telemetry.target_weight_lb - 5))}
                className="w-10 h-10 rounded-lg bg-slate-800 text-2xl font-bold hover:bg-slate-700"
              >-</button>
              <span className="text-4xl font-bold font-mono">{telemetry.target_weight_lb}</span>
              <button 
                onClick={() => handleWeightChange(Math.min(200, telemetry.target_weight_lb + 5))}
                className="w-10 h-10 rounded-lg bg-slate-800 text-2xl font-bold hover:bg-slate-700"
              >+</button>
            </div>
          </div>
        </div>

        {/* Dynamic Mode Modifier Selection Pills */}
        <div className="flex space-x-4 mt-8">
          {[
            { id: 'standard', label: 'Standard', pct: 0 },
            { id: 'eccentric', label: 'Eccentric (+25%)', pct: 25 },
            { id: 'chains', label: 'Chains (+25%)', pct: 25 },
            { id: 'spotter', label: 'Spotter Mode', pct: 0 },
            { id: 'burnout', label: 'Burnout Drop', pct: 0 }
          ].map((mode) => (
            <button
              key={mode.id}
              onClick={() => setMode(mode.id, mode.pct)}
              className={`px-5 py-3 rounded-xl font-medium transition ${
                telemetry.profile_mode === mode.id 
                  ? 'bg-blue-600 text-white shadow-lg shadow-blue-900/40' 
                  : 'bg-slate-900 text-slate-400 border border-slate-800 hover:border-slate-700'
              }`}
            >
              {mode.label}
            </button>
          ))}
        </div>
      </div>

      {/* PERSISTENT SPOTIFY DOCK */}
      <div className="bg-slate-900/90 border border-slate-800 rounded-3xl p-6 flex items-center justify-between backdrop-blur-xl">
        <div className="flex items-center space-x-4">
          <div className="w-16 h-16 bg-slate-800 rounded-2xl flex items-center justify-center text-slate-500 overflow-hidden">
            <Music size={32} />
          </div>
          <div>
            <h4 className="font-semibold text-lg">Heavy Workout Mix</h4>
            <p className="text-slate-400 text-sm">Librespot • Tonal Gym Speaker</p>
          </div>
        </div>

        <div className="flex items-center space-x-8">
          <button className="text-slate-400 hover:text-white transition"><Square size={24} /></button>
          <button className="w-14 h-14 bg-white text-slate-950 rounded-full flex items-center justify-center hover:scale-105 transition">
            <Play size={28} className="ml-1 fill-current" />
          </button>
          <button className="text-slate-400 hover:text-white transition"><FastForward size={24} /></button>
        </div>
      </div>
    </div>
  );
};
```

## **4.4 Design System & Athletic Aesthetic Specification**

* **Adaptive Theme Engine (Dark & Light Modes):**  
  * Implemented via CSS custom properties (`--bg-canvas`, `--bg-surface`, `--bg-elevated`, `--text-primary`, `--text-muted`, `--border-subtle`) toggled via `[data-theme="dark"]` and `[data-theme="light"]`.  
* **Dark Mode (Default — Carbon Obsidian):** `#07090E` primary canvas, `#0F141C` elevated card surface, `#18202C` interactive surface, crisp white `#F8FAFC` typography.  
  * **Light Mode (Daylight Studio — Peloton/Apple Fitness+ Daylight Aesthetic):** `#F4F6F9` cool alabaster canvas, `#FFFFFF` pure white elevated cards with subtle ambient shadows (`0 8px 24px rgba(15, 20, 28, 0.06)`), `#E2E8F0` interactive surfaces, and deep slate `#0F172A` high-contrast typography for sunlit gym rooms.  
* **Per-User Customizable Accent Color:**  
  * Each user profile stores a custom `--accent-primary` hex color (with preset athletic swatches like Electric Cyan `#00F0FF`, Volt Lime `#CCFF00`, Blaze Orange `#FF5500`, Hyper Pink `#FF007A`, and Ultra Violet `#8B5CF6`, plus a custom hex picker).  
  * Automatically derives `--accent-glow` (`rgba(r, g, b, 0.35)`) and `--accent-muted` (`rgba(r, g, b, 0.15)`) in CSS so the central 360px weight dial, active cable ROM bars, and buttons adapt instantly when switching between users (e.g., Alex vs. Taylor).  
* **Imperial (lbs) & Metric (kg) Unit System:**  
  * User-selectable per profile (`LB` or `KG`) with a one-tap unit toggle in the top profile bar.  
  * Hardware-bound serial commands and database records remain in canonical `lbs` (`5–200 lbs`), while the UI dial, \+/- step buttons (`1 lb` / `5 lb` in Imperial; `0.5 kg` / `2.5 kg` in Metric), volume totals, and PR charts convert seamlessly.

## **4.5 Pluggable Music Provider Architecture (Adapter Pattern)**

* **Open-Source Extensibility Goal:** Decouple the UI and workout audio-ducking engine from any single streaming service using a clean TypeScript Adapter Pattern (`IMusicProvider`), enabling community contributors to drop in providers for YouTube Music, Apple Music, Amazon Music, Plexamp, or Jellyfin.  
* **Core `IMusicProvider` Interface (`src/server/music/MusicProvider.ts`):**

```ts
export interface ProviderCapabilities {
  supportsConnectDevices: boolean;
  supportsVolumeDucking: boolean;
  supportsPlaylists: boolean;
}

export interface NormalizedAudioDevice {
  id: string;
  name: string;
  type: string;
  isActive: boolean;
  volumePct: number;
}

export interface NormalizedPlaylist {
  id: string;
  name: string;
  uri: string;
  artworkUrl?: string;
  trackCount: number;
}

export interface NormalizedPlaybackState {
  isPlaying: boolean;
  trackName: string;
  artistName: string;
  albumName: string;
  artworkUrl?: string;
  progressMs: number;
  durationMs: number;
  activeDeviceId?: string;
}

export interface IMusicProvider {
  readonly id: 'spotify' | 'youtube_music' | 'apple_music' | 'amazon_music' | string;
  readonly displayName: string;
  readonly capabilities: ProviderCapabilities;

  getAuthUrl(userId: string): Promise<string>;
  handleAuthCallback(userId: string, code: string): Promise<void>;
  refreshAuthIfNeeded(userId: string): Promise<string>;

  getPlaybackState(userId: string): Promise<NormalizedPlaybackState>;
  play(userId: string, contextUri?: string, deviceId?: string): Promise<void>;
  pause(userId: string): Promise<void>;
  nextTrack(userId: string): Promise<void>;
  previousTrack(userId: string): Promise<void>;
  setVolume(userId: string, volumePct: number): Promise<void>;
  getPlaylists(userId: string): Promise<NormalizedPlaylist[]>;
  getDevices(userId: string): Promise<NormalizedAudioDevice[]>;
  transferPlayback(userId: string, deviceId: string): Promise<void>;
  duckVolume(userId: string, targetPct: number, durationMs: number): Promise<void>;
}
```

* **Reference Implementation & Community Stubs:** The reference `SpotifyProvider` implements `IMusicProvider`, integrating seamlessly with `librespot` running locally on the Tonal hardware. The centralized `MusicProviderRegistry` manages active adapters and dynamically routes REST requests under `/api/music/:provider/*`. Community stub implementations (`YouTubeMusicProvider`, `AppleMusicProvider`) provide initial boilerplates for auth handshakes and API normalization so frontend components consume a uniform `NormalizedPlaybackState` state.

## **4.6 Model Context Protocol (MCP) Server Layer (`src/server/mcp/mcpServer.ts`)**

* **Overview:** Built-in MCP server (using `@modelcontextprotocol/sdk` supporting both HTTP SSE at `/mcp/sse` for remote LAN AI agents/Claude Desktop/Home Assistant and `stdio` transport) that lets an AI assistant discover `swonal-ui`, inspect the user's exercise catalog, PRs, and workout history, and design or schedule custom workouts directly into the database.  
* **Exposed MCP Resources:**  
  * `swonal://exercises/catalog` — Full list of available Tonal exercises, muscle groups, required accessories (`HANDLES`, `BAR`, `ROPE`, `BODYWEIGHT`), and recommended arm height/rotation/angle positions.  
  * `swonal://users/{userId}/profile-and-prs` — User's 1RM estimates, max weights per exercise, and recent volume trends.  
  * `swonal://users/{userId}/workouts` — Existing custom workout templates and recent completed session logs.  
* **Exposed MCP Tools:**  
  * `list_exercises` (filter by `muscleGroup`, `accessory`, or `unilateral`)  
  * `get_user_strength_history` (`userId`, `exerciseId`, `limit`)  
  * `create_custom_workout` (`userId`, `name`, `description`, `blocks`: validated Zod schema of Warmup/Straight/Superset/Circuit blocks with exercise IDs, sets, target reps, dynamic mode `standard|eccentric|chains|spotter|burnout`, modifier percentage, and rest seconds)  
* **Unit System Conversion Rule for MCP:** The `create_custom_workout` and `get_user_strength_history` tools accept an optional `unit: 'LB' | 'KG'` parameter (defaulting to the user's `weightUnit` preference) and automatically convert `KG` inputs into canonical `weightLb` values before saving to SQLite.  
  * `update_workout_template` (`templateId`, `blocks`)  
  * `queue_workout_for_session` (`userId`, `templateId` — pre-loads the workout onto the Tonal portrait screen so the user can walk up and tap Start)  
* **MCP Tool Registration Implementation:**

```ts
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { prisma } from '../db';

export const mcpServer = new McpServer({
  name: 'swonal-ui-mcp',
  version: '1.0.0',
});

// Tool: get_user_strength_history
mcpServer.tool(
  'get_user_strength_history',
  'Retrieve past performance logs and 1RM metrics for a specific user and exercise.',
  {
    userId: z.string().uuid(),
    exerciseId: z.string().uuid(),
    limit: z.number().int().min(1).max(50).default(10),
  },
  async ({ userId, exerciseId, limit }) => {
    const pr = await prisma.personalRecord.findUnique({
      where: { userId_exerciseId: { userId, exerciseId } },
    });
    const setLogs = await prisma.setLog.findMany({
      where: { exerciseId, session: { userId } },
      orderBy: { timestamp: 'desc' },
      take: limit,
    });
    return {
      content: [{ type: 'text', text: JSON.stringify({ personalRecord: pr, recentLogs: setLogs }, null, 2) }],
    };
  }
);

// Tool: create_custom_workout
mcpServer.tool(
  'create_custom_workout',
  'Design and store a new structured workout template for a user.',
  {
    userId: z.string().uuid(),
    name: z.string().min(1),
    description: z.string().optional(),
    blocks: z.array(z.object({
      blockType: z.enum(['WARMUP', 'STRAIGHT_SET', 'SUPERSET', 'CIRCUIT']),
      restSeconds: z.number().int().default(60),
      items: z.array(z.object({
        exerciseId: z.string().uuid(),
        sets: z.number().int().min(1),
        targetReps: z.number().int().min(1),
        mode: z.enum(['standard', 'eccentric', 'chains', 'spotter', 'burnout']).default('standard'),
        modifierPct: z.number().int().default(0),
        targetWeightLb: z.number().positive(),
      })),
    })),
  },
  async ({ userId, name, description, blocks }) => {
    const template = await prisma.workoutTemplate.create({
      data: {
        userId,
        name,
        description,
        blocksJson: JSON.stringify(blocks),
      },
    });
    return {
      content: [{ type: 'text', text: JSON.stringify({ status: 'created', templateId: template.id }, null, 2) }],
    };
  }
);
```

---

## **4.7 Multi-User Household Profile Switcher & Partner Workout Mode (Must-Have)**

* **Netflix/Peloton-Style Household Lock/Home Screen & Top-Bar Quick Switcher:**  
  * Supports multiple household profiles (e.g., Alex, Taylor, guests) with a single tap from the top status bar or home screen avatar grid.  
  * Switching users immediately hot-swaps:  
    * 1\. Active CSS theme (`themeMode`: Dark vs. Light) and `--accent-primary` color variables.  
    * 2\. Preferred `weightUnit` (`LB` vs. `KG`).  
    * 3\. Active `IMusicProvider` session, playlists, and preferred playback device (`MusicIntegration`).  
    * 4\. Historical 1RM / `PersonalRecord` lookup so target weights on the dial automatically recalculate to that user's strength history.  
* **Partner / Co-Lifting Mode ("Work In" Mode):**  
  * Allows 2+ household users to attach to a single `WorkoutSession`.  
  * As Set 1 finishes for User A (e.g., Alex at 35 lbs), `swonal-ui` automatically switches the active profile to User B (e.g., Taylor at 75 lbs) for Set 1, sends the new `set_weight` command to `tonal-daemon`, updates the accent color on the dial, and logs each set to the respective user's `SetLog` and `PersonalRecord` tables.

## **4.8 Mobile Companion Web View & QR Code Remote (Must-Have)**

* **Responsive Dual-Layout Routing in `packages/server-app`:**  
  * `/` (Portrait Kiosk View, `1080x1920`): Optimized for the 23.8-inch Tonal touchscreen. Includes a persistent **"Companion Remote" QR Code badge** in the top bar / home screen encoding `http://<HOME_SERVER_LAN_IP>:3000/companion?session=<activeSessionId>`.  
  * `/companion` (Mobile-First Responsive View, `390x844` viewport): Designed for iOS/Android phones and desktop browsers on the LAN.  
* **Mobile Companion Capabilities:**  
  * **Couch Workout Builder:** Full drag-and-drop creation and editing of `WorkoutTemplate` routines (Warmups, Straight Sets, Supersets, Circuits), exercise browsing, and target weight/mode configuration without standing at the wall screen.  
  * **"Send to Tonal" Queueing:** One-tap button on the phone that pre-loads a selected workout template and user profile onto the Tonal kiosk screen via WebSocket so the lifter can walk up and click the Smart Handle to start.  
  * **Live Between-Sets Remote Control:** Real-time WebSocket sync allowing the phone to adjust target weight (`+/-`), switch dynamic modes (`Eccentric`, `Chains`, `Spotter`), skip rest timers, log RPE/notes, or control the active Music Provider playlist/volume between sets.

# **5\. Deployment Orchestration & Mocking**

## **5.1 Docker Orchestration (`docker-compose.yml`)**

```
version: '3.8'

services:
  server-app:
    build:
      context: ./packages/server-app
      dockerfile: Dockerfile
    container_name: swonal-server
    restart: unless-stopped
    ports:
      - "3000:3000"
    environment:
      - NODE_ENV=production
      - DATABASE_URL=file:/data/swonal.db
      - SPOTIFY_CLIENT_ID=${SPOTIFY_CLIENT_ID}
      - SPOTIFY_CLIENT_SECRET=${SPOTIFY_CLIENT_SECRET}
      - SPOTIFY_REDIRECT_URI=http://localhost:3000/api/spotify/callback
    volumes:
      - swonal-db-data:/data

volumes:
  swonal-db-data:
    driver: local
```

## **5.2 Magisk Auto-Boot Script (`start-daemon.sh`)**

Install this startup script into Magisk persistent init path on Tonal V1 (`/data/adb/service.d/99-swonal-daemon.sh`):

```
#!/system/bin/sh
# Wait for Android boot completion and USB CDC device availability
until [ $(getprop sys.boot_completed) -eq 1 ]; do
    sleep 2
done

until [ -c /dev/ttyACM0 ]; do
    sleep 1
done

# Set permissions for execution context
chmod 0666 /dev/ttyACM0

# Path initialization for Termux / embedded Python runtime
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
export PYTHONHOME="/data/data/com.termux/files/usr"

DAEMON_DIR="/data/swonal/packages/tonal-daemon"

cd $DAEMON_DIR
nohup python3 -m src.main > /data/swonal/daemon.log 2>&1 &
```

---

# **6\. Verification & End-to-End Acceptance Strategy**

```
                          [ Development Workstation ]
                                       |
              +------------------------+------------------------+
              |                                                 |
              v                                                 v
  [ Local Docker Container ]                       [ Physical Tonal Hardware ]
  - server-app (:3000)                             - Root Android 6.0 Host
  - tonal-daemon (--mock)                          - Magisk service.d Daemon
  - Offline sine-wave simulator                    - Swolectl -> /dev/ttyACM0
  - Synthetic rep counter verification              - Luminary Micro 5.2.18.0
```

1. **Offline Verification (Mock Engine):**  
   * Execute the Python daemon locally using `--mock`:

```
cd packages/tonal-daemon
python3 -m src.main --mock
```

   * Launch `server-app` inside Docker via `docker-compose up --build`.  
   * Open `http://localhost:3000` to confirm live sine-wave telemetry visualization, simulated rep counting, target load manipulation, and safety disconnect timers without physically connecting hardware.  
2. **On-Device Hardware Verification:**  
   * Deploy `start-daemon.sh` to `/data/adb/service.d/` on the Tonal MediaTek board.  
   * Restart hardware; query system status to confirm background initiation:

```
curl http://<TONAL_IP>:8765/docs
```

   * Validate mode-switching functionality (`POST /api/mode/stock`) to ensure standard operational integrity and serial lock cleanup.

---

# **7\. Product Roadmap & Feature Prioritization Matrix**

* **Phase 1 — Core Must-Haves (Initial Build Target):**  
  * Rooted on-device `tonal-daemon` (`swolectl` `/dev/ttyACM0` bridge, 30Hz telemetry, `bleak` BLE Smart Handles/Bar listener, and Stock \<-\> Custom Mode Switcher).  
  * `server-app` 1080x1920 Portrait Kiosk UI with Light/Dark mode, per-user customizable accent color, and Imperial/Metric (`LB`/`KG`) unit conversion.  
  * **Multi-User Household Profile Switcher & Partner Co-Lifting Mode.**  
  * **Mobile Companion Web View (`/companion`) & QR Code Remote** for building workouts from a phone/desktop and controlling live sessions.  
  * Pluggable `IMusicProvider` architecture with `SpotifyProvider` \+ local `librespot` audio ducking.  
  * Built-in Model Context Protocol (`MCP`) Server (`/mcp/sse` and `stdio`) for AI workout creation and history analysis.  
* **Phase 1.5 — Nice-to-Have (Include in Initial Build if Clean):**  
  * **Bar Tilt / Unbalanced Cable Safety Cutout ("Auto-Unrack")** in `motor_bridge.py` (`abs(left_cable.position - right_cable.position) > threshold` for \>250ms when using the Smart Bar).  
* **Phase 2 — Feature Later (Future Open-Source Roadmap):**  
  * **Self-Hosted Exercise Demo Video / GIF Loops:** Optional `mediaUrl` field on `Exercise` serving local `.mp4`/`.webm` form loops from `/data/media/` in a picture-in-picture card.  
  * **Home Assistant Webhook / MQTT Event Bus:** Publishing `swonal/state` and `swonal/session` MQTT payloads (e.g., to automate gym fans or match room RGB lighting to the active lifter's `accentColor`).  
  * **Community Music Provider Adapters:** `YouTubeMusicProvider`, `AppleMusicProvider`, `AmazonMusicProvider`, and `PlexampProvider`.