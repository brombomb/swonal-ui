# Agent Guide (`agent.md`)

Context, hardware constraints, design decisions, and operational playbooks for continuing work on **swonal-ui**.

---

## 1. Repository Architecture

- **`packages/android-kiosk`** (`com.swonal.kiosk`, Kotlin, Classic Android Views + XML/ViewBinding + Custom `Canvas`, `minSdk = 23`, `targetSdk = 34`, `compileSdk = 35`, `arm64-v8a`)
  - **Standalone-First Core (Default):** Runs 100% serverlessly on the Tonal V1 wall screen (`1080x1920`, `Physical density: 213dpi`) and supports optional Home/Cloud Server Sync via `CloudSyncManager` (`com.swonal.kiosk.sync`).
  - **On-Device Persistence (`SwonalDatabase`, `androidx.sqlite:sqlite-bundled` 2.5.1):** Bundled SQLite 3.46+ with WAL mode, seeded from `assets/seed/seed_catalog.json` (50 exercises) + 9 SVG form diagrams (`assets/media/`), plus sync tombstones (`deleted_at_ms`), `VACUUM INTO` backups, and **Android Keystore AES-256-GCM** encryption (`SecretCipher`).
  - **On-Device Engines:** `LocalWorkoutEngine` (sessions, PRs, progressive overload, Partner Work-In, CSV/JSON exports, PBKDF2 passcode lock), `LocalAiCoachEngine` (voice commands + offline coach + BYOK LLMs), `LocalSpotifyController` (Keymaster S256 PKCE + `:8765` librespot provisioning + 429 backoff), `LocalLanMusicProviders`, `LocalNostrSwocialEngine` (pure-Kotlin BIP-340 Schnorr + NIP-19 in `NostrCrypto`), and `LocalHomeAssistantClient`.
  - **Embedded `:3000` Server (`EmbeddedSwonalServer` / `EmbeddedApiRouter` via `NanoWSD`):** Serves the Vite-compiled React SPA bundled in `assets/web/` (synced by Gradle `syncWebAssets` from `packages/server-app/dist/client`), `/api/*` REST parity, `/ws` Companion socket (never disengaging weight when a phone disconnects while Kiosk UI heartbeat is alive), and path-traversal-guarded `/media/*`.
  - **Native 60fps Hot Path & Dialogs:** Separates 30Hz telemetry (`telemetryFlow`, `vbtFlow`) from discrete UI state (`uiStateFlow`); includes native `RoutineBuilderDialog` and `ServerSyncSettingsDialog`; embeds **Mozilla GeckoView 140 ESR (`arm64-v8a`)** in `SwonalWebActivity` for `/history`, `/settings`, and `/admin`; includes `StockOverlayService` (`TYPE_SYSTEM_ERROR`, `RootShell`) LockTask escape hatch and `LocalDaemonFailSafeClient` (`127.0.0.1:8765`).
  - **Android 6.0 (API 23) Constraints:** Never use `java.time.*`, `java.util.Base64`, or API 24+ `Map.getOrDefault`/`computeIfAbsent` in `packages/android-kiosk` — use `CompatUtil` (`Base64Compat`, `IsoTime`, `CryptoCompat`). All external HTTPS/WSS OkHttp clients must wire `SwonalTlsConfig` (`@raw/swonal_extra_roots`), and all `@Serializable` packages must be kept in `app/proguard-rules.pro`.
- **`packages/tonal-daemon`** (Python 3.11+, FastAPI + WebSockets on port `8765`)
  - Runs on the Tonal V1 under Termux + Magisk `service.d` (`start-daemon.sh`).
  - Bridges `/dev/ttyACM0` via `swolectl` (`from swolectl import Controller, SafetyPolicy` in `src/motor_bridge.py`, reading live `telemetry`, `cart_position` `0x1020`, `column_rotation` `0x1021`, and `arm_positions` `0x1025` including lock-pin flags), BLE accessories (`bleak`), librespot speaker control (`/api/spotify/*`), and Stock <-> Custom mode switching.
  - Supports `--mock` mode (`python -m src.main --mock`) for offline 30Hz simulation on a dev machine.
  - `swolectl` is **not on PyPI** — it is specified in `packages/tonal-daemon/pyproject.toml` as `swolectl @ git+https://github.com/d4l3k/swolectl.git`.
- **`packages/server-app`** (`@swonal-ui/server-app`, Node 22 / Express / Prisma SQLite / Vite + React 18 / Tailwind CSS, port `3000`)
  - **Optional Power-User Sync Server & SPA Build Source:** Its compiled Vite React UI (`dist/client/`) is bundled directly into the Android Kiosk APK (`assets/web/`). When deployed optionally as an always-on Docker/Node server, it provides bi-directional `/api/sync/*` (LWW, tombstones, max-envelope PRs, idempotent `SetLog`, exercise name remap) + `/ws/sync`, 24/7 MCP queueing (`AppSetting.pending_queued_workout`), HA MQTT autodiscovery, and daily rotating `VACUUM INTO` backups (`backupService.ts`).
  - Canonical weight storage is always **pounds (`LB`, `5.0–200.0 lb`)**; `KG` conversion (`1 lb = 0.45359237 kg`) happens at display/API boundaries; velocity displays in **`in/s`** (`cm/s / 2.54`) for `LB` and **`cm/s`** for `KG`.
- **`packages/device-assets`**
  - Tracked APKs (< 100 MB GitHub limit):
    - `termux-android5-v0.119.0-beta.3.apk` (`77.87 MB`, Android 5/6 universal Termux)
  - Ignored in `.gitignore` (> 100 MB): `bromite-108-*.apk` (`176 MB`, downloaded on-demand by `scripts/install-device.sh`).
  - Use preinstalled AOSP `com.android.launcher3` on the device as the Home launcher (supports PWA shortcuts).
- **`scripts/install-device.sh`**
  - All-in-one ADB installer for Termux, Bromite 108, `librespot` (`99-swonal-librespot.sh`), and `tonal-daemon` (`98-swonal-daemon.sh`).

---

## 2. Target Device Constraints (Tonal V1)

- **Hardware / OS**: MediaTek (`arm64-v8a`), Android 6.0 Marshmallow, portrait `1080x1920` display, **Magisk v30** already installed on device (do not bundle/reinstall older Magisk APKs).
- **System WebView is obsolete (`com.android.webview` 44.0.2403.119)**:
  - Never use a WebView-wrapper browser (e.g., Via Browser); it renders a white screen on modern Vite/React/TLS.
  - Always use a standalone-engine browser (**Firefox 68.11.0 ESR** or **Bromite 108**).
- **Android 6.0 shell & Termux quirks**:
  - Android 6's `tar` lacks `gunzip` (`tar -xzf` fails with `exec gunzip: No such file or directory`). Always transfer uncompressed `.tar` archives (`tar -cf` / `tar -xf`).
  - Running Termux binaries (`pkg`, `pip`, `python3`, `librespot`) via `su` or Magisk `service.d` fails with missing shared libraries (`libapt-private.so`, `libandroid-support.so`) unless `PREFIX=/data/data/com.termux/files/usr`, `LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib`, `HOME`, `TMPDIR`, and `PATH` are exported (and `pkg`/`pip` run as Termux's UID via `run_in_termux()` in `scripts/install-device.sh`).
  - Check `packages/tonal-daemon/start-daemon.sh` preserves `PREFIX`, `LD_LIBRARY_PATH`, `PYTHONHOME`, and `PATH`.

---

## 3. Browser Compatibility & Performance Rules (Firefox 68.11.0 ESR + Low-Power GPU)

1. **Vite build target (`packages/server-app/vite.config.ts`)**:
   - Keep `build.target: ['es2018', 'firefox68', 'chrome70']`. Vite 6 defaults to `es2020`, which emits raw `?.` and `??` that cause a `SyntaxError` in Firefox 68 before React mounts (black/blank screen).
2. **`<dialog>` polyfill (`packages/server-app/src/client/src/main.tsx` + `index.css`)**:
   - Native `HTMLDialogElement.prototype.showModal()` is absent in Firefox 68. `main.tsx` polyfills `showModal()` / `close()` / `Escape` / backdrop clicks, and `index.css` styles both `dialog:modal` and `dialog[open]`.
3. **Range sliders (`index.css` `.swonal-range`)**:
   - `accent-color` (`accent-current`) is unsupported in Firefox 68 and renders a white default track. Use `.swonal-range` with custom `-webkit-slider-*` and `-moz-range-*` rules plus an inline `linear-gradient` fill.
4. **30Hz GPU/CPU performance**:
   - Avoid full-screen SVG filters (`feTurbulence`), CSS `mask-image`, or heavy `backdrop-filter` blurs over the 30Hz kiosk stage — they trigger software CPU rasterization on the MediaTek GPU.
   - Keep `contain: strict` on background texture layers (`.swonal-dot-grid`, `.swonal-noise-grain`).

---

## 4. Wireless ADB & `scrcpy` Playbook

- Local ADB keys can live in `tonal_keys/` (`adbkey`, `adbkey.pub`), which is `.gitignore`d.
- **Do not run `adb kill-server` on default port `5037`** if other local/USB devices are active. Isolate the Tonal connection on port `5038`:
  ```sh
  export ANDROID_ADB_SERVER_PORT=5038
  export ADB_VENDOR_KEYS="$(pwd)/tonal_keys/adbkey"

  adb connect <TONAL_IP>:5555
  scrcpy -s <TONAL_IP>:5555 --no-audio --max-fps 30 --video-bit-rate 4M -m 1080
  ```
- Android `adbd` over TCP (`5555`) only allows **one** client at a time; a second host connecting gets `protocol fault` / `offline`.
- Open Android Settings / Developer Options via ADB if needed:
  ```sh
  ANDROID_ADB_SERVER_PORT=5038 adb -s <TONAL_IP>:5555 shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS
  ```

---

## 5. UI & Design Decisions

- **Themes & Contrast (`packages/server-app/src/client/src/index.css`)**:
  - **Dark Mode**: 3-tier obsidian surface hierarchy (`--bg-canvas: #06090e`, `--bg-surface`, `--bg-secondary-bar`), `36px` crosshair grid, subtle dot-stipple grain, top-lit card borders, and continuous radial accent spotlight behind the 620px dial (`opacity-25` idle, `opacity-95 scale-105` engaged).
  - **Light Mode**: `#f4f6f9` canvas, `#ffffff` cards, `36px` blueprint hairline grid, `--accent-ink: color-mix(in srgb, var(--accent-primary) 52%, #0f172a)` for readable colored text/icons on white/gray surfaces, and stronger slate button borders (`--elevated-shadow`).
- **Typography & Icons**:
  - `tabular-nums` on all live weights, reps, velocities, and ROM counters.
  - Uniform `stroke-width: 1.75` on all `svg.lucide` icons.
- **Kiosk Layout (`KioskView.tsx`)**:
  - Fixed-height (`h-36`) movement stepper + docked Form Demo bar so toggling the Form Demo causes zero vertical layout jump.
  - Cable travel bars (`renderCableBar`) use `w-32 shrink-0` (prevents negative velocity values from shifting bars horizontally) and an `inset-[2px]` inner track with a `58px` `rounded-full` puck mapped via `calc(${travelPct}% - ${(travelPct / 100) * 58}px)` so `0%` sits flush at the bottom with no crescent gap.
  - Quick Preferences dropdown uses `var(--bg-surface-solid)` (opaque) with a `pointerdown` + `Escape` click-away listener (`quickSettingsRef`).
  - VBT velocity bar animates smoothly (`transition-all duration-75`) from live velocity during reps and holds `velocityDropPct * 2%` between reps.
  - Hardware status badge shows `TONAL CDC 30Hz` (live `tonal-daemon`), `MOCK 30Hz` (`--mock`), or `DAEMON WAIT` (`:8765` unreachable).
- **Companion Layout (`CompanionView.tsx`)**:
  - Compact header without unit switcher; status dot tucked in an `elevated-pill`; bottom nav anchored with `border-t border-white/10`, `pb-[env(safe-area-inset-bottom)]`, and `--bg-secondary-bar`.
  - Workout card header aligns `Saved Workouts`, arm/accessory badge, `< Prev`, and `Next >` in a single `h-8` row; `WEIGHT OFF` pad uses `.swonal-carbon-pad`.

---

## 6. Verification & Build Commands

Run after every change before committing or restarting the server:

```sh
# Clean-room full CI check (workflows + server-app + tonal-daemon + android-kiosk, isolated from .env & swonal.db)
./scripts/ci-local.sh

# Release APK check (verifies R8 minification & resource shrinking + bundled web assets)
cd packages/android-kiosk && ./gradlew :app:assembleRelease --no-daemon

# Installer / boot script syntax check
bash -n scripts/install-device.sh && sh -n packages/tonal-daemon/start-daemon.sh
```
