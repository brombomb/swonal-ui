# swonal-ui

**Open-Source Workout Controller, Hardware Motor Bridge, Pluggable Music Hub, & MCP Server for Rooted Tonal V1**

`swonal-ui` modernizes the rooted **Tonal 1 (V1)** smart home gym using a dual-tier architecture:
1. **`packages/tonal-daemon`** (Python 3.11+, FastAPI, WebSockets on `:8765`) — Runs directly on the Tonal Android 6.0 host via Magisk `service.d`, bridging `/dev/ttyACM0` (`swolectl`), BLE Smart Handles/Bar (`bleak`), Stock <-> Custom Android mode switching, and the **Phase 1.5 Bar Tilt Safety Cutout** ("Auto-Unrack"). Includes a full 30Hz `--mock` hardware simulator for offline development.
2. **`packages/server-app`** (Node.js 22, TypeScript, Express, WebSockets, Prisma + SQLite, React + Vite + Tailwind on `:3000`) — Runs on your local home server or Docker host, serving:
   - **Portrait Kiosk UI (`/`)**: Optimized for the `1080x1920` Tonal touchscreen with Adaptive Dark (`Carbon Obsidian`) & Light (`Daylight Studio`) themes, per-user accent colors, `LB`/`KG` conversion, Partner "Work In" co-lifting rotation, 360px SVG load dial, arm position alignment guide, Web Audio cues with automatic music ducking, and Companion QR modal.
  - **Mobile Companion (`/companion`) & Settings (`/settings`)**: Responsive remote control, Workout Builder, Saved Workouts picker, 12-week workout streak & consistency heatmap, and full application/MCP/security settings.
  - **Hardware Admin & Exercise Manager (`/admin`)**: Real-hardware calibration (strain-gauge zeroing, daemon latency check, live cable/arm sensor readout, Stock/Custom mode handoff) and custom exercise/media catalog management (`POST /api/exercises`, `PATCH /api/exercises/:id/media`, `DELETE /api/exercises/:id`).
   - **Mobile Companion View (`/companion`)**: Mobile-first responsive remote control, Couch Workout Builder (multi-block `WARMUP`, `STRAIGHT_SET`, `SUPERSET`, `CIRCUIT` routines), one-tap **Send to Tonal**, and PR/session history.
   - **Pluggable Music Provider Architecture**: Adapter pattern (`IMusicProvider`) with full `SpotifyProvider` (OAuth 2.0 + `librespot` Connect + volume ducking + offline simulation) and community stubs for `YouTubeMusicProvider` and `AppleMusicProvider`.
   - **Built-in Model Context Protocol (MCP) Server**: Exposes `/mcp/sse` (HTTP SSE) and `stdio` transports so AI assistants can inspect the 50-exercise catalog, read PRs/history, design routines, and queue workouts directly to the Tonal screen.

---

## Repository Structure

```
swonal-ui/
├── docker-compose.yml
├── .env.example
├── PROGRESS.md
├── DECISIONS.md
├── scripts/
│   └── verify-e2e.mjs
└── packages/
    ├── tonal-daemon/              # Python hardware daemon & --mock simulator (:8765)
    │   ├── pyproject.toml
    │   ├── Dockerfile
    │   ├── config.example.yaml
    │   ├── start-daemon.sh
    │   ├── src/
    │   │   ├── main.py
    │   │   ├── motor_bridge.py
    │   │   ├── mock_motor.py
    │   │   ├── ble_handles.py
    │   │   └── mode_switcher.py
    │   └── tests/test_daemon.py
    └── server-app/                # Node.js backend, Prisma SQLite, MCP, & React UI (:3000)
        ├── Dockerfile
        ├── package.json
        ├── prisma/
        │   ├── schema.prisma
        │   └── seed.ts
        ├── src/
        │   ├── server/
        │   │   ├── index.ts
        │   │   ├── db.ts
        │   │   ├── mcp/
        │   │   ├── music/
        │   │   ├── routes/
        │   │   ├── services/
        │   │   └── utils/units.ts
        │   └── client/
        │       └── src/
        │           ├── App.tsx
        │           ├── components/KioskView.tsx
        │           ├── components/CompanionView.tsx
        │           ├── hooks/useSwonalState.ts
        │           └── utils/
        └── tests/
```

---

## Quickstart: Local Mock Development

### 1. Start the Python Hardware Daemon (`--mock`)
```bash
cd packages/tonal-daemon
python3 -m venv .venv
.venv/bin/pip install -e .
.venv/bin/pytest -v
.venv/bin/python -m src.main --mock
```
The daemon listens on `http://0.0.0.0:8765` (`/health`, `/ws`, `/api/mode/*`, `/api/mock/*`).

### 2. Initialize Database, Build, & Start `server-app`
```bash
cd packages/server-app
npm install
cp ../../.env.example .env
npx prisma db push
npx tsx prisma/seed.ts
npm test
npm run typecheck
npm run build
npm start
```
Open:
- **Portrait Kiosk UI:** `http://localhost:3000/`
- **Mobile Companion Remote:** `http://localhost:3000/companion`
- **MCP SSE Endpoint:** `http://localhost:3000/mcp/sse`

### 3. Run the Full End-to-End Integration Verification Suite
Ensure ports `8765` and `3000` are free, then run from the repository root:
```bash
node scripts/verify-e2e.mjs
```
This launches both `tonal-daemon --mock` and `server-app`, verifies REST APIs, 30Hz WebSocket telemetry, rep counting, simulated BLE events, Partner "Work In" rotation, the Phase 1.5 Bar Tilt Safety Cutout, music volume ducking, and `/mcp/sse`.

---

## Quickstart: Docker Compose (Full Mock Stack)

```bash
cp .env.example .env
docker compose up --build
```
This launches:
- `tonal-daemon-mock` on port `8765` (`MOCK_TONAL=true`)
- `server-app` on port `3000` with persistent SQLite storage in volume `swonal-db-data:/data`

To connect the Dockerized `server-app` to a **physical rooted Tonal V1 tablet** on your LAN, set `TONAL_DAEMON_URL=http://<TONAL_IP>:8765` and `TONAL_DAEMON_WS_URL=ws://<TONAL_IP>:8765/ws` in `.env` and start `server-app`:
```bash
docker compose up -d --no-deps --build server-app
```

---

## Deploying `tonal-daemon` to Rooted Tonal V1

1. Follow `RUNBOOK-Tonal V1 Rooting & On-Device Setup Guide.md` to root the MediaTek Android 6.0 host with Magisk and set up Termux/Python 3.11+.
2. Copy `packages/tonal-daemon` to `/data/swonal/packages/tonal-daemon` on the tablet and install it in Termux (`pkg install -y python git && pip install -e /data/swonal/packages/tonal-daemon`, which includes `swolectl`).
3. Copy `config.example.yaml` to `config.yaml` and configure your Bluetooth MAC addresses (`handle_left_mac`, `handle_right_mac`, `bar_control_mac`) and `daemon.server_url`.
4. Install the boot script into Magisk `service.d`:
   ```sh
   cp packages/tonal-daemon/start-daemon.sh /data/adb/service.d/99-swonal-daemon.sh
   chmod +x /data/adb/service.d/99-swonal-daemon.sh
   ```
5. Reboot and verify `curl http://<TONAL_IP>:8765/health`.

---

## Configuration & Integration Reference

### Canonical Units (`LB` / `KG`)
- Motor resistance commands and SQLite records (`SetLog.weightLb`, `PersonalRecord.maxWeightLb`, `WorkoutTemplate.blocksJson[].items[].targetWeightLb`) always store canonical **pounds (`5.0–200.0 lb`)**.
- Switching a user profile to `KG` dynamically converts UI dial values (`0.5 kg` / `2.5 kg` steps), PR charts, volume totals, and MCP `unit: "KG"` tool responses (`1 lb = 0.45359237 kg`).

### Phase 1.5 Bar Tilt Safety Cutout
- When `active_accessory == "BAR"` and `weight_enabled == True`, `MotorBridge._check_bar_tilt_safety()` monitors `abs(left_cable.position - right_cable.position)`.
- If the delta exceeds `bar_tilt_threshold_delta` (default `35.0`) continuously for `bar_tilt_dwell_ms` (`250 ms`), resistance is immediately disabled and a `{"type": "safety_event", "reason": "BAR_TILT_CUTOUT"}` alert is broadcast to the UI.

### Built-in MCP Server
- **HTTP SSE:** Connect AI agents (Claude Desktop, Home Assistant, custom agents) to `http://<SERVER_IP>:3000/mcp/sse`.
- **Stdio:** `npm --prefix packages/server-app run mcp:stdio`
- **Resources:** `swonal://exercises/catalog`, `swonal://users/{userId}/profile-and-prs`, `swonal://users/{userId}/workouts`
- **Tools:** `list_exercises`, `get_user_strength_history`, `create_custom_workout`, `update_workout_template`, `queue_workout_for_session`

### Spotify, `librespot`, & Community Music Providers
- Five adapters are registered out of the box (`spotify`, `youtube_music`, `apple_music`, `amazon_music`, `plexamp`) and selectable per profile in **Playlists & Devices**.
- Configure `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, and `SPOTIFY_REDIRECT_URI` (or `PUBLIC_URL` / Settings **Public Server URL Override**) in `.env`, then authorize via **Settings** or `GET /api/spotify/login` (`GET /api/spotify/callback`, `GET /api/spotify/status`, `GET /api/spotify/token`).
- Playback automatically prefers `librespot` (`"Tonal Gym"`) whenever it is online, falling back to the built-in **Spotify Web Playback SDK** (`"Swonal Web Player"`) in the browser or your preferred Connect device.
- Expand any playlist in **Playlists & Devices** to browse and play individual tracks (`GET /api/music/:provider/playlists/:playlistId/tracks`).
- Without Spotify credentials, `SpotifyProvider` operates in an interactive local simulation mode with `"Tonal Gym (librespot)"` device selection and automatic volume ducking (`duckVolume`) during set/PR audio cues.
- Set `PLEXAMP_URL` (e.g. `http://192.168.1.50:32500`) to forward transport controls to a headless Plexamp instance.

### Self-Hosted Exercise Form Loops (`/media`)
- All 50 seeded exercises include a `mediaUrl` pointing to animated SVG form loops served from `/media`.
- Drop custom `.mp4`, `.webm`, or `.gif` clips into `MEDIA_DIR` (defaults to `/data/media` or `packages/server-app/data/media`) and assign them via `PATCH /api/exercises/:id/media` to render in the kiosk Picture-in-Picture (PiP) **Form Demo** card.

### Home Assistant MQTT & Webhook Event Bus
- Set `MQTT_BROKER_URL` (plus optional `MQTT_USERNAME`, `MQTT_PASSWORD`, `MQTT_TOPIC_PREFIX=swonal`) and/or `HA_WEBHOOK_URL` (`HA_WEBHOOK_TOKEN`) to publish `swonal/state` (weight engagement, active lifter, `accentColor`, mode, rep count, safety faults) and `swonal/session` events (`set_logged`, `workout_queued`, `safety_cutout`).
- Inspect recent payloads and fire test events via `GET /api/integrations/ha/status`, `POST /api/integrations/ha/test`, or the **Hardware & Mock Controls** dialog.

### Security & Internet Access Lock
- Configure an app passcode in **Settings → Security & Internet Access Lock** (or set `SWONAL_AUTH_PASSCODE` in `.env`) to require login (`POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/status`, `GET /api/auth/token`, `POST /api/auth/configure`) for all remote/proxied requests and WebSocket `/ws` connections.
- Direct unproxied `localhost` kiosk requests can optionally bypass login while Cloudflare/LAN connections remain protected, and MCP SSE endpoints accept `?token=<apiToken>` or `Authorization: Bearer <apiToken>`.

### All-in-One Tonal Device Installer (`librespot`, APKs & Via Browser)
- Run `./scripts/install-device.sh --server-url https://swonal.brombomb.com` (add `--target <tonal-ip>:5555` for ADB over Wi-Fi) from your host machine to download and sideload **Magisk v27.0**, **Termux (Android 5/6)**, and **Via Browser**, install `librespot` (`"Tonal Gym"` boot service), deploy `tonal-daemon`, and launch Via Browser to your Swonal URL.
