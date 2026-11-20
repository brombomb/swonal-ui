# swonal-ui

**Open-Source Workout Controller, Hardware Motor Bridge, Decentralized P2P Social Feed, & AI Addons for Rooted Tonal V1**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Node.js: 22+](https://img.shields.io/badge/Node.js-22+-green.svg)](https://nodejs.org/)
[![Python: 3.11+](https://img.shields.io/badge/Python-3.11+-yellow.svg)](https://www.python.org/)
[![Android: 6.0+ (API 23)](https://img.shields.io/badge/Android-6.0+_API_23-brightgreen.svg)](https://developer.android.com)
[![GitHub Release](https://img.shields.io/github/v/release/brombomb/swonal-ui?include_prereleases&color=orange)](https://github.com/brombomb/swonal-ui/releases)

`swonal-ui` frees and modernizes the rooted **Tonal 1 (V1)** digital-weight home gym. It replaces proprietary cloud lock-in with a completely open, self-hosted, offline-first ecosystem featuring:

- **Native 60fps Android Kiosk**: Hardware-accelerated Canvas views rendered directly on the 1080x1920 portrait wall display, with zero garbage-collection stutter.
- **Bi-Directional 30Hz Motor Bridge**: Real-time strain-gauge load sensing, automatic rep counting, velocity-based training (VBT), and arm orientation telemetry.
- **Fail-Safe Fast Path**: Instant `<5ms` emergency motor disengage (`WEIGHT OFF`) directly over local loopback socket, safeguarding lifters even during network loss.
- **Decentralized P2P Friends (Nostr)**: Self-sovereign gym buddy network powered by Nostr (secp256k1 `npub1...`). Share workouts, exchange routine templates, and send encouragement (`💪`, `🔥`, `🏆`) peer-to-peer across home NATs without central servers or tracking.
- **Mobile Companion Web App (`/companion`)**: Remote control, routine builder, friends feed, and live metrics from any smartphone on your local network.
- **AI Addons for MCP & Voice (Work in Progress)**: Autonomous workout programming via Model Context Protocol (MCP) agents (Claude, Cursor) and BYOK in-set voice coaching.
- **Home Assistant Integration**: Real-time MQTT discovery and webhook event bus for smart home automations.

---

## Visual Tour

*All screenshots captured at 1080x1920 portrait resolution on physical Tonal V1 hardware, highlighting multi-user personal accent themes.*

| **Live Workout Stage** *(Electric Lime)* | **Movement Catalog & Exercises** *(Vibrant Violet)* |
|:---:|:---:|
| <img src="docs/screenshots/kiosk_live.png" alt="Live Workout Stage" width="420" /> | <img src="docs/screenshots/kiosk_exercise_library.png" alt="Exercise Catalog" width="420" /> |
| *Real-time 30Hz load dial, symmetrical cable travel pucks, VBT velocity meter, rep counter, rest countdown, and quick weight steppers.* | *50+ built-in movements, target muscle groups, required accessories (Handles, Bar, Rope), and arm position presets.* |

| **Arm Alignment & Sensor Sync** *(Neon Cyan)* | **Form Demo & Biomechanics** *(Hot Coral)* |
|:---:|:---:|
| <img src="docs/screenshots/kiosk_arm_guide.png" alt="Arm Alignment Guide" width="420" /> | <img src="docs/screenshots/kiosk_form_demo.png" alt="Form Demo Preview" width="420" /> |
| *Live hardware readout of arm height, rotation, and angle sensors (`0x1020`/`0x1021`/`0x1025`) matching target exercise configuration.* | *Picture-in-Picture (PiP) animated SVG/video form loop demonstrating proper lifting technique and primary muscle activation.* |

| **AI Voice Coach & MCP Agent** *(Solar Amber)* | **Decentralized Friends Feed** *(Emerald)* |
|:---:|:---:|
| <img src="docs/screenshots/kiosk_ai_coach.png" alt="AI Voice Coach & MCP Dialog" width="420" /> | <img src="docs/screenshots/kiosk_friends_feed.png" alt="Friends Feed" width="420" /> |
| *Bring-Your-Own-Key AI Voice Coach (OpenAI, Anthropic, Gemini, Ollama) and live MCP server integration for autonomous workout programming.* | *Decentralized peer-to-peer social feed via Nostr: celebrate friend PRs, send reactions (`💪`, `🔥`, `🏆`), and 1-tap import routines.* |

---

## Pre-Built Releases & Distribution

Because Tonal V1 is a **single, standardized, fixed hardware platform** (MediaTek MT8173, portrait 1080x1920, Android 6.0 Marshmallow with Magisk root), pre-built binaries are distributed directly — **no Android build tools or complex toolchains required**:

### 1. Pre-Built Android Kiosk APK
Download the latest `swonal-kiosk.apk` from [GitHub Releases](https://github.com/brombomb/swonal-ui/releases) and install via Wi-Fi ADB:

```bash
adb connect <TONAL_IP>:5555
adb install -r swonal-kiosk.apk
```

### 2. Pre-Built Server Container
Run the server app instantly on your home server, NAS, or desktop using Docker:

```bash
docker run -d \
  --name swonal-server \
  --restart unless-stopped \
  -p 3000:3000 \
  -e TONAL_DAEMON_URL=http://<TONAL_IP>:8765 \
  -e TONAL_DAEMON_WS_URL=ws://<TONAL_IP>:8765/ws \
  -v swonal-data:/app/data \
  ghcr.io/brombomb/swonal-ui:latest
```

Automated builds and releases are produced continuously by the [.github/workflows/release.yml](.github/workflows/release.yml) CI/CD pipeline whenever a release tag is pushed.

---

## AI Addons for MCP & Voice (Work in Progress)

Swonal includes first-class AI extensibility designed for modern LLMs and agentic workflows:

### Model Context Protocol (MCP) Server
The built-in MCP server exposes live Tonal hardware telemetry, exercise catalogs, and lifter PRs directly to AI tools like **Claude Desktop**, **Cursor**, **Gemini CLI**, or custom Python agents:

- **Transports**:
  - HTTP Server-Sent Events (SSE): `http://<SERVER_IP>:3000/mcp/sse`
  - Local Stdio: `node packages/server-app/dist/server/mcp/stdio.js`
- **Resources**:
  - `swonal://exercises/catalog`: Complete library of movements, mechanics, and recommended arm positions.
  - `swonal://users/{userId}/profile-and-prs`: Lifter strength profiles, estimated 1RMs, and weight unit preferences.
  - `swonal://session/active`: Live in-progress workout session telemetry.
- **Tools**:
  - `create_workout_template`: Build personalized multi-block workout routines.
  - `queue_workout_for_session`: Queue an AI-programmed routine directly onto the Tonal screen.
  - `update_weight`: Set precise target digital weight and training mode.
  - `log_set`: Automatically record completed sets with RPE and volume tracking.

### Real-Time Voice Coach (BYOK)
- Connect any OpenAI, Anthropic, Google Gemini, or local Ollama model (`0600` encrypted server storage).
- Real-time in-set coaching tips, velocity loss warnings, and automatic progressive overload recommendations based on actual cable strain gauge telemetry.
- *(Note: Voice recognition and latency optimizations are actively evolving as a work-in-progress).*

---

## Getting Started

### Prerequisites

1. **Rooted Tonal V1 Tablet**: Running Android 6.0 Marshmallow with Magisk v27+ installed and active.
   - *If your device is not yet rooted, follow the step-by-step guide in [RUNBOOK-Tonal V1 Rooting & On-Device Setup Guide.md](RUNBOOK-Tonal%20V1%20Rooting%20&%20On-Device%20Setup%20Guide.md) using `mtkclient`.*
2. **Wi-Fi Connection**: Both your host server/laptop and the Tonal tablet connected to the same local network.
3. **Host Tools**: `docker` (or Node.js 22+ & Python 3.11+), and Android platform-tools (`adb`).

---

### Step 1: Deploy `server-app` (Your Home Server)

Run `server-app` on any local server, Raspberry Pi, NAS, or desktop on your network (e.g. `192.168.1.100` or a local domain):

#### Option A: Docker Compose (Recommended)

```bash
git clone https://github.com/brombomb/swonal-ui.git
cd swonal-ui

# Create configuration
cp .env.example .env

# Launch container with persistent SQLite database
docker compose up -d --build server-app
```

#### Option B: Bare-Metal Node.js

```bash
cd packages/server-app
npm install
cp ../../.env.example .env
npx prisma db push
npx tsx prisma/seed.ts
npm run build
npm start
```

Verify that the dashboard opens cleanly at `http://<SERVER_IP>:3000`.

---

### Step 2: Automated On-Device Installation (One Command)

The automated script [`scripts/install-device.sh`](scripts/install-device.sh) handles the entire tablet deployment over Wi-Fi ADB:

```bash
./scripts/install-device.sh \
  --target <TONAL_IP>:5555 \
  --server-url http://<SERVER_IP>:3000 \
  --allow-debug
```

#### What the Automated Installer Configures:
- **Native Kiosk App**: Deploys `com.swonal.kiosk` pre-configured to your server.
- **Persistent Wireless ADB**: Configures `/data/adb/service.d/00-swonal-adbd.sh` to extract the Wi-Fi IP and keep port `5555` alive across all reboots.
- **Hardware Serial Protection**: Restricts `/dev/ttyACM0` strictly to `0600 root:root` and stops stock Tonal MCB services from locking the motor controller.
- **Magisk Root Pre-Grant**: Automatically pre-grants permanent superuser rights (`policy=2`, `notification=0`) to eliminate notification toasts.
- **Hardware Motor Bridge**: Deploys `tonal-daemon` into Termux and sets up background auto-start services on device boot.

---

### Step 3: Everyday Use

Once installed, Swonal Kiosk automatically boots on the Tonal wall display:

- **Switching to Stock Tonal & Back**: Tap the quick-action menu at the top right of the screen and choose **"Exit to Stock Tonal"**. The daemon releases `/dev/ttyACM0` and launches stock software with a discreet floating overlay pill. Tapping the pill returns instantly to Swonal Kiosk without rebooting.
- **Mobile Remote Control & Social Feed**: Open `http://<SERVER_IP>:3000/companion` on your smartphone (or scan the on-screen QR code) to queue exercises, adjust weight, view your friends' workout feed, react with motivation (`💪`, `🔥`, `🏆`), or import shared routines with one tap.
- **Local Fail-Safe Protection**: If Wi-Fi ever drops mid-set, tapping **WEIGHT OFF** bypasses the network entirely via local loopback socket (`127.0.0.1:8765`), dropping cable tension in `<5ms`.
- **Dedicated Application Settings**: Navigate to `/settings` or tap the gear icon to configure Lifters, Friends & P2P Nostr Identity, BYOK AI Coach, Network overrides, and Security/Data backups.

---

## Local Development (Without Hardware)

You can run and test the complete ecosystem on any macOS, Linux, or Windows (WSL2) laptop:

```bash
# 1. Start the 30Hz Mock Hardware Simulator
cd packages/tonal-daemon
source .venv/bin/activate
pip install -e .
pytest -v
python3 -m src.main --mock

# 2. In another terminal, start the Server App
cd packages/server-app
npm install
npx prisma db push && npx tsx prisma/seed.ts
npm test
npm run dev

# 3. In another terminal, verify End-to-End communication
node scripts/verify-e2e.mjs
```

---

## Community & Contributing

Contributions are warmly welcomed! Please ensure all test suites pass before submitting pull requests:

```bash
npm --prefix packages/server-app test
packages/tonal-daemon/.venv/bin/pytest packages/tonal-daemon/tests/test_daemon.py
(cd packages/android-kiosk && ./gradlew :app:testDebugUnitTest)
```

## License

This project is licensed under the [MIT License](LICENSE).  
*Tonal is a registered trademark of Tonal Systems, Inc. This independent open-source project is not affiliated with, sponsored by, or endorsed by Tonal Systems, Inc.*
