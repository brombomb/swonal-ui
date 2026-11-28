# swonal-ui Implementation Progress

## Current Status
# swonal-ui Implementation Progress

## Current Status
- **Active Milestone:** Milestone 8 Complete — Standalone-First Architecture + Optional Home/Cloud Server Sync
- **Last Completed Task:** Embedded `:3000` Kiosk server, native SQLite engine (`BundledSQLiteDriver`), native Routine Builder, Spotify PKCE, local AI Voice Coach, Nostr Swocial, Home Assistant webhooks, idempotent `/api/sync/*` + `/ws/sync` server sync, and security/audit hardening on `feat/standalone-first-sync`
- **Next Immediate Task:** None — ready for review/merge
- **Verification / Test Status:** Pending final `./scripts/ci-local.sh` & `:app:assembleRelease` verification pass after audit hardening

---

## Step 0: Persistent Record & Task Keeping Protocol
- [x] Create `.gitignore`
- [x] Create `PROGRESS.md`
- [x] Create `DECISIONS.md`

## Milestone 1: Monorepo Scaffolding, Python `packages/tonal-daemon`, & Mock Hardware Engine
- [x] Scaffold `packages/tonal-daemon` directory, `pyproject.toml`, `config.example.yaml`, and `start-daemon.sh`
- [x] Implement `packages/tonal-daemon/src/mock_motor.py` (30Hz sine-wave cable pulls, dynamic modes, rep counting, arm positions, bar tilt injection)
- [x] Implement `packages/tonal-daemon/src/motor_bridge.py` (`swolectl` wrapper, safety bounds `5-200 lb`, heartbeat watchdog, Phase 1.5 `_check_bar_tilt_safety`)
- [x] Implement `packages/tonal-daemon/src/ble_handles.py` (`bleak` BLE Smart Handles/Bar listener + single/double/long press + mock simulation)
- [x] Implement `packages/tonal-daemon/src/mode_switcher.py` (Stock <-> Custom Android handoff via `su` / mock transitions)
- [x] Implement `packages/tonal-daemon/src/main.py` (FastAPI + WebSocket hub on `:8765`, REST endpoints, mock debug endpoints)
- [x] Write and verify `packages/tonal-daemon/tests/test_daemon.py` (`pytest`)
- [x] Commit Milestone 1 (`feat(milestone-1): ...`)

## Milestone 2: Database Layer, Seed Catalog, & Pluggable Music Provider Architecture
- [x] Initialize `packages/server-app` (`package.json` with Prisma `^5.22.0`, `tsconfig.json`, `.env`)
- [x] Implement `packages/server-app/prisma/schema.prisma` (canonical `lb` storage) and run `prisma db push`
- [x] Implement `packages/server-app/prisma/seed.ts` (48+ exercises, Alex & Taylor profiles, PRs, templates) and run seed
- [x] Implement `src/server/db.ts` and `src/server/utils/units.ts` (`LB` <-> `KG` conversion)
- [x] Implement Pluggable Music Provider Architecture (`MusicProvider.ts`, `Registry.ts`, `SpotifyProvider.ts`, `YouTubeMusicProvider.ts`, `AppleMusicProvider.ts`)
- [x] Write and verify Milestone 2 unit tests (`vitest`)
- [x] Commit Milestone 2 (`feat(milestone-2): ...`)

## Milestone 3: Built-in Model Context Protocol (MCP) Server Layer
- [x] Implement `src/server/mcp/mcpServer.ts` (3 resources & 5 tools with `LB`/`KG` conversion, HTTP SSE + `stdio`)
- [x] Implement `src/server/index.ts` and REST/WebSocket proxy routes (daemon WS relay, session state sync, partner rotation, music & daemon REST proxies, `/mcp/sse`)
- [x] Write and verify Milestone 3 MCP & API unit tests (`vitest`)
- [x] Commit Milestone 3 (`feat(milestone-3): ...`)

## Milestone 4: Athletic Design System & 1080x1920 Portrait Kiosk UI (`/`)
- [x] Configure Vite, Tailwind CSS, and Adaptive Theme Engine (`[data-theme="dark"]`, `[data-theme="light"]`, dynamic user accent CSS vars)
- [x] Implement Web Audio synthesized workout sound engine (`soundEngine.ts`) with music ducking
- [x] Implement `useSwonalState.ts` real-time state, telemetry, partner rotation, and unit hook
- [x] Build `KioskView.tsx` (`1080x1920` Top Status Bar, Center Workout Stage with 360px SVG dial, ROM bars, Arm Position Guide, Routine Selector, Mock Diagnostic Drawer, Persistent Music Dock)
- [x] Verify TypeScript (`tsc --noEmit`) and production frontend build (`npm run build`)
- [x] Commit Milestone 4 (`feat(milestone-4): ...`)

## Milestone 5: Mobile Companion Web View (`/companion`) & Real-Time Sync
- [x] Build `CompanionView.tsx` (`/companion` responsive Live Remote Control, Couch Workout Builder, Send to Tonal, Strength History & PRs)
- [x] Wire dual-layout routing (`/`, `/companion`) in `App.tsx`
- [x] Verify TypeScript (`tsc --noEmit`) and production build (`npm run build`)
- [x] Commit Milestone 5 (`feat(milestone-5): ...`)

## Post-Milestone Hardening: Physical Hardware Integration & Reboot Persistence
- [x] Restored customized user profiles ("Rob" and "Jeri") and updated `packages/server-app/prisma/seed.ts` upsert (`update: {}`) to prevent wiping customized users on server restart.
- [x] Fixed Python 3.8 / Termux runtime compatibility in `packages/tonal-daemon`:
  - Added `from __future__ import annotations` across daemon source modules.
  - Replaced Python 3.10+ pipe union syntax with `typing.Union`.
  - Added Pydantic v1 `model_dump = dict` shim for backwards compatibility.
  - Added loop executor `_to_thread` fallback for Python < 3.9.
  - Bundled pure-Python `swolectl` without `slots=True` and with `StrEnum` fallback.
- [x] Resolved `/dev/ttyACM0` serial port locking:
  - Added `am force-stop com.riptlabs.mcb.service` and `am force-stop com.tonal.app` to `start-daemon.sh` and `/data/adb/service.d/98-swonal-daemon.sh` to allow swonal-daemon exclusive access to the motor controller.
- [x] Fixed `mock_mode` telemetry parsing and dynamic health polling:
  - Fixed property mismatch in `KioskView.tsx`, `CompanionView.tsx`, and `AdminView.tsx` where `mock_mode` from the daemon `/health` endpoint was previously ignored in favor of `mock`.
  - Added 5s interval polling for daemon health in `KioskView.tsx` and `CompanionView.tsx` so reconnects after daemon restarts dynamically update the hardware status pill.
- [x] Application Settings Dark Theme & Performance Optimizations:
  - Eliminated white flash before page load: Added `<meta name="color-scheme" content="dark" />`, inline dark canvas styles (`#06090e`), and `#initial-loader` in `index.html`.
  - Accelerated Settings loading time: Converted `App.tsx` routes to dynamic lazy code-splitting (`React.lazy()` / `Suspense`), reducing the initial JavaScript payload for `/settings` by over 50% (from 424kB down to 181kB core + 47kB SettingsView chunk).
  - Added background pre-warming of `GeckoRuntime` in `SwonalApp.onCreate()` and native themed dark loading overlay in `SwonalWebActivity.kt`.
  - Pre-granted permanent Magisk root policy (`policy: 2`, `notification: 0`) to `com.swonal.kiosk`, clearing stuck denial toasts.
- [x] Hardened reboot persistence and hardware isolation:
  - Installed persistent Wireless ADB watchdogs in `/data/adb/post-fs-data.d/00-swonal-adbd.sh` and `/data/adb/service.d/00-swonal-adbd.sh` (`persist.adb.tcp.port 5555`).
  - Added early suppression of stock Tonal MCB motor service (`pm disable com.riptlabs.mcb.service` and `am force-stop`) so stock apps cannot hijack `/dev/ttyACM0` on boot.
  - Set hardware serial permissions strictly to `0600 root:root` so non-root apps cannot open `/dev/ttyACM0`.
  - Updated `mode_switcher.py` to enable MCB only when transitioning to stock mode, and disable/re-lock on return to custom mode.
  - Verified full reboot recovery over Wi-Fi ADB: tablet boots, port 5555 auto-connects, `tonal-daemon` claims `/dev/ttyACM0` (`mock_mode: false`), and kiosk UI displays `• TONAL CDC 30Hz` with household profiles intact.
- [x] Settings View 5 Dedicated Tabs & Music/Audio Refinements:
  - Organized `SettingsView.tsx` into 5 clean, focused tabs: `Users` (lifter profiles, units LB/KG, accent colors), `Music` (Spotify Connect, Plexamp, YTM, Apple, Amazon), `AI` (BYOK Voice Coach), `Network` (LAN IPs, Companion QR override, MCP SSE endpoint, Home Assistant event bus), and `Security` (passcode lock, localhost bypass, API tokens, database export/backups).
  - Cleaned up internal `librespot` branding across Android kiosk UI and server-app web views, replacing user-facing labels with "Tonal Speakers" / "Built-in Speakers".
  - Fixed Tonal Zeroconf mDNS discovery for on-device Spotify receiver by adding persistent kernel multicast route (`224.0.0.0/4 dev wlan0`) and ALSA config exports.
  - Attenuated screen tap sounds: replaced deafening stock Android click effects with a subtle `0.20f` `Cue.TAP` synthesizer in `SwonalSoundEngine`, routed through `USAGE_MEDIA` to track media volume (33% device volume produces soft, comfortable tactile clicks), and synchronized `STREAM_SYSTEM` with `STREAM_MUSIC`.
  - Handled Spotify Web API 429 rate-limiting with exponential backoff (`Retry-After`), persisted playlists to SQLite metadata cache, and expanded curated fallback workout playlists.
- [x] Verified live physical Tonal hardware connection:
  - Native tablet kiosk shows `• TONAL CDC 30Hz` in magenta.
  - Tonal daemon (`192.168.0.140:8765`) reports `mock_mode: false` bound to `/dev/ttyACM0`.
  - All test suites passing (`pytest` 9/9, `vitest` 17/17, Gradle unit tests 26/26).
  - Implemented `_to_thread` async helper fallback using `loop.run_in_executor` for Python < 3.9.
  - Bundled pure-Python `swolectl` with Python 3.8-compatible dataclasses (removed `slots=True`) and `StrEnum` fallback.
- [x] Hardened Magisk `service.d` boot scripts on the Tonal V1 tablet (`192.168.0.140`):
  - Fixed boot loop check from `[ sys.boot_completed -eq 1 ]` to safe string comparison `[ sys.boot_completed != "1" ]` in both `98-swonal-daemon.sh` and `99-swonal-librespot.sh`.
  - Added persistent Wi-Fi ADB retention (`setprop service.adb.tcp.port 5555; stop adbd; start adbd`).
  - Added pre-startup force-stop of `com.tonal.app` and `com.riptlabs.mcb.service` to release exclusive file locks on `/dev/ttyACM0`.
  - Set root environment variables (`HOME`, `PREFIX`, `LD_LIBRARY_PATH`, `PATH`, `PYTHONHOME`) in `start-daemon.sh`.
- [x] Updated `BootReceiver.kt` in `android-kiosk` to ensure Wi-Fi ADB port 5555 is retained on `BOOT_COMPLETED`.
- [x] Verified live physical Tonal motor telemetry streaming at 30Hz (`TONAL CDC 30Hz`) and active `librespot` ALSA sink.

## Milestone 7: Decentralized Peer-to-Peer Friends System (Nostr Protocol)
- [x] Update Prisma schema (`User.nostrPrivKeyHex`, `nostrPubKeyHex`, `nostrNpub`, `nostrRelays`, `shareWorkouts`, and new `Friend`, `FriendActivity` models).
- [x] Install & integrate `nostr-tools` (`^2.10.4`) with Schnorr signatures (`secp256k1`) and NIP-19 npub encoding.
- [x] Implement `nostrFriendsService.ts` (identity lifecycle, relay pool broadcasting, event signing, relay querying with timeout fallback, routine import/share, Kind 7 reactions).
  - Custom Event Kind `30101`: Workout Completion Summary (volume, sets, reps, duration, PRs).
  - Custom Event Kind `30102`: Routine Template Share (importable to local library).
  - Standard Event Kind `7`: Reaction (`💪`, `🔥`, `🏆`).
- [x] Add REST routes (`/api/friends/...`) and auto-publish on workout completion.
- [x] Add dedicated "Friends" tab in `SettingsView.tsx` (Npub display, 1-click copy, friend roster manager, relay health badges).
- [x] Restore and enhance "Music" tab in `SettingsView.tsx` (Spotify OAuth redirect copy, provider configs).
- [x] Add "Friends" tab in Mobile Companion (`CompanionView.tsx`) with real-time peer feed, reaction counters, and 1-tap routine import.
- [x] Add live motivational friend ticker to rest-timer overlay in Kiosk (`KioskView.tsx`).
- [x] Add automated unit tests (`tests/friends.test.ts` - 12 tests, 100% pass; 29/29 Vitest total).
- [x] Update `README.md` and `PROGRESS.md`.

## Milestone 8: Standalone-First Architecture + Optional Home/Cloud Server Sync (`feat/standalone-first-sync`)
- [x] **On-Device Persistence & Engine (Checkpoint 1):** Bundled Android SQLite (`androidx.sqlite.driver.bundled.BundledSQLiteDriver`) with 50 seeded exercises + 9 bundled SVG diagrams (`seed_catalog.json`), Android API 23 (`minSdk = 23`) compatibility (`CompatUtil`, `IsoTime`, `SecretCipher`, `CryptoCompat`), `LocalWorkoutEngine` (dynamic cable physics, PR tracking, analytics, CSV export, backup/restore), and `LocalDaemonFailSafeClient` (`127.0.0.1:8765`).
- [x] **On-Device Integrations & Native Dialogs (Checkpoint 2):** Native `LocalAiCoachEngine` (regex voice parser, workout insights, BYOK cloud/Ollama endpoints), `LocalHomeAssistantClient` webhooks, `LocalNostrSwocialEngine` (`NostrCrypto` secp256k1 + Schnorr + NIP-19), `LocalSpotifyController` (PKCE speaker pairing), and `LocalLanMusicProviders` (Plexamp/AirPlay/Chromecast).
- [x] **Embedded `:3000` Server & Native Routine Builder (Checkpoint 3):** Embedded NanoWSD HTTP + `/ws` server inside `android-kiosk` (`EmbeddedSwonalServer` + `EmbeddedApiRouter`) serving the bundled Vite React SPA (`/companion`, `/history`, `/settings`) plus native `RoutineBuilderDialog` and `ServerSyncSettingsDialog`.
- [x] **Optional Home/Cloud Server Sync & 24/7 MCP Queue (Checkpoint 4):** Bidirectional idempotent sync (`/api/sync/push`, `/api/sync/pull`, `/api/sync/restore-bundle`, `/api/sync/ack-queued-workout`, `/ws/sync`), Prisma tombstone (`deletedAt`) & monotonic `updatedAt` sync metadata, rotating SQLite backups (`backupService.ts`), persistent MCP `pending_queued_workout` delivery across offline Tonal reboots, and `scripts/install-device.sh` `--standalone` / `--server-url` installer flow.
- [x] **Adversarial Audit Remediation & Docs (Checkpoint 5):** API 24+ -> API 23 fixes, R8 serializer keep rules, CSWSH Origin validation on `/ws` & `/ws/sync`, bounded chunked body reader, remote `/ws` safety guards, Keystore failure safety, SSRF host filtering, sync FK/PR merge/ACK race fixes, and repository documentation updates.

### Follow-Up TODOs (Post-Standalone PR #3)
- [x] **Native Android Screens for Everything Except `/companion`:** Added native `HistoryDialog`, `AppSettingsDialog`, and `AdminDialog` inside `packages/android-kiosk` (and removed eager GeckoView startup warmup), while preserving the always-on web `/history` & `/companion` views on `server-app`.
- [x] **MCP Streamable HTTP (`2025-03-26`) Transport:** Added stateful `POST`/`GET`/`DELETE /mcp` Streamable HTTP transport (`StreamableHTTPServerTransport`) alongside legacy `/mcp/sse` (`2024-11-05`) in `packages/server-app` so modern MCP clients connect directly without `mcp-remote`.
- [x] **Optional On-Device HTTPS for Companion (`:53443`):** Added opt-in on-device HTTPS listener (`LocalTlsCertManager` + `SwonalApp.reconfigureLocalHttps` + toggle in `AppSettingsDialog`) with a local Root CA, LAN IP SANs, and downloadable `GET /api/tls/ca.crt` profile for mobile browser mic/Web Speech support without a reverse proxy.
- [x] **Bundled Offline Voice STT (Vosk over `AudioRecord`):** Integrated `vosk-android` (`OfflineVoiceRecognizer`) over 16 kHz `AudioRecord` so the Kiosk's inline **Voice** toggle works 100% natively on stock AOSP 6.0 (API 23) Tonal V1 ROMs without Google Speech Services (downloads the ~40 MB English model once into `filesDir` with zip-slip protection, then runs offline).
- [x] **Copy Block in Routine Builders:** Added a Copy button to each block in the Kiosk `RoutineBuilderDialog` and the Companion routine builder that inserts a deep copy (block type, rest, and every exercise with its sets/reps/weight/mode/modifier) directly below the original for faster routine creation.
- [ ] **Local AI Coach Deep-Dive & Docs (`how does local AI work`):** Document how `LocalAiCoachEngine` works out of the box (offline regex command parser + local VBT/progression heuristics vs. BYOK OpenAI/Anthropic/Gemini/Ollama) and evaluate richer local/on-device LLM options.
- [ ] **Tonal V1 Hidden MCB Control Modes (Upstream `swolectl` PR):** Confirmed `CONTROL_MODE_FLYWHEEL` (`FLYWHEEL_FLUID`/`FLYWHEEL_MASS`, `flywheel_mass`/`flywheel_drag`), `CONTROL_MODE_ISOKINETIC`, and `CONTROL_MODE_PILATES` (`pilates_max_weight`/`pilates_spring_constant`) exist inside Tonal V1's `com.riptlabs.mcb.service` (`libmcb-service.so`). Decode their exact MCB serial packet formats, verify safely at 5–10 lb, and upstream to `swolectl` before exposing in Swonal.
- [ ] **Extended `0x0013` ROM Weight Curve Profiles (Upstream `swolectl` PR):** `swolectl` currently exposes `flags` curve `0` (Chains) and `4` (Smart Flex / Flat w/ Eccentric). Investigate, low-weight test, and upstream the remaining MCB `rom_weight_mode` curves (`1` Bell w/ Eccentric, `2` Reverse Chains w/ Eccentric, `3` Chains w/ Eccentric, `5` Quadratic Ascending w/ Eccentric, `6` Perturbation, `7` Reverse Chains, `8` Eccentric Reduction).
- [ ] **Companion Video Form Guide AI:** Leverage the Phone Companion camera (over HTTPS `:53443`) for live pose estimation & AI form-check coaching during sets (rep depth, bar path, posture cues).
