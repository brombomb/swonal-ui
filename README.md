<div align="center">
  <img src="docs/swonal-logo.png" alt="swonal-ui Logo" width="112" />
  <h1>swonal-ui</h1>
  <p><strong>Open-Source Workout Controller, Hardware Motor Bridge, Decentralized P2P Social Feed, &amp; AI Addons for Rooted Tonal V1</strong></p>

  [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
  [![Node.js: 22+](https://img.shields.io/badge/Node.js-22+-green.svg)](https://nodejs.org/)
  [![Python: 3.11+](https://img.shields.io/badge/Python-3.11+-yellow.svg)](https://www.python.org/)
  [![Android: 6.0+ (API 23)](https://img.shields.io/badge/Android-6.0+_API_23-brightgreen.svg)](https://developer.android.com)
  [![GitHub Release](https://img.shields.io/github/v/release/brombomb/swonal-ui?include_prereleases&color=orange)](https://github.com/brombomb/swonal-ui/releases)
  [![CI](https://github.com/brombomb/swonal-ui/actions/workflows/ci.yml/badge.svg)](https://github.com/brombomb/swonal-ui/actions/workflows/ci.yml)
  [![Discord](https://img.shields.io/badge/Discord-Join%20Community-5865F2?logo=discord&logoColor=white)](https://discord.gg/hApx3nkryr)

  <br />
  <br />

  <img src="docs/screenshots/kiosk_live.png" alt="swonal-ui Live 60fps Kiosk on Rooted Tonal V1" width="380" />
  <br />
  <em>Native 60fps portrait workout controller running directly on rooted Tonal V1 hardware — zero cloud lock-in, zero subscription.</em>
</div>

---

`swonal-ui` frees and modernizes the rooted **Tonal 1 (V1)** digital-weight home gym. It replaces proprietary cloud lock-in with a **Standalone-First, Offline-Resilient** architecture running natively on the Tonal tablet — with an **optional Home / Cloud Sync Server** for power users:

> [!CAUTION]
> **Motorized Fitness Hardware Safety Warning (Beta Software):** `swonal-ui` directly controls physical motor resistance up to 200 lb on third-party hardware and is provided **AS IS** with no warranty. Always start with light weight (5–15 lb), verify your physical handle buttons and on-screen **WEIGHT OFF** fail-safe before lifting heavy, and never leave weight engaged unattended. You assume all risk of injury or hardware damage.

- **100% Standalone On-Device Kiosk (`:53013` Embedded LAN Server)**: Runs directly on the 1080x1920 portrait wall display with zero external server required. Backed by an on-device bundled SQLite database (50 built-in exercises + SVG form diagrams), native **Routine Builder**, local progressive overload, CSV/JSON/SQLite backups, and an embedded `:53013` Companion Web & WebSocket server right on the Tonal tablet.
- **Native 60fps Android Kiosk + In-App Auto-Updater**: Hardware-accelerated Canvas views rendered at 60fps, with automatic GitHub Releases update checks, human-readable **What's New** changelogs ([`CHANGELOG.md`](CHANGELOG.md)), and 1-tap silent root installation (blocked while weight is engaged for safety).
- **Bi-Directional 30Hz Motor Bridge**: Real-time strain-gauge load sensing, automatic rep counting, velocity-based training (VBT), live Tonal V1 arm orientation decoding (`H 1–12`, `R 1–3`, `A 1–5` with per-axis color coding and moving/locked detection), and Smart Handle / Bar Control Module (`0x2003`) button toggle support.
- **Routine Schedules & Free Lift Randomizer**: Assign weekdays (`S M T W T F S`) to any saved routine for 1-click **"Today's Routine"** loading right from the main screen, or tap the **`🎲`** dice button in the Free Lift Catalog to roll a random exercise.
- **Multi-Layer Motor Safety & Fail-Safe Fast Path**: Instant `<5ms` emergency motor disengage (`WEIGHT OFF`) over local loopback, automatic weight shutoff on workout/exercise switch, 45-second cable inactivity auto-cutoff (`INACTIVITY_AUTO_CUTOFF`), instant disengage on kiosk disconnect or daemon shutdown, serialized async motor command lock, and monotonic watchdog protection.
- **On-Device Music, AI Voice Coach & Decentralized Swocial**: Pair Tonal Speakers directly via on-device Spotify Keymaster PKCE (plus optional LAN Plexamp / YouTube Music / HA speakers), trigger hands-free AirPods voice commands & BYOK LLM coaching locally, publish Home Assistant webhooks directly over LAN, and sync peer-to-peer workouts/routines via Nostr (`Your Public Swocial Code`).
- **Mobile Companion Web App (`http://<TONAL_IP>:53013/companion`)**: Remote control, routine builder, workout history, and friends feed from any phone on your Wi-Fi network — served directly by the Tonal itself (no extra server needed!).
- **Optional Power-User Home / Cloud Sync Server (`server-app`)**: Optionally pair an always-on Docker / Node.js `server-app` in **Menu → Standalone & Home Server Sync** for 24/7 MCP agent workout queuing (even while Tonal sleeps), centralized Home Assistant MQTT autodiscovery, automated daily rotating off-device SQLite backups, and seamless offline fallback whenever unreachable.

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
| *Live arm height, rotation, and angle position indicators matching the target exercise configuration.* | *Picture-in-Picture (PiP) animated form loop demonstrating proper lifting technique and primary muscle activation.* |

| **AI Voice Coach & MCP Agent** *(Solar Amber)* | **Swocial Network & Feed** *(Emerald)* |
|:---:|:---:|
| <img src="docs/screenshots/kiosk_ai_coach.png" alt="AI Voice Coach & MCP Dialog" width="420" /> | <img src="docs/screenshots/kiosk_friends_feed.png" alt="Swocial Feed" width="420" /> |
| *Bring-Your-Own-Key AI Voice Coach (OpenAI, Anthropic, Gemini, Ollama) and live MCP server integration for autonomous workout programming.* | *Peer-to-peer fitness network: tap "Swocial" in the header or phone companion to share your Swocial QR code, celebrate friend PRs, send reactions (`💪`, `🔥`, `🏆`), and 1-tap import routines.* |

---

## Pre-Built Releases & Distribution

Because Tonal V1 is a **single, standardized, fixed hardware platform** (MediaTek MT8173, portrait 1080x1920, Android 6.0 Marshmallow with Magisk root), pre-built binaries are distributed directly — **no Android build tools or complex toolchains required**:

### 1. Pre-Built Android Kiosk APK (100% Standalone Default)
Download the latest `swonal-kiosk.apk` from [GitHub Releases](https://github.com/brombomb/swonal-ui/releases) and install via Wi-Fi ADB (or deploy all-in-one with `./scripts/install-device.sh --target <TONAL_IP>:5555`):

```bash
adb connect <TONAL_IP>:5555
adb install -r swonal-kiosk.apk
```

Everything works locally on the Tonal tablet with zero external server required — on-device SQLite DB (50 exercises + SVG diagrams), native Routine Builder, Spotify Keymaster PKCE speaker pairing, offline/BYOK AI Voice Coach, Home Assistant webhooks, Nostr Swocial, and the embedded `:53013` Companion Web UI (`http://<TONAL_IP>:53013/companion`).

### 2. Optional Pre-Built Sync Server Container (Power Users)
Run `server-app` optionally on your home server, NAS, or VPS if you want **24/7 always-on MCP workout queuing** (delivering routines even when the Tonal was asleep), centralized Home Assistant MQTT discovery, multi-device web access, and daily rotating SQLite backups:

```bash
docker run -d \
  --name swonal-server \
  --restart unless-stopped \
  -p 3000:3000 \
  -v swonal-data:/data \
  ghcr.io/brombomb/swonal-ui:latest
```

Once running, enter `http://<SERVER_IP>:3000` under **Menu → Standalone & Home Server Sync** on your Tonal (paired tablets sync idempotently via `/api/sync/*` and `/ws/sync`). Automated builds and releases are produced continuously by [.github/workflows/release.yml](.github/workflows/release.yml) whenever a release tag is pushed.

### Standalone (Serverless) vs. Optional Sync Server

| Capability | Standalone (Serverless — Default) | + Optional Home / Cloud Server (`server-app`) |
| --- | --- | --- |
| **Hardware Needed** | Just the rooted Tonal V1 tablet (zero external server) | Tonal V1 + any always-on Docker host, NAS, Raspberry Pi, or VPS |
| **Workouts, PRs & Analytics** | 100% local on-device SQLite (`BundledSQLiteDriver`, 50 exercises + 9 SVGs, full offline resilience) | Same on-device SQLite + bidirectional background sync (`/api/sync/*`, `/ws/sync`) |
| **Motor Control & Safety** | Direct local loopback (`127.0.0.1:8765`, `<5ms` `WEIGHT OFF` & dead-man's switch) | Identical local `<5ms` safety on Tonal (server never sits in the safety loop) |
| **Phone Companion & Web UI** | Served directly from Tonal's embedded `:53013` LAN server (`http://<TONAL_IP>:53013/companion`, `/history`, `/settings`) while Tonal is awake | Always-on web dashboard (`http://<SERVER_IP>:3000`) accessible even when Tonal is asleep, plus live telemetry relay |
| **Routine Builder** | Native Kiosk dialog + embedded `/settings` web UI | Native Kiosk + always-on web UI + remote creation via MCP |
| **AI Voice Coach** | Offline local heuristic coach + direct BYOK (OpenAI, Anthropic, Gemini, Ollama) from the tablet | Same on-device coach + server-side coach endpoint |
| **MCP (Claude / ChatGPT)** | Not always-on (tablet sleeps between workouts) | **24/7 always-on MCP server** (SSE & stdio) — LLMs inspect history/PRs and queue workouts anytime; routines auto-deliver when Tonal wakes |
| **Music (Spotify & LAN)** | On-device Spotify Keymaster PKCE speaker pairing (`librespot`), Plexamp, YTM Desktop, HA media players | Same on-device playback + server-side provider management |
| **Home Assistant** | Direct outbound LAN/HTTPS webhooks & REST service calls from the tablet | Full centralized **MQTT Discovery** (live sensors/entities) + webhooks |
| **Nostr Swocial** | On-device secp256k1/Schnorr signing & relay feed | Synced friends/profiles + server-side relay access |
| **Backups & Recovery** | Manual JSON/CSV export & restore via embedded `:53013` | **Automated daily rotating SQLite backups** (`VACUUM INTO`, 7-day retention) + 1-tap full restore to a replacement tablet |

---

## AI Addons for MCP & Voice

Swonal includes first-class AI extensibility designed for modern LLMs and agentic workflows:

### Model Context Protocol (MCP) Server (Optional Always-On Sync Server)
When running the optional `server-app` sync server, the built-in MCP server exposes live Tonal hardware telemetry, exercise catalogs, and lifter PRs directly to AI tools like **Claude Desktop**, **Cursor**, **Gemini CLI**, or custom Python agents — including **24/7 offline-Tonal queueing** (routines queued via MCP while the Tonal is asleep persist in `AppSetting.pending_queued_workout` and automatically appear on screen when Tonal wakes):

- **Transports**:
  - Streamable HTTP (`2025-03-26`, recommended): `http://<SERVER_IP>:3000/mcp`
  - Legacy HTTP Server-Sent Events (SSE): `http://<SERVER_IP>:3000/mcp/sse`
  - Local Stdio: `node packages/server-app/dist/server/mcp/stdio.js`
- **Resources**:
  - `swonal://exercises/catalog`: Complete library of movements, mechanics, and recommended arm positions.
  - `swonal://users/{userId}/profile-and-prs`: Lifter strength profiles, estimated 1RMs, and weight unit preferences.
  - `swonal://session/active`: Live in-progress workout session telemetry.
- **Tools**:
  - `create_workout_template`: Build personalized multi-block workout routines.
  - `queue_workout_for_session`: Queue an AI-programmed routine directly onto the Tonal screen (even offline, synced via `/ws/sync` & `/api/sync/pull`).
  - `update_weight`: Set precise target digital weight and training mode.
  - `log_set`: Automatically record completed sets with RPE and volume tracking.

### Real-Time Voice Coach & Hands-Free AirPods Control (Local & BYOK)
- **Hands-Free AirPods / Bluetooth Voice Mode**: Tap the prominent **AirPods Voice** button in the Kiosk header or Companion Remote to issue continuous hands-free commands while lifting:
  - **Weight Deltas & Targets**: *"Add 5 pounds"*, *"Increase weight by ten"*, *"Drop 5 pounds"*, *"Set weight to 50 pounds"* (supports both digits and spoken number words).
  - **Weight Engage / Disengage**: *"Weight on"*, *"Engage weight"*, *"Weight off"*, *"Rack weight"*.
  - **Dynamic Training Modes**: *"Switch to chains mode"*, *"Eccentric mode"*, *"Spotter mode"*, *"Burnout mode"*, *"Standard mode"*.
  - **Workouts & Exercises**: *"Next exercise"*, *"Previous exercise"*, *"Switch to Barbell Bicep Curl"*, *"Skip rest"*, *"Complete set"*.
- **Live Audio Encouragement & VBT Coaching**: Speaks real-time coaching cues directly into your AirPods on weight engage, rep milestones (first rep, halfway, final 2 reps, target reached), PR celebrations (*"Boom! New personal record!"*), and >=25% velocity drop fatigue alerts.
- **Built-In Offline Coach + BYOK LLM Support**: Works 100% offline out-of-the-box on the Tonal, or connect OpenAI, Anthropic, Google Gemini, or local Ollama directly from the tablet (API keys encrypted at rest with **Android Keystore AES-256-GCM** via `SecretCipher`, and `0600` encrypted storage if using the optional server).

---

## Getting Started

### Prerequisites

1. **Rooted Tonal V1 Tablet**: Running Android 6.0 Marshmallow with Magisk v27+ installed and active.
   - *If your device is not yet rooted, follow the step-by-step guide in [RUNBOOK-Tonal V1 Rooting & On-Device Setup Guide.md](RUNBOOK-Tonal%20V1%20Rooting%20&%20On-Device%20Setup%20Guide.md) using `mtkclient`.*
2. **Wi-Fi Connection**: Connected to your home network (for Phone Companion at `http://<TONAL_IP>:53013/companion`, Nostr relays, Spotify, and ADB install).
3. **Host Tools**: Android platform-tools (`adb`) for Standalone installation; `docker` or Node.js 22+ only needed if running the optional Sync Server or building from source.

---

### Step 1: Automated On-Device Installation (One Command — Standalone Default)

Swonal Kiosk runs **100% standalone** directly on the Tonal tablet with zero separate server required. Run [`scripts/install-device.sh`](scripts/install-device.sh) over Wi-Fi ADB:

```bash
./scripts/install-device.sh \
  --target <TONAL_IP>:5555 \
  --allow-debug
```

*(Optionally pass `--server-url http://<SERVER_IP>:3000` if you also run the optional Home Sync Server below, or pair it anytime from the Kiosk UI under **Menu → Standalone & Home Server Sync**.)*

#### What the Automated Installer Configures:
- **Native Fullscreen Kiosk (`:53013` Embedded LAN Server)**: Deploys Swonal Kiosk with on-device SQLite persistence, built-in Web SPA (`http://<TONAL_IP>:53013/companion`), Nostr Swocial, Spotify Keymaster PKCE, and native Routine Builder.
- **Embedded Browser Engine**: Prepares GeckoView ESR for high-performance rendering of `/history`, `/settings`, and `/admin` completely offline.
- **Hardware Motor Bridge**: Configures the high-speed background service (`:8765`) that controls motor resistance and streams real-time 30Hz cable telemetry.
- **Persistent Wireless ADB**: Keeps wireless access active across reboots so you can manage and update your Tonal without cables.
- **Automatic Storage Maintenance**: Cleans temporary installation files, caches, and logs automatically so your Tonal never runs low on disk space.
- **Fast Incremental Updates (`--sync`)**: Pushes only changed files in seconds when deploying updates:
  ```bash
  ./scripts/install-device.sh --target <TONAL_IP>:5555 --sync
  ```

---

### Step 2 (Optional Power Users): Deploy `server-app` Home / Cloud Sync Server

Only needed if you want **24/7 always-on MCP agent access**, centralized Home Assistant MQTT autodiscovery, automated daily rotating SQLite backups, or multi-device sync:

#### Option A: Docker Compose (Recommended)

```bash
git clone https://github.com/brombomb/swonal-ui.git
cd swonal-ui

# Create configuration
cp .env.example .env

# Launch container with persistent SQLite database + rotating backups
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

Verify that the dashboard opens cleanly at `http://<SERVER_IP>:3000`, then enter `http://<SERVER_IP>:3000` under **Menu → Standalone & Home Server Sync** on your Tonal. Once paired, `server-app` automatically syncs sessions, PRs, routines, and MCP queued workouts (`/api/sync/*` and `/ws/sync`), while Tonal seamlessly continues offline if your server reboots.

---

### Step 3: Everyday Use

Once installed, Swonal Kiosk automatically boots on the Tonal wall display:

- **Switching Between Swonal & Stock Tonal**: Tap the menu at the top right of the screen and choose **"Stock Tonal UI"**. A floating button allows you to return to Swonal at any time without rebooting.
- **Phone Companion & Swocial**: Open `http://<TONAL_IP>:53013/companion` on your smartphone (or scan the on-screen **Companion QR** modal) to queue exercises, adjust weight, build routines, view friends' workouts, and share routines directly over local Wi-Fi.
- **Hardware Safety**: Pressing **WEIGHT OFF** immediately cuts cable tension locally (`<5ms`). Weights also automatically disengage when switching workouts/exercises, when the kiosk app disconnects (or the `tonal-daemon` watchdog triggers — while remote `/companion` phone disconnects leave active Kiosk resistance untouched), or after 45 seconds of cable inactivity. Optional `DAEMON_API_TOKEN` authentication protects `:8765` across the LAN.
- **Remote Screen View**: View and control the Tonal display remotely from your computer using:
  ```bash
  ./scripts/scrcpy.sh
  ```
- **Application Settings**: Tap the gear icon to manage lifters and their saved workout programs, customize AI voice coach settings (BYOK & MCP server), and configure network preferences.

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

# 3. Verify Android Kiosk (JUnit 5 tests, embedded :53013 server, and release APK)
cd ../android-kiosk
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

---

## Community & Contributing

Join the community on Discord to share setups, workouts, and feedback: **[Join the Swonal Discord](https://discord.gg/hApx3nkryr)**

Contributions are warmly welcomed! Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `perf:`) so Release Please automatically bumps versions, updates `CHANGELOG.md`, and builds signed APK releases when merged to `main`.

Run the clean-room local CI script before pushing to verify all three suites and workflow files without touching your local `.env` or `swonal.db`:

```bash
./scripts/ci-local.sh
```

## License

This project is licensed under the [MIT License](LICENSE).  
*Tonal is a registered trademark of Tonal Systems, Inc. This independent open-source project is not affiliated with, sponsored by, or endorsed by Tonal Systems, Inc.*
